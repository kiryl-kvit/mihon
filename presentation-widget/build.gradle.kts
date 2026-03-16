import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("mihon.library")
    id("mihon.library.compose")
}

extensions.configure<LibraryExtension> {
    namespace = "tachiyomi.presentation.widget"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    api(projects.i18n)

    implementation(compose.glance)
    implementation(libs.material)

    implementation(kotlinx.immutables)

    implementation(platform(libs.coil.bom))
    implementation(libs.coil.core)

    api(libs.injekt)
}
