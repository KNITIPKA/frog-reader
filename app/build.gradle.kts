import java.util.Properties
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (built-in Kotlin) — only compiler plugins are applied here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

// Version changes are deliberate release commits. Rebuilding the same source
// must not change the version or mutate tracked files.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseVersionCode = requireNotNull(versionProps.getProperty("code")).toInt()
val releaseVersionName = requireNotNull(versionProps.getProperty("name"))
require(releaseVersionCode > 0 && releaseVersionName.isNotBlank())

// Private signing material stays outside Git. Configure credentials via
// FROGREADER_KEYSTORE_* environment variables, -PsigningProperties=<file>, or a
// gitignored root signing.properties file. Without credentials, release builds
// stay unsigned (required for F-Droid and public CI).
val signingPropsArg = providers.gradleProperty("signingProperties").orNull
val signingPropsText = if (signingPropsArg != null) {
    val candidate = File(signingPropsArg)
    val resolved = if (candidate.isAbsolute) candidate else rootProject.file(signingPropsArg)
    requireNotNull(providers.fileContents(layout.file(provider { resolved })).asText.orNull) {
        "Signing properties file not found: ${resolved.path}"
    }
} else {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing.properties")).asText.orNull
}
val signingProps = Properties().apply {
    signingPropsText?.reader()?.use(::load)
}
fun optionalSigningValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
fun requiredSigningValue(name: String): String =
    requireNotNull(optionalSigningValue(name)) {
        "Missing release signing value: $name (configure environment variable or signing properties)"
    }
val signingKeys = listOf(
    "FROGREADER_KEYSTORE_PATH",
    "FROGREADER_KEYSTORE_PASSWORD",
    "FROGREADER_KEY_ALIAS",
    "FROGREADER_KEY_PASSWORD",
)
val hasReleaseSigning = signingPropsArg != null || signingKeys.any { optionalSigningValue(it) != null }

android {
    namespace = "com.example.frogreader"
    // A small JNI adapter calls annotation functions in the existing PDFium binary.
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileSdk {
        version = release(37)
    }

    dependenciesInfo {
        // Required for reproducible & F-Droid builds: omit Google's encrypted dependency metadata block.
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "knitipka.frogreader"
        // AndroidX PDF beta requires API 28, including its sandbox service.
        minSdk = 28
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // The welcome badge follows the same release label as About and Settings.
        resValue("string", "welcome_release_label", versionName!!)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=none"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val rawPath = requiredSigningValue("FROGREADER_KEYSTORE_PATH")
                val candidate = File(rawPath)
                storeFile = if (candidate.isAbsolute) candidate else rootProject.file(rawPath)
                storePassword = requiredSigningValue("FROGREADER_KEYSTORE_PASSWORD")
                keyAlias = requiredSigningValue("FROGREADER_KEY_ALIAS")
                keyPassword = requiredSigningValue("FROGREADER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        aidl = true
        compose = true
        resValues = true
    }
    packaging {
        // Keep the supplied AndroidX/PDFium binaries unchanged. Only our small
        // annotation JNI adapter is compiled locally.
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
            "**/libimage_processing_util_jni.so",
            "**/libsurface_util_jni.so",
            "**/libpdfium.so",
            "**/libpdfiumandroid.so",
            "**/libsqliteJni.so",
        )
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Rust stays independent from the existing PDF CMake build. Host and Android
// targets use separate output directories, so test hooks never enter the APK.
val defaultCargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    .takeIf { it.isFile && it.canExecute() }?.absolutePath ?: "cargo"
val cargoExecutable = providers.gradleProperty("cargoExecutable")
    .orElse(providers.environmentVariable("CARGO_HOME").map { "$it/bin/cargo" })
    .getOrElse(defaultCargo)
val rustWorkspace = rootProject.layout.projectDirectory.dir("rust")
val rustInputs = rootProject.fileTree("rust") {
    include("Cargo.toml", "Cargo.lock", "**/Cargo.toml", "**/src/**", ".cargo/config.toml", "build-android.sh")
    exclude("target/**")
}
val rustToolchain = rootProject.layout.projectDirectory.file("rust-toolchain.toml")
val rustHostOutput = layout.buildDirectory.dir("rust/host")
val rustJniOutput = layout.buildDirectory.dir("generated/rustJniLibs")
val rustAndroidOutput = layout.buildDirectory.dir("rust/android")
val hostLibraryName = System.mapLibraryName("frogreader_core")

abstract class CargoNdkBuildTask : Exec() {
    @get:OutputDirectory
    abstract val jniOutputDirectory: DirectoryProperty
}

val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the pinned Rust JNI library for host JVM tests."
    workingDir(rustWorkspace)
    inputs.files(rustInputs, rustToolchain).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("hostOs", System.getProperty("os.name"))
    inputs.property("hostArch", System.getProperty("os.arch"))
    outputs.file(rustHostOutput.map { it.file("release/$hostLibraryName") })
    commandLine(cargoExecutable, "build", "--release", "--locked", "--workspace",
        "--features", "test-hooks", "--target-dir", rustHostOutput.get().asFile.absolutePath)
}

val cargoTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the Rust workspace unit tests."
    workingDir(rustWorkspace)
    inputs.files(rustInputs, rustToolchain)
    commandLine(cargoExecutable, "test", "--workspace", "--locked")
}

val cargoNdkBuild by tasks.registering(CargoNdkBuildTask::class) {
    group = "rust"
    description = "Builds Android JNI libraries with pinned cargo-ndk and NDK, without host test hooks."
    workingDir(rustWorkspace)
    inputs.files(rustInputs, rustToolchain).withPathSensitivity(PathSensitivity.RELATIVE)
    jniOutputDirectory.set(rustJniOutput)
    environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.sdkDirectory.get()
        .dir("ndk/30.0.16248370").asFile.absolutePath)
    commandLine("sh", rustWorkspace.file("build-android.sh").asFile.absolutePath,
        cargoExecutable, rustJniOutput.get().asFile.absolutePath,
        rustAndroidOutput.get().asFile.absolutePath)
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild) { it.jniOutputDirectory }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val sqliteVersion = libs.versions.sqlite.get()
configurations.matching { it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:$sqliteVersion"))
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
    dependsOn(cargoBuildHost)
    inputs.file(rustHostOutput.map { it.file("release/$hostLibraryName") })
    systemProperty("java.library.path", rustHostOutput.get().dir("release").asFile.absolutePath)
    systemProperty("frogreader.requireRust", "true")
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
        // Opt-in private large-book measurements can retain multiple full models.
        maxHeapSize = "3g"
    }
    providers.environmentVariable("FROGREADER_PROFILE_JFR").orNull?.let {
        systemProperty("frogreader.profileJfr", it)
    }
    if (name == "testDebugUnitTest") {
        providers.gradleProperty("engineBenchmarkReport").orNull?.let {
            systemProperty("frogreader.engineBenchmarkReport", it)
            outputs.file(it)
        }
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)
    implementation(libs.pdfium)
    // Version-pinned native contracts enable a bounded outline walk on the same locked document.
    implementation(libs.pdfium.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.brotli.dec)
    testImplementation(libs.commonmark)
    testImplementation(libs.commonmark.tables)
    testImplementation(libs.commonmark.strikethrough)
    // Real XML pull parser for JVM unit tests (Android provides its own at runtime).
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
