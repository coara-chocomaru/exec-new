#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <errno.h>

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;
static jobject g_tzService = NULL;
static jmethodID g_method_a = NULL;
static jclass g_class_IMinkSocketFd = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
}

JNIEXPORT jobject JNICALL Java_com_example_tzpoc_NativeHelper_nativeConnectSocket
  (JNIEnv* env, jclass clazz, jobject tzService, jstring path, jintArray handleArr) {
    if (tzService == NULL) {
        LOGE("tzService is null");
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

JNIEXPORT jstring JNICALL Java_com_example_tzpoc_NativeHelper_nativeReadFile
  (JNIEnv* env, jclass clazz, jstring path) {
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
    return (*env)->NewStringUTF(env, buf);
}
