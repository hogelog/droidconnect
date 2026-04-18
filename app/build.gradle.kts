import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.sentry.android.gradle")
}

// Resolve the short git SHA at configuration time and embed it into
// BuildConfig so the app can show a `<versionName>-<shortrev>` footer.
// Falls back to "unknown" if git is unavailable (e.g. source archive builds).
val gitShortRev: String = run {
    val stdout = ByteArrayOutputStream()
    try {
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        stdout.toString().trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
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

        // Sentry DSN is injected at build time from the SENTRY_DSN environment
        // variable (sourced from GitHub Actions `vars.SENTRY_DSN` in CI).
        // DSN is a public client-side identifier per Sentry's guidance, so
        // committing it as a var (not a secret) is intentional. When the env
        // var is absent (e.g. local builds without Sentry configured), the
        // placeholder expands to an empty string and Sentry auto-init becomes
        // a no-op -- the SDK detects the empty DSN and skips initialization.
        manifestPlaceholders["sentryDsn"] = System.getenv("SENTRY_DSN") ?: ""

        // Tag Sentry events with release + dist so issues can be tracked per
        // build. Release follows Sentry's Android convention
        // (`applicationId@versionName+versionCode`); dist is the short git SHA
        // so the exact commit behind any given versionCode is recoverable.
        manifestPlaceholders["sentryRelease"] = "$applicationId@$versionName+$versionCode"
        manifestPlaceholders["sentryDist"] = gitShortRev

        buildConfigField("String", "GIT_SHORT_REV", "\"$gitShortRev\"")
    }

    // Use a committed debug keystore so CI builds across PRs share the same
    // signing identity. Without this, AGP auto-generates a fresh debug keystore
    // on every CI runner, producing APKs that cannot be installed as updates
    // over each other (Android rejects installs with mismatched signatures).
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

// sshlib bundles error_prone_annotations classes in its fat jar, causing duplicate
// class errors with the separate error_prone_annotations dependency pulled transitively.
configurations.configureEach {
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

// Sentry Gradle plugin configuration. Symbol/mapping uploads require
// SENTRY_AUTH_TOKEN, which is deliberately out of scope for this PR --
// they will be wired up in a follow-up along with release.yml changes.
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

    // Pinned explicitly to stabilize dependency locking.
    // AGP 8.7.3's data binding artifact transforms resolve
    // kotlin-stdlib-common in debug/releaseRuntimeClasspath during the
    // real build, but `:app:dependencies --write-locks` does not capture
    // it there -- producing a strict lock mismatch on CI. Declaring it
    // explicitly forces the lock state to match the actual resolution.
    // (In Kotlin 2.0+ this artifact is an essentially empty KMP metadata
    // jar on the JVM, so pinning has no runtime cost.)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
}
