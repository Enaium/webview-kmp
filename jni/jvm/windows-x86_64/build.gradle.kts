/*
 * Per-OS/arch JNI artifact: windows-x86_64.
 * Ships webview_jni.dll as a classpath resource at
 * /cn/enaium/webview/native/windows-x86_64/, which NativeLoader
 * (in :webview-kmp's jvmMain) extracts and System.load()s at runtime.
 *
 * The DLL is built with MinGW: natively on Windows hosts (choco/msys2), or
 * cross-compiled on Linux hosts with the x86_64-w64-mingw32 toolchain.
 */
import java.io.File
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "windows"
val jniArch = "x86_64"
val classifier = "$jniOs-$jniArch"
val libFile = "webview_jni.dll"
val resourceDir = "cn/enaium/webview/native/$classifier"

fun hasTool(name: String): Boolean {
    return System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
        val f = File(it, name)
        f.isFile && f.canExecute()
    }
}

val host = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()

// The DLL is built with MinGW: natively on Windows hosts (choco/msys2), or
// cross-compiled on Linux hosts with the x86_64-w64-mingw32 toolchain.
val onWindowsHost = host.isWindows && (hostArch == "amd64" || hostArch == "x86_64")
val onLinuxCross = host.isLinux && hasTool("x86_64-w64-mingw32-gcc")
val canBuildHere = onWindowsHost || onLinuxCross

val makeGenerator = when {
    System.getenv("MSYSTEM") != null -> "MSYS Makefiles"
    host.isWindows -> "MinGW Makefiles"
    else -> null
}

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures webview_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    val args = mutableListOf(
        "cmake",
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/win32",
        // DLLs are RUNTIME outputs in CMake, not LIBRARY outputs.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        // Statically link the MinGW runtime so the DLL has no dependency on
        // libstdc++-6.dll / libgcc_s_seh-1.dll, which are not on the JVM's
        // PATH.
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++",
    )
    if (makeGenerator != null) {
        args.addAll(listOf("-G", makeGenerator))
    }
    if (onLinuxCross) {
        args.addAll(
            listOf(
                "-DCMAKE_SYSTEM_NAME=Windows",
                "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
                "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
                "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
            ),
        )
    }
    commandLine(args)
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds webview_jni.dll for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine("cmake", "--build", ".", "--config", "Release")
    inputs.files(rootProject.file("jni/CMakeLists.txt"), rootProject.file("jni/jni_bridge.cpp"))
    inputs.dir(rootProject.file("webview"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Use the build task's declared outputs (lazily resolved at execution
    // time) instead of the directory Provider, which may be snapshotted
    // empty at configuration time.
    from(buildJniLibrary.map { it.outputs.files }) {
        include(libFile)
        into(resourceDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "webview-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("webview-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for webview-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
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
