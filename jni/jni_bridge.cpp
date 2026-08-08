/*
 *  Copyright (c) 2026 Enaium
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 *  DEALINGS IN THE SOFTWARE.
 */

#include <jni.h>
#include <map>
#include <string>
#include "webview/webview.h"

// ============================================================================
// Callbacks from the webview main loop (bind/dispatch) run on the GUI thread.
// The JavaVM is cached in JNI_OnLoad so a JNIEnv can always be obtained, even
// if a callback ever fires from a thread the JVM did not create.
// ============================================================================

static JavaVM* g_jvm = nullptr;

static JNIEnv* env_for_callbacks() {
    JNIEnv* env = nullptr;
    jint rc = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    }
    return env;
}

// ----------------------------------------------------------------------------
// Binding callbacks
//
// Each webview_bind() call stores a JNI global reference to the Kotlin
// BindingCallback and passes it as the C API's user argument. The reference
// is released by webview_unbind() or webview_destroy().
// ----------------------------------------------------------------------------

static void binding_trampoline(const char* id, const char* req, void* arg) {
    jobject callback = static_cast<jobject>(arg);
    JNIEnv* env = env_for_callbacks();
    if (env == nullptr) {
        return;
    }
    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "invoke",
                                     "(Ljava/lang/String;Ljava/lang/String;)V");
    if (mid == nullptr) {
        return;
    }
    jstring jid = env->NewStringUTF(id);
    jstring jreq = env->NewStringUTF(req);
    env->CallVoidMethod(callback, mid, jid, jreq);
    env->DeleteLocalRef(jreq);
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(cls);
}

// ----------------------------------------------------------------------------
// Dispatch callbacks
//
// webview_dispatch() is one-shot: the global reference is deleted after the
// Runnable has been executed on the GUI thread.
// ----------------------------------------------------------------------------

static void dispatch_trampoline(webview_t w, void* arg) {
    jobject runnable = static_cast<jobject>(arg);
    JNIEnv* env = env_for_callbacks();
    if (env == nullptr) {
        return;
    }
    jclass cls = env->GetObjectClass(runnable);
    jmethodID mid = env->GetMethodID(cls, "run", "()V");
    if (mid != nullptr) {
        env->CallVoidMethod(runnable, mid);
    }
    env->DeleteLocalRef(cls);
    env->DeleteGlobalRef(runnable);
}

