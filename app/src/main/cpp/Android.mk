LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := pocjni
LOCAL_SRC_FILES := poc_jni.c
LOCAL_LDLIBS    := -llog
LOCAL_CFLAGS    := -std=c99

include $(BUILD_SHARED_LIBRARY)
