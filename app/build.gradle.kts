plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Lock only the user-facing classpaths that actually ship in the APK and
// that compile against. Internal/metadata/test configurations (Kotlin
// multiplatform metadata, AGP test platform, Kotlin compiler classpaths)
// have unstable resolution behavior between `:app:dependencies` and the
// actual build, so locking them produces brittle lockfiles.
configurations.configureEach {
    if (isCanBeResolved && name.matches(Regex("(debug|release)(Compile|Runtime)Classpath"))) {
        resolutionStrategy.activateDependencyLocking()
    }
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
