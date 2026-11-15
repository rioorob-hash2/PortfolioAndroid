import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись: пароли берём ТОЛЬКО из keystore.properties (он в .gitignore), в коде их нет.
val keystoreFile = rootProject.file("keystore.jks")
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val signingReady = keystoreFile.exists() &&
    listOf("storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() }

// Без ключей релизная сборка падает с понятным сообщением, а не отдаёт молча неподписанный APK.
if (!signingReady) {
    gradle.taskGraph.whenReady {
        val wantsSigned = allTasks.any {
            it.project == project && it.name in setOf("assembleProd", "packageProd", "assembleRelease", "packageRelease")
        }
        if (wantsSigned) {
            throw GradleException(
                "Нет ключей подписи: положи в корень проекта keystore.jks и keystore.properties " +
                    "(storePassword, keyAlias, keyPassword). Тесты и debug-сборка работают и без них."
            )
        }
    }
}

android {
    namespace = "com.brotik.portfolio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brotik.portfolio"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (signingReady) {
        signingConfigs {
            create("portfolio") {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingReady) signingConfig = signingConfigs.getByName("portfolio")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (signingReady) signingConfig = signingConfigs.getByName("portfolio")
        }
        // На этой машине путь app-release.apk иногда блокируется фильтром, поэтому релиз = prod.
        create("prod") {
            initWith(getByName("release"))
            isDebuggable = false
            if (signingReady) signingConfig = signingConfigs.getByName("portfolio")
            matchingFallbacks += listOf("release")
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { it.maxHeapSize = "2g" }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    val shared = rootProject.file("../PriceHubbApp/robolectric-deps")
    val own = rootProject.file("robolectric-deps")
    systemProperty("robolectric.dependency.dir", (if (own.exists()) own else shared).absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
    systemProperty("robolectric.dependency.repo.id", "central")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
