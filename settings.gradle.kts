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
        google() // [필수] Health Services는 여기에 살고 있습니다.
        mavenCentral()
        flatDir{
            dirs("app/libs")
        }
    }
}

rootProject.name = "DS Android App"
include(":app")