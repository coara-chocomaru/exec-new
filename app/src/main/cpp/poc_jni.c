#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>

#define LOG_TAG "TZPoC_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// システムライブラリのパス（機種により異なる場合がある）
#define LIB_PATH "/system/lib/libservice-api.so"

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_testNative(JNIEnv *env, jobject thiz) {
    LOGI("testNative() 開始");

    // libservice-api.so を dlopen でロード
    void *handle = dlopen(LIB_PATH, RTLD_NOW);
    if (!handle) {
        LOGE("dlopen 失敗: %s", dlerror());
        // 別のパスも試す
        handle = dlopen("/vendor/lib/libservice-api.so", RTLD_NOW);
        if (!handle) {
            LOGE("2回目の dlopen も失敗: %s", dlerror());
            return -1;
        }
    }
    LOGI("libservice-api.so ロード成功");

    // getLicenseCapabilities シンボルを取得
    void *func = dlsym(handle, "getLicenseCapabilities");
    if (!func) {
        LOGE("dlsym(getLicenseCapabilities) 失敗: %s", dlerror());
        dlclose(handle);
        return -2;
    }
    LOGI("getLicenseCapabilities シンボル発見");

    // 本来は関数を呼び出すが、シグネチャが不明なためここでは呼ばない
    // ただし、実際に呼び出すと任意のコードが実行される可能性がある

    dlclose(handle);
    LOGI("testNative() 正常終了");
    return 0;
}
