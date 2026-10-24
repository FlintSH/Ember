import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val emberVersionName: String = versionProperties.getProperty("versionName")
    ?: error("version.properties must define versionName")

/** 1.2.3 becomes 10203. Rolling builds pass their own increasing code. */
fun versionCodeOf(name: String): Int {
    val parts = name.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "versionName must be MAJOR.MINOR.PATCH below 100: $name" }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

// "stable" builds dev.fl1nt.ember. "rolling" builds a separate app that can be
// installed next to it, so testing main never blocks a stable update.
val channel = providers.gradleProperty("ember.channel").getOrElse("stable")
require(channel == "stable" || channel == "rolling") { "ember.channel must be stable or rolling" }
val rollingVersionCode = providers.gradleProperty("ember.versionCode").map(String::toInt)
val commitSha = providers.gradleProperty("ember.commit").getOrElse("dev")

// Release signing comes from the environment so secrets never live in Git.
val keystorePath = providers.environmentVariable("EMBER_KEYSTORE_PATH")
val hasReleaseSigning = keystorePath.isPresent

android {
    namespace = "dev.fl1nt.ember"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.fl1nt.ember"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(37)
        }
        versionName = if (channel == "rolling") "$emberVersionName-rolling+$commitSha" else emberVersionName
        versionCode = if (channel == "rolling") {
            rollingVersionCode.orNull ?: error("Rolling builds need -Pember.versionCode")
        } else {
            versionCodeOf(emberVersionName)
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CHANNEL", "\"$channel\"")
        buildConfigField("String", "COMMIT", "\"$commitSha\"")
        buildConfigField("String", "REPOSITORY_URL", "\"https://github.com/FlintSH/Ember\"")
        resValue("string", "app_name", if (channel == "rolling") "Ember Rolling" else "Ember")
        if (channel == "rolling") applicationIdSuffix = ".rolling"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = providers.environmentVariable("EMBER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("EMBER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("EMBER_KEY_PASSWORD").get()
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Ember Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local release builds fall back to the debug key; CI refuses to publish them.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.maxHeapSize = "2g"
                // Robolectric's API 36 runtime reaches into JDK internals that JDK 17+ hides by default.
                test.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "ObsoleteLintCustomCheck")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    dependenciesInfo {
        // Keep the APK free of Google-encrypted dependency metadata; F-Droid and
        // reproducible builds can't verify it.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
