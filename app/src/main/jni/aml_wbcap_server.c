/*
 * aml_wbcap_server — отдельный root-бинарник для захвата экрана на Amlogic
 * через обратную запись дисплейного контроллера (write back).
 *
 * Дисплейный контроллер умеет отдавать в память уже собранный кадр — тот самый,
 * что уходит на телевизор, со всеми слоями сразу. Точка съёма post blend стоит
 * после смешивания, поэтому в кадр попадает и видео, и экранное меню; ни то, ни
 * другое поодиночке брать не приходится.
 *
 * Это важно как раз на этих приставках: видеослой идёт мимо композитора, и в
 * обычном снимке экрана на его месте пустота. Здесь такой беды нет.
 *
 * Кадры отдаёт драйвер amlvideo2 обычным способом, через V4L2. Порт задаётся
 * одним числом в VIDIOC_S_INPUT:
 *
 *     биты 0..15  порт (0xa007 — выход смешивателя)
 *     бит  24     номер VDIN
 *     бит  28     поднять VDIN самому
 *
 * Цвета драйвер отдаёт сразу в RGB, перекладывать их не приходится.
 *
 * Кадр берём полным. Просить у драйвера маленький нельзя: он передаёт размер
 * масштабатору дисплейного контроллера, а тот при сильном сжатии начинает
 * выводить уменьшенную копию кадра в углу экрана — видно на телевизоре, пока
 * идёт захват.
 *
 * Уменьшает движок 2D, в два приёма: за один он такое сжатие не берёт. Кадр в
 * его памяти лежит уже описанным — драйвер захвата отдаёт номер холста вместе
 * с буфером, так что ни адреса, ни копирования не нужно. Если движок почему-то
 * недоступен, кадр ужимает процессор выборкой.
 *
 * Требуется:
 *   - root-доступ (su)
 *   - /dev/video12 — узел amlvideo2.1, к которому ведёт цепочка кадров от VDIN1
 *
 * Формат вывода (stdout, двоичный), на каждый кадр:
 *
 *     4 байта LE: ширина
 *     4 байта LE: высота
 *     ширина * высота * 3 байта: данные RGB
 *
 * Запуск: aml_wbcap_server <width> <height> <fps> [device] [vdin]
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <stdint.h>
#include <linux/videodev2.h>
#include <sys/mman.h>
#include <stdint.h>

/* ── Движок 2D: уменьшает кадр, не занимая процессор ──────────────────── */
#define GE2D_IOC_MAGIC 'G'

struct ge2d_planes_ion {
    unsigned long addr;
    unsigned int w, h;
    int shared_fd;
};

struct ge2d_para_ex {
    int canvas_index, top, left, width, height, format, mem_type, color;
    unsigned char x_rev, y_rev, fill_color_en, fill_mode;
};

struct ge2d_key_ctrl {
    int key_enable, key_color, key_mask, key_mode;
};

/*
 * Раскладка полей должна в точности повторять ядерную (ge2d.h): её размер
 * входит в номер команды, а неизвестный номер этот драйвер принимает молча и
 * ничего не делает — ни ошибки, ни настройки. Поля второго источника здесь
 * есть: в этой сборке ядра он включён, и без них размер расходится на восемь
 * байт.
 */
struct ge2d_config_ion {
    struct ge2d_para_ex src_para, src2_para, dst_para;
    struct ge2d_key_ctrl src_key, src2_key;

    unsigned char src1_cmult_asel;
    unsigned char src2_cmult_asel;
    unsigned char src2_cmult_ad;
    int alu_const_color;
    unsigned char src1_gb_alpha_en;
    unsigned int src1_gb_alpha;
    unsigned char src2_gb_alpha_en;
    unsigned int src2_gb_alpha;
    unsigned int op_mode;
    unsigned char bitmask_en;
    unsigned char bytemask_only;
    unsigned int bitmask;
    unsigned char dst_xy_swap;

