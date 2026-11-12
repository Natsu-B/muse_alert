plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.musealert.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.musealert.app"
        minSdk = 29
        targetSdk = 36
        // CI ではタグと実行番号から決める。更新インストールには versionCode が増えている必要がある。
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // リリース用の署名鍵は環境変数で渡す (GitHub Actions では Secrets から)。
    // 毎回同じ鍵で署名しないと、上書きインストールできない。
    val keystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // javax.mail はリフレクションを使うので、難読化・縮小はしない。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/mimetypes.default", "META-INF/mailcap.default")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
