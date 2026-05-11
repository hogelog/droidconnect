pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradle.develocity") version "4.4.1"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        publishing.onlyIf { System.getenv("CI") == "true" }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketSecureShell"

include(":app")
include(":terminal-emulator")
include(":terminal-view")

project(":terminal-emulator").projectDir = file("vendor/termux-app/terminal-emulator")
project(":terminal-view").projectDir = file("vendor/termux-app/terminal-view")
