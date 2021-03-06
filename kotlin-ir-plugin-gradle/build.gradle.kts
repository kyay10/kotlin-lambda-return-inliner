import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":kotlin-ir-plugin")
  packageName(project.group.toString())
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.extra["kotlin_plugin_id"]}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${project.version}\"")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}
gradlePlugin {
  plugins {
    create("kotlin-lambda-return-inliner") {
      id = "com.github.kyay10.kotlin-lambda-return-inliner"
      displayName = "Kotlin Lambda Return Inliner compiler plugin"
      description = "Kotlin compiler plugin that optimises lambdas returned by inline functions and stored in local variables"
      implementationClass = "com.github.kyay10.LambdaReturnInlinerGradlePlugin"
    }
  }
}
