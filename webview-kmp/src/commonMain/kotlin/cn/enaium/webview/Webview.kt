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
// Top-level expect factory functions
// =========================================================================

/**
 * Creates a new webview instance with its own native window.
 *
 * @param debug Enable developer tools if supported by the backend.
 * @throws IllegalStateException if the webview cannot be created (e.g. the
 *   WebView2 runtime is missing on Windows).
 */
expect fun createWebview(debug: Boolean = false): Webview

/**
 * Returns the version of the bundled webview library.
 */
expect fun getWebviewVersion(): WebviewVersion

// =========================================================================
// Common types
// =========================================================================

/** Version information of the bundled webview library. */
data class WebviewVersion(
    /** Major version. */
    val major: Int,
    /** Minor version. */
    val minor: Int,
    /** Patch version. */
    val patch: Int,
    /** SemVer 2.0.0 version number in MAJOR.MINOR.PATCH format. */
    val versionNumber: String,
    /** SemVer 2.0.0 pre-release labels prefixed with "-" if specified, otherwise an empty string. */
    val preRelease: String,
    /** SemVer 2.0.0 build metadata prefixed with "+", otherwise an empty string. */
    val buildMetadata: String,
)

/** Native handle kind. The actual type depends on the backend. */
enum class NativeHandleKind(val value: Int) {
    /** Top-level window. `GtkWindow` pointer (GTK), `NSWindow` pointer (Cocoa) or `HWND` (Win32). */
    UI_WINDOW(0),
    /** Browser widget. `GtkWidget` pointer (GTK), `NSView` pointer (Cocoa) or `HWND` (Win32). */
    UI_WIDGET(1),
    /** Browser controller. `WebKitWebView` pointer (WebKitGTK), `WKWebView` pointer (Cocoa/WebKit) or `ICoreWebView2Controller` pointer (Win32/WebView2). */
    BROWSER_CONTROLLER(2),
}

/** Window size hints. */
enum class WindowSizeHint(val value: Int) {
    /** Width and height are default size. */
    NONE(0),
    /** Width and height are minimum bounds. */
    MIN(1),
    /** Width and height are maximum bounds. */
    MAX(2),
    /** Window size can not be changed by a user. */
    FIXED(3),
}

/**
 * Receives calls made from the JS side to a binding created with
 * [Webview.bind].
 *
 * @param id The identifier of the binding call; pass it to
 *   [Webview.returnResult] to respond to the JS side.
 * @param req A JSON array of the arguments passed to the JS function.
 */
fun interface BindingCallback {
    fun invoke(id: String, req: String)
}

// =========================================================================
// Common interface
// =========================================================================

/**
 * A webview instance.
 *
 * The native window and event loop are managed by the webview library.
 * Create instances with [createWebview].
 */
interface Webview : AutoCloseable {
    /**
     * Runs the main loop until it's terminated.
     *
     * This call blocks the calling thread. Note that on macOS the main loop
     * must run on the main thread.
     */
    fun run()

    /**
     * Stops the main loop. It is safe to call this function from another
     * background thread.
     */
    fun terminate()

    /**
     * Schedules [block] to be invoked on the thread with the run/event loop.
     *
     * Since library functions generally do not have thread safety guarantees,
     * this function can be used to schedule code to execute on the main/GUI
     * thread and thereby make that execution safe in multi-threaded
     * applications.
     */
    fun dispatch(block: () -> Unit)

    /**
     * Returns the native handle of the window associated with the webview
     * instance. The handle can be a `GtkWindow` pointer (GTK), `NSWindow`
     * pointer (Cocoa) or `HWND` (Win32).
     */
    fun getWindow(): Long

    /**
     * Returns a native handle of choice.
     */
    fun getNativeHandle(kind: NativeHandleKind): Long

    /**
     * Updates the title of the native window.
     */
    fun setTitle(title: String)

    /**
     * Updates the size of the native window.
     *
     * Note that using [WindowSizeHint.MAX] is not supported with GTK 4.
     */
    fun setSize(width: Int, height: Int, hints: WindowSizeHint = WindowSizeHint.NONE)

    /**
     * Navigates the webview to the given URL. The URL may be a properly
     * encoded data URI.
     */
    fun navigate(url: String)

    /**
     * Loads HTML content into the webview.
     */
    fun setHtml(html: String)

    /**
     * Injects JavaScript code to be executed immediately upon loading a page.
     * The code will be executed before `window.onload`.
     */
    fun init(js: String)

    /**
     * Evaluates arbitrary JavaScript code. Use bindings if you need to
     * communicate the result of the evaluation.
     */
    fun eval(js: String)

    /**
     * Binds [callback] to a new global JavaScript function named [name].
     *
     * Internally, JS glue code is injected to create the JS function by the
     * given name. The callback receives a request identifier and a JSON array
     * of the arguments passed to the JS function.
     *
     * @throws IllegalStateException if a binding already exists with the
     *   specified name.
     */
    fun bind(name: String, callback: BindingCallback)

    /**
     * Removes a binding created with [bind].
     *
     * @throws IllegalStateException if no binding exists with the specified
     *   name.
     */
    fun unbind(name: String)

    /**
     * Responds to a binding call from the JS side. This function is safe to
     * call from another thread.
     *
     * @param id The identifier of the binding call. Pass along the value
     *   received in the binding callback.
     * @param status A status of zero tells the JS side that the binding call
     *   was successful; any other value indicates an error.
     * @param result The result of the binding call to be returned to the JS
     *   side. This must either be a valid JSON value or an empty string for
     *   the primitive JS value `undefined`.
     */
    fun returnResult(id: String, status: Int = 0, result: String = "")

    /**
     * Destroys the webview instance and closes the native window.
     */
    override fun close()
}

// =========================================================================
// Error handling
// =========================================================================

internal fun webviewErrorName(code: Int): String {
    return when (code) {
        -5 -> "WEBVIEW_ERROR_MISSING_DEPENDENCY"
        -4 -> "WEBVIEW_ERROR_CANCELED"
        -3 -> "WEBVIEW_ERROR_INVALID_STATE"
        -2 -> "WEBVIEW_ERROR_INVALID_ARGUMENT"
        -1 -> "WEBVIEW_ERROR_UNSPECIFIED"
        0 -> "WEBVIEW_ERROR_OK"
        1 -> "WEBVIEW_ERROR_DUPLICATE"
        2 -> "WEBVIEW_ERROR_NOT_FOUND"
        else -> "WEBVIEW_ERROR_UNKNOWN($code)"
    }
}

internal fun checkWebviewError(code: Int, operation: String) {
    if (code >= 0) return
    error("webview $operation failed: ${webviewErrorName(code)}")
}
