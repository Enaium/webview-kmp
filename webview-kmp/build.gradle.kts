import java.io.File
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val jniDir = rootProject.projectDir.resolve("jni")

val hostOs = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()

// Whether the current host can cross-compile the webview library for the
// given Kotlin/Native target. Apple targets build from macOS via Xcode;
// linux targets are built on Linux hosts (WebKitGTK is resolved via
// pkg-config, so linuxArm64 requires a native aarch64 host); mingwX64 is
// cross-compiled on Linux hosts with the x86_64-w64-mingw32 toolchain.
fun hasCrossCompiler(name: String): Boolean {
    return System.getenv("PATH")?.split(File.pathSeparator).orEmpty().any { dir ->
        val f = File(dir, name)
        f.isFile && f.canExecute()
    }
}

fun canBuildNativeTarget(targetName: String): Boolean {
    return when {
        hostOs.isMacOsX && targetName.startsWith("macos") -> true
        hostOs.isLinux && targetName == "linuxX64" -> true
        hostOs.isLinux && targetName == "linuxArm64" && hostArch == "aarch64" -> true
        hostOs.isLinux && targetName == "mingwX64" && hasCrossCompiler("x86_64-w64-mingw32-gcc") -> true
        else -> false
    }
}

fun resolveCmakeExecutable(): String {
    val exeName = if (OperatingSystem.current().isWindows) "cmake.exe" else "cmake"

    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }

    val extraPaths = listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/opt/local/bin",
    )
    extraPaths.forEach { dir ->
        val candidate = File(dir, exeName)
        if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
    }

    return exeName
}

val cmakeExecutable: String by lazy { resolveCmakeExecutable() }

