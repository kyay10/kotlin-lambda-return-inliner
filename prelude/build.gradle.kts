plugins {
  kotlin("multiplatform")
  id("org.jetbrains.dokka")
  id("convention.publication")
}

val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
  dependsOn(dokkaHtml)
  archiveClassifier.set("javadoc")
  from(dokkaHtml.outputDirectory)
}
kotlin {
  targets.all {
    compilations.all {
      kotlinOptions.freeCompilerArgs += "-Xallow-kotlin-package"
    }
  }
  jvm {
    withJava()
    compilations.all {
      kotlinOptions.jvmTarget = "1.8"
    }
    testRuns["test"].executionTask.configure {
      useJUnit()
    }
  }
  val hostOs = System.getProperty("os.name")
  val isMingwX64 = hostOs.startsWith("Windows")
  val nativeTarget = when {
    hostOs == "Mac OS X" -> macosX64("native")
    hostOs == "Linux" -> linuxX64("native")
    isMingwX64 -> mingwX64("native")
    else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
  }


  js(IR) {
    /*browser {
      binaries.executable()
      webpackTask {
        cssSupport.enabled = true
      }
      runTask {
        cssSupport.enabled = true
      }
      testTask {
        useKarma {
          useChromeHeadless()
          webpackConfig.cssSupport.enabled = true
        }
      }
    }*/
  }
  /* Targets configuration omitted.
  *  To find out how to configure the targets, please follow the link:
  *  https://kotlinlang.org/docs/reference/building-mpp-with-gradle.html#setting-up-targets */

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(kotlin("stdlib-common"))
        implementation("com.google.code.findbugs:jsr305:3.0.2")
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }
  }
}

publishing {
  publications {
    publications.withType<MavenPublication> {
      artifact(javadocJar)
    }
  }
}
