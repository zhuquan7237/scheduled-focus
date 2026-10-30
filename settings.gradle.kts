pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ScheduledFocus"
include(":app")
// Test-only package fixture: opt in explicitly; never part of normal release builds.
if (providers.gradleProperty("wechatFixture").orNull == "true") include(":wechat-fixture")
