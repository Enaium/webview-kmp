/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.webview

// =========================================================================
// JNI bridge – loads the native library and provides external declarations
// =========================================================================

internal object Jni {
    init {
        NativeLoader.load()
    }

    external fun nativeCreate(debug: Boolean, window: Long): Long
    external fun nativeDestroy(ptr: Long): Int
    external fun nativeRun(ptr: Long): Int
    external fun nativeTerminate(ptr: Long): Int
    external fun nativeDispatch(ptr: Long, runnable: Runnable): Int
    external fun nativeGetWindow(ptr: Long): Long
    external fun nativeGetNativeHandle(ptr: Long, kind: Int): Long
    external fun nativeSetTitle(ptr: Long, title: String): Int
    external fun nativeSetSize(ptr: Long, width: Int, height: Int, hints: Int): Int
    external fun nativeNavigate(ptr: Long, url: String): Int
    external fun nativeSetHtml(ptr: Long, html: String): Int
    external fun nativeInit(ptr: Long, js: String): Int
    external fun nativeEval(ptr: Long, js: String): Int
    external fun nativeBind(ptr: Long, name: String, callback: BindingCallback): Int
    external fun nativeUnbind(ptr: Long, name: String): Int
    external fun nativeReturn(ptr: Long, id: String, status: Int, result: String): Int

    external fun nativeVersionMajor(): Int
    external fun nativeVersionMinor(): Int
    external fun nativeVersionPatch(): Int
    external fun nativeVersionNumber(): String
    external fun nativeVersionPreRelease(): String
    external fun nativeVersionBuildMetadata(): String
}

// =========================================================================
// JVM actual implementation
// =========================================================================

class JvmWebview internal constructor(internal val ptr: Long) : Webview {

    override fun run() {
        checkWebviewError(Jni.nativeRun(ptr), "run")
    }

    override fun terminate() {
        checkWebviewError(Jni.nativeTerminate(ptr), "terminate")
    }

    override fun dispatch(block: () -> Unit) {
        checkWebviewError(Jni.nativeDispatch(ptr, Runnable(block)), "dispatch")
    }

    override fun getWindow(): Long = Jni.nativeGetWindow(ptr)

    override fun getNativeHandle(kind: NativeHandleKind): Long =
        Jni.nativeGetNativeHandle(ptr, kind.value)

    override fun setTitle(title: String) {
        checkWebviewError(Jni.nativeSetTitle(ptr, title), "setTitle")
    }

    override fun setSize(width: Int, height: Int, hints: WindowSizeHint) {
        checkWebviewError(Jni.nativeSetSize(ptr, width, height, hints.value), "setSize")
    }

    override fun navigate(url: String) {
        checkWebviewError(Jni.nativeNavigate(ptr, url), "navigate")
    }

    override fun setHtml(html: String) {
        checkWebviewError(Jni.nativeSetHtml(ptr, html), "setHtml")
    }

    override fun init(js: String) {
        checkWebviewError(Jni.nativeInit(ptr, js), "init")
    }

    override fun eval(js: String) {
        checkWebviewError(Jni.nativeEval(ptr, js), "eval")
    }

    override fun bind(name: String, callback: BindingCallback) {
        val rc = Jni.nativeBind(ptr, name, callback)
        if (rc != 0) {
            error("webview bind('$name') failed: ${webviewErrorName(rc)}")
        }
    }

    override fun unbind(name: String) {
        val rc = Jni.nativeUnbind(ptr, name)
        if (rc != 0) {
            error("webview unbind('$name') failed: ${webviewErrorName(rc)}")
        }
    }

    override fun returnResult(id: String, status: Int, result: String) {
        checkWebviewError(Jni.nativeReturn(ptr, id, status, result), "return")
    }

    override fun close() {
        Jni.nativeDestroy(ptr)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createWebview(debug: Boolean): Webview {
    val ptr = Jni.nativeCreate(debug, 0L)
    check(ptr != 0L) { "webview_create failed (missing dependency?)" }
    return JvmWebview(ptr)
}

actual fun getWebviewVersion(): WebviewVersion = WebviewVersion(
    major = Jni.nativeVersionMajor(),
    minor = Jni.nativeVersionMinor(),
    patch = Jni.nativeVersionPatch(),
    versionNumber = Jni.nativeVersionNumber(),
    preRelease = Jni.nativeVersionPreRelease(),
    buildMetadata = Jni.nativeVersionBuildMetadata(),
)