    unsigned int hf_init_phase; int hf_rpt_num;
    unsigned int hsc_start_phase_step; int hsc_phase_slope;
    unsigned int vf_init_phase; int vf_rpt_num;
    unsigned int vsc_start_phase_step; int vsc_phase_slope;
    unsigned char src1_vsc_phase0_always_en, src1_hsc_phase0_always_en;
    unsigned char src1_hsc_rpt_ctrl, src1_vsc_rpt_ctrl;

    struct ge2d_planes_ion src_planes[4], src2_planes[4], dst_planes[4];
};

/* Размер выверен на живом ядре: другой размер — другая команда. */
typedef char ge2d_config_size_check[sizeof(struct ge2d_config_ion) == 408 ? 1 : -1];

struct ge2d_rect { int x, y, w, h; };

struct ge2d_op {
    unsigned int color;
    struct ge2d_rect src1_rect, src2_rect, dst_rect;
    int op;
};

#define GE2D_CONFIG_EX_ION _IOW(GE2D_IOC_MAGIC, 0x03, struct ge2d_config_ion)
/* Команда растяжения задана прямым числом, а не макросом. */
#define GE2D_STRETCHBLIT_NOALPHA 0x4702

/* Откуда движок берёт картинку: свой холст или память по дескриптору. */
#define MEM_TYPE_ALLOC 2
#define MEM_TYPE_CANVAS_READY 3

#define GE2D_ENDIAN_SHIFT 24
#define GE2D_LITTLE_ENDIAN (1 << GE2D_ENDIAN_SHIFT)
#define GE2D_COLOR_MAP_SHIFT 20
/*
 * Три байта на точку в порядке R, G, B. У движка это зовётся BGR: имя описывает
 * порядок от старшего байта слова, а не порядок в памяти. Драйвер захвата
 * описывает свои буферы этим же кодом, так что источник и приёмник совпадают.
 */
#define GE2D_FORMAT_S24_RGB \
    (GE2D_LITTLE_ENDIAN | 0x00200 | (5 << GE2D_COLOR_MAP_SHIFT))

/* Память под приёмник берём из ION: движку нужен физически непрерывный кусок. */
struct ion_alloc_data {
    size_t len, align;
    unsigned int heap_id_mask, flags;
    int handle;
};
struct ion_fd_data { int handle, fd; };

/* Куча непрерывной памяти; номер совпадает с её видом. */
#define HEAP_CONTIGUOUS 4

#define ION_IOC_MAGIC 'I'
#define ION_IOC_ALLOC _IOWR(ION_IOC_MAGIC, 0, struct ion_alloc_data)
#define ION_IOC_MAP   _IOWR(ION_IOC_MAGIC, 2, struct ion_fd_data)

/*
 * Уменьшать сразу в двадцать раз движок не умеет: он считает шаг выборки как
 * dst * 10 / src, и на таком отношении шаг обнуляется — операция не выполняется
 * и обрывается по времени. Поэтому идём в два приёма через промежуточный кадр,
 * держа каждый шаг в пределах пятикратного.
 */
#define MAX_STEP_RATIO 5

struct scaler {
    int ge2d_fd;
    int ion_fd;
    int mid_fd;      /* промежуточный кадр; -1, если хватает одного прохода */
    int mid_w, mid_h;
    int dst_fd;
    void *dst;
    size_t dst_len;
};

#define LOG_TAG "AmlWbCapSrv"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

#define DEFAULT_DEVICE "/dev/video12"
#define VDIN_ATTR_FMT "/sys/class/vdin/vdin%d/attr"
#define PORT_POST_BLEND 0xa007
#define START_VDIN (1u << 28)

/*
 * Буферов берём с запасом: обратная запись отдаёт кадр каждую развёртку, и на
 * четырёх очередь успевает опустеть.
 */
/*
 * Буферов просим немного: кадр берём крупным, и памяти под захват у драйвера
 * всего несколько таких кадров. Сколько дадут — с тем и работаем.
 */
#define BUFFERS 4

/*
 * Размер, который просим у драйвера. Меньше просить нельзя — появляется копия
 * кадра на экране; больше незачем.
 */
#define GRAB_WIDTH  1920
#define GRAB_HEIGHT 1080

/* Шаг выборки при усреднении: на клетку хватает и каждой восьмой точки. */
#define SAMPLE_STEP 16

