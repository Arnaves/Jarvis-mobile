plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs out of the box.
            // Replace with your own signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }
    androidResources {
        // Vosk reads its model files from disk after copying; keep them uncompressed for a fast first copy.
        noCompress += listOf("mdl", "fst", "int", "conf", "mat")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // On-device LLM runtime (Gemma / Qwen / Phi .task models, fully offline)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // Geofences for "when I reach office…" tasks
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // play-services pulls an old Fragment; ActivityResult APIs need >= 1.3
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Offline, always-on "Hey JARVIS" wake-word detection
    implementation("com.alphacephei:vosk-android:0.3.47") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric normally downloads android-all jars from Maven Central at test time, outside Gradle's
// cache and retries, and Maven Central rate-limits that (HTTP 429). Resolve them through Gradle
// instead and run Robolectric offline against the copied jars.
// One configuration per jar: they are versions of the same module, and a single configuration
// would let Gradle's conflict resolution keep only the newest.
val robolectricSdkJars = listOf(
    "14-robolectric-10818077-i7", // SDK 34 (tests' @Config)
    "15-robolectric-12650502-i7", // SDK 35 (targetSdk, also loaded)
).mapIndexed { i, v ->
    configurations.create("robolectricJars$i") { isTransitive = false }.also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$v")
    }
}
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars by tasks.registering(Copy::class) {
    robolectricSdkJars.forEach { from(it) }
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
        showStandardStreams = false
    }
    doFirst { println("Robolectric offline jars: " + robolectricDir.get().asFile.listFiles()?.joinToString { it.name }) }
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}
