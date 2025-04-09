buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    dependencies {
        // El classpath para plugins ya no es necesario con el nuevo sistema de plugins (plugins block)
        // Puedes eliminar estas líneas si estás usando solo el `plugins {}` moderno
    }
}

plugins {
    // Aplicación y librerías de Android con versión unificada
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false

    // Kotlin Android Plugin
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false

    // Hilt Plugin (ya no necesitas agregarlo en buildscript)
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
