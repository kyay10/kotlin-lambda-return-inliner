pluginManagement {
  repositories {
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
  }
}

rootProject.name = "sample"

//TODO: add some system-level property here or something to automatically toggle this
includeBuild("..")

