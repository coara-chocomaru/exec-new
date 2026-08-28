LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := libpoc
LOCAL_SRC_FILES := libpoc.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2 -fPIC
include $(BUILD_SHARED_LIBRARY)
