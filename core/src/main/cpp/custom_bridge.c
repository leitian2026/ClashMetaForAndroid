#include <jni.h>

#include "libclash.h"
#include "jni_helper.h"

// CUSTOM (CUSTOM.md #7): subscribe to "node IP changed" events of the core.
JNIEXPORT void JNICALL
Java_com_github_kr328_clash_core_custom_CustomBridge_nativeSubscribeNodeIp(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jobject callback) {
    jobject _callback = new_global(callback);

    subscribeNodeIp(_callback);
}
