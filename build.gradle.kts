plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Set namespace for Termux submodule libraries (AGP 8.x requires namespace in build.gradle)
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            when (project.name) {
                "terminal-emulator" -> namespace = "com.termux.terminal"
                "terminal-view" -> namespace = "com.termux.view"
            }
        }
    }
}