/* Потолок числа клеток: под них держим накопители. */
#define MAX_CELLS (256 * 256)
#define MAX_SIDE 1920
#define FRAME_TIMEOUT_SEC 3

/*
 * Допуск на дрожание. Кадры приходят с частотой развёртки, и без него запрос
 * «столько же, сколько даёт экран» срезал бы ровно половину: приход то чуть
 * раньше расчётного мига, то чуть позже.
 */
#define JITTER_US 2000L

struct buffer {
    void *start;
    size_t length;
    int canvas;   /* номер холста для движка; 0, если драйвер его не дал */
};

static volatile int g_running = 1;

static void signal_handler(int sig) {
    (void)sig;
    g_running = 0;
}

/*
 * Подбирает хвост от предыдущего запуска.
 *
 * Если прошлый сервер был убит на ходу, драйвер захват не освобождает: счётчик
 * открытий остаётся ненулевым, обратная запись продолжает работать, и регистры
 * дисплея не возвращаются в исходное — на экране остаётся мусор. Штатная команда
 * остановки этот хвост подбирает, поэтому даём её перед началом работы. Когда
 * освобождать нечего, команда просто ничего не делает.
 */
static void release_stale_capture(int vdin) {
    char path[64];
    int fd;

    snprintf(path, sizeof(path), VDIN_ATTR_FMT, vdin);
    fd = open(path, O_WRONLY);
    if (fd < 0) return;
    if (write(fd, "v4l2stop", 8) < 0) {
        /* Права есть только у root; без них просто работаем как раньше. */
    }
    close(fd);
    /* Драйверу нужно время на остановку: следующий кадр иначе застанет её. */
    usleep(200000);
}

static int xioctl(int fd, unsigned long req, void *arg) {
    int r;
    do {
        r = ioctl(fd, req, arg);
    } while (r < 0 && errno == EINTR);
    return r;
}

/*
 * Ужимает кадр до нужного размера.
 *
 * Идём строго по строкам, слева направо: память захвата некэшируемая, и порядок
 * обращений решает всё. Обход по клеткам с прыжками между строками обходился в
 * сорок с лишним миллисекунд на кадр — при последовательном чтении та же работа
 * укладывается в единицы, потому что каждая вычитанная строка кэша идёт в дело
 * целиком.
 *
 * Строки берём через одну (шаг задаётся ниже): для средних цветов по клетке
 * этого достаточно.
 */
static void shrink(const uint8_t *src, int sw, int sh, uint8_t *dst, int dw, int dh) {
    static uint32_t acc[MAX_CELLS * 3];
    static uint32_t cnt[MAX_CELLS];
    int x, y, i;

    if (dw * dh > MAX_CELLS) return;

    memset(acc, 0, sizeof(uint32_t) * dw * dh * 3);
    memset(cnt, 0, sizeof(uint32_t) * dw * dh);

    for (y = 0; y < sh; y += SAMPLE_STEP) {
        const uint8_t *row = src + (size_t)y * sw * 3;
        int cell_y = y * dh / sh;
        uint32_t *arow = acc + (size_t)cell_y * dw * 3;
        uint32_t *crow = cnt + (size_t)cell_y * dw;

        for (x = 0; x < sw; x += SAMPLE_STEP) {
            int cell_x = x * dw / sw;
            const uint8_t *p = row + x * 3;

            arow[cell_x * 3] += p[0];
            arow[cell_x * 3 + 1] += p[1];
            arow[cell_x * 3 + 2] += p[2];
            crow[cell_x]++;
        }
    }

    for (i = 0; i < dw * dh; i++) {
        uint32_t n = cnt[i] ? cnt[i] : 1;
        dst[i * 3] = (uint8_t)(acc[i * 3] / n);
        dst[i * 3 + 1] = (uint8_t)(acc[i * 3 + 1] / n);
        dst[i * 3 + 2] = (uint8_t)(acc[i * 3 + 2] / n);
    }
}

