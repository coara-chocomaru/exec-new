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

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeWriteFile(JNIEnv* env, jclass clazz, jstring path, jstring content) {
    if (path == NULL || content == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    const char* ccontent = (*env)->GetStringUTFChars(env, content, NULL);
    if (cpath == NULL || ccontent == NULL) {
        if (cpath) (*env)->ReleaseStringUTFChars(env, path, cpath);
        if (ccontent) (*env)->ReleaseStringUTFChars(env, content, ccontent);
        return NULL;
    }
    int fd = open(cpath, O_WRONLY);
    if (fd < 0) {
        LOGE("open(%s) for write failed: %s", cpath, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        (*env)->ReleaseStringUTFChars(env, content, ccontent);
        return (*env)->NewStringUTF(env, strerror(errno));
    }
    ssize_t written = write(fd, ccontent, strlen(ccontent));
    close(fd);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    (*env)->ReleaseStringUTFChars(env, content, ccontent);
    if (written < 0) return (*env)->NewStringUTF(env, strerror(errno));
    return (*env)->NewStringUTF(env, "OK");
}

// 汎用トランザクション送信
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

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, 0, BINDER_SERVICE_MANAGER_GET_SERVICE, 0, data);
    (*env)->DeleteLocalRef(env, data);
    return reply;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderReadReply(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jint flags) {
    return Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, handle, code, flags, NULL);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderWriteMemory(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jlong address, jlong value) {
    char result[256] = {0};
    uint8_t data[16];
    memcpy(data, &address, 8);
    memcpy(data + 8, &value, 8);
    jbyteArray jdata = (*env)->NewByteArray(env, 16);
    (*env)->SetByteArrayRegion(env, jdata, 0, 16, (jbyte*)data);
    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, handle, code, 0, jdata);
    (*env)->DeleteLocalRef(env, jdata);
    if (reply != NULL) {
        snprintf(result, sizeof(result), "WriteMemory: succeeded, reply len=%d", (int)(*env)->GetArrayLength(env, reply));
        (*env)->DeleteLocalRef(env, reply);
    } else {
        snprintf(result, sizeof(result), "WriteMemory: failed (no reply or error)");
    }
    return (*env)->NewStringUTF(env, result);
}

// コマンド実行テスト：コマンド文字列をデータとして送信
JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderExecCommand(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jstring command) {
    char result[512] = {0};
    if (command == NULL) {
        snprintf(result, sizeof(result), "ExecCommand: command is null");
        return (*env)->NewStringUTF(env, result);
    }
    const char* cmd = (*env)->GetStringUTFChars(env, command, NULL);
    if (cmd == NULL) {
        snprintf(result, sizeof(result), "ExecCommand: failed to get command string");
        return (*env)->NewStringUTF(env, result);
    }
    size_t len = strlen(cmd) + 1;
    jbyteArray jdata = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, jdata, 0, len, (jbyte*)cmd);
    (*env)->ReleaseStringUTFChars(env, command, cmd);

    jbyteArray reply = Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(env, clazz, fd, handle, code, 0, jdata);
    (*env)->DeleteLocalRef(env, jdata);
    if (reply != NULL) {
        jsize rlen = (*env)->GetArrayLength(env, reply);
        if (rlen > 0) {
            uint8_t* rbuf = malloc(rlen + 1);
            if (rbuf) {
                (*env)->GetByteArrayRegion(env, reply, 0, rlen, (jbyte*)rbuf);
                rbuf[rlen] = '\0';
                snprintf(result, sizeof(result), "ExecCommand: succeeded, reply (%d bytes): %s", rlen, (char*)rbuf);
                free(rbuf);
            } else {
                snprintf(result, sizeof(result), "ExecCommand: succeeded, reply len=%d", rlen);
            }
        } else {
            snprintf(result, sizeof(result), "ExecCommand: succeeded, empty reply");
        }
        (*env)->DeleteLocalRef(env, reply);
    } else {
        snprintf(result, sizeof(result), "ExecCommand: failed (no reply or error)");
    }
    return (*env)->NewStringUTF(env, result);
}
