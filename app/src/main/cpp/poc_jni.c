#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <ctype.h>
#include <time.h>

#define DUMP_BASE "/sdcard/download/"
#define BLOCK_PREFIX "/dev/block/"
#define PROC_PREFIX "/proc/self/"
#define VENDOR_PREFIX "/vendor/bin/"
#define DATA_PREFIX "/data/"
#define SYSTEM_DIR "system"
#define MISC_DIR "misc"
#define MAX_PATH 4096
#define CHUNK_SIZE (1024 * 1024)

// 安全な文字列連結
static void safe_concat(char *dest, const char *src, size_t max) {
    size_t len = strlen(dest);
    if (len + strlen(src) + 1 < max) {
        strcat(dest, src);
    }
}

// ディレクトリ作成（再帰的）
static void mkdir_recursive(const char *path) {
    char tmp[MAX_PATH];
    char *p;
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}

// ファイルコピー（1MB読み込み専用）
static void dump_file_partial(const char *src, const char *dst) {
    int fd_in = open(src, O_RDONLY);
    if (fd_in < 0) return;
    int fd_out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd_out < 0) {
        close(fd_in);
        return;
    }
    char buf[CHUNK_SIZE];
    ssize_t n;
    size_t total = 0;
    while ((n = read(fd_in, buf, sizeof(buf))) > 0) {
        if (write(fd_out, buf, n) != n) break;
        total += n;
        if (total >= CHUNK_SIZE) break; // 1MB only for block devices
    }
    close(fd_in);
    close(fd_out);
}

// 完全ファイルコピー（テキスト・小ファイル用）
static void dump_file_full(const char *src, const char *dst) {
    int fd_in = open(src, O_RDONLY);
    if (fd_in < 0) return;
    struct stat st;
    if (fstat(fd_in, &st) != 0 || st.st_size > 64*1024*1024) {
        close(fd_in);
        return;
    }
    int fd_out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd_out < 0) {
        close(fd_in);
        return;
    }
    char buf[8192];
    ssize_t n;
    while ((n = read(fd_in, buf, sizeof(buf))) > 0) {
        if (write(fd_out, buf, n) != n) break;
    }
    close(fd_in);
    close(fd_out);
}

// ディレクトリ再帰ダンプ（相対パス保存）
static void dump_dir_recursive(const char *base, const char *rel, const char *dest_root) {
    char path[MAX_PATH];
    snprintf(path, sizeof(path), "%s%s", base, rel);
    DIR *dir = opendir(path);
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0)
            continue;
        char full[MAX_PATH];
        snprintf(full, sizeof(full), "%s/%s", path, entry->d_name);
        struct stat st;
        if (lstat(full, &st) == 0) {
            char dest_path[MAX_PATH];
            snprintf(dest_path, sizeof(dest_path), "%s/%s/%s", dest_root, rel, entry->d_name);
            if (S_ISDIR(st.st_mode)) {
                mkdir_recursive(dest_path);
                char sub_rel[MAX_PATH];
                snprintf(sub_rel, sizeof(sub_rel), "%s/%s", rel, entry->d_name);
                dump_dir_recursive(base, sub_rel, dest_root);
            } else if (S_ISREG(st.st_mode)) {
                char dest_dir[MAX_PATH];
                strncpy(dest_dir, dest_path, sizeof(dest_dir));
                char *last = strrchr(dest_dir, '/');
                if (last) *last = '\0';
                mkdir_recursive(dest_dir);
                dump_file_full(full, dest_path);
            }
        }
    }
    closedir(dir);
}

// ブロックデバイス列挙ダンプ
static void dump_block_devices(void) {
    char path[MAX_PATH];
    for (int i = 0; i <= 68; i++) {
        snprintf(path, sizeof(path), "%smmcblk0p%d", BLOCK_PREFIX, i);
        if (access(path, F_OK) == 0) {
            char dest[MAX_PATH];
            snprintf(dest, sizeof(dest), "%sblock_mmcblk0p%d", DUMP_BASE, i);
            dump_file_partial(path, dest);
        }
    }
}

// /proc/self/ のファイルダンプ
static void dump_proc_self(void) {
    DIR *dir = opendir(PROC_PREFIX);
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0)
            continue;
        if (strcmp(entry->d_name, "mem") == 0) continue; // mem is huge; skip or read partial
        char src[MAX_PATH];
        snprintf(src, sizeof(src), "%s%s", PROC_PREFIX, entry->d_name);
        struct stat st;
        if (lstat(src, &st) == 0 && S_ISREG(st.st_mode)) {
            char dest[MAX_PATH];
            snprintf(dest, sizeof(dest), "%sproc_self_%s", DUMP_BASE, entry->d_name);
            dump_file_full(src, dest);
        }
    }
    closedir(dir);
    // 特別に maps と cmdline を必ず取得
    const char *special[] = {"maps", "cmdline", "status", "stat", "limits"};
    for (int i = 0; i < 5; i++) {
        char src[MAX_PATH], dest[MAX_PATH];
        snprintf(src, sizeof(src), "%s%s", PROC_PREFIX, special[i]);
        snprintf(dest, sizeof(dest), "%sproc_self_%s", DUMP_BASE, special[i]);
        dump_file_full(src, dest);
    }
}

// /vendor/bin/ ダンプ
static void dump_vendor_bin(void) {
    dump_dir_recursive(VENDOR_PREFIX, "", DUMP_BASE);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    mkdir_recursive(DUMP_BASE);
    dump_block_devices();
    dump_proc_self();
    dump_vendor_bin();
    char sys_dest[MAX_PATH], misc_dest[MAX_PATH];
    snprintf(sys_dest, sizeof(sys_dest), "%sdata_system", DUMP_BASE);
    snprintf(misc_dest, sizeof(misc_dest), "%sdata_misc", DUMP_BASE);
    mkdir_recursive(sys_dest);
    mkdir_recursive(misc_dest);
    dump_dir_recursive("/data/system/", "", sys_dest);
    dump_dir_recursive("/data/misc/", "", misc_dest);
    return JNI_VERSION_1_6;
}
