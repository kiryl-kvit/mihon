import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

extensions.configure<LibraryExtension> {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    api(libs.bundles.sqldelight)
}
