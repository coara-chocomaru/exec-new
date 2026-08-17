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

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ION definitions
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

// Binder definitions
#define BINDER_WRITE_READ _IOWR('b', 1, struct binder_write_read)
#define BINDER_VERSION _IOWR('b', 9, struct binder_version)

struct binder_version {
    int32_t protocol_version;
};

struct binder_write_read {
    void *write_buffer;
    size_t write_size;
    size_t write_consumed;
    void *read_buffer;
    size_t read_size;
    size_t read_consumed;
    uint64_t write_buffer_ptr;
    uint64_t read_buffer_ptr;
};

static JavaVM* g_vm = NULL;

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
    int fd = open(cpath, O_RDONLY);
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
    struct binder_write_read bwr = {0};
    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) {
        struct binder_version version;
        ret = ioctl(fd, BINDER_VERSION, &version);
        if (ret == 0) {
            snprintf(result, sizeof(result), "Binder version: %d (protocol %d) - vulnerability may be exploitable",
                     version.protocol_version, version.protocol_version);
        } else {
            snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
        }
    } else {
        snprintf(result, sizeof(result), "Unexpected success in BINDER_WRITE_READ");
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderFurther(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};
    // Try to get binder version first
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder protocol: %d. ", version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s. ", strerror(errno));
    }

    // Try to send a simple transaction (using a dummy handle 0)
    // This is known to work on some devices and may cause kernel log messages
    struct binder_transaction_data {
        void *data;
        size_t data_size;
        void *offsets;
        size_t offsets_size;
        uint64_t buffer;
        uint64_t flags;
        uint64_t sender_pid;
        uint64_t sender_euid;
        uint64_t target_handle;
    } __attribute__((packed));

    // We'll use a small buffer to try to write
    int buf[1] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_buffer = (void*)buf;
    bwr.write_size = sizeof(buf);
    bwr.write_consumed = 0;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) {
        strcat(result, "WRITE_READ (dummy) failed (expected): ");
        strcat(result, strerror(errno));
    } else {
        strcat(result, "WRITE_READ succeeded (unexpected)");
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
