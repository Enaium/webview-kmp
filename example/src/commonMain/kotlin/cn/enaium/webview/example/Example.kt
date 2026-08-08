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

package cn.enaium.webview.example

import cn.enaium.webview.createWebview
import cn.enaium.webview.getWebviewVersion
import cn.enaium.webview.WindowSizeHint

/**
 * Runs a small demo app that opens a native webview window and demonstrates
 * the Kotlin Multiplatform API: version query, window setup, HTML loading,
 * JS <-> Kotlin bindings and dispatch() from a background thread.
 */
fun runExample() {
    val version = getWebviewVersion()
    println("webview-kmp example: webview ${version.versionNumber}")

    createWebview(debug = true).use { webview ->
        webview.setTitle("webview-kmp example")
        webview.setSize(900, 600, WindowSizeHint.NONE)

        // Expose `window.add(a, b)` to the page: JS calls the binding, the
        // native callback receives the JSON array of arguments, and the
        // result is returned to the awaiting JS promise.
        webview.bind("add") { id, req ->
            val sum = parseJsonNumbers(req).sum()
            webview.returnResult(id, 0, sum.toString())
        }

        // Expose `window.exit()` to close the window (terminates the main loop).
        webview.bind("exit") { id, _ ->
            webview.returnResult(id, 0, "")
            webview.terminate()
        }

        // Demonstrate dispatch()/terminate() from a background thread: the
        // webview API is only safe to touch from the GUI thread, so the timer
        // posts work to it via dispatch().
        schedule(3000) {
            webview.dispatch {
                webview.eval(
                    "document.getElementById('notice').textContent = " +
                        "'3s: dispatched from a background thread'",
                )
            }
        }
        schedule(6000) {
            webview.terminate()
        }

        webview.setHtml(HTML)
        webview.run()
    }
    println("example finished")
}

/**
 * Schedules [block] on a daemon thread after [millis].
 */
expect fun schedule(millis: Long, block: () -> Unit)

private fun parseJsonNumbers(req: String): List<Int> {
    val trimmed = req.trim().removeSurrounding("[", "]")
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(',').map { it.trim().toInt() }
}

private val HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>webview-kmp example</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; margin: 0; padding: 48px; }
  h1 { margin: 0 0 8px; }
  p { margin: 12px 0; }
  input { width: 90px; padding: 6px 10px; font-size: 16px; }
  button { padding: 8px 18px; font-size: 15px; cursor: pointer; }
  #result { font-size: 22px; font-weight: 700; }
  #notice { color: #888; font-family: monospace; }
</style>
</head>
<body>
  <h1>webview-kmp</h1>
  <p>Native webview window driven from Kotlin Multiplatform.</p>
  <p>
    <input id="a" type="number" value="1"> +
    <input id="b" type="number" value="2">
    <button onclick="addNumbers()">=</button>
    <span id="result">?</span>
  </p>
  <p><button onclick="doExit()">Exit</button></p>
  <p id="notice"></p>
<script>
  async function addNumbers() {
    const a = Number(document.getElementById('a').value)
    const b = Number(document.getElementById('b').value)
    const sum = await window.add(a, b)
    document.getElementById('result').textContent = sum
    document.getElementById('notice').textContent =
      'JS window.add(' + a + ', ' + b + ') -> Kotlin callback -> JS promise result'
  }
  function doExit() {
    window.exit()
  }
</script>
</body>
</html>
""".trimIndent()
