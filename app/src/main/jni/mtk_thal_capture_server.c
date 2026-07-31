/*
 * mtk_thal_capture_server — отдельный root-бинарник для захвата экрана на MediaTek
 * через libthal_capture.so (клиент HIDL).
 *
 * Использует do_capture_window(), который обращается к HIDL-сервису
 * vendor.mediatek.hardware.capture@1.0. Снимает прямо с конвейера дисплея (видео вместе
 * с экранным меню) аппаратным DIP — процессор почти не нагружается.
 *
 * Поддерживает два режима с автоматическим переключением:
 *   - режим Android: обычный захват (CapPoint от render HAL)
 *   - режим HDMI:    захват с патчем (принудительный CapPoint=9, обход проверки безопасности)
 *
 * Требуется:
 *   - root-доступ (su)
 *   - /dev/dma_heap/mtk_dip_capture_uncached с правом записи (chmod 666)
 *   - LD_LIBRARY_PATH=/vendor/lib:/system/lib
 *   - 32-битный бинарник ARM (armeabi-v7a) — все библиотеки вендора 32-битные
 *
 * Формат вывода (stdout, двоичный), на каждый кадр:
 *
 *     4 байта LE: ширина
 *     4 байта LE: высота
 *     ширина * высота * 3 байта: данные RGB
 *
 * Запуск: mtk_thal_capture_server <width> <height> <fps>
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <time.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#define LOG_TAG "MtkThalCapSrv"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

/* Выделение памяти из DMA heap */
struct dma_heap_allocation_data {
    __u64 len;
    __u32 fd;
    __u32 fd_flags;
    __u64 heap_flags;
};
#define DMA_HEAP_IOCTL_ALLOC _IOWR('H', 0x0, struct dma_heap_allocation_data)

#define DMA_HEAP_PATH "/dev/dma_heap/mtk_dip_capture_uncached"

/*
 * Сигнатура do_capture_window, восстановленная из libthal_capture.so:
 *   int do_capture_window(
 *       int window_id,    // 0
 *       int capture_type, // 0
 *       int crop_x,       // 0
 *       int crop_y,       // 0
 *       int crop_w,       // должно совпадать с output_w
 *       int crop_h,       // должно совпадать с output_h
 *       int output_w,
 *       int output_h,
 *       int buffer_fd,    // дескриптор DMA-buf из mtk_dip_capture_uncached
 *       int buffer_size   // output_w * output_h * 4 (RGBA)
 *   );
 *   Возвращает 0 при успехе.
 */
typedef int (*fn_do_capture_window)(int, int, int, int, int, int, int, int, int, int);

static volatile int g_running = 1;

static void signal_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return -1;
        p += n;
        len -= n;
    }
    return 0;
}

static int alloc_dma_buf(int size) {
    int heap_fd = open(DMA_HEAP_PATH, O_RDWR);
    if (heap_fd < 0) {
        LOGE("Cannot open %s: %s", DMA_HEAP_PATH, strerror(errno));
        return -1;
    }

    struct dma_heap_allocation_data data;
    memset(&data, 0, sizeof(data));
    data.len = size;
    data.fd_flags = O_RDWR | O_CLOEXEC;

    if (ioctl(heap_fd, DMA_HEAP_IOCTL_ALLOC, &data) < 0) {
        LOGE("DMA alloc failed (%d bytes): %s", size, strerror(errno));
        close(heap_fd);
        return -1;
    }
    close(heap_fd);
    return data.fd;
}

/*
 * Состояние патча для захвата HDMI.
 *
 * libthal_capture.so блокирует захват входа HDMI проверкой bIsSecurity (возвращает ret=3),
 * а HIDL-сервис отвергает CapPoint, специфичные для HDMI. Мы правим do_capture_window()
 * прямо в памяти: заменяем проверку безопасности на принудительный CapPoint=9
 * (STREAM_ALL_VIDEO) и безусловный переход.
 *
 * Патч накладывается один раз при старте, но режим захвата переключается на ходу:
 *   - режим Android: do_capture_window с window=0 (как обычно)
 *   - режим HDMI:    do_capture_window с window=0 (остальное берёт на себя патч)
 *
 * Логика переключения: если захват Android подряд не удаётся N раз, уходим в режим HDMI
 * и периодически пробуем Android снова, чтобы вернуться, когда он заработает.
 */

