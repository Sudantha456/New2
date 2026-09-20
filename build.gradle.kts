/*
 * You-Tube — root build script.
 *
 * Plugin versions are declared once here and applied in :app.
 *
 * Verified-compatible matrix (mirrors Google's own `android/socialite` sample):
 *   Gradle 8.11.1 · AGP 8.9.3 · Kotlin 2.1.10 · Compose BOM 2025.05.01
 */

plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
}

// Keep the incremental/parallel behaviour predictable across all modules.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
