pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "webview-kmp"

include(":webview-kmp")
include(":example")

// Per-OS/arch JNI artifacts that bundle the prebuilt libwebview_jni shared
// library as a classpath resource. NativeLoader extracts the matching one at
// runtime.
listOf(
    "linux-x86_64",
    "linux-aarch64",
    "darwin-x86_64",
    "darwin-aarch64",
    "windows-x86_64",
).forEach { classifier ->
    val name = ":jni-jvm-$classifier"
    include(name)
    project(name).projectDir = file("jni/jvm/$classifier")
}
