// native-lib.c
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TZPoC", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TZPoC", __VA_ARGS__)

static void* lib_handle = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    lib_handle = dlopen("libservice-api.so", RTLD_NOW);
    if (!lib_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_ERR;
    }
    LOGI("libservice-api.so loaded successfully");
    return JNI_VERSION_1_6;
}

// ヘルパー: 配列型チェック
static int check_int_array(JNIEnv* env, jobject obj) {
    jclass clazz = (*env)->FindClass(env, "[I");
    return (*env)->IsInstanceOf(env, obj, clazz);
}

// ------------------------------------------------------------
// TlocServiceImpl
// ------------------------------------------------------------
JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_TlocServiceImpl_tlocWarmUp
  (JNIEnv* env, jobject thiz, jstring str, jint len, jintArray result) {
    if (!lib_handle) return -1;
    if (!check_int_array(env, result)) {
        LOGE("tlocWarmUp: result is not int[]");
        return -3;
    }

    int (*func)(char*, unsigned int, int*) = dlsym(lib_handle, "tlocWarmUp");
    if (!func) {
        LOGE("tlocWarmUp symbol not found");
        return -2;
    }

    const char* cstr = (*env)->GetStringUTFChars(env, str, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    int ret = func((char*)cstr, (unsigned int)len, res);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseStringUTFChars(env, str, cstr);
    return ret;
}

JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_TlocServiceImpl_getTrustedLocation
  (JNIEnv* env, jobject thiz, jbyteArray data, jint len, jintArray result, jobject cb, jint flags, jintArray outLen) {
    if (!lib_handle) return -1;
    if (!check_int_array(env, result) || !check_int_array(env, outLen)) {
        LOGE("getTrustedLocation: result or outLen is not int[]");
        return -3;
    }

    int (*func)(unsigned char*, unsigned int, int*, void*, unsigned int, unsigned int*) =
        dlsym(lib_handle, "getTrustedLocation");
    if (!func) {
        LOGE("getTrustedLocation symbol not found");
        return -2;
    }

    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    jint* olen = (*env)->GetIntArrayElements(env, outLen, NULL);
    int ret = func((unsigned char*)buf, (unsigned int)len, res, NULL, (unsigned int)flags, (unsigned int*)olen);
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseIntArrayElements(env, outLen, olen, 0);
    return ret;
}

// ------------------------------------------------------------
// RticReportImpl
// ------------------------------------------------------------
JNIEXPORT jint JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_RticReportImpl_getRticData
  (JNIEnv* env, jobject thiz, jbyteArray data, jint len, jlong id, jintArray result, jobject cb,
   jint flags, jintArray outLen, jint mode) {
    if (!lib_handle) return -1;
    if (!check_int_array(env, result) || !check_int_array(env, outLen)) {
        LOGE("getRticData: result or outLen is not int[]");
        return -3;
    }

    int (*func)(unsigned char*, unsigned int, unsigned long long, int*, void*, unsigned int, unsigned int*, int) =
        dlsym(lib_handle, "getRticDataEx");
    if (!func) {
        LOGE("getRticDataEx symbol not found");
        return -2;
    }

    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    jint* res = (*env)->GetIntArrayElements(env, result, NULL);
    jint* olen = (*env)->GetIntArrayElements(env, outLen, NULL);
    int ret = func((unsigned char*)buf, (unsigned int)len, (unsigned long long)id, res, NULL,
                   (unsigned int)flags, (unsigned int*)olen, (int)mode);
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);
    (*env)->ReleaseIntArrayElements(env, result, res, 0);
    (*env)->ReleaseIntArrayElements(env, outLen, olen, 0);
    return ret;
}

// ------------------------------------------------------------
// ServiceManagerImpl
// ------------------------------------------------------------
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeInit
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeInit");
    if (func) func();
    else LOGE("ServiceManagerImpl_nativeInit not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeDestroy
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_ServiceManagerImpl_nativeDestroy");
    if (func) func();
    else LOGE("ServiceManagerImpl_nativeDestroy not found");
}

// ------------------------------------------------------------
// BuildUtils
// ------------------------------------------------------------
JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeInit
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeInit");
    if (func) func();
    else LOGE("BuildUtils_nativeInit not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeDestroy
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeDestroy");
    if (func) func();
    else LOGE("BuildUtils_nativeDestroy not found");
}

JNIEXPORT jstring JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeGetBuildFlavor
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return (*env)->NewStringUTF(env, "error_no_lib");
    jstring (*func)(JNIEnv*, jobject) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_BuildUtils_nativeGetBuildFlavor");
    if (!func) {
        LOGE("nativeGetBuildFlavor not found");
        return (*env)->NewStringUTF(env, "symbol_not_found");
    }
    return func(env, thiz);
}

// ------------------------------------------------------------
// WifiAuditorServiceImpl
// ------------------------------------------------------------
JNIEXPORT jlong JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeCreate
  (JNIEnv* env, jobject thiz, jstring path) {
    if (!lib_handle) return 0;
    jlong (*func)(JNIEnv*, jobject, jstring) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeCreate");
    if (!func) {
        LOGE("nativeCreate not found");
        return 0;
    }
    return func(env, thiz, path);
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeDestroy
  (JNIEnv* env, jobject thiz, jlong handle) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jlong) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeDestroy");
    if (func) func(env, thiz, handle);
    else LOGE("nativeDestroy not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartScan
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartScan");
    if (func) func();
    else LOGE("nativeStartScan not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartClientScan
  (JNIEnv* env, jobject thiz, jstring clientId) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartClientScan");
    if (func) func(env, thiz, clientId);
    else LOGE("nativeStartClientScan not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartAssociationScan
  (JNIEnv* env, jobject thiz) {
    if (!lib_handle) return;
    void (*func)() = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeStartAssociationScan");
    if (func) func();
    else LOGE("nativeStartAssociationScan not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeOnFeedbackReceived
  (JNIEnv* env, jobject thiz, jstring a, jstring b, jstring c, jbyte d) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring, jstring, jstring, jbyte) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeOnFeedbackReceived");
    if (func) func(env, thiz, a, b, c, d);
    else LOGE("nativeOnFeedbackReceived not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeUpdateModel
  (JNIEnv* env, jobject thiz, jstring model) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeUpdateModel");
    if (func) func(env, thiz, model);
    else LOGE("nativeUpdateModel not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeGetTrustedAps
  (JNIEnv* env, jobject thiz, jstring clientId, jbyteArray data, jint len) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring, jbyteArray, jint) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeGetTrustedAps");
    if (func) func(env, thiz, clientId, data, len);
    else LOGE("nativeGetTrustedAps not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRemoveTrustedAp
  (JNIEnv* env, jobject thiz, jstring clientId, jstring bssid, jstring ssid, jbyteArray data, jint len) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring, jstring, jstring, jbyteArray, jint) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRemoveTrustedAp");
    if (func) func(env, thiz, clientId, bssid, ssid, data, len);
    else LOGE("nativeRemoveTrustedAp not found");
}

JNIEXPORT void JNICALL Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRegisterClient
  (JNIEnv* env, jobject thiz, jstring clientId) {
    if (!lib_handle) return;
    void (*func)(JNIEnv*, jobject, jstring) = dlsym(lib_handle, "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_WifiAuditorServiceImpl_nativeRegisterClient");
    if (func) func(env, thiz, clientId);
    else LOGE("nativeRegisterClient not found");
}
