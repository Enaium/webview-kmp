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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webview

import kotlinx.cinterop.*
import webview.*

// =========================================================================
// Callback trampolines
//
// Kotlin closures are passed to the C API through StableRefs; the trampoline
// functions recover and invoke them when the webview main loop fires.
// =========================================================================

private val bindTrampoline = staticCFunction {
    id: CPointer<ByteVar>?, req: CPointer<ByteVar>?, arg: CPointer<out CPointed>? ->
    val callback = arg!!.asStableRef<BindingCallback>().get()
    callback.invoke(id?.toKString().orEmpty(), req?.toKString().orEmpty())
}

private val dispatchTrampoline = staticCFunction { _: COpaquePointer?, arg: COpaquePointer? ->
    val ref = arg!!.asStableRef<() -> Unit>()
    ref.get().invoke()
    ref.dispose()
}

// =========================================================================
// Enum mapping
// =========================================================================

private fun nativeHandleKind(kind: NativeHandleKind): webview_native_handle_kind_t = when (kind) {
    NativeHandleKind.UI_WINDOW -> webview_native_handle_kind_t.WEBVIEW_NATIVE_HANDLE_KIND_UI_WINDOW
    NativeHandleKind.UI_WIDGET -> webview_native_handle_kind_t.WEBVIEW_NATIVE_HANDLE_KIND_UI_WIDGET
    NativeHandleKind.BROWSER_CONTROLLER ->
        webview_native_handle_kind_t.WEBVIEW_NATIVE_HANDLE_KIND_BROWSER_CONTROLLER
}

private fun windowSizeHint(hint: WindowSizeHint): webview_hint_t = when (hint) {
    WindowSizeHint.NONE -> webview_hint_t.WEBVIEW_HINT_NONE
    WindowSizeHint.MIN -> webview_hint_t.WEBVIEW_HINT_MIN
    WindowSizeHint.MAX -> webview_hint_t.WEBVIEW_HINT_MAX
    WindowSizeHint.FIXED -> webview_hint_t.WEBVIEW_HINT_FIXED
}

// =========================================================================
// Native (cinterop) actual implementation
// =========================================================================

class NativeWebview internal constructor(internal val ptr: webview_t) : Webview {

    private val bindings = mutableMapOf<String, StableRef<BindingCallback>>()

    override fun run() {
        checkWebviewError(webview_run(ptr), "run")
    }

    override fun terminate() {
        checkWebviewError(webview_terminate(ptr), "terminate")
    }

    override fun dispatch(block: () -> Unit) {
        val ref = StableRef.create(block)
        val rc = webview_dispatch(ptr, dispatchTrampoline, ref.asCPointer())
        checkWebviewError(rc, "dispatch")
    }

    override fun getWindow(): Long = webview_get_window(ptr)?.toLong() ?: 0L

    override fun getNativeHandle(kind: NativeHandleKind): Long =
        webview_get_native_handle(ptr, nativeHandleKind(kind))?.toLong() ?: 0L

    override fun setTitle(title: String) {
        checkWebviewError(webview_set_title(ptr, title), "setTitle")
    }

    override fun setSize(width: Int, height: Int, hints: WindowSizeHint) {
        checkWebviewError(
            webview_set_size(ptr, width, height, windowSizeHint(hints)),
            "setSize",
        )
    }

    override fun navigate(url: String) {
        checkWebviewError(webview_navigate(ptr, url), "navigate")
    }

    override fun setHtml(html: String) {
        checkWebviewError(webview_set_html(ptr, html), "setHtml")
    }

    override fun init(js: String) {
        checkWebviewError(webview_init(ptr, js), "init")
    }

    override fun eval(js: String) {
        checkWebviewError(webview_eval(ptr, js), "eval")
    }

    override fun bind(name: String, callback: BindingCallback) {
        val ref = StableRef.create(callback)
        val rc = webview_bind(ptr, name, bindTrampoline, ref.asCPointer())
        if (rc != WEBVIEW_ERROR_OK) {
            ref.dispose()
            error("webview bind('$name') failed: ${webviewErrorName(rc)}")
        }
        bindings[name] = ref
    }

    override fun unbind(name: String) {
        val rc = webview_unbind(ptr, name)
        if (rc != WEBVIEW_ERROR_OK) {
            error("webview unbind('$name') failed: ${webviewErrorName(rc)}")
        }
        bindings.remove(name)?.dispose()
    }

    override fun returnResult(id: String, status: Int, result: String) {
        checkWebviewError(webview_return(ptr, id, status, result), "return")
    }

    override fun close() {
        bindings.values.forEach { it.dispose() }
        bindings.clear()
        webview_destroy(ptr)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createWebview(debug: Boolean): Webview {
    val ptr = webview_create(if (debug) 1 else 0, null)
        ?: error("webview_create failed (missing dependency?)")
    return NativeWebview(ptr)
}

actual fun getWebviewVersion(): WebviewVersion {
    val info = webview_version() ?: error("webview_version returned null")
    val v = info.pointed
    return WebviewVersion(
        major = v.version.major.toInt(),
        minor = v.version.minor.toInt(),
        patch = v.version.patch.toInt(),
        versionNumber = v.version_number.toKString(),
        preRelease = v.pre_release.toKString(),
        buildMetadata = v.build_metadata.toKString(),
    )
}
