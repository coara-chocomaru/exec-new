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

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
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

// ---------- Utility methods ----------
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
    snprintf(result, sizeof(result), "ION test succeeded: allocated and mapped 4096 bytes");
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[256] = {0};
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder version: %d (protocol %d)", version.protocol_version, version.protocol_version);
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
        strcat(result, "BINDER_WRITE_READ with transaction succeeded. ");
        if (bwr.read_consumed > 0) {
            strcat(result, "Reply received.\n");
        }
    } else {
        strcat(result, "BINDER_WRITE_READ transaction failed: ");
        strcat(result, strerror(errno));
        strcat(result, "\n");
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
        snprintf(result, sizeof(result), "Overflow test: BINDER_WRITE_READ with huge buffer succeeded");
    } else {
        snprintf(result, sizeof(result), "Overflow test: BINDER_WRITE_READ with huge buffer failed: %s", strerror(errno));
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

// ---------- New final verification methods ----------
JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderWriteMemory(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jlong address, jlong value) {
    char result[256] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    // Craft a transaction with a buffer containing address and value
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        uint64_t data[2];
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    memset(&tx.tdata, 0, sizeof(tx.tdata));
    tx.tdata.target.handle = (uint32_t)handle;
    tx.tdata.code = (uint32_t)code;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 16; // 8 bytes address + 8 bytes value
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)tx.data;
    tx.tdata.data.ptr.offsets = 0;
    tx.data[0] = (uint64_t)address;
    tx.data[1] = (uint64_t)value;

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        snprintf(result, sizeof(result), "WriteMemory: ioctl succeeded, read_consumed=%llu",
                 (unsigned long long)bwr.read_consumed);
    } else {
        snprintf(result, sizeof(result), "WriteMemory: ioctl failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderReadReply(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jint flags) {
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

    // Read up to 4096 bytes of reply
    uint8_t reply_buf[4096];
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0 && bwr.read_consumed > 0) {
        jbyteArray arr = (*env)->NewByteArray(env, (jsize)bwr.read_consumed);
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)bwr.read_consumed, (jbyte*)reply_buf);
        return arr;
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderExecCommand(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jstring cmd) {
    char result[512] = {0};
    if (cmd == NULL) {
        snprintf(result, sizeof(result), "ExecCommand: command is null");
        return (*env)->NewStringUTF(env, result);
    }
    const char* command = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (command == NULL) {
        snprintf(result, sizeof(result), "ExecCommand: failed to get command string");
        return (*env)->NewStringUTF(env, result);
    }

    size_t cmd_len = strlen(command) + 1;
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    // Allocate a buffer for the transaction data containing the command
    size_t data_size = cmd_len;
    void* data_buf = malloc(data_size);
    if (!data_buf) {
        (*env)->ReleaseStringUTFChars(env, cmd, command);
        snprintf(result, sizeof(result), "ExecCommand: malloc failed");
        return (*env)->NewStringUTF(env, result);
    }
    memcpy(data_buf, command, cmd_len);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    memset(&tx.tdata, 0, sizeof(tx.tdata));
    tx.tdata.target.handle = (uint32_t)handle;
    tx.tdata.code = (uint32_t)code;
    tx.tdata.flags = 0;
    tx.tdata.data_size = data_size;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data_buf;
    tx.tdata.data.ptr.offsets = 0;

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    // Read reply
    uint8_t reply_buf[512];
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data_buf);
    (*env)->ReleaseStringUTFChars(env, cmd, command);

    if (ret == 0) {
        if (bwr.read_consumed > 0) {
            snprintf(result, sizeof(result), "ExecCommand: succeeded, reply (%llu bytes): %s",
                     (unsigned long long)bwr.read_consumed, (char*)reply_buf);
        } else {
            snprintf(result, sizeof(result), "ExecCommand: succeeded, no reply");
        }
    } else {
        snprintf(result, sizeof(result), "ExecCommand: ioctl failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}
