#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jstring JNICALL
Java_com_wigig_poc_MainActivity_runCommand(JNIEnv *env, jobject thiz, jstring cmd) {
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
