@file:Suppress("UnstableApiUsage")

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
  implementation("org.ow2.asm:asm:9.2")
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable")
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  kapt("com.google.auto.service:auto-service:1.0")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0")

  // Needed for running tests since the tests inherit out classpath
  implementation(project(":prelude"))

  testImplementation(kotlin("test-junit5"))
  testImplementation(platform("org.junit:junit-bom:5.7.1"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.7.2")
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
  testImplementation("com.github.tschuchortdev:kotlin-compile-testing:1.4.3")
}

buildConfig {
  packageName(group.toString().replace("-", ""))
  buildConfigField(
    "String",
    "INLINE_INVOKE_FQNAME",
    "\"${group.toString().replace("-", "")}.inlineInvoke\""
  )
  buildConfigField(
    "String",
    "CONTAINS_COPIED_DECLARATIONS_ANNOTATION_FQNAME",
    "\"${group.toString().replace("-", "")}.ContainsCopiedDeclarations\""
  )
  buildConfigField(
    "String",
    "KOTLIN_PLUGIN_ID",
    "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\""
  )
  buildConfigField(
    "String",
    "SAMPLE_JVM_MAIN_PATH",
    "\"${rootProject.projectDir.absolutePath}/sample/src/jvmMain/kotlin/\""
  )
  buildConfigField(
    "String",
    "SAMPLE_GENERATED_SOURCES_DIR",
    "\"${rootProject.projectDir.absolutePath}/sample/build/generated/source/kotlinLambdaReturnInliner/jvmMain\""
  )
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
  kotlinOptions.freeCompilerArgs += "-Xinline-classes"
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
