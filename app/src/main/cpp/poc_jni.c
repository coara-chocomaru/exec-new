#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <linux/types.h>

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BINDER_PING_TRANSACTION 0xFFFFFFFE
#define BINDER_SERVICE_MANAGER_GET_SERVICE 1

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeOpenDevice(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return -1;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return -1;
    int fd = open(cpath, O_RDWR);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", cpath, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -errno;
    }
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return fd;
}

// 汎用トランザクション送信（データ付き・空データ可）
JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(JNIEnv* env, jclass clazz,
                                                             jint fd, jint handle, jint code, jint flags, jbyteArray data) {
    uint8_t* tx_data = NULL;
    size_t tx_data_size = 0;
    if (data != NULL) {
        tx_data_size = (*env)->GetArrayLength(env, data);
        tx_data = malloc(tx_data_size);
        if (tx_data == NULL) {
            LOGE("malloc failed");
            return NULL;
        }
        (*env)->GetByteArrayRegion(env, data, 0, tx_data_size, (jbyte*)tx_data);
    }

    // コマンド + binder_transaction_data + データ の連続領域
    size_t cmd_size = sizeof(uint32_t) + sizeof(struct binder_transaction_data);
    size_t total_size = cmd_size + tx_data_size;
    uint8_t* buf = malloc(total_size);
    if (buf == NULL) {
        if (tx_data) free(tx_data);
        LOGE("malloc for buffer failed");
        return NULL;
    }

    uint32_t* cmd = (uint32_t*)buf;
    *cmd = BC_TRANSACTION;
    struct binder_transaction_data* tdata = (struct binder_transaction_data*)(buf + sizeof(uint32_t));
    memset(tdata, 0, sizeof(struct binder_transaction_data));
    tdata->target.handle = (uint32_t)handle;
    tdata->code = (uint32_t)code;
    tdata->flags = (uint32_t)flags;
    tdata->data_size = tx_data_size;
    tdata->offsets_size = 0;
    tdata->data.ptr.buffer = (binder_uintptr_t)(buf + cmd_size);
    tdata->data.ptr.offsets = 0;

    if (tx_data_size > 0 && tx_data != NULL) {
        memcpy(buf + cmd_size, tx_data, tx_data_size);
    }

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = total_size;
    bwr.write_buffer = (binder_uintptr_t)buf;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);

    if (tx_data) free(tx_data);
    free(buf);

    if (ret < 0) {
        LOGE("ioctl failed: %s", strerror(errno));
        return NULL;
    }
    if (bwr.read_consumed == 0) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, bwr.read_consumed);
    (*env)->SetByteArrayRegion(env, result, 0, bwr.read_consumed, (jbyte*)read_buf);
    return result;
}

// PING トランザクション (code=0xFFFFFFFE)
JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderPing(JNIEnv* env, jclass clazz, jint fd) {
    return Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_PING_TRANSACTION, 0, NULL);
}

// サービス取得 (handle=0, code=1, サービス名をデータとして送信)
JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetService(JNIEnv* env, jclass clazz, jint fd, jstring serviceName) {
    if (serviceName == NULL) return NULL;
    const char* name = (*env)->GetStringUTFChars(env, serviceName, NULL);
    if (name == NULL) return NULL;
    size_t len = strlen(name) + 1;
    jbyteArray data = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, data, 0, len, (jbyte*)name);
    (*env)->ReleaseStringUTFChars(env, serviceName, name);

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_SERVICE_MANAGER_GET_SERVICE, 0, data);
    (*env)->DeleteLocalRef(env, data);
    return reply;
}

// ダンプ用：空トランザクションを送り、応答をファイルに保存（Java 側でファイル名を指定）
JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderDumpReply(JNIEnv* env, jclass clazz,
                                                           jint fd, jint handle, jint code, jint flags, jstring filename) {
    char result[256] = {0};
    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, handle, code, flags, NULL);
    if (reply != NULL) {
        jsize len = (*env)->GetArrayLength(env, reply);
        snprintf(result, sizeof(result), "reply len=%d", len);
        // ファイル保存は Java 側で行うため、ここでは応答をそのまま返す
        // Java 側で dumpToFile を呼び出す
        (*env)->DeleteLocalRef(env, reply);
    } else {
        snprintf(result, sizeof(result), "no reply or error");
    }
    return (*env)->NewStringUTF(env, result);
}