#define HDMI_PATCH_SIZE 6

static const uint8_t hdmi_patch_pattern[HDMI_PATCH_SIZE] =
    { 0x9d, 0xf8, 0xb0, 0x00, 0x80, 0xb1 };
static const uint8_t hdmi_patch_bytes[HDMI_PATCH_SIZE] =
    { 0x09, 0x20, 0x24, 0x90, 0x10, 0xe0 };

static uint8_t hdmi_patch_original[HDMI_PATCH_SIZE];
static uint8_t *hdmi_patch_addr = NULL;

/* Ищем место патча в do_capture_window и сохраняем исходные байты */
static void hdmi_patch_init(fn_do_capture_window do_capture) {
    uint8_t *func = (uint8_t *)((uintptr_t)do_capture & ~1u);

    for (int i = 0; i < 2048 - HDMI_PATCH_SIZE; i++) {
        if (memcmp(func + i, hdmi_patch_pattern, HDMI_PATCH_SIZE) == 0) {
            hdmi_patch_addr = func + i;
            memcpy(hdmi_patch_original, hdmi_patch_addr, HDMI_PATCH_SIZE);
            LOGI("HDMI patch site found at offset +%d", i);
            return;
        }
    }
    LOGI("HDMI patch pattern not found (may not be needed on this firmware)");
}

