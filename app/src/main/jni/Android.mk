LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := poc
LOCAL_SRC_FILES := libpoc.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -Wextra -O2 -D_GNU_SOURCE
include $(BUILD_SHARED_LIBRARY)
