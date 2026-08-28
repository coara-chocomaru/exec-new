#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>

#define LOG_TAG "NativeInspector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jobjectArray JNICALL
Java_com_poc_Main_nativeListDirectory(JNIEnv *env, jclass clazz, jstring path) {
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_str == NULL) return NULL;

    DIR *dir = opendir(path_str);
    if (dir == NULL) {
        LOGE("opendir(%s) failed: %s (%d)", path_str, strerror(errno), errno);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return NULL;
    }

    struct dirent *entry;
    int count = 0;
    while ((entry = readdir(dir)) != NULL) count++;
    rewinddir(dir);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    int idx = 0;
    char full_path[1024];
    struct stat st;
    while ((entry = readdir(dir)) != NULL) {
        snprintf(full_path, sizeof(full_path), "%s/%s", path_str, entry->d_name);
        if (stat(full_path, &st) == -1) {
            char buf[512];
            snprintf(buf, sizeof(buf), "%s|unknown|0|0", entry->d_name);
            jobject str = (*env)->NewStringUTF(env, buf);
            (*env)->SetObjectArrayElement(env, result, idx, str);
            idx++;
            continue;
        }

        char type = 'F';
        if (S_ISDIR(st.st_mode)) type = 'D';
        else if (S_ISLNK(st.st_mode)) type = 'L';
        else if (S_ISBLK(st.st_mode)) type = 'B';
        else if (S_ISCHR(st.st_mode)) type = 'C';
        else if (S_ISFIFO(st.st_mode)) type = 'P';
        else if (S_ISSOCK(st.st_mode)) type = 'S';

        int perms = st.st_mode & 0777;

        char buf[512];
        snprintf(buf, sizeof(buf), "%s|%c|%ld|%03o", entry->d_name, type, (long)st.st_size, perms);
        jobject str = (*env)->NewStringUTF(env, buf);
        (*env)->SetObjectArrayElement(env, result, idx, str);
        idx++;
    }

    closedir(dir);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_poc_Main_nativeReadFile(JNIEnv *env, jclass clazz, jstring path) {
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_str == NULL) return NULL;

    int fd = open(path_str, O_RDONLY);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", path_str, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return (*env)->NewStringUTF(env, "");
    }

    char buffer[1025];
    ssize_t bytes = read(fd, buffer, 1024);
    close(fd);

    if (bytes <= 0) {
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return (*env)->NewStringUTF(env, "");
    }

    buffer[bytes] = '\0';
    for (int i = 0; i < bytes; i++) {
        if (buffer[i] < 0x20 && buffer[i] != '\n' && buffer[i] != '\t') {
            buffer[i] = '.';
        }
    }
    jstring result = (*env)->NewStringUTF(env, buffer);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    return result;
}
