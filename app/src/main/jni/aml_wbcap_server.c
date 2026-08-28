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
 * Масштабирует VDIN аппаратно, и цвета драйвер отдаёт сразу в RGB: какой размер
 * и формат попросили в VIDIOC_S_FMT, такие и приходят — процессору не остаётся
 * ни изменения размера, ни перекладывания цветов.
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
#include <linux/videodev2.h>

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
#define BUFFERS 8
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
    fmt.fmt.pix.width = out_w;
    fmt.fmt.pix.height = out_h;
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
    /* Драйвер вправе поправить размер — дальше работаем с тем, что он дал. */
    out_w = fmt.fmt.pix.width;
    out_h = fmt.fmt.pix.height;

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
            close(fd);
            return 1;
        }
        buffers[i].length = buf.length;
        buffers[i].start = mmap(NULL, buf.length, PROT_READ | PROT_WRITE,
                                MAP_SHARED, fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED) {
            LOGE("Cannot map buffer %d: %s", i, strerror(errno));
            close(fd);
            return 1;
        }
        if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            LOGE("Cannot queue buffer %d: %s", i, strerror(errno));
            close(fd);
            return 1;
        }
    }

    if (xioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        LOGE("Cannot start streaming: %s", strerror(errno));
        close(fd);
        return 1;
    }

    LOGI("Capture started: %dx%d @ %dfps from %s", out_w, out_h, fps, dev);

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
            continue;
        }
        ts_last = ts_now;

        {
            uint32_t header[2] = { (uint32_t)out_w, (uint32_t)out_h };
            int failed = write_all(STDOUT_FILENO, header, sizeof(header)) < 0 ||
                         write_all(STDOUT_FILENO, buffers[buf.index].start,
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
    for (i = 0; i < (int)req.count; i++)
        munmap(buffers[i].start, buffers[i].length);
    close(fd);
    LOGI("Capture stopped");
    return 0;
}
