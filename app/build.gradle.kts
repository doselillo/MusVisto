plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

detekt {
    // Construye sobre las reglas por defecto + nuestros overrides mínimos.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    // El baseline silencia la deuda EXISTENTE (no bloquea el refactor en
    // curso) pero detekt falla ante deuda NUEVA introducida después.
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

android {
    namespace = "com.doselfurioso.musvisto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.doselfurioso.musvisto"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0-beta9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Solo para poder instalar release en dispositivos de desarrollo.
            // Sustituir por la clave real cuando se publique.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // Pasa al test cualquier -Dmusvisto.* (p. ej. -Dmusvisto.sim,
                // -Dmusvisto.games) y muestra el stdout del simulador.
                it.systemProperties(
                    System.getProperties()
                        .filterKeys { k -> (k as String).startsWith("musvisto.") }
                        .mapKeys { (k, _) -> k as String }
                )
                it.testLogging {
                    events("passed", "failed", "skipped", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

}
