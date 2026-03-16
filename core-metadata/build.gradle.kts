import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "tachiyomi.core.metadata"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.sourceApi)

    implementation(kotlinx.bundles.serialization)
}