/* Выделяет буфер в непрерывной памяти. Возвращает дескриптор или -1. */
static int ion_alloc(int ion_fd, size_t len) {
    struct ion_alloc_data alloc;
    struct ion_fd_data fdd;

    memset(&alloc, 0, sizeof(alloc));
    alloc.len = (len + 4095) & ~4095UL;
    alloc.align = 4096;
    /*
     * Только непрерывная память. Страничная тоже выделяется, но физического
     * адреса у неё нет, движок такой буфер не примет, а необслуженная настройка
     * оставляет приёмником холст с номером ноль — то есть кадровый буфер
     * экрана. Лучше отказаться сразу.
     */
    alloc.heap_id_mask = 1 << HEAP_CONTIGUOUS;
    if (ioctl(ion_fd, ION_IOC_ALLOC, &alloc) < 0) {
        LOGE("ION alloc failed: %s", strerror(errno));
        return -1;
    }

    memset(&fdd, 0, sizeof(fdd));
    fdd.handle = alloc.handle;
    if (ioctl(ion_fd, ION_IOC_MAP, &fdd) < 0) {
        LOGE("ION map failed: %s", strerror(errno));
        return -1;
    }
    return fdd.fd;
}

/*
 * Готовит движок: открывает его и выделяет приёмный буфер.
 * Возвращает 0, если движок недоступен — тогда работаем как раньше, процессором.
 */
static int scaler_open(struct scaler *sc, int w, int h) {
    struct ion_alloc_data alloc;
    struct ion_fd_data fdd;

    memset(sc, 0, sizeof(*sc));
    sc->ge2d_fd = open("/dev/ge2d", O_RDWR);
    if (sc->ge2d_fd < 0) {
        LOGE("Cannot open /dev/ge2d: %s", strerror(errno));
        return 0;
    }

    sc->ion_fd = open("/dev/ion", O_RDWR);
    if (sc->ion_fd < 0) {
        LOGE("Cannot open /dev/ion: %s", strerror(errno));
        close(sc->ge2d_fd); sc->ge2d_fd = -1; return 0;
    }

    sc->dst_len = ((size_t)w * h * 3 + 4095) & ~4095UL;
    sc->dst_fd = ion_alloc(sc->ion_fd, sc->dst_len);
    if (sc->dst_fd < 0) goto fail;

    sc->dst = mmap(NULL, sc->dst_len, PROT_READ | PROT_WRITE, MAP_SHARED, sc->dst_fd, 0);
    if (sc->dst == MAP_FAILED) { sc->dst = NULL; goto fail; }

    /*
     * Промежуточный кадр нужен, только если за один приём не уложиться.
     * Берём его вчетверо крупнее выхода — тогда оба шага остаются пологими.
     */
    sc->mid_fd = -1;
    if (GRAB_WIDTH / w > MAX_STEP_RATIO || GRAB_HEIGHT / h > MAX_STEP_RATIO) {
        sc->mid_w = w * 4;
        sc->mid_h = h * 4;
        sc->mid_fd = ion_alloc(sc->ion_fd, (size_t)sc->mid_w * sc->mid_h * 3);
        if (sc->mid_fd < 0) goto fail;
    }

    return 1;

fail:
    if (sc->ion_fd >= 0) close(sc->ion_fd);
    if (sc->ge2d_fd >= 0) close(sc->ge2d_fd);
    sc->ge2d_fd = sc->ion_fd = -1;
    return 0;
}

/*
 * Один проход движка: из холста или буфера-источника в буфер-приёмник.
 * Источник задаётся либо номером холста (тогда src_fd < 0), либо дескриптором.
 */
