#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TZPoC", __VA_ARGS__)

static void* handle = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    handle = dlopen("libservice-api.so", RTLD_NOW);
    if (!handle) LOGI("dlopen failed: %s", dlerror());
    else LOGI("libservice-api.so loaded");
    return JNI_VERSION_1_6;
}

// TlocServiceImpl
JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_TlocServiceImpl_tlocWarmUp
  (JNIEnv* env, jobject thiz, jstring str, jint len, jintArray result) {
    if (!handle) return -1;
    int (*func)(char*, unsigned int, int*) = dlsym(handle, "tlocWarmUp");
    if (!func) return -2;
    const char* cstr = (*env)->GetStringUTFChars(env, str, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    int ret = func((char*)cstr, (unsigned int)len, res);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseStringUTFChars(env, str, cstr);
    return ret;
}

JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_TlocServiceImpl_getTrustedLocation
  (JNIEnv* env, jobject thiz, jbyteArray data, jint len, jintArray result, jobject cb, jint flags, jintArray outLen) {
    if (!handle) return -1;
    int (*func)(unsigned char*, unsigned int, int*, void*, unsigned int, unsigned int*) = 
        dlsym(handle, "getTrustedLocation");
    if (!func) return -2;
    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    jint* olen = (*env)->GetIntArrayElements(env, outLen, NULL);
    int ret = func((unsigned char*)buf, (unsigned int)len, res, NULL, (unsigned int)flags, (unsigned int*)olen);
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseIntArrayElements(env, outLen, olen, 0);
    return ret;
}

// RticReportImpl
JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_RticReportImpl_getRticData
  (JNIEnv* env, jobject thiz, jbyteArray data, jint len, jlong id, jintArray result, jobject cb, jint flags, jintArray outLen, jint mode) {
    if (!handle) return -1;
    int (*func)(unsigned char*, unsigned int, unsigned long long, int*, void*, unsigned int, unsigned int*, int) = 
        dlsym(handle, "getRticDataEx");
    if (!func) return -2;
    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    jint* olen = (*env)->GetIntArrayElements(env, outLen, NULL);
    int ret = func((unsigned char*)buf, (unsigned int)len, (unsigned long long)id, res, NULL, (unsigned int)flags, (unsigned int*)olen, (int)mode);
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseIntArrayElements(env, outLen, olen, 0);
    return ret;
}

// ServiceManagerImpl
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeInit
  (JNIEnv* env, jobject thiz) {
    if (!handle) return;
    void (*func)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeInit");
    if (func) func();
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeDestroy
  (JNIEnv* env, jobject thiz) {
    if (!handle) return;
    void (*func)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeDestroy");
    if (func) func();
}

// BuildUtils
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeInit
  (JNIEnv* env, jobject thiz) { void (*f)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeInit"); if(f) f(); }

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeDestroy
  (JNIEnv* env, jobject thiz) { void (*f)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeDestroy"); if(f) f(); }

JNIEXPORT jstring JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeGetBuildFlavor
  (JNIEnv* env, jobject thiz) {
    if (!handle) return (*env)->NewStringUTF(env, "error");
    jstring (*f)(JNIEnv*, jobject) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeGetBuildFlavor");
    if (!f) return (*env)->NewStringUTF(env, "null");
    return f(env, thiz);
}

// WifiAuditorServiceImpl (要約)
#define WRAP_WIFI(name, ret, ...) \
    ret (*f_##name)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_" #name); \
    if(f_##name) f_##name(__VA_ARGS__);

JNIEXPORT jlong JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeCreate
  (JNIEnv* env, jobject thiz, jstring path) {
    if (!handle) return 0;
    jlong (*f)(JNIEnv*, jobject, jstring) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeCreate");
    return f ? f(env, thiz, path) : 0;
}
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeDestroy
  (JNIEnv* env, jobject thiz, jlong h) { void (*f)(JNIEnv*, jobject, jlong) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeDestroy"); if(f) f(env, thiz, h); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartScan
  (JNIEnv* env, jobject thiz) { void (*f)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartScan"); if(f) f(); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartClientScan
  (JNIEnv* env, jobject thiz, jstring id) { void (*f)(JNIEnv*, jobject, jstring) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartClientScan"); if(f) f(env, thiz, id); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartAssociationScan
  (JNIEnv* env, jobject thiz) { void (*f)() = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartAssociationScan"); if(f) f(); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeOnFeedbackReceived
  (JNIEnv* env, jobject thiz, jstring a, jstring b, jstring c, jbyte d) { void (*f)(JNIEnv*, jobject, jstring, jstring, jstring, jbyte) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeOnFeedbackReceived"); if(f) f(env, thiz, a, b, c, d); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeUpdateModel
  (JNIEnv* env, jobject thiz, jstring m) { void (*f)(JNIEnv*, jobject, jstring) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeUpdateModel"); if(f) f(env, thiz, m); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeGetTrustedAps
  (JNIEnv* env, jobject thiz, jstring id, jbyteArray d, jint l) { void (*f)(JNIEnv*, jobject, jstring, jbyteArray, jint) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeGetTrustedAps"); if(f) f(env, thiz, id, d, l); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRemoveTrustedAp
  (JNIEnv* env, jobject thiz, jstring id, jstring bssid, jstring ssid, jbyteArray d, jint l) { void (*f)(JNIEnv*, jobject, jstring, jstring, jstring, jbyteArray, jint) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRemoveTrustedAp"); if(f) f(env, thiz, id, bssid, ssid, d, l); }
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRegisterClient
  (JNIEnv* env, jobject thiz, jstring id) { void (*f)(JNIEnv*, jobject, jstring) = dlsym(handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRegisterClient"); if(f) f(env, thiz, id); }
