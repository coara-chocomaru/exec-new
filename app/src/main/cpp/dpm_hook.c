#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>

JNIEXPORT jstring JNICALL
Java_com_example_dpmpoc_NativeHelper_execCommand(JNIEnv *env, jclass clazz, jstring cmd) {
    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (cmd_str == NULL) {
        return (*env)->NewStringUTF(env, "Error: cannot get cmd");
    }
    uid_t uid = getuid();
    char buf[256];
    snprintf(buf, sizeof(buf), "Executing: %s, uid=%d", cmd_str, uid);
    FILE *fp = popen(cmd_str, "r");
    if (fp == NULL) {
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
        return (*env)->NewStringUTF(env, "popen failed");
    }
    char result[1024] = {0};
    fread(result, 1, sizeof(result)-1, fp);
    pclose(fp);

    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
    return (*env)->NewStringUTF(env, result);
}
