pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // NewPipeExtractor (on-device YouTube lookups) is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe"); includeGroup("com.github.TeamNewPipe.NewPipeExtractor") } }
    }
}
rootProject.name = "LocalTube"
include(":app")