static int hdmi_patch_write(const uint8_t *bytes) {
    if (!hdmi_patch_addr) return -1;
    uintptr_t page = (uintptr_t)hdmi_patch_addr & ~0xFFFu;
    if (mprotect((void *)page, 0x2000, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
        return -1;
    memcpy(hdmi_patch_addr, bytes, HDMI_PATCH_SIZE);
    mprotect((void *)page, 0x2000, PROT_READ | PROT_EXEC);
    return 0;
}

static int hdmi_patch_apply(void) {
    return hdmi_patch_write(hdmi_patch_bytes);
}

static int hdmi_patch_revert(void) {
    return hdmi_patch_write(hdmi_patch_original);
}

static void enable_dip_debug(void) {
    static const char *paths[] = {
        "/sys/devices/platform/1d497e00.dip0/dip_debug",
        "/sys/devices/platform/1d498400.dip1/dip_debug",
        "/sys/devices/platform/1d498a00.dip2/dip_debug",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        int fd = open(paths[i], O_WRONLY);
        if (fd >= 0) { write(fd, "1", 1); close(fd); }
    }
}

/* Сколько неудач подряд до перехода в режим HDMI */
#define FALLBACK_THRESHOLD 5
/* Как часто (в мс) пробовать режим Android, находясь в режиме HDMI */
#define PROBE_INTERVAL_MS 500

int main(int argc, char *argv[]) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <width> <height> <fps>\n", argv[0]);
        return 1;
    }

    int req_width = atoi(argv[1]);
    int req_height = atoi(argv[2]);
    int fps = atoi(argv[3]);

    if (req_width <= 0 || req_height <= 0 || fps <= 0 ||
        req_width > 1920 || req_height > 1920) {
        LOGE("Invalid args: %dx%d @ %d fps (max 1920)", req_width, req_height, fps);
        return 1;
    }

    /*
     * do_capture_window вырезает область пикселей из буфера композиции SurfaceFlinger
     * (на этом телевизоре 1920x1080). Запрос меньшего размера не уменьшает картинку —
     * он просто обрезает левый верхний угол.
     *
     * Поэтому всегда снимаем весь экран в 1920x1080, а уменьшаем программно уже перед
     * отправкой в канал. Для размеров под сетку светодиодов (например, 30x18) это держит
     * поток данных небольшим.
     */
    #define CAP_W 1920
    #define CAP_H 1080

    /*
     * Выходной размер — то, что уходит в канал.
     * Держим минимум 240p (426x240): так сохраняются пропорции и остаётся достаточно
     * пикселей для выборки цветов. Окончательное сведение к сетке светодиодов делает
     * само приложение.
     */
    int out_w = req_width;
    int out_h = req_height;
    if (out_w < 426 || out_h < 240) {
        /* Увеличиваем с сохранением пропорций хотя бы до 240p */
        float scale = 1.0f;
        if (out_w > 0 && out_h > 0) {
            float sw = 426.0f / out_w;
            float sh = 240.0f / out_h;
            scale = sw > sh ? sw : sh;
        }
        out_w = (int)(out_w * scale);
        out_h = (int)(out_h * scale);
        if (out_w < 426) out_w = 426;
        if (out_h < 240) out_h = 240;
    }
    /* Приводим к чётному */
    out_w = (out_w + 1) & ~1;
    out_h = (out_h + 1) & ~1;

    long frame_interval_us = 1000000L / fps;

    signal(SIGPIPE, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    /* Загружаем libthal_capture.so */
    void *lib = dlopen("libthal_capture.so", RTLD_NOW);
    if (!lib) lib = dlopen("/vendor/lib/libthal_capture.so", RTLD_NOW);
    if (!lib) {
        LOGE("Cannot load libthal_capture.so: %s", dlerror());
        return 2;
    }

    fn_do_capture_window do_capture =
        (fn_do_capture_window)dlsym(lib, "do_capture_window");
    if (!do_capture) {
        LOGE("Symbol do_capture_window not found");
        dlclose(lib);
        return 3;
    }

    /* Готовим патч HDMI: находим место и сохраняем исходные байты, но пока не применяем */
    hdmi_patch_init(do_capture);
    enable_dip_debug();

    /* Выделяем буфер DMA под полноэкранный захват в RGBA */
    int rgba_size = CAP_W * CAP_H * 4;
    int buf_fd = alloc_dma_buf(rgba_size);
    if (buf_fd < 0) {
        dlclose(lib);
        return 4;
    }

    void *dma_buf = mmap(NULL, rgba_size, PROT_READ | PROT_WRITE, MAP_SHARED, buf_fd, 0);
    if (dma_buf == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        close(buf_fd);
        dlclose(lib);
        return 5;
    }

    /*
     * Локальная копия снятого кадра. DIP пишет в буфер DMA, который может быть
     * некэшируемым и меняться под руками, поэтому копирование в обычную память кучи
     * гарантирует, что мы читаем согласованный снимок.
     */
    uint8_t *rgba_copy = (uint8_t *)malloc(rgba_size);
    if (!rgba_copy) {
        LOGE("malloc rgba_copy failed");
        munmap(dma_buf, rgba_size);
        close(buf_fd);
        dlclose(lib);
        return 6;
    }

    /* Заранее выделяем выходной буфер RGB (запрошенного размера) */
    int rgb_size = out_w * out_h * 3;
    uint8_t *rgb_buf = (uint8_t *)malloc(rgb_size);
    if (!rgb_buf) {
        LOGE("malloc rgb_buf failed");
        free(rgba_copy);
        munmap(dma_buf, rgba_size);
        close(buf_fd);
        dlclose(lib);
        return 6;
    }

    /* Прогревочный захват */
    do_capture(0, 0, 0, 0, CAP_W, CAP_H, CAP_W, CAP_H, buf_fd, rgba_size);

    LOGI("Capture started: capture %dx%d, output %dx%d @ %d fps",
         CAP_W, CAP_H, out_w, out_h, fps);

    /*
     * Заголовок состояния (отправляется один раз перед кадрами):
     *   4 байта LE: сигнатура 0x4D544B53 ("MTKS")
     *   4 байта LE: флаги (бит 0 — доступен патч HDMI)
     * По нему приложение понимает, поддерживается ли захват HDMI.
     */
    {
        uint32_t status_header[2];
        status_header[0] = 0x4D544B53;
        status_header[1] = hdmi_patch_addr ? 1 : 0;
        if (write_all(STDOUT_FILENO, status_header, 8) < 0) {
            LOGE("Failed to write status header");
            free(rgb_buf); free(rgba_copy);
            munmap(dma_buf, rgba_size); close(buf_fd); dlclose(lib);
            return 7;
        }
    }

    struct timespec ts_start, ts_end, ts_last_probe;
    clock_gettime(CLOCK_MONOTONIC, &ts_last_probe);

    int consecutive_errors = 0;
    int hdmi_mode = 0;

    while (g_running) {
        clock_gettime(CLOCK_MONOTONIC, &ts_start);

        /* Проверяем, жив ли ещё родительский процесс */
        struct pollfd pfd = { .fd = STDIN_FILENO, .events = POLLIN | POLLHUP };
        if (poll(&pfd, 1, 0) > 0 && (pfd.revents & (POLLHUP | POLLERR))) {
            LOGI("Parent disconnected");
            break;
        }

        /*
         * В режиме HDMI периодически пробуем захват Android: снимаем патч, делаем один
         * снимок и, если он удался, остаёмся в режиме Android. Если нет — возвращаем
         * патч и продолжаем в режиме HDMI.
         */
        if (hdmi_mode) {
            long probe_elapsed_ms = (ts_start.tv_sec - ts_last_probe.tv_sec) * 1000L +
                (ts_start.tv_nsec - ts_last_probe.tv_nsec) / 1000000L;
            if (probe_elapsed_ms >= PROBE_INTERVAL_MS) {
                ts_last_probe = ts_start;
                hdmi_patch_revert();
                int probe_ret = do_capture(0, 0, 0, 0, CAP_W, CAP_H,
                                           CAP_W, CAP_H, buf_fd, rgba_size);
                if (probe_ret == 0) {
                    hdmi_mode = 0;
                    consecutive_errors = 0;
                    LOGI("Android capture restored, leaving HDMI mode");
                    /* Кадр годный — идём дальше по обычному пути обработки */
                    goto process_frame;
                }
                /* Проба не удалась, возвращаем патч */
                hdmi_patch_apply();
            }
        }

        int ret = do_capture(0, 0, 0, 0, CAP_W, CAP_H, CAP_W, CAP_H, buf_fd, rgba_size);

        if (ret != 0) {
            consecutive_errors++;

            if (!hdmi_mode && consecutive_errors >= FALLBACK_THRESHOLD) {
                /* Переходим в режим HDMI */
                if (hdmi_patch_apply() == 0) {
                    hdmi_mode = 1;
                    clock_gettime(CLOCK_MONOTONIC, &ts_last_probe);
                    LOGI("Switching to HDMI capture mode after %d errors", consecutive_errors);
                    /* Сразу пробуем ещё раз, уже с наложенным патчем */
                    continue;
                }
            }

            int backoff_ms = consecutive_errors < 20 ? 100 : 1000;
            usleep(backoff_ms * 1000);
            continue;
        }

        if (consecutive_errors > 0 && !hdmi_mode) {
            LOGI("Capture recovered after %d errors", consecutive_errors);
        }
        consecutive_errors = 0;

process_frame:
        /* Снимок: сразу копируем буфер DMA в обычную память */
        memcpy(rgba_copy, dma_buf, rgba_size);

        /* Уменьшаем RGBA 1920x1080 → RGB out_w x out_h методом ближайшего соседа */
        const uint8_t *src = rgba_copy;
        for (int y = 0; y < out_h; y++) {
            int sy = y * CAP_H / out_h;
            for (int x = 0; x < out_w; x++) {
                int sx = x * CAP_W / out_w;
                int si = (sy * CAP_W + sx) * 4;
                int di = (y * out_w + x) * 3;
                rgb_buf[di]     = src[si];
                rgb_buf[di + 1] = src[si + 1];
                rgb_buf[di + 2] = src[si + 2];
            }
        }

        /* Пишем кадр: заголовок и данные RGB */
        uint32_t header[2] = { (uint32_t)out_w, (uint32_t)out_h };
        if (write_all(STDOUT_FILENO, header, 8) < 0) break;
        if (write_all(STDOUT_FILENO, rgb_buf, rgb_size) < 0) break;

        /* Выдерживаем заданную частоту кадров */
        clock_gettime(CLOCK_MONOTONIC, &ts_end);
        long elapsed_us = (ts_end.tv_sec - ts_start.tv_sec) * 1000000L +
                          (ts_end.tv_nsec - ts_start.tv_nsec) / 1000L;
        long sleep_us = frame_interval_us - elapsed_us;
        if (sleep_us > 0) usleep(sleep_us);
    }

    free(rgb_buf);
    free(rgba_copy);
    munmap(dma_buf, rgba_size);
    close(buf_fd);
    dlclose(lib);
    LOGI("Exiting");
    return 0;
}
