@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
  id("com.github.gmazzo.buildconfig")
  id("convention.publication")
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("maven-plugin"))
  api(project(":kotlin-plugin-native"))
}

// A bit of a hack to copy over the META-INF services information so that Maven knows about the LambdaReturnInlinerComponentRegistrar
val servicesDirectory = "META-INF/services"
val copyServices = tasks.register<Copy>("copyServices") {
  val nativePlugin = project(":kotlin-plugin-native")
  from(nativePlugin.kaptGeneratedServicesDir)
  into(kaptGeneratedServicesDir)
}

buildConfig {
  packageName(group.toString().replace("-", ""))
  buildConfigField(
    "String",
    "KOTLIN_PLUGIN_ID",
    "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\""
  )
}

tasks.withType<KotlinCompile> {
  dependsOn(copyServices)
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
}

java {
  withSourcesJar()
  withJavadocJar()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
    }
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
  freeCompilerArgs = listOf("-Xinline-classes")
}

val Project.kaptGeneratedServicesDir: File
  get() =
    Kapt3GradleSubplugin.getKaptGeneratedClassesDir(this, sourceSets.main.get().name).resolve(
      servicesDirectory
    )
