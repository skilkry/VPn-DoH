pluginManagement {
    repositories {
        google()                // Repositorio de Google
        mavenCentral()          // Repositorio principal de Maven
        gradlePluginPortal()    // Repositorio de plugins de Gradle
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.android.*")  // Grupos relacionados con Android
                includeGroupByRegex("com\\.google.*")   // Grupos relacionados con Google
                includeGroupByRegex("androidx.*")       // Grupos relacionados con AndroidX
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)  // Evita repos locales en los módulos
    repositories {
        google()                            // Repositorio de Google
        mavenCentral()                      // Repositorio principal de Maven
        maven("https://jitpack.io")         // JitPack para dependencias adicionales
    }
}

rootProject.name = "DnsVpn"  // Nombre del proyecto raíz
include(":app")              // Incluye el módulo de la aplicación