static int blit(struct scaler *sc, int src_canvas, int src_fd,
                int sw, int sh, int dst_fd, int dw, int dh) {
    struct ge2d_config_ion cfg;
    struct ge2d_op op;

    memset(&cfg, 0, sizeof(cfg));
    /*
     * Второй источник не нужен, но нулём его оставлять нельзя: ноль означает
     * «взять слой экрана», и ядро уходит выяснять его свойства, а затем
     * отказывает. Отмечаем его как незадействованный.
     */
    cfg.src2_para.mem_type = MEM_TYPE_CANVAS_READY;

    cfg.src_para.format = GE2D_FORMAT_S24_RGB;
    cfg.src_para.width = sw;
    cfg.src_para.height = sh;
    if (src_fd < 0) {
        /*
         * Холст драйвер захвата настраивает сам под каждый свой буфер и отдаёт
         * номер наружу, так что адрес нам знать незачем. При этом типе памяти
         * движок берёт номер как есть и в свою настройку холстов не лезет.
         */
        cfg.src_para.mem_type = MEM_TYPE_CANVAS_READY;
        cfg.src_para.canvas_index = src_canvas;
    } else {
        cfg.src_para.mem_type = MEM_TYPE_ALLOC;
        cfg.src_planes[0].w = sw;
        cfg.src_planes[0].h = sh;
        cfg.src_planes[0].shared_fd = src_fd;
    }

    cfg.dst_para.mem_type = MEM_TYPE_ALLOC;
    cfg.dst_para.format = GE2D_FORMAT_S24_RGB;
    cfg.dst_para.width = dw;
    cfg.dst_para.height = dh;
    cfg.dst_planes[0].w = dw;
    cfg.dst_planes[0].h = dh;
    cfg.dst_planes[0].shared_fd = dst_fd;

    /*
     * Незанятые плоскости оставляем нулями: ядро считает любой ненулевой
     * дескриптор настоящим и пытается получить по нему адрес.
     */

    if (ioctl(sc->ge2d_fd, GE2D_CONFIG_EX_ION, &cfg) < 0) {
        static int told;
        if (!told) { LOGE("ge2d config rejected: %s", strerror(errno)); told = 1; }
        return 0;
    }

    memset(&op, 0, sizeof(op));
    op.src1_rect.w = sw;
    op.src1_rect.h = sh;
    op.dst_rect.w = dw;
    op.dst_rect.h = dh;
    if (ioctl(sc->ge2d_fd, GE2D_STRETCHBLIT_NOALPHA, &op) < 0) {
        static int told;
        if (!told) { LOGE("ge2d blit rejected: %s", strerror(errno)); told = 1; }
        return 0;
    }

    return 1;
}

/* Уменьшает кадр движком. Возвращает 0, если не вышло — вызывающий сделает сам. */
static int scaler_run(struct scaler *sc, int src_canvas, int sw, int sh, int dw, int dh) {
    if (sc->mid_fd < 0)
        return blit(sc, src_canvas, -1, sw, sh, sc->dst_fd, dw, dh);

    return blit(sc, src_canvas, -1, sw, sh, sc->mid_fd, sc->mid_w, sc->mid_h) &&
           blit(sc, 0, sc->mid_fd, sc->mid_w, sc->mid_h, sc->dst_fd, dw, dh);
}

static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= n;
    }
    return 0;
}

