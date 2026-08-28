LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := native-inspector
LOCAL_SRC_FILES := native-lib.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2 -fPIC
LOCAL_LDFLAGS := -Wl,-soname,libnative-inspector.so \
                 -Wl,--no-undefined \
                 -Wl,-z,noexecstack \
                 -Wl,-z,relro \
                 -Wl,-z,now
include $(BUILD_SHARED_LIBRARY)
