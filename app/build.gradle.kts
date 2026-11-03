plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("""\d+\.\d""").matches(appVersion)) { "Version must be one decimal place" }
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: appVersion.split(".").let { it[0].toInt() * 10 + it[1].toInt() }
val appVersionSuffix = (findProperty("appVersionSuffix") as String?) ?: ""
android {
    namespace = "dev.n2k02.chargewatt"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.n2k02.chargewatt"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion + appVersionSuffix
    }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
    implementation("dev.n2k02:updater:0.1.19")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.register("verifySharedLibraries") {
    group = "verification"
    doLast {
        val modules = configurations.getByName("implementation").dependencies
            .filter { it.group == "dev.n2k02" }.associate { it.name to it.version }
        check(modules["theme"] == "0.1.4" && modules["updater"] == "0.1.19") {
            "Use pinned theme 0.1.4 and updater 0.1.19"
        }
    }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn("verifySharedLibraries")
}
