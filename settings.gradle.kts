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
        // RootEncoder (RTMP streaming) is published on JitPack
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.pedroSG94.*") }
        }
    }
}

rootProject.name = "SOSlive"
include(":app")
