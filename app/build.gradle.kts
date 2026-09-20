import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 签名文件路径和密码放在根目录 keystore.properties，属于本机私密配置，不要随项目外传。
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.burgess.schedule"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.burgess.schedule"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "1.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName(
                if (keystoreProperties.isNotEmpty()) "release" else "debug"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.google.mlkit.text.recognition.chinese)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// AGP 9.3's built-in Kotlin compiler puts Android unit-test classes under
// intermediates/built_in_kotlinc. Keep the normal Android task untouched and
// expose a deterministic JVM runner for the same compiled test sources.
val debugUnitTestKotlinClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
)
val debugMainKotlinClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
)
tasks.register<Test>("verifyDebugUnitTests") {
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
    testClassesDirs = project.files(debugUnitTestKotlinClasses)
    classpath = project.files(
        debugUnitTestKotlinClasses,
        debugMainKotlinClasses,
        configurations.getByName("debugUnitTestRuntimeClasspath")
    )
    useJUnit()
}
