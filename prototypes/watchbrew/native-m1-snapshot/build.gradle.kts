import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.gradle.api.attributes.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.license.report)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.aarso.watchbrew"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.aarso.watchbrew.demo"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-demo"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Apache-2.0; Wear 1.5 uses Compose 1.8, matching the existing core BOM.
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    testImplementation(libs.junit)
    testImplementation(libs.watch.json.test) // Public domain; persistence codec on JVM.
}

// Scan the watch runtime separately from phone/native inference dependencies.
configurations.create("watchLicenseScan") {
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(configurations.getByName("implementation"))
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attribute(Attribute.of("artifactType", String::class.java), "jar")
    }
}
licenseReport {
    configurations = arrayOf("watchLicenseScan")
    filters = arrayOf(LicenseBundleNormalizer())
    allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
}
