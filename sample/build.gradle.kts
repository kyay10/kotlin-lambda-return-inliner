plugins {
  kotlin("multiplatform") version Dependencies.kotlin
  id("com.github.kyay10.kotlin-lambda-return-inliner") version "0.1.1-SNAPSHOT"
}

group = "com.github.kyay10.kotlin_lambda_return_inliner"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven(url = "https://jitpack.io")
}

kotlin {
  jvm {
    compilations.all {
      kotlinOptions.jvmTarget = "1.8"
      kotlinOptions.useIR = true
      kotlinOptions.freeCompilerArgs += "-Xallow-kotlin-package"
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
  sourceSets {
    val commonMain by getting
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }
    val jvmMain by getting {

    }
    val jvmTest by getting {
      dependencies {
        implementation(kotlin("test-junit"))
      }
    }
    val nativeMain by getting
    val nativeTest by getting
    val jsMain by getting
    val jsTest by getting {
      dependencies {
        implementation(kotlin("test-js"))
      }
    }
  }
}
