rootProject.name = "kotlin-lambda-return-inliner"
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
  }
}
include(":gradle-plugin")
include(":kotlin-plugin")
include(":kotlin-plugin-native")
include("prelude")
includeBuild("convention-plugins")
include("maven-plugin")
