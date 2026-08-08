# webview-kmp

[![Maven Central](https://img.shields.io/maven-central/v/cn.enaium.webview/webview-kmp?label=Maven%20Central)](https://central.sonatype.com/artifact/cn.enaium.webview/webview-kmp)
[![License](https://img.shields.io/github/license/Enaium/webview-kmp)](https://github.com/Enaium/webview-kmp/blob/main/LICENSE)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/Enaium/webview-kmp/test.yml?label=test)](https://github.com/Enaium/webview-kmp/actions/workflows/test.yml)
[![GitHub Repo stars](https://img.shields.io/github/stars/Enaium/webview-kmp?style=social)](https://github.com/Enaium/webview-kmp)

Kotlin Multiplatform bindings for [webview](https://github.com/webview/webview) — a tiny cross-platform webview library for building GUIs with the system web engine. Desktop only: JVM and Kotlin/Native.

| Platform backend | macOS                | Linux                       | Windows          |
| ---------------- | -------------------- | --------------------------- | ---------------- |
| Engine           | WKWebView (WebKit)   | WebKitGTK 4.1 + GTK 3       | WebView2 (Edge)  |

## Supported Platforms

| Platform       | Targets                                            | Mechanism                                             |
| -------------- | -------------------------------------------------- | ----------------------------------------------------- |
| **JVM**        | Linux x86_64/aarch64, macOS arm64/x86_64, Windows x86_64 | JNI (per-OS/arch JAR resource, auto-extracted by `NativeLoader`) |
| **macOS**      | arm64, x86_64                                      | Kotlin/Native cinterop (static library)               |
| **Linux**      | x86_64, arm64                                      | Kotlin/Native cinterop (static library)               |
| **Windows**    | mingwX64                                           | Kotlin/Native cinterop (static library)               |

## Runtime Requirements

- **Windows**: the [WebView2 runtime](https://developer.microsoft.com/microsoft-edge/webview2/) must be installed (preinstalled on Windows 11).
- **Linux**: `libwebkit2gtk-4.1` + `libgtk-3` must be installed at runtime, e.g. on Debian/Ubuntu:

  ```bash
  sudo apt install libwebkit2gtk-4.1-0 libgtk-3-0
  ```

## Gradle Dependency

**Kotlin Multiplatform:**

```kotlin
implementation("cn.enaium.webview:webview-kmp:1.0.1")
```

**JVM:** the right native binary is resolved automatically — the `webview-kmp-jvm` artifact pulls in the matching `:jni-jvm-*` sibling on the classpath:

- `webview-kmp-jni-jvm-linux-x86_64`
- `webview-kmp-jni-jvm-linux-aarch64`
- `webview-kmp-jni-jvm-darwin-x86_64`
- `webview-kmp-jni-jvm-darwin-aarch64`
- `webview-kmp-jni-jvm-windows-x86_64`

`NativeLoader` detects `os.name`/`os.arch` at runtime, extracts the matching binary from the classpath to a temp directory, and `System.load`s it. No `java.library.path` setup is required for downstream JVM consumers.

## Quick Start

```kotlin
import cn.enaium.webview.createWebview

createWebview(debug = true).use { webview ->
    webview.setTitle("Hello webview-kmp")
    webview.setSize(800, 600)

    // Expose `window.add(a, b)` to the page: JS calls the binding, the native
    // callback receives the JSON array of arguments, and the result is
    // returned to the awaiting JS promise.
    webview.bind("add") { id, req ->
        val sum = req.trim().removeSurrounding("[", "]")
            .split(',').sumOf { it.trim().toInt() }
        webview.returnResult(id, 0, sum.toString())
    }

    webview.setHtml(
        """
        <button onclick="window.add(1, 2).then(r => alert(r))">click</button>
        """.trimIndent(),
    )

    webview.run()  // blocks until the window is closed
}
```

> **macOS note**: the webview's Cocoa backend must run on the process main thread. Modern JDKs launch `main()` on a new thread by default, so run the JVM app with `-XstartOnFirstThread` on macOS (the `example` module's `run` task already does this).

## API Reference

```kotlin
fun createWebview(debug: Boolean = false): Webview
fun getWebviewVersion(): WebviewVersion
```

### Webview

| Member                                      | Description                                                        |
| ------------------------------------------- | ------------------------------------------------------------------ |
| `run()`                                     | Runs the main loop until terminated (blocking; main thread on macOS) |
| `terminate()`                               | Stops the main loop; safe to call from any thread                   |
| `dispatch(block)`                           | Schedules `block` on the GUI thread                                 |
| `getWindow()` / `getNativeHandle(kind)`     | Native window / widget / browser-controller handles                 |
| `setTitle(title)`                           | Updates the native window title                                     |
| `setSize(width, height, hints)`             | Updates the native window size                                      |
| `navigate(url)`                             | Navigates to a URL (or data URI)                                    |
| `setHtml(html)`                             | Loads HTML content                                                  |
| `init(js)`                                  | Injects JS to run on every page load, before `window.onload`        |
| `eval(js)`                                  | Evaluates arbitrary JS                                              |
| `bind(name, callback)` / `unbind(name)`     | Registers/removes a global JS function (`window.<name>`)            |
| `returnResult(id, status, result)`          | Responds to a binding call from JS; safe from any thread            |
| `close()`                                   | Destroys the webview and closes the window                          |

All implementations are `AutoCloseable`; call `close()` to release the native state.

### JS ↔ Kotlin Bindings

`bind` creates an async JS function. The Kotlin callback receives the request `id` and a JSON array `req` of the JS arguments; the response must be a valid JSON value (or `""` for `undefined`):

```kotlin
webview.bind("echo") { id, req -> webview.returnResult(id, 0, req) }
// JS: const result = await window.echo(42)   // result === "[42]"
```

## Example

The [`example/`](example/) module demonstrates the API on every platform: version query, window setup, JS ↔ Kotlin bindings and `dispatch()` from a background thread. The window auto-closes after 6 seconds.

```bash
./gradlew :example:run              # JVM (desktop)
./gradlew :example:runMacosArm64    # Kotlin/Native macOS arm64
./gradlew :example:runMacosX64      # Kotlin/Native macOS x86_64
./gradlew :example:runLinuxX64      # Kotlin/Native Linux x86_64
# Windows (mingwX64) executable: cross-compiled on Linux, copy to a Windows machine
```

## Building from Source

### Prerequisites

- JDK 21+
- CMake 3.16+
- Xcode command-line tools (for Apple targets)
- Linux: `libwebkit2gtk-4.1-dev` + `libgtk-3-dev` (for linux targets); `gcc-mingw-w64-x86-64` (for the mingwX64 target); `gcc-aarch64-linux-gnu` + arm64 WebKitGTK packages (for the linuxArm64 target)

### Clone with submodules

```bash
git clone --recursive https://github.com/Enaium/webview-kmp.git
cd webview-kmp
```

### Publish to Maven Local

```bash
./gradlew :webview-kmp:publishToMavenLocal
```

### Run tests

```bash
./gradlew :webview-kmp:jvmTest        # JVM (JNI)
./gradlew :webview-kmp:macosArm64Test # macOS native
./gradlew :webview-kmp:linuxX64Test   # Linux native
```

## Project Structure

```
webview-kmp/
├── webview/                   # Git submodule (C++ library, never modified)
├── jni/
│   ├── CMakeLists.txt         # webview static lib + JNI shared library build
│   ├── jni_bridge.cpp         # JNI bridge (C++ → JVM)
│   └── jvm/                   # Per-OS/arch JNI publication subprojects
│       ├── darwin-aarch64, darwin-x86_64
│       ├── linux-x86_64, linux-aarch64
│       └── windows-x86_64
├── webview-kmp/               # Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/        # expect declarations + common interfaces
│       ├── commonTest/
│       ├── jvmMain/           # JVM actual (JNI) + NativeLoader
│       ├── jvmTest/
│       ├── nativeMain/        # Native actual (cinterop)
│       ├── macosArm64Test/
│       └── nativeInterop/cinterop/
├── example/                   # Cross-platform desktop demo
└── .github/workflows/         # test (3 runners) + publish (Maven Central)
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file.
