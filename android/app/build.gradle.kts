plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Actions run numbers increase for the single Mobile gate workflow.
// Local Android builds keep code 1; CI builds receive a monotonic code.
val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
val mobileVersionCode = ciRunNumber?.let { run ->
    val parsed = run.toIntOrNull()
    require(parsed != null && parsed in 1..2_100_000_000) {
        "Invalid GitHub Actions run number for Android versionCode"
    }
    parsed
} ?: 1

android {
    namespace = "dev.heisluka.mobakula"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.heisluka.mobakula"
        minSdk = 26
        targetSdk = 36
        versionCode = mobileVersionCode
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
