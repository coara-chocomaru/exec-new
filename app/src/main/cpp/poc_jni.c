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
Java_com_example_tzpoc_MainActivity_nativeGetBinderVersion(JNIEnv* env, jclass clazz, jint fd) {
    struct binder_version ver;
    char result[128] = {0};
    if (ioctl(fd, BINDER_VERSION, &ver) == 0) {
        snprintf(result, sizeof(result), "Protocol version: %d", ver.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetNodeDebugInfo(JNIEnv* env, jclass clazz, jint fd, jlong ptr) {
    struct binder_node_debug_info info;
    memset(&info, 0, sizeof(info));
    info.ptr = (binder_uintptr_t)ptr;
    char result[256] = {0};
    if (ioctl(fd, BINDER_GET_NODE_DEBUG_INFO, &info) == 0) {
        snprintf(result, sizeof(result), "ptr=%llx cookie=%llx strong=%u weak=%u",
                 (unsigned long long)info.ptr, (unsigned long long)info.cookie,
                 info.has_strong_ref, info.has_weak_ref);
        // 次のノードへ進むため、ポインタを更新（実際には応答から次のポインタを取得する必要あり）
        // この簡易版では、次の呼び出しで info.ptr を指定できるが、正しくは応答で返されるべき
        // ここでは常に同じポインタで呼ばれるが、実質的なデモとして動作
    } else {
        snprintf(result, sizeof(result), "failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

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

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderPing(JNIEnv* env, jclass clazz, jint fd) {
    return Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_PING_TRANSACTION, 0, NULL);
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetService(JNIEnv* env, jclass clazz, jint fd, jstring serviceName) {
    if (serviceName == NULL) return NULL;
    const char* name = (*env)->GetStringUTFChars(env, serviceName, NULL);
    if (name == NULL) return NULL;
    size_t len = strlen(name) + 1;
    jbyteArray data = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, data, 0, len, (jbyte*)name);
    (*env)->ReleaseStringUTFChars(env, serviceName, name);

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, 1, 0, data);
    (*env)->DeleteLocalRef(env, data);
    return reply;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderTransactionWithDump(JNIEnv* env, jclass clazz,
                                                                     jint fd, jint handle, jint code, jint flags, jbyteArray data) {
    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, handle, code, flags, data);
    char result[1024] = {0};
    if (reply != NULL) {
        jsize len = (*env)->GetArrayLength(env, reply);
        uint8_t* buf = malloc(len + 1);
        if (buf) {
            (*env)->GetByteArrayRegion(env, reply, 0, len, (jbyte*)buf);
            buf[len] = '\0';
            char hex[512] = {0};
            int hexlen = 0;
            for (int i = 0; i < len && i < 64; i++) {
                hexlen += snprintf(hex + hexlen, sizeof(hex) - hexlen, "%02x ", buf[i]);
            }
            snprintf(result, sizeof(result), "reply len=%d, hex: %s", len, hex);
            free(buf);
        } else {
            snprintf(result, sizeof(result), "reply len=%d", len);
        }
        (*env)->DeleteLocalRef(env, reply);
    } else {
        snprintf(result, sizeof(result), "no reply or error");
    }
    return (*env)->NewStringUTF(env, result);
}
