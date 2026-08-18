#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>

#define LOG_TAG "TZPoC_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 関数ポインタ型定義 (JNI 規約に準拠)
typedef jint (*GetLicenseFunc)(JNIEnv*, jclass, jbyteArray);
typedef jint (*GetQmigFunc)(JNIEnv*, jclass);
typedef jobject (*GetRticDataFunc)(JNIEnv*, jclass, jbyteArray, jlong);
typedef jobject (*GetTrustedLocationFunc)(JNIEnv*, jclass, jbyteArray);

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_callSystemNativeMethods(JNIEnv *env, jobject thiz, jbyteArray creds) {
    LOGI("callSystemNativeMethods() started");
    jint totalResult = 0;

    // ---- 1. libservice-api.so をロード ----
    const char *libPaths[] = {
        "/system/lib/libservice-api.so",
        "/vendor/lib/libservice-api.so",
        "/system/lib64/libservice-api.so",
        "/vendor/lib64/libservice-api.so"
    };
    void *handle = NULL;
    for (int i = 0; i < 4; i++) {
        handle = dlopen(libPaths[i], RTLD_NOW);
        if (handle) {
            LOGI("dlopen success: %s", libPaths[i]);
            break;
        }
    }
    if (!handle) {
        LOGE("dlopen libservice-api failed: %s", dlerror());
        return -1;
    }

    // ---- 2. getLicenseCapabilities 呼び出し ----
    GetLicenseFunc getLicense = (GetLicenseFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_cloud_ReportJobService_getLicenseCapabilities");
    if (getLicense) {
        LOGI("getLicenseCapabilities found");
        jint licenseVal = getLicense(env, NULL, creds);
        LOGI("getLicenseCapabilities returned: %d", licenseVal);
        totalResult += licenseVal;
    } else {
        LOGE("dlsym getLicenseCapabilities failed: %s", dlerror());
    }

    // ---- 3. getQmigCapabilities 呼び出し ----
    GetQmigFunc getQmig = (GetQmigFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_cloud_ReportJobService_getQmigCapabilities");
    if (getQmig) {
        LOGI("getQmigCapabilities found");
        jint qmigVal = getQmig(env, NULL);
        LOGI("getQmigCapabilities returned: %d", qmigVal);
        totalResult += qmigVal;
    } else {
        LOGE("dlsym getQmigCapabilities failed: %s", dlerror());
    }

    // ---- 4. getRticData 呼び出し (RticReportImpl) ----
    GetRticDataFunc getRtic = (GetRticDataFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_RticReportImpl_getRticData");
    if (getRtic) {
        LOGI("getRticData found");
        jobject rticResult = getRtic(env, NULL, creds, 0L);
        if (rticResult) {
            LOGI("getRticData returned non-null object");
            // ここで結果を解析することも可能 (RticReport クラスのフィールドを読み取る)
        } else {
            LOGI("getRticData returned null");
        }
    } else {
        LOGE("dlsym getRticData failed: %s", dlerror());
    }

    // ---- 5. getTrustedLocation 呼び出し (TlocServiceImpl) ----
    GetTrustedLocationFunc getTloc = (GetTrustedLocationFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_core_TlocServiceImpl_getTrustedLocation");
    if (getTloc) {
        LOGI("getTrustedLocation found");
        jobject tlocResult = getTloc(env, NULL, creds);
        if (tlocResult) {
            LOGI("getTrustedLocation returned non-null object");
        } else {
            LOGI("getTrustedLocation returned null");
        }
    } else {
        LOGE("dlsym getTrustedLocation failed: %s", dlerror());
    }

    dlclose(handle);

    // ---- 6. 追加: liblocservice.so のシンボル確認 (呼び出しはスキップ) ----
    void *locHandle = dlopen("/system/lib/liblocservice.so", RTLD_NOW);
    if (!locHandle) {
        locHandle = dlopen("/vendor/lib/liblocservice.so", RTLD_NOW);
    }
    if (locHandle) {
        // LocService::GetLocation のシンボルが存在するか確認
        void *sym = dlsym(locHandle, "_ZN10LocService11GetLocationEv");
        if (sym) {
            LOGI("_ZN10LocService11GetLocationEv found (call skipped, needs instance)");
        } else {
            LOGE("_ZN10LocService11GetLocationEv not found");
        }
        dlclose(locHandle);
    } else {
        LOGE("dlopen liblocservice failed");
    }

    LOGI("callSystemNativeMethods completed, totalResult=%d", totalResult);
    return totalResult;
}
