#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>

#define LOG_TAG "TZPoC_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 関数ポインタ型定義 (アセンブリ解析結果に基づく)
typedef jint (*GetLicenseFunc)(JNIEnv*, jclass, jbyteArray);
typedef jint (*GetQmigFunc)(JNIEnv*, jclass);
typedef int (*LocGetLocationFunc)(void*);

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_callSystemNativeMethods(JNIEnv *env, jobject thiz) {
    LOGI("callSystemNativeMethods() started");
    jint totalResult = 0;

    // ---- 1. libservice-api.so のロードと関数呼び出し ----
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

    // 2. getLicenseCapabilities シンボル取得
    GetLicenseFunc getLicense = (GetLicenseFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_cloud_ReportJobService_getLicenseCapabilities");
    if (!getLicense) {
        LOGE("dlsym getLicenseCapabilities failed: %s", dlerror());
    } else {
        LOGI("getLicenseCapabilities symbol found");
        // ダミーの byte[] を作成
        jbyteArray dummyArr = (*env)->NewByteArray(env, 16);
        if (dummyArr) {
            jint licenseVal = getLicense(env, NULL, dummyArr);
            LOGI("getLicenseCapabilities returned: %d", licenseVal);
            totalResult += licenseVal;
            (*env)->DeleteLocalRef(env, dummyArr);
        } else {
            LOGE("NewByteArray failed");
        }
    }

    // 3. getQmigCapabilities シンボル取得
    GetQmigFunc getQmig = (GetQmigFunc)dlsym(handle,
        "Java_com_qualcomm_qti_qms_service_connectionsecurity_cloud_ReportJobService_getQmigCapabilities");
    if (!getQmig) {
        LOGE("dlsym getQmigCapabilities failed: %s", dlerror());
    } else {
        LOGI("getQmigCapabilities symbol found");
        jint qmigVal = getQmig(env, NULL);
        LOGI("getQmigCapabilities returned: %d", qmigVal);
        totalResult += qmigVal;
    }

    dlclose(handle);

    // ---- 4. liblocservice.so のロードと LocService::GetLocation 呼び出し ----
    void *locHandle = NULL;
    const char *locPaths[] = {
        "/system/lib/liblocservice.so",
        "/vendor/lib/liblocservice.so",
        "/system/lib64/liblocservice.so",
        "/vendor/lib64/liblocservice.so"
    };
    for (int i = 0; i < 4; i++) {
        locHandle = dlopen(locPaths[i], RTLD_NOW);
        if (locHandle) {
            LOGI("dlopen success: %s", locPaths[i]);
            break;
        }
    }
    if (locHandle) {
        LocGetLocationFunc getLoc = (LocGetLocationFunc)dlsym(locHandle, "_ZN10LocService11GetLocationEv");
        if (getLoc) {
            LOGI("LocService::GetLocation symbol found");
            // LocService のインスタンスが必要だが、シングルトンが存在する可能性がある
            // ここでは呼び出しを試みる（クラッシュリスクあり）
            // 安全のため、実際の呼び出しはコメントアウト
            // int locResult = getLoc(NULL);
            // LOGI("GetLocation returned: %d", locResult);
            // totalResult += locResult;
        } else {
            LOGE("dlsym _ZN10LocService11GetLocationEv failed: %s", dlerror());
        }
        dlclose(locHandle);
    } else {
        LOGE("dlopen liblocservice failed: %s", dlerror());
    }

    LOGI("callSystemNativeMethods completed, totalResult=%d", totalResult);
    return totalResult;
}
