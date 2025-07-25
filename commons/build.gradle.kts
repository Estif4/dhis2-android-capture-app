import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.compose.compiler)
}

apply(from = "${project.rootDir}/jacoco/jacoco.gradle.kts")

repositories {
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots") }
}

android {
    compileSdk = libs.versions.sdk.get().toInt()
    namespace = "org.dhis2.commons"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testOptions.targetSdk = libs.versions.sdk.get().toInt()
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += listOf("default")

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        dataBinding = true
    }

    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.icu4j)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    api(project(":ui-components"))
    implementation(project(":commonskmm"))

    api(libs.dhis2.android.sdk) {
        exclude("org.hisp.dhis", "core-rules")
        exclude("com.facebook.flipper")
        exclude("com.facebook.soloader")
        this.isChanging = true
    }

    api(libs.dhis2.ruleengine) {
        exclude("junit", "junit")
    }

    api(libs.dhis2.expressionparser)

    api(libs.google.autoValue)
    kapt(libs.google.autoValue)
    api(libs.androidx.coreKtx)
    api(libs.androidx.appcompat)
    api(libs.androidx.fragmentKtx)
    api(libs.androidx.viewModelKtx)
    api(libs.androidx.recyclerView)
    debugApi(libs.androidx.compose.uitooling)
    api(libs.androidx.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.livedata)
    api(libs.androidx.compose.paging)
    api(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.composeVM)
    api(libs.google.material)
    api(libs.androidx.material3)
    api(libs.androidx.material3.window)
    api(libs.androidx.material3.adaptative.android)
    api(libs.google.gson)
    api(libs.dagger)
    kapt(libs.dagger.compiler)
    api(libs.barcodeScanner.zxing)
    api(libs.rx.java)
    api(libs.rx.android)
    api(libs.analytics.timber)
    api(libs.github.glide)
    kapt(libs.github.glide.compiler)
    api(libs.barcodeScanner.scanner) {
        exclude("com.google.zxing", "core")
    }
    api(libs.barcodeScanner.zxing.android) {
        exclude("com.google.zxing", "core")
    }
    api(libs.rx.binding.compat)
    testApi(libs.test.junit)
    androidTestApi(libs.test.mockitoCore)
    androidTestApi(libs.test.mockitoKotlin)
    androidTestApi(libs.test.dexmaker.mockitoInline)
    androidTestApi(libs.test.junit.ext)
    androidTestApi(libs.test.espresso)
    androidTestApi(libs.test.espresso.idlingresource)
    api(libs.test.espresso.idlingresource)
    api(libs.test.espresso.idlingconcurrent)
    api(libs.analytics.sentry)
    api(libs.analytics.sentry.compose)
    implementation(libs.github.treeView)
    api(libs.dhis2.mobile.designsystem) {
        isChanging = true
    }
    coreLibraryDesugaring(libs.desugar)
}
