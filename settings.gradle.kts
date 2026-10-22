// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// El resolver de foojay se quito junto con gradle/gradle-daemon-jvm.properties:
// fijaba el daemon en JetBrains JDK 25, que en CI habia que descargar de
// foojay.io en cada run (el HEAD devolvia 400 Bad Request de forma
// intermitente y el build moria antes de compilar). El proyecto compila con
// Java 17 y el CI ya instala el JDK con setup-java, asi que no hace falta
// descargar toolchains.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AAR local con el backend Vulkan de llama.cpp compilado dentro
        // (el oficial de Maven Central solo trae CPU).
        maven { url = uri("$rootDir/local-maven") }
    }
}
rootProject.name = "LiteRT-Chat"
include(":app")
