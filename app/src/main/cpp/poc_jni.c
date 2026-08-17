#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <limits.h>

#include "binder.h"

#define LOG_TAG "BinderPOC"
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

static char* read_file_content(const char* path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;
    char* buf = malloc(8192);
    if (!buf) { close(fd); return NULL; }
    ssize_t n = read(fd, buf, 8191);
    close(fd);
    if (n <= 0) { free(buf); return NULL; }
    buf[n] = '\0';
    return buf;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderVersion(JNIEnv* env, jclass clazz, jint fd) {
    char result[128] = {0};
    struct binder_version ver;
    if (ioctl(fd, BINDER_VERSION, &ver) == 0) {
        snprintf(result, sizeof(result), "Protocol version: %d", ver.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderSetMaxThreads(JNIEnv* env, jclass clazz, jint fd, jint max) {
    char result[128] = {0};
    if (ioctl(fd, BINDER_SET_MAX_THREADS, &max) == 0) {
        snprintf(result, sizeof(result), "Set max threads to %d OK", max);
    } else {
        snprintf(result, sizeof(result), "BINDER_SET_MAX_THREADS failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetNodeInfo(JNIEnv* env, jclass clazz, jint fd, jint handle) {
    char result[256] = {0};
    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = handle;
    if (ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info) == 0) {
        snprintf(result, sizeof(result), "Node info: strong=%u weak=%u", info.strong_count, info.weak_count);
    } else {
        snprintf(result, sizeof(result), "BINDER_GET_NODE_INFO_FOR_REF failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(JNIEnv* env, jclass clazz, jint fd, jint targetHandle, jint flags) {
    char result[512] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    memset(&tx.tdata, 0, sizeof(tx.tdata));
    tx.tdata.target.handle = targetHandle;
    tx.tdata.flags = flags;
    tx.tdata.data_size = 0;
    tx.tdata.offsets_size = 0;

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        snprintf(result, sizeof(result), "Transaction sent, read_consumed=%llu", (unsigned long long)bwr.read_consumed);
        if (bwr.read_consumed > 0) strcat(result, " (reply received)");
    } else {
        snprintf(result, sizeof(result), "BINDER_WRITE_READ failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderOverflow(JNIEnv* env, jclass clazz, jint fd, jlong size) {
    char result[256] = {0};
    if (size <= 0) {
        snprintf(result, sizeof(result), "Invalid size");
        return (*env)->NewStringUTF(env, result);
    }
    char* buf = malloc((size_t)size);
    if (!buf) {
        snprintf(result, sizeof(result), "Failed to allocate %lld bytes", (long long)size);
        return (*env)->NewStringUTF(env, result);
    }
    memset(buf, 0x41, (size_t)size);

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = (binder_size_t)size;
    bwr.write_buffer = (binder_uintptr_t)buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(buf);
    if (ret == 0) {
        snprintf(result, sizeof(result), "Overflow write succeeded (unexpected)");
    } else {
        snprintf(result, sizeof(result), "Overflow write failed: %s (expected)", strerror(errno));
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

JNIEXPORT jobjectArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderfsList(JNIEnv* env, jclass clazz, jstring path) {
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
Java_com_example_tzpoc_MainActivity_nativeBinderfsRead(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    char* content = read_file_content(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (content == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, content);
    free(content);
    return result;
}
