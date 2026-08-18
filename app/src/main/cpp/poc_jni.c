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

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BINDER_PING_TRANSACTION 0xFFFFFFFE
#define BINDER_SERVICE_MANAGER_GET_SERVICE 1

#ifndef BINDER_VERSION
struct binder_version {
    int32_t protocol_version;
};
#endif

#ifndef BC_TRANSACTION
#define BC_TRANSACTION 0x40046201
#endif

#ifndef BINDER_WRITE_READ
#define BINDER_WRITE_READ 0x40046200
#endif

#ifndef BINDER_VERSION_IOCTL
#define BINDER_VERSION_IOCTL _IOR('b', 9, struct binder_version)
#endif

#ifndef TF_ONE_WAY
#define TF_ONE_WAY 0x01
#endif

#ifndef binder_uintptr_t
#define binder_uintptr_t uintptr_t
#endif

// ----- 基本ユーティリティ -----
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

// ----- 汎用トランザクション送信（データ付き・空データ可） -----
JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(JNIEnv* env, jclass clazz,
                                                             jint fd, jint handle, jint code, jint flags, jbyteArray data) {
    // データを取得
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

    // トランザクション構造体
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        // データが続く（オフセット0）
    } __attribute__((packed)) tx;
    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = (uint32_t)handle;
    tx.tdata.code = (uint32_t)code;
    tx.tdata.flags = (uint32_t)flags;
    tx.tdata.sender_pid = 0;
    tx.tdata.sender_euid = 0;
    tx.tdata.data_size = tx_data_size;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)tx_data;  // データは続けて配置

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    // 読み取りバッファ（最大4096）
    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);

    if (tx_data != NULL) free(tx_data);

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
JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderPing(JNIEnv* env, jclass clazz, jint fd) {
    return Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_PING_TRANSACTION, 0, NULL);
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetService(JNIEnv* env, jclass clazz, jint fd, jstring serviceName) {
    if (serviceName == NULL) return NULL;
    const char* name = (*env)->GetStringUTFChars(env, serviceName, NULL);
    if (name == NULL) return NULL;
    size_t len = strlen(name) + 1;  // null終端を含む
    jbyteArray data = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, data, 0, len, (jbyte*)name);
    (*env)->ReleaseStringUTFChars(env, serviceName, name);

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_SERVICE_MANAGER_GET_SERVICE, 0, data);
    (*env)->DeleteLocalRef(env, data);
    return reply;
}
