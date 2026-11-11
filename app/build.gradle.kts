plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.onde.radio"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.onde.radio"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.7.7"
    }

    // Clé de signature fixe, fournie par les secrets GitHub KEYSTORE_BASE64 et KEYSTORE_PASSWORD
    // (la compilation la décode dans app/release.keystore, jamais envoyé dans le dépôt).
    // Sans elle, chaque compilation aurait une signature différente et Android refuserait
    // d'installer la mise à jour (« package en conflit »).
    val keystoreFile = file("release.keystore")
    val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        if (keystoreFile.exists() && !keystorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "radioonde"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sans clé (compilation sur un ordinateur), on signe avec la clé de debug.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    }
    lint {
        // Media3 marque une partie de son API comme « instable » : on l'utilise volontairement.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
