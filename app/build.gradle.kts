import java.util.Properties
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("com.android.application")
    id("jacoco")
}

android {
    namespace = "com.ettlinger.wearrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.etti.clawhark"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val keyPropsFile = rootProject.file("keystore.properties")
            if (keyPropsFile.exists()) {
                val props = Properties().apply { keyPropsFile.inputStream().use { load(it) } }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "META-INF/INDEX.LIST" // JVM JAR index; unused by Android.
        resources.merges += "META-INF/DEPENDENCIES"
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads app classes without a CodeSource location.
        isIncludeNoLocationClasses = true
        includes = listOf("com.ettlinger.wearrecorder.*")
    }
}

dependencies {
    // Android uses NetHttpTransport; Apache transports target desktop/server Java.
    implementation("com.google.apis:google-api-services-drive:v3-rev20260916-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "google-http-client-apache-v2")
    }
    implementation("com.google.http-client:google-http-client-gson:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.core:core:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}
