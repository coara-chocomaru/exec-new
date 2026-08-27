LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := exploit
LOCAL_SRC_FILES := exploit.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2 -fPIC
LOCAL_LDFLAGS := -Wl,-soname,exploit.so \
                 -Wl,--no-undefined \
                 -Wl,-z,noexecstack \
                 -Wl,-z,relro \
                 -Wl,-z,now
include $(BUILD_SHARED_LIBRARY)
