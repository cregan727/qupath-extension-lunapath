plugins {
    groovy
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-lunapath"
    version = "0.1.0"
    group = "io.github.lunapath"
    description = "LunaPath: cell detection, class sync, and phenotype classification"
    automaticModule = "io.github.lunapath.qupath.extension"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.bundles.groovy)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}