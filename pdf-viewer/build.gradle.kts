plugins { id("com.android.library") }

android {
    namespace = "androidx.pdf"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions.unitTests.isReturnDefaultValues = true
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    lint { disable += "RestrictedApi" }
}

kotlin {
    compilerOptions {
        // Preserve Kotlin internal JVM names used by the published Compose wrapper.
        moduleName = "pdf-viewer"
        optIn.add("androidx.pdf.ExperimentalPdfApi")
    }
}

dependencies {
    api("androidx.pdf:pdf-core:${libs.versions.pdf.get()}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
    api("org.jspecify:jspecify:1.0.0")
    implementation(libs.androidx.core.ktx)
    implementation("com.google.errorprone:error_prone_annotations:2.30.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation(libs.androidx.exifinterface)
    implementation("androidx.customview:customview:1.2.0")
    implementation("androidx.annotation:annotation:1.7.0")
    implementation("androidx.autofill:autofill:1.3.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
