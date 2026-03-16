import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "mihon.core.archive"
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.libarchive)
    implementation(libs.unifile)
}
