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
#include <linux/ion.h>
#include <linux/msm_ion.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <linux/binder.h>

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
    // Reference: CVE-2016-6683 / CVE-2017-13218 - ion heap overflow
    // Attempt to allocate a small ION buffer
    struct ion_allocation_data alloc_data = {
        .len = 4096,
        .align = 4096,
        .heap_id_mask = ION_HEAP_SYSTEM,
        .flags = 0,
        .handle = 0
    };
    int ret = ioctl(fd, ION_IOC_ALLOC, &alloc_data);
    if (ret < 0) {
        snprintf(result, sizeof(result), "ION_IOC_ALLOC failed: %s", strerror(errno));
        return (*env)->NewStringUTF(env, result);
    }
    // If success, try to map it (simulate)
    struct ion_fd_data fd_data = {
        .handle = alloc_data.handle,
        .fd = 0
    };
    ret = ioctl(fd, ION_IOC_MAP, &fd_data);
    if (ret < 0) {
        snprintf(result, sizeof(result), "ION_IOC_MAP failed: %s", strerror(errno));
        // Still try to free the handle
        struct ion_handle_data handle_data = { .handle = alloc_data.handle };
        ioctl(fd, ION_IOC_FREE, &handle_data);
        return (*env)->NewStringUTF(env, result);
    }
    // Cleanup
    close(fd_data.fd);
    struct ion_handle_data handle_data = { .handle = alloc_data.handle };
    ioctl(fd, ION_IOC_FREE, &handle_data);
    snprintf(result, sizeof(result), "ION test succeeded: allocated and mapped 4096 bytes (vulnerability may be exploitable)");
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwbinderTest(JNIEnv* env, jclass clazz, jint fd) {
    char result[256] = {0};
    // Reference: CVE-2020-0041 / CVE-2020-0069 - binder use-after-free
    // Try to send a simple transaction (will likely fail with EINVAL)
    struct binder_write_read bwr = {0};
    // We'll just test ioctl with a dummy command
    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) {
        snprintf(result, sizeof(result), "BINDER_WRITE_READ failed (expected): %s", strerror(errno));
        // This is expected, but we can also try to read version
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