// ----------------------------------------------------------------------------
// JNI entry points
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webview_Jni_nativeCreate(JNIEnv* env, jclass clazz,
                                        jboolean debug, jlong window) {
    webview_t w = webview_create(debug ? 1 : 0, reinterpret_cast<void*>(window));
    return reinterpret_cast<jlong>(w);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeDestroy(JNIEnv* env, jclass clazz,
                                         jlong ptr) {
    webview_t w = reinterpret_cast<webview_t>(ptr);
    webview_destroy(w);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeRun(JNIEnv* env, jclass clazz, jlong ptr) {
    return webview_run(reinterpret_cast<webview_t>(ptr));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeTerminate(JNIEnv* env, jclass clazz,
                                           jlong ptr) {
    return webview_terminate(reinterpret_cast<webview_t>(ptr));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeDispatch(JNIEnv* env, jclass clazz,
                                          jlong ptr, jobject runnable) {
    jobject global = env->NewGlobalRef(runnable);
    if (global == nullptr) {
        return WEBVIEW_ERROR_UNSPECIFIED;
    }
    return webview_dispatch(reinterpret_cast<webview_t>(ptr),
                            dispatch_trampoline, global);
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webview_Jni_nativeGetWindow(JNIEnv* env, jclass clazz,
                                           jlong ptr) {
    return reinterpret_cast<jlong>(
        webview_get_window(reinterpret_cast<webview_t>(ptr)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webview_Jni_nativeGetNativeHandle(JNIEnv* env, jclass clazz,
                                                 jlong ptr, jint kind) {
    return reinterpret_cast<jlong>(webview_get_native_handle(
        reinterpret_cast<webview_t>(ptr),
        static_cast<webview_native_handle_kind_t>(kind)));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeSetTitle(JNIEnv* env, jclass clazz,
                                          jlong ptr, jstring title) {
    const char* chars = env->GetStringUTFChars(title, nullptr);
    webview_error_t rc = webview_set_title(reinterpret_cast<webview_t>(ptr),
                                           chars);
    env->ReleaseStringUTFChars(title, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeSetSize(JNIEnv* env, jclass clazz,
                                         jlong ptr, jint width, jint height,
                                         jint hints) {
    return webview_set_size(reinterpret_cast<webview_t>(ptr), width, height,
                            static_cast<webview_hint_t>(hints));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeNavigate(JNIEnv* env, jclass clazz,
                                          jlong ptr, jstring url) {
    const char* chars = env->GetStringUTFChars(url, nullptr);
    webview_error_t rc = webview_navigate(reinterpret_cast<webview_t>(ptr),
                                          chars);
    env->ReleaseStringUTFChars(url, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeSetHtml(JNIEnv* env, jclass clazz,
                                         jlong ptr, jstring html) {
    const char* chars = env->GetStringUTFChars(html, nullptr);
    webview_error_t rc = webview_set_html(reinterpret_cast<webview_t>(ptr),
                                          chars);
    env->ReleaseStringUTFChars(html, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeInit(JNIEnv* env, jclass clazz, jlong ptr,
                                      jstring js) {
    const char* chars = env->GetStringUTFChars(js, nullptr);
    webview_error_t rc = webview_init(reinterpret_cast<webview_t>(ptr), chars);
    env->ReleaseStringUTFChars(js, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeEval(JNIEnv* env, jclass clazz, jlong ptr,
                                      jstring js) {
    const char* chars = env->GetStringUTFChars(js, nullptr);
    webview_error_t rc = webview_eval(reinterpret_cast<webview_t>(ptr), chars);
    env->ReleaseStringUTFChars(js, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeBind(JNIEnv* env, jclass clazz, jlong ptr,
                                      jstring name, jobject callback) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    jobject global = env->NewGlobalRef(callback);
    webview_error_t rc = WEBVIEW_ERROR_UNSPECIFIED;
    if (global != nullptr) {
        rc = webview_bind(reinterpret_cast<webview_t>(ptr), chars,
                          binding_trampoline, global);
        if (rc != WEBVIEW_ERROR_OK) {
            env->DeleteGlobalRef(global);
        }
    }
    env->ReleaseStringUTFChars(name, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeUnbind(JNIEnv* env, jclass clazz, jlong ptr,
                                        jstring name) {
    const char* chars = env->GetStringUTFChars(name, nullptr);
    webview_error_t rc = webview_unbind(reinterpret_cast<webview_t>(ptr),
                                        chars);
    env->ReleaseStringUTFChars(name, chars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeReturn(JNIEnv* env, jclass clazz, jlong ptr,
                                        jstring id, jint status,
                                        jstring result) {
    const char* idChars = env->GetStringUTFChars(id, nullptr);
    const char* resultChars = env->GetStringUTFChars(result, nullptr);
    webview_error_t rc = webview_return(reinterpret_cast<webview_t>(ptr),
                                        idChars, status, resultChars);
    env->ReleaseStringUTFChars(result, resultChars);
    env->ReleaseStringUTFChars(id, idChars);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeVersionMajor(JNIEnv* env, jclass clazz) {
    return webview_version()->version.major;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeVersionMinor(JNIEnv* env, jclass clazz) {
    return webview_version()->version.minor;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webview_Jni_nativeVersionPatch(JNIEnv* env, jclass clazz) {
    return webview_version()->version.patch;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webview_Jni_nativeVersionNumber(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(webview_version()->version_number);
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webview_Jni_nativeVersionPreRelease(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(webview_version()->pre_release);
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webview_Jni_nativeVersionBuildMetadata(JNIEnv* env,
                                                      jclass clazz) {
    return env->NewStringUTF(webview_version()->build_metadata);
}
