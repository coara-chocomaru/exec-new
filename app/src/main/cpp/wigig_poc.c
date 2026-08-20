#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#define LOG_TAG "WigigPoC"
JNIEXPORT jstring JNICALL Java_com_wigig_poc_MainActivity_testNativeCommand(JNIEnv *env, jobject thiz, jstring cmd) {
    const char *c = (*env)->GetStringUTFChars(env, cmd, NULL);
    char result[4096] = {0};
    FILE *fp = popen(c, "r");
    if (fp) { fread(result, 1, sizeof(result)-1, fp); pclose(fp); }
    else strcpy(result, "popen failed");
    (*env)->ReleaseStringUTFChars(env, cmd, c);
    return (*env)->NewStringUTF(env, result);
}
JNIEXPORT void JNICALL Java_com_wigig_poc_MainActivity_triggerStackOverflow(JNIEnv *env, jobject thiz, jstring longParam) {
    const char *p = (*env)->GetStringUTFChars(env, longParam, NULL);
    char buf[128];
    strcpy(buf, p);  // 意図的なオーバーフロー
    LOGD("Copied %s", buf);
    (*env)->ReleaseStringUTFChars(env, longParam, p);
}
