import java.util.Properties
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (built-in Kotlin) — only compiler plugins are applied here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version changes are deliberate release commits. Rebuilding the same source
// must not change the version or mutate tracked files.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseVersionCode = requireNotNull(versionProps.getProperty("code")).toInt()
val releaseVersionName = requireNotNull(versionProps.getProperty("name"))
require(releaseVersionCode > 0 && releaseVersionName.isNotBlank())

// Private signing material stays outside Git. Without credentials, release
// builds are unsigned; CI still verifies the optimized application.
val releaseStorePath = providers.environmentVariable("FROGREADER_KEYSTORE_PATH").orNull
val legacyReleaseSigning = providers.gradleProperty("legacyReleaseSigning")
    .map { it.toBooleanStrict() }.getOrElse(false)
fun signingValue(name: String) = requireNotNull(providers.environmentVariable(name).orNull) {
    "Missing release signing environment variable: $name"
}

android {
    namespace = "com.example.frogreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "knitipka.frogreader"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // The welcome badge follows the same release label as About and Settings.
        resValue("string", "welcome_release_label", versionName!!)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStorePath != null) {
            create("externalRelease") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingValue("FROGREADER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("FROGREADER_KEY_ALIAS")
                keyPassword = signingValue("FROGREADER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Alpha releases used the maintainer's local debug certificate.
            // BETA 1 explicitly reuses it so existing installs can update.
            signingConfig = when {
                releaseStorePath != null -> signingConfigs.getByName("externalRelease")
                legacyReleaseSigning -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    packaging {
        // Package known AndroidX native binaries as supplied. The app has no
        // native compilation, so preserving their symbols avoids requiring a
        // local NDK just to reprocess these vendor artifacts.
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
            "**/libimage_processing_util_jni.so",
            "**/libsurface_util_jni.so",
        )
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

val mockitoAgent = configurations.create("mockitoAgent") {
    isCanBeConsumed = false
    isTransitive = false
}

abstract class MockitoAgentArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentClasspath: ConfigurableFileCollection

    override fun asArguments() = listOf("-javaagent:${agentClasspath.singleFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    // Attach Mockito at startup instead of dynamically attaching on newer JDKs.
    jvmArgumentProviders.add(objects.newInstance<MockitoAgentArguments>().apply {
        agentClasspath.from(mockitoAgent)
    })
    // Inline mocking appends bootstrap classes, which cannot use the CDS archive.
    jvmArgs("-Xshare:off")
    // The persistent FrogCompare corpus is generated only when explicitly asked.
    // Project properties do not reach the forked unit-test JVM by default.
    systemProperty(
        "frogreader.generateTestBooks",
        providers.gradleProperty("generateTestBooks").getOrElse("false"),
    )
    providers.gradleProperty("testBookDir").orNull?.let {
        systemProperty("frogreader.testBookDir", it)
    }
    providers.environmentVariable("FROGREADER_PROFILE_EPUB").orNull?.let {
        inputs.file(it)
        systemProperty("frogreader.profileEpub", it)
    }
    providers.environmentVariable("FROGREADER_PROFILE_JFR").orNull?.let {
        systemProperty("frogreader.profileJfr", it)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.brotli.dec)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    // Real XML pull parser for JVM unit tests (Android provides its own at runtime).
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
