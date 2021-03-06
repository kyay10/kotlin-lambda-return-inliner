buildscript {
  extra["kotlin_plugin_id"] = "com.github.kyay10.kotlin-lambda-return-inliner"
}
plugins {
  kotlin("jvm") version "1.4.31" apply false
  id("org.jetbrains.dokka") version "0.10.1" apply false
  id("com.gradle.plugin-publish") version "0.12.0" apply false
  id("com.github.gmazzo.buildconfig") version "3.0.0" apply false
}

allprojects {
  group = "com.github.kyay10"
  version = "0.1.0-SNAPSHOT"
}

subprojects {
  repositories {
    mavenCentral()
    jcenter()
  }
}
