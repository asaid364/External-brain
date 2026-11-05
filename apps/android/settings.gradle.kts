pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "LifeRecord"
include(":app")
include(":core", ":desktop")
project(":core").projectDir = file("../../packages/shared-core")
project(":desktop").projectDir = file("../windows-sync")