// Linker options for the final binary, resolved per platform:
// - Apple links the WebKit framework directly.
// - Linux must link the WebKitGTK/GTK stack found by pkg-config at
//   configuration time (targets are only configured on Linux hosts).
fun pkgConfigLibs(modules: List<String>): List<String> {
    if (!hostOs.isLinux) return emptyList()
    return try {
        val process = ProcessBuilder(listOf("pkg-config", "--libs") + modules)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) {
            // ld.lld (used by Kotlin/Native) only accepts -l/-L-style flags:
            // drop GCC-driver flags (-pthread, -Wl,...) and add the host's
            // multiarch library directory, since lld searches the bundled
            // Kotlin/Native sysroot rather than the host's default paths.
            val multiarch = when (hostArch) {
                "x86_64", "amd64" -> "x86_64-linux-gnu"
                "aarch64", "arm64" -> "aarch64-linux-gnu"
                else -> null
            }
            val libDirs = listOfNotNull(
                multiarch?.let { "-L/usr/lib/$it" },
                multiarch?.let { "-L/lib/$it" },
                "-ldl",
            )
            output.trim().split(Regex("\\s+"))
                .filter {
                    it.isNotBlank() &&
                        !it.startsWith("-pthread") &&
                        !it.startsWith("-Wl,")
                }
                .plus(libDirs)
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

// The webview C API is defined in a plain C header (api.h), but the final
// binary link needs platform-specific libraries:
// - Apple links the WebKit framework directly.
// - Linux must link the WebKitGTK/GTK stack found by pkg-config at
//   configuration time (targets are only configured on Linux hosts).
// - MinGW links the Win32 system libraries used by the edge backend.
// cinterop records these in the generated klib, so they are embedded into
// the per-target .def file rather than passed on the command line (the
// -linker-option form is not supported by cinterop in recent Kotlin).
fun webviewDefFile(targetName: String, canBuild: Boolean): File {
    val dir = layout.buildDirectory.dir("def").get().asFile
    dir.mkdirs()
    val file = File(dir, "webview-$targetName.def")
    val linkerOpts = if (!canBuild) emptyList() else when {
        targetName.startsWith("macos") -> listOf("-framework WebKit", "-ldl")
        targetName.startsWith("linux") ->
            // --allow-shlib-undefined: the host's shared libraries (glibc
            // 2.34+) carry versioned undefined references (e.g.
            // dlopen@GLIBC_2.34) that only resolve at runtime against the
            // host's libc; lld would reject them against the bundled
            // Kotlin/Native sysroot (glibc 2.19).
            listOf("--allow-shlib-undefined") + pkgConfigLibs(listOf("webkit2gtk-4.1", "gtk+-3.0"))
        targetName.startsWith("mingw") -> listOf(
            "-ladvapi32", "-lole32", "-lshell32",
            "-lshlwapi", "-luser32", "-lversion",
        )
        else -> emptyList()
    }
    file.writeText(
        buildString {
            appendLine("headers = api.h")
            appendLine("compilerOpts = -std=c11")
            if (linkerOpts.isNotEmpty()) {
                appendLine("linkerOpts = ${linkerOpts.joinToString(" ")}")
            }
        },
    )
    return file
}

kotlin {
    // ==================== JVM ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Native (desktop only) ====================
    macosArm64()
    macosX64()

    linuxX64()
    linuxArm64()

    mingwX64()

    // ==================== cinterop for all native targets ====================
    targets.withType<KotlinNativeTarget> {
        val targetName = this.name
        val canBuild = canBuildNativeTarget(targetName)
        if (!canBuild) {
            // Non-buildable targets can still be compiled and published (their
            // cinterop klibs ship without the static library), but test
            // binaries cannot be linked on this host.
            val capitalized = targetName.replaceFirstChar { c -> c.uppercase() }
            tasks.matching { it.name.startsWith("link") && it.name.endsWith(capitalized) }
                .configureEach { enabled = false }
        }
        compilations.getByName("main") {
            cinterops {
                create("webview") {
                    defFile(webviewDefFile(targetName, canBuild))
                    packageName("webview")
                    includeDirs(
                        project.file("src/nativeInterop/cinterop"),
                        rootProject.file("webview/core/include/webview"),
                    )
                    if (canBuild) {
                        // Embed the per-target static library into the produced
                        // cinterop klib. Targets that can't be built on this host
                        // still get bindings (for klib publishing); the static
                        // library is built and embedded when building on the
                        // matching host.
                        val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
                        extraOpts(
                            "-libraryPath", outputDir.absolutePath,
                            "-staticLibrary", "libwebview.a",
                        )
                    }
                }
            }
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Bundle all five JNI artifacts so consumers get the right
                // native binary out of the box; NativeLoader picks one at
                // runtime by os.name/os.arch.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

// ==================== Native: build static webview library for each target ====================
fun registerNativeBuildTasks(targetName: String, cmakeFlags: List<String> = emptyList()) {
    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-$targetName").get().asFile

    val configureTask = tasks.register<Exec>("configureNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${outputDir.absolutePath}",
            ) + cmakeFlags,
        )
    }

    val buildTask = tasks.register<Exec>("buildNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    }

    tasks.matching {
        it.name.startsWith("cinteropWebview") &&
            it.name.endsWith(targetName.replaceFirstChar { c -> c.uppercase() })
    }.configureEach {
        dependsOn(buildTask)
    }
}

if (hostOs.isMacOsX) {
    registerNativeBuildTasks(
        "macosArm64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        ),
    )
    registerNativeBuildTasks(
        "macosX64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        ),
    )
} else if (hostOs.isLinux) {
    registerNativeBuildTasks("linuxX64")
    if (hostArch == "aarch64") {
        registerNativeBuildTasks("linuxArm64")
    }
    // Cross-compile the MinGW static library with the
    // x86_64-w64-mingw32 toolchain (canBuildNativeTarget gates on it).
    registerNativeBuildTasks(
        "mingwX64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=Windows",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
            "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
        ),
    )
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "webview-kmp",
        // null -> the plugin falls back to project.version (gradle.properties
        // or -Pversion on the command line)
        version = null,
    )

    pom {
        name.set("webview-kmp")
        description.set(
            "Kotlin Multiplatform bindings for the webview library (desktop: JVM and Kotlin/Native).",
        )
        url.set("https://github.com/Enaium/webview-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/webview-kmp")
            connection.set("scm:git:git@github.com:Enaium/webview-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/webview-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/webview-kmp/issues")
        }
    }
}
