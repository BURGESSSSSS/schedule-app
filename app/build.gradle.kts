import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
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
val requiredReleaseSigningProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingReleaseSigningProperties = requiredReleaseSigningProperties.filter {
    keystoreProperties.getProperty(it)?.isNotBlank() != true
}
if (keystorePropertiesFile.exists() && missingReleaseSigningProperties.isNotEmpty()) {
    throw GradleException(
        "keystore.properties is incomplete; missing required properties: " +
            missingReleaseSigningProperties.joinToString(", ")
    )
}
val configuredReleaseStoreFile = keystoreProperties.getProperty("storeFile")?.let(::file)
val configuredReleaseKeyAlias = keystoreProperties.getProperty("keyAlias")
val isStandardAndroidDebugSigningIdentity =
    configuredReleaseStoreFile?.name?.equals("debug.keystore", ignoreCase = true) == true ||
        configuredReleaseKeyAlias?.equals("androiddebugkey", ignoreCase = true) == true
val hasReleaseSigning = keystorePropertiesFile.exists() && !isStandardAndroidDebugSigningIdentity
if (!hasReleaseSigning) {
    logger.warn(
        if (isStandardAndroidDebugSigningIdentity) {
            "The configured release identity is the standard Android debug key; release APKs will be unsigned."
        } else {
            "keystore.properties is absent; release APKs will be unsigned."
        }
    )
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
        if (hasReleaseSigning) {
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // AGP's AIDL dependency parser cannot decode this project's Chinese path on Windows.
        // IUserService.java is generated from the checked-in AIDL contract with build-tools 36.
        aidl = false
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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.libadb.android)
    debugImplementation(libs.sun.security.android)
    debugImplementation(libs.conscrypt.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
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
val testRuntimeDirectory = layout.buildDirectory.dir("test-runtime")
val androidTestRuntimeDirectory = layout.buildDirectory.dir("test-runtime/android")
val portableTestClasses = tasks.register<Jar>("portableTestClasses") {
    dependsOn("compileDebugUnitTestKotlin")
    from(debugUnitTestKotlinClasses)
    destinationDirectory.set(testRuntimeDirectory)
    archiveFileName.set("schedule-tests.jar")
}
val portableMainClasses = tasks.register<Jar>("portableMainClasses") {
    dependsOn("compileDebugKotlin")
    from(debugMainKotlinClasses)
    destinationDirectory.set(testRuntimeDirectory)
    archiveFileName.set("schedule-main.jar")
}
abstract class ExtractAarClassesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun extractClassesJars() {
        val destination = destinationDirectory.get().asFile
        destination.mkdirs()
        runtimeArtifacts.files.filter { it.extension == "aar" }.forEach { aar ->
            ZipFile(aar).use { zip ->
                zip.getEntry("classes.jar")?.let { entry ->
                    zip.getInputStream(entry).use { input ->
                        destination.resolve(aar.nameWithoutExtension + ".jar").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
val portableAndroidRuntime = tasks.register<ExtractAarClassesTask>("portableAndroidRuntime") {
    runtimeArtifacts.from(configurations.named("debugUnitTestRuntimeClasspath"))
    destinationDirectory.set(androidTestRuntimeDirectory)
}
tasks.register<Test>("verifyDebugUnitTests") {
    dependsOn(portableAndroidRuntime)
    dependsOn(portableTestClasses, portableMainClasses)
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
    testClassesDirs = project.files(debugUnitTestKotlinClasses)
    classpath = project.files(
        portableTestClasses.flatMap { it.archiveFile },
        portableMainClasses.flatMap { it.archiveFile },
        fileTree(androidTestRuntimeDirectory) { include("*.jar") },
        configurations.getByName("debugUnitTestRuntimeClasspath")
    )
    useJUnit()
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
