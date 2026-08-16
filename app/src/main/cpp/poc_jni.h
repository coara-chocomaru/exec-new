#ifndef POC_JNI_H
#define POC_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved);

JNIEXPORT jobject JNICALL Java_com_example_tzpoc_NativeHelper_nativeConnectSocket
  (JNIEnv* env, jclass clazz, jobject tzService, jstring path, jintArray handleArr);

JNIEXPORT jstring JNICALL Java_com_example_tzpoc_NativeHelper_nativeReadFile
  (JNIEnv* env, jclass clazz, jstring path);

#ifdef __cplusplus
}
#endif

#endif
