import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
  id("com.github.gmazzo.buildconfig")
}

repositories {
  mavenCentral()
}
dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.2.0")
  implementation("org.ow2.asm:asm:9.1")
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable")

  kapt("com.google.auto.service:auto-service:1.0-rc7")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0-rc7")

  // Needed for running tests since the tests inherit out classpath
  api(project(":prelude"))

  testImplementation(kotlin("test-junit"))
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
  testImplementation("com.github.tschuchortdev:kotlin-compile-testing:1.3.4")
}

buildConfig {
  packageName(group.toString().replace("-", ""))
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\"")
  buildConfigField("String", "SAMPLE_JVM_MAIN_PATH", "\"${rootProject.projectDir.absolutePath}/sample/src/jvmMain/kotlin/\"")
  buildConfigField("String", "SAMPLE_GENERATED_SOURCES_DIR", "\"${rootProject.projectDir.absolutePath}/sample/build/generated/source/kotlinLambdaReturnInliner/jvmMain\"")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
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
