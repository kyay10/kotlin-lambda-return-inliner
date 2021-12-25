@file:Suppress("UnstableApiUsage")

import com.gradle.publish.MavenCoordinates
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.utils.addToStdlib.cast

plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
  id("com.gradle.plugin-publish")
  id("convention.publication")
}



dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":kotlin-plugin")
  packageName(project.group.toString().replace("-", ""))
  buildConfigField(
    "String",
    "KOTLIN_PLUGIN_ID",
    "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\""
  )
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${project.version}\"")
  val preludeProject = project(":prelude")
  buildConfigField("String", "PRELUDE_LIBRARY_GROUP", "\"${preludeProject.group}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_NAME", "\"${preludeProject.name}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_VERSION", "\"${preludeProject.version}\"")

}

java {
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs += "-Xinline-classes"
}
val pluginDescription =
  "Kotlin compiler plugin that optimises lambdas returned by inline functions and stored in local variables"
val pluginName = "kotlin-lambda-return-inliner"
val pluginDisplayName = "Kotlin Lambda Return Inliner compiler plugin"

gradlePlugin {
  plugins {
    create(pluginName) {
      id = "io.github.kyay10.kotlin-lambda-return-inliner"
      displayName = pluginDisplayName
      description = pluginDescription
      implementationClass = "io.github.kyay10.kotlinlambdareturninliner.LambdaReturnInlinerGradlePlugin"
    }
  }
}
pluginBundle {
  website = "https://github.com/kyay10/kotlin-lambda-return-inliner"
  vcsUrl = website
  description = pluginDescription

  version = rootProject.version
  (plugins) {
    pluginName {
      displayName = pluginDisplayName
      // SEO go brrrrrrr...
      tags = listOf(
        "kotlin",
        "lambda",
        "inline",
        "optimization",
        "struct",
        "performance",
        "functional",
        "higher order function",
        "fp",
        "functional programming",
        "graphics"
      )
      version = rootProject.version.cast()
    }
  }
  val mavenCoordinatesConfiguration = { coords: MavenCoordinates ->
    coords.groupId = group.cast()
    coords.artifactId = name
    coords.version = version.cast()
  }

  mavenCoordinates(mavenCoordinatesConfiguration)
}
