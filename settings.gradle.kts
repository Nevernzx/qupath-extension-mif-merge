pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

plugins {
    // foojay-resolver-convention auto-downloads JDK 25 for the toolchain;
    // declare it explicitly because the qupath-extension-settings plugin doesn't.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}

qupath {
    // Match the QuPath version this extension is developed against
    version = "0.8.0-SNAPSHOT"
}

rootProject.name = "qupath-extension-mif-merge"
