/*
 * Per-OS/arch JNI artifact: linux-aarch64.
 * Ships libwebview_jni.so as a classpath resource at
 * /cn/enaium/webview/native/linux-aarch64/, which NativeLoader
 * (in :webview-kmp's jvmMain) extracts and System.load()s at runtime.
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

val jniOs = "linux"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libwebview_jni.so"
val resourceDir = "cn/enaium/webview/native/$classifier"

val hostArch = System.getProperty("os.arch").lowercase()

fun hasTool(name: String): Boolean {
    return System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
        val f = File(it, name)
        f.isFile && f.canExecute()
    }
}

// Built natively on aarch64 hosts, or cross-compiled from x86_64 hosts with
// the aarch64-linux-gnu toolchain and arm64 WebKitGTK/GTK packages
// (multiarch); pkg-config must resolve the arm64 .pc files then.
val onAarch64Host = hostArch == "aarch64"
val onX64Cross = hostArch != "aarch64" && hasTool("aarch64-linux-gnu-gcc")
val canBuildHere = OperatingSystem.current().isLinux && (onAarch64Host || onX64Cross)

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures libwebview_jni for $classifier."
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
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
    )
    if (onX64Cross) {
        environment("PKG_CONFIG_LIBDIR", "/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig:/usr/lib/pkgconfig")
        args += listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
        )
    }
    commandLine(args)
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libwebview_jni.so for $classifier."
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
