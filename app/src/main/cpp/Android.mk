LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := dpm_hook
LOCAL_SRC_FILES := dpm_hook.c
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
