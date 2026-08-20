#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "WigigPoC"

JNIEXPORT jstring JNICALL
Java_com_wigig_poc_MainActivity_testNativeCommand(JNIEnv *env, jobject thiz, jstring cmd) {
    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);
    char result[4096] = {0};
    FILE *fp = popen(cmd_str, "r");
    if (fp) {
        fread(result, 1, sizeof(result) - 1, fp);
        pclose(fp);
    } else {
        strcpy(result, "popen failed");
    }
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT void JNICALL
Java_com_wigig_poc_MainActivity_triggerStackOverflow(JNIEnv *env, jobject thiz, jstring longParam) {
    const char *param = (*env)->GetStringUTFChars(env, longParam, NULL);
    char buffer[128];
    // 意図的なバッファオーバーフロー（サイズチェックなし）
    strcpy(buffer, param);
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Copied %s (len=%zu)", buffer, strlen(buffer));
    (*env)->ReleaseStringUTFChars(env, longParam, param);
}
