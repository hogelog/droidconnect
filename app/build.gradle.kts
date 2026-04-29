plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.sentry.android.gradle")
}

// Embedded into BuildConfig so the app can show a `<versionName>-<shortrev>` footer.
// Falls back to "unknown" when git is unavailable (e.g. source archive builds).
val gitShortRev: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

val prNumber: String? = System.getenv("PR_NUMBER")?.takeIf { it.isNotBlank() }
val baseVersionName = "0.1.0"
val appVersionName: String = if (prNumber != null) {
    "$baseVersionName-pr-$prNumber-$gitShortRev"
} else {
    baseVersionName
}

// Lock only the classpaths that ship in the APK. Internal/metadata/test
// configurations resolve inconsistently between `:app:dependencies` and the
// real build, producing brittle lockfiles.
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
        versionName = appVersionName

        // Sentry DSN is a public client-side identifier (kept in CI vars, not secrets).
        // When unset the placeholder expands to "" and Sentry auto-init becomes a no-op.
        manifestPlaceholders["sentryDsn"] = System.getenv("SENTRY_DSN") ?: ""
        manifestPlaceholders["sentryRelease"] = "$applicationId@$versionName+$versionCode"
        manifestPlaceholders["sentryDist"] = gitShortRev

        buildConfigField("String", "GIT_SHORT_REV", "\"$gitShortRev\"")
    }

    // Committed debug keystore so APKs from different CI runners share a signing
    // identity and can be installed as updates over each other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// sshlib's fat jar bundles error_prone_annotations, conflicting with the
// transitive dependency.
configurations.configureEach {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

// Symbol/mapping uploads are off until SENTRY_AUTH_TOKEN is wired up in CI.
sentry {
    autoUploadProguardMapping.set(false)
    autoUploadNativeSymbols.set(false)
    includeSourceContext.set(false)
}

dependencies {
    implementation(project(":terminal-view"))
    implementation("org.connectbot:sshlib:2.2.22")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.sentry:sentry-android:8.39.1")

    // Pinned to stabilize dependency locking: AGP 8.7.3's data binding transforms
    // resolve kotlin-stdlib-common at build time, but `--write-locks` doesn't
    // capture it, producing a lock mismatch on CI. (In Kotlin 2.0+ this artifact
    // is an empty KMP metadata jar on the JVM, so pinning has no runtime cost.)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
}
