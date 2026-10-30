plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// On Windows/JDK 17, Gradle test-worker argument files may fail with non-ASCII paths.
// Allow an ASCII build-output directory without relocating the user's source checkout.
providers.environmentVariable("FOCUS_BUILD_ROOT").orNull?.let { outputRoot ->
    allprojects { layout.buildDirectory.set(file("$outputRoot/$name")) }
}
