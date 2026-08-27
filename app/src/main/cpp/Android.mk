LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := exploit
LOCAL_SRC_FILES := exploit.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2

include $(BUILD_SHARED_LIBRARY)
