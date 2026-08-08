import java.io.File
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The webview library can only be built on its matching host (the static
// library embeds platform frameworks / system libraries), so the example
// declares the targets that are actually buildable on this machine:
// - macOS hosts: jvm + macosArm64 + macosX64
// - Linux hosts: jvm + linuxX64 (+ linuxArm64 on aarch64, + mingwX64 with
//   the x86_64-w64-mingw32 cross toolchain)
val hostOs = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()

fun hasCrossCompiler(name: String): Boolean {
    return System.getenv("PATH")?.split(File.pathSeparator).orEmpty().any { dir ->
        val f = File(dir, name)
        f.isFile && f.canExecute()
    }
}

val nativeTargets = mutableListOf<String>()

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    if (hostOs.isMacOsX) {
        macosArm64 { binaries.executable() }
        macosX64 { binaries.executable() }
        nativeTargets += listOf("macosArm64", "macosX64")
    } else if (hostOs.isLinux) {
        linuxX64 { binaries.executable() }
        nativeTargets += "linuxX64"
        if (hostArch == "aarch64") {
            linuxArm64 { binaries.executable() }
            nativeTargets += "linuxArm64"
        }
        if (hasCrossCompiler("x86_64-w64-mingw32-gcc")) {
            mingwX64 { binaries.executable() }
            nativeTargets += "mingwX64"
        }
    }

    // With kotlin.mpp.applyDefaultHierarchyTemplate=false (set project-wide)
    // there is no intermediate nativeMain source set; share the native entry
    // point across all native targets explicitly.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        binaries.withType<Executable>().configureEach {
            // Kotlin/Native executables look for `main` in the root package by
            // default, so point the entry point at the example's `main`.
            entryPoint = "cn.enaium.webview.example.main"
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":webview-kmp"))
            }
        }
    }
}

// JVM: run the desktop example (NativeLoader picks the JNI binary bundled in
// the matching :jni-jvm-* artifact).
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the JVM example (desktop)."
    mainClass.set("cn.enaium.webview.example.Main_jvmKt")
    classpath = (jvmMainCompilation.runtimeDependencyFiles ?: files()) +
        jvmMainCompilation.output.allOutputs
    // On macOS the webview's Cocoa backend must run on the process main
    // thread, but modern JDKs launch main() on a new thread by default.
    if (hostOs.isMacOsX) {
        jvmArgs("-XstartOnFirstThread")
    }
}

// Native: run the linked debug executable. The Windows (mingwX64) executable
// must be copied to a Windows machine, so no run task is registered for it.
nativeTargets.forEach { targetName ->
    if (targetName.startsWith("mingw")) return@forEach
    val capitalized = targetName.replaceFirstChar { it.uppercase() }
    val linkTask = tasks.named<KotlinNativeLink>("linkDebugExecutable$capitalized")
    tasks.register<Exec>("run$capitalized") {
        group = "application"
        description = "Runs the $targetName example."
        dependsOn(linkTask)
        doFirst {
            commandLine(linkTask.get().outputFile.get().absolutePath)
        }
    }
}
