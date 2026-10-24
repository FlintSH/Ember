plugins {
    alias(libs.plugins.android.test)
}

// Black-box end-to-end tests. They drive an installed Ember APK with UI Automator,
// so they can test the exact minified, release-signed build CI publishes.
// See docs/testing.md for how CI runs them against a real Flare server.
android {
    namespace = "dev.fl1nt.ember.e2e"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk {
            version = release(29)
        }
        // API 36 keeps Android 17's local network rules from blocking the tests' own API checks.
        targetSdk {
            version = release(36)
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.okhttp)
    implementation(libs.truth)
    implementation(libs.junit)
}
