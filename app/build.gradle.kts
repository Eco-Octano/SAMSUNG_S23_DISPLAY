plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ecooctano.telemetry"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ecooctano.telemetry"
        // Galaxy S23 sai de fábrica com Android 13 (API 33). minSdk 29 permite usar
        // MediaStore (Documents/) sem permissão de armazenamento.
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
    }

    // Duas versões do mesmo código:
    //  - display:  painel do carro (Galaxy S23). Mantém o applicationId original, então
    //              atualiza o app já instalado sem perder as corridas salvas.
    //  - analysis: análise de dados (Galaxy Tab S9 ou qualquer Android). Não usa GPS nem
    //              sensores; importa os pacotes .ecooctano.zip gerados pelo display.
    flavorDimensions += "mode"
    productFlavors {
        create("display") {
            dimension = "mode"
            buildConfigField("String", "APP_MODE", "\"display\"")
            resValue("string", "app_name", "Eco Octano Display")
            manifestPlaceholders["screenOrientation"] = "landscape"
        }
        create("analysis") {
            dimension = "mode"
            applicationIdSuffix = ".analysis"
            versionNameSuffix = "-analysis"
            buildConfigField("String", "APP_MODE", "\"analysis\"")
            resValue("string", "app_name", "Eco Octano Análise")
            manifestPlaceholders["screenOrientation"] = "unspecified"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Jetpack Compose (Material 3)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Localização (FusedLocationProviderClient)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Mapa OpenStreetMap (sem API key)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
