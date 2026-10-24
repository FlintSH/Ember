import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Lets the app's lint check this module against Android API levels.
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
        // Compile against the JDK 17 API so newer JDK methods can't slip in.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(libs.truth)
                implementation(libs.okhttp.mockwebserver)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Runs Ember's client against a real Flare instance. Requires
        // FLARE_TEST_URL and FLARE_TEST_TOKEN; see scripts/flare-fixture.
        register<JvmTestSuite>("integrationTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.truth)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    outputs.upToDateWhen { false }
                    environment("FLARE_FIXTURE", providers.environmentVariable("FLARE_FIXTURE").getOrElse(""))
                }
            }
        }
    }
}
