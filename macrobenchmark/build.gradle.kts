import com.android.build.api.dsl.TestExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("mihon.benchmark")
}

extensions.configure<TestExtension> {
    namespace = "tachiyomi.macrobenchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "BaselineProfile"
    }

    buildTypes {
        // This benchmark buildType is used for benchmarking, and should function like your
        // release build (for example, with minification on). It's signed with a debug key
        // for easy local/CI testing.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks.add("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(androidx.test.ext)
    implementation(androidx.test.espresso.core)
    implementation(androidx.test.uiautomator)
    implementation(androidx.benchmark.macro)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
