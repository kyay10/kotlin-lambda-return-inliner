import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
  id("com.gradle.plugin-publish")
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":kotlin-plugin")
  packageName(project.group.toString().replace("-", ""))
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.extra["kotlin_plugin_id"]}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${project.version}\"")
  val preludeProject = project(":prelude")
  buildConfigField("String", "PRELUDE_LIBRARY_GROUP", "\"${preludeProject.group}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_NAME", "\"${preludeProject.name}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_VERSION", "\"${preludeProject.version}\"")

}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}
val pluginDescription =
  "Kotlin compiler plugin that optimises lambdas returned by inline functions and stored in local variables"
val pluginName = "kotlin-lambda-return-inliner"
val pluginDisplayName = "Kotlin Lambda Return Inliner compiler plugin"
gradlePlugin {
  plugins {
    create(pluginName) {
      id = "com.github.kyay10.kotlin-lambda-return-inliner"
      displayName = pluginDisplayName
      description = pluginDescription
      implementationClass = "com.github.kyay10.kotlinlambdareturninliner.LambdaReturnInlinerGradlePlugin"
    }
  }
}
pluginBundle {
  website = "https://github.com/kyay10/kotlin-lambda-return-inliner"
  vcsUrl = website
  description = pluginDescription

  version = "0.1.0-SNAPSHOT"
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
      version = "0.1.0-SNAPSHOT"
    }
  }

}
