#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define LOG_TAG "UsbBusReset(native)"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Sends a real USB port reset (equivalent to a physical unplug/replug at the hub) on an
// already-open, already permission-granted fd from UsbDeviceConnection.getFileDescriptor().
// No root needed: USBDEVFS_RESET only requires write access to the usbfs device node, which
// UsbManager's own permission grant already provides to this process's fd.
JNIEXPORT jint JNICALL
Java_com_net_lldpsniffer_usb_UsbBusReset_nativeReset(JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;

    int rc = ioctl(fd, USBDEVFS_RESET, 0);
    if (rc < 0) {
        int err = errno;
        LOGD("USBDEVFS_RESET failed on fd %d: errno=%d (%s)", fd, err, strerror(err));
        return -err;
    }
    LOGD("USBDEVFS_RESET succeeded on fd %d", fd);
    return 0;
}