int main(int argc, char **argv) {
    int out_w, out_h, fps, vdin;
    const char *dev;
    struct buffer buffers[BUFFERS];
    struct v4l2_format fmt;
    struct v4l2_requestbuffers req;
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    unsigned int input;
    int grab_w, grab_h, mapped = 0;
    struct scaler sc;
    int use_scaler = 0, checked = 0;
    uint8_t *small;
    const uint8_t *frame_out;
    long frame_interval_us;
    struct timespec ts_last;
    int fd, i;

    if (argc < 4) {
        LOGE("Usage: %s <width> <height> <fps> [device] [vdin]", argv[0]);
        return 1;
    }
    out_w = atoi(argv[1]);
    out_h = atoi(argv[2]);
    fps = atoi(argv[3]);
    dev = (argc > 4) ? argv[4] : DEFAULT_DEVICE;
    vdin = (argc > 5) ? atoi(argv[5]) : 1;

    if (out_w < 2 || out_h < 2 || out_w > MAX_SIDE || out_h > MAX_SIDE) {
        LOGE("Bad frame size %dx%d", out_w, out_h);
        return 1;
    }
    if (fps < 1 || fps > 120) fps = 30;
    frame_interval_us = 1000000L / fps;

    signal(SIGPIPE, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    release_stale_capture(vdin);

    fd = open(dev, O_RDWR);
    if (fd < 0) {
        LOGE("Cannot open %s: %s", dev, strerror(errno));
        return 1;
    }

    input = START_VDIN | ((unsigned)vdin << 24) | PORT_POST_BLEND;
    if (xioctl(fd, VIDIOC_S_INPUT, &input) < 0) {
        LOGE("Cannot select write-back port: %s", strerror(errno));
        close(fd);
        return 1;
    }

    memset(&fmt, 0, sizeof(fmt));
    fmt.type = type;
    fmt.fmt.pix.width = GRAB_WIDTH;
    fmt.fmt.pix.height = GRAB_HEIGHT;
    /*
     * Просим сразу RGB: драйвер умеет отдавать его сам, и перекладывать цвета
     * в пространстве пользователя не приходится вовсе.
     */
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_RGB24;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;
    if (xioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        LOGE("Cannot set format: %s", strerror(errno));
        close(fd);
        return 1;
    }
    /* Драйвер вправе поправить размер — ужимать будем с того, что он дал. */
    grab_w = fmt.fmt.pix.width;
    grab_h = fmt.fmt.pix.height;

    small = malloc((size_t)out_w * out_h * 3);
    if (!small) {
        LOGE("Out of memory");
        close(fd);
        return 1;
    }

    {
        /*
         * Без этого драйвер отдаёт свои тридцать кадров в секунду и не больше:
         * столько записано у него по умолчанию. Просим ровно то, что заказали.
         */
        struct v4l2_streamparm parm;
        memset(&parm, 0, sizeof(parm));
        parm.type = type;
        parm.parm.capture.timeperframe.numerator = 1;
        parm.parm.capture.timeperframe.denominator = (unsigned)fps;
        if (xioctl(fd, VIDIOC_S_PARM, &parm) < 0)
            LOGE("Cannot set frame rate: %s", strerror(errno));
    }

    memset(&req, 0, sizeof(req));
    req.count = BUFFERS;
    req.type = type;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        LOGE("Cannot request buffers: %s", strerror(errno));
        close(fd);
        return 1;
    }

    for (i = 0; i < (int)req.count; i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = type;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            LOGE("Cannot query buffer %d: %s", i, strerror(errno));
            break;
        }
        buffers[i].length = buf.length;
        buffers[i].start = mmap(NULL, buf.length, PROT_READ | PROT_WRITE,
                                MAP_SHARED, fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED) {
            /*
             * Память под захват кончилась. Драйвер обещает больше буферов, чем
             * реально может отдать при крупном кадре, поэтому довольствуемся
             * теми, что уже есть.
             */
            LOGI("Mapped %d buffers of %u requested", i, req.count);
            break;
        }
        /*
         * Драйвер кладёт сюда номер холста, которым он описал этот буфер.
         * Движку большего и не нужно: холст уже знает и адрес, и длину строки.
         */
        buffers[i].canvas = (int)buf.reserved;

        if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            LOGE("Cannot queue buffer %d: %s", i, strerror(errno));
            break;
        }
        mapped++;
    }

    if (mapped < 2) {
        LOGE("Only %d buffers available, need at least two", mapped);
        free(small);
        close(fd);
        return 1;
    }

    use_scaler = scaler_open(&sc, out_w, out_h);
    if (use_scaler && !buffers[0].canvas) {
        LOGI("Driver gave no canvas for its buffers, shrinking on CPU");
        use_scaler = 0;
    }

    if (xioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        LOGE("Cannot start streaming: %s", strerror(errno));
        close(fd);
        return 1;
    }

    LOGI("Capture started: %dx%d from %dx%d @ %dfps from %s",
         out_w, out_h, grab_w, grab_h, fps, dev);

    clock_gettime(CLOCK_MONOTONIC, &ts_last);

    while (g_running) {
        struct v4l2_buffer buf;
        struct pollfd pfd[2];
        struct timespec ts_now;
        long since_last_us;
        int r;

        /* Ждём кадр и заодно следим, жив ли родитель. */
        pfd[0].fd = fd;
        pfd[0].events = POLLIN;
        pfd[0].revents = 0;
        pfd[1].fd = STDIN_FILENO;
        pfd[1].events = POLLIN | POLLHUP;
        pfd[1].revents = 0;

        r = poll(pfd, 2, FRAME_TIMEOUT_SEC * 1000);
        if (r < 0) {
            if (errno == EINTR) continue;
            LOGE("Poll failed: %s", strerror(errno));
            break;
        }
        if (pfd[1].revents & (POLLHUP | POLLERR)) {
            LOGI("Parent disconnected");
            break;
        }
        if (getppid() == 1) {
            LOGI("Parent gone");
            break;
        }
        if (r == 0) {
            LOGE("No frames for %d seconds", FRAME_TIMEOUT_SEC);
            break;
        }
        if (!(pfd[0].revents & POLLIN)) continue;

        memset(&buf, 0, sizeof(buf));
        buf.type = type;
        buf.memory = V4L2_MEMORY_MMAP;
        if (xioctl(fd, VIDIOC_DQBUF, &buf) < 0) {
            LOGE("Cannot dequeue buffer: %s", strerror(errno));
            break;
        }

        /*
         * Частоту держим пропуском, а не сном: кадры приходят с частотой развёртки,
         * и если после ожидания кадра ещё и поспать интервал, выйдет ровно вдвое
         * меньше заказанного. Лишний кадр просто возвращаем в очередь.
         */
        clock_gettime(CLOCK_MONOTONIC, &ts_now);
        since_last_us = (ts_now.tv_sec - ts_last.tv_sec) * 1000000L +
                        (ts_now.tv_nsec - ts_last.tv_nsec) / 1000L;
        if (since_last_us + JITTER_US < frame_interval_us) {
            if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
                LOGE("Cannot requeue buffer: %s", strerror(errno));
                break;
            }
            /*
             * Досыпаем до своего срока. Без этого цикл крутится вхолостую:
             * драйвер отдаёт кадры быстрее, чем они нужны, и мы забираем их
             * только затем, чтобы тут же вернуть — процессор при этом занят
             * полностью, хотя работы нет никакой.
             */
            usleep((useconds_t)(frame_interval_us - since_last_us));
            continue;
        }
        ts_last = ts_now;

        {
            const uint8_t *out;

            if (use_scaler &&
                scaler_run(&sc, buffers[buf.index].canvas, grab_w, grab_h, out_w, out_h)) {
                out = (const uint8_t *)sc.dst;

                if (!checked) {
                    /*
                     * Адрес буфера мы вычислили, а не получили — на первом кадре
                     * сверяем движок с процессором. Расходятся — значит адрес не
                     * тот, и дальше считаем сами.
                     */
                    long diff = 0;
                    int k, n = out_w * out_h * 3;

                    shrink((const uint8_t *)buffers[buf.index].start, grab_w, grab_h,
                           small, out_w, out_h);
                    for (k = 0; k < n; k++)
                        diff += abs((int)out[k] - (int)small[k]);

                    checked = 1;
                    LOGI("hardware vs cpu difference: %ld", diff / n);
                    if (diff / n > 24) {
                        LOGI("Hardware scaler unavailable here, shrinking on CPU");
                        use_scaler = 0;
                        out = small;
                    } else {
                        LOGI("Hardware scaler checked out");
                    }
                }
            } else {
                shrink((const uint8_t *)buffers[buf.index].start, grab_w, grab_h,
                       small, out_w, out_h);
                out = small;
            }
            frame_out = out;
        }

        {
            uint32_t header[2] = { (uint32_t)out_w, (uint32_t)out_h };
            int failed = write_all(STDOUT_FILENO, header, sizeof(header)) < 0 ||
                         write_all(STDOUT_FILENO, frame_out,
                                   (size_t)out_w * out_h * 3) < 0;

            /* Буфер возвращаем в любом случае, иначе очередь встанет. */
            if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
                LOGE("Cannot requeue buffer: %s", strerror(errno));
                break;
            }
            if (failed) break;
        }
    }

    xioctl(fd, VIDIOC_STREAMOFF, &type);
    for (i = 0; i < mapped; i++)
        munmap(buffers[i].start, buffers[i].length);
    free(small);
    close(fd);
    LOGI("Capture stopped");
    return 0;
}
