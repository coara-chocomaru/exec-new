LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := dumppoc
LOCAL_SRC_FILES := dump.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_LDFLAGS := -Wl,--exclude-libs,libgcc.a -Wl,--exclude-libs,libatomic.a
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -O2 -fPIC

include $(BUILD_SHARED_LIBRARY)
