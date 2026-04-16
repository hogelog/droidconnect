plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "org.hogel.droidconnect"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.hogel.droidconnect"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// sshlib bundles error_prone_annotations classes in its fat jar, causing duplicate
// class errors with the separate error_prone_annotations dependency pulled transitively.
configurations.configureEach {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("org.connectbot:sshlib:2.2.22")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

// Resolves every resolvable configuration so `--write-locks` captures all
// runtime/compile classpaths. `./gradlew dependencies` alone misses some
// resolutions that only occur during the actual build.
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "$path must be run with --write-locks"
        }
    }
    doLast {
        configurations.filter {
            it.isCanBeResolved &&
                    // Only lock the classpaths the actual build uses. Other
                    // configurations (metadata, kotlin-build-tools, internal
                    // AGP test platform, android test classpaths) either
                    // resolve against incompatible subproject variants or are
                    // irrelevant to the production lock state.
                    it.name.matches(Regex("(debug|release)(UnitTest)?(Compile|Runtime)Classpath"))
        }.forEach { it.resolve() }
    }
}
