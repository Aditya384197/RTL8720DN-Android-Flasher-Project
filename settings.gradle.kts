pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for com.github.mik3y:usb-serial-for-android
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "RTL8720DN-Flasher"
include(":app")
