plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radarguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radarguard"
        // 26 = Android 8.0. Permite icone adaptativo em XML e dispensa PNG no repo.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug: o CI gera um APK que instala direto no
            // celular sem precisar de segredo nenhum no repositorio. Serve para uso
            // pessoal; publicar na Play Store exigiria uma chave de upload propria.
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

    androidResources {
        // radares.bin ja e gzip; recomprimir so gastaria tempo de build.
        noCompress += "bin"
    }
}

// Sem dependencias de propósito: nem AndroidX, nem Play Services, nem Compose.
// O APK fica na casa de centenas de KB e nao ha nada pra quebrar em atualizacao.
dependencies {
}
