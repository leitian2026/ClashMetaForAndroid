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

// CUSTOM (CUSTOM.md #8, #15): active connections of the core, as JSON (sort order and search text).
JNIEXPORT jstring JNICALL
Java_com_github_kr328_clash_core_custom_CustomBridge_nativeQueryConnections(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jint sort,
                                                                            jstring filter) {
    scoped_string _filter = get_string(filter);
    scoped_string response = queryCustomConnections(sort, _filter);

    return new_string(response);
}

// CUSTOM (CUSTOM.md #8): close one connection by its id.
JNIEXPORT jboolean JNICALL
Java_com_github_kr328_clash_core_custom_CustomBridge_nativeCloseConnection(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jstring id) {
    scoped_string _id = get_string(id);

    return closeCustomConnection(_id) != 0 ? JNI_TRUE : JNI_FALSE;
}
