#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGD("JNI_OnLoad called");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload called");
}

JNIEXPORT jobject JNICALL Java_com_example_tzpoc_MainActivity_nativeConnectSocket
  (JNIEnv* env, jobject thiz, jobject tzService, jstring path, jintArray handleArr) {
    LOGD("nativeConnectSocket called");
    if (tzService == NULL) {
        LOGE("tzService is null");
        return NULL;
    }
    if (path == NULL) {
        LOGE("path is null");
        return NULL;
    }
    jclass cls = (*env)->GetObjectClass(env, tzService);
    if (cls == NULL) {
        LOGE("Failed to get class of tzService");
        return NULL;
    }
    jmethodID mid = (*env)->GetMethodID(env, cls, "a", "(Ljava/lang/String;[I)Landroid/os/ParcelFileDescriptor;");
    if (mid == NULL) {
        LOGE("Method a not found");
        return NULL;
    }
    jobject pfd = (*env)->CallObjectMethod(env, tzService, mid, path, handleArr);
    return pfd;
}

JNIEXPORT jstring JNICALL Java_com_example_tzpoc_MainActivity_nativeReadFile
  (JNIEnv* env, jobject thiz, jstring path) {
    LOGD("nativeReadFile called");
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    int fd = open(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        LOGE("open failed: %s", strerror(errno));
        return NULL;
    }
    char buf[4096];
    ssize_t len = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (len <= 0) return NULL;
    buf[len] = '\0';
    jstring result = (*env)->NewStringUTF(env, buf);
    return result;
}

JNIEXPORT jint JNICALL Java_com_example_tzpoc_MainActivity_nativeSendLongData
  (JNIEnv* env, jclass clazz, jobject pfdObj, jbyteArray data, jint len) {
    LOGI("nativeSendLongData called, len=%d", len);
    if (pfdObj == NULL || data == NULL) {
        LOGE("Invalid parameters");
        return -1;
    }
    jclass pfdClass = (*env)->GetObjectClass(env, pfdObj);
    jmethodID getFdMethod = (*env)->GetMethodID(env, pfdClass, "getFileDescriptor", "()Ljava/io/FileDescriptor;");
    if (getFdMethod == NULL) {
        LOGE("Failed to find getFileDescriptor method");
        return -1;
    }
    jobject fdObj = (*env)->CallObjectMethod(env, pfdObj, getFdMethod);
    if (fdObj == NULL) {
        LOGE("Failed to get FileDescriptor");
        return -1;
    }

    jclass fdClass = (*env)->GetObjectClass(env, fdObj);
    jfieldID descField = (*env)->GetFieldID(env, fdClass, "descriptor", "I");
    if (descField == NULL) {
        LOGE("Failed to find descriptor field");
        return -1;
    }
    jint fd = (*env)->GetIntField(env, fdObj, descField);
    if (fd < 0) {
        LOGE("Invalid FD: %d", fd);
        return -1;
    }

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) {
        LOGE("Failed to get byte array");
        return -1;
    }

    ssize_t written = write(fd, buf, len);
    if (written < 0) {
        LOGE("write failed: %s", strerror(errno));
    } else {
        LOGI("Successfully wrote %zd bytes", written);
    }

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)written;
}
