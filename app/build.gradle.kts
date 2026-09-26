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
import org.gradle.api.tasks.JavaExec

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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    implementation(libs.conscrypt.android)
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
// intermediates/built_in_kotlinc. Run those classes directly because Gradle's
// Test worker currently discovers them but cannot load them from this output.
val debugUnitTestKotlinClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
)
val androidTestRuntimeDirectory = layout.buildDirectory.dir("test-runtime/android")
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
tasks.register<JavaExec>("verifyDebugUnitTests") {
    dependsOn(portableAndroidRuntime)
    dependsOn("compileDebugKotlin", "compileDebugUnitTestKotlin")
    classpath = project.files(
        debugUnitTestKotlinClasses,
        layout.projectDirectory.dir("src/test/resources"),
        fileTree(androidTestRuntimeDirectory) { include("*.jar") },
        configurations.getByName("debugUnitTestRuntimeClasspath")
    )
    mainClass.set("org.junit.runner.JUnitCore")
    doFirst {
        val testRoot = debugUnitTestKotlinClasses.get().asFile
        val testNames = fileTree(testRoot)
            .matching { include("**/*Test.class") }
            .files
            .filterNot { '$' in it.name }
            .map { testClass ->
                testRoot.toPath().relativize(testClass.toPath()).toString()
                    .replace(File.separatorChar, '.')
                    .removeSuffix(".class")
            }
            .sorted()
        check(testNames.isNotEmpty()) { "No compiled JVM test classes were found" }
        setArgs(testNames)
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
