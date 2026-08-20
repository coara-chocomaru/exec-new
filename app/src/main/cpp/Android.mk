LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := wigig_poc
LOCAL_SRC_FILES := wigig_poc.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
