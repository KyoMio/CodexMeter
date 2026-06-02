pluginManagement {
    repositories {
        maven("https://dl-ssl.google.com/dl/android/maven2/") {
            name = "GoogleDlSsl"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://dl-ssl.google.com/dl/android/maven2/") {
            name = "GoogleDlSsl"
        }
        mavenCentral()
    }
}

rootProject.name = "CodexMeter"
include(":app")
