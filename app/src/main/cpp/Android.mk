LOCAL_PATH:= $(call my-dir)

# Only build for arm64-v8a (native bootstrap binaries only exist for this arch)
APP_ABI := arm64-v8a

# libtermux-bootstrap: bootstrap for Termux (zip extraction)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
