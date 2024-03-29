import java.util.*

plugins {
  `maven-publish`
  signing
}

// Stub secrets to let the project sync and build without the publication values set up
ext["signing.keyId"] = null
ext["signing.password"] = null
ext["signing.secretKeyRingFile"] = null
ext["ossrhUsername"] = null
ext["ossrhPassword"] = null

// Grabbing secrets from local.properties file or from environment variables, which could be used on CI
val secretPropsFile = project.rootProject.file("local.properties")
if (secretPropsFile.exists()) {
  secretPropsFile.reader().use {
    Properties().apply {
      load(it)
    }
  }.onEach { (name, value) ->
    ext[name.toString()] = value
  }
} else {
  ext["signing.keyId"] = System.getenv("SIGNING_KEY_ID")
  ext["signing.password"] = System.getenv("SIGNING_PASSWORD")
  ext["signing.secretKeyRingFile"] = System.getenv("SIGNING_SECRET_KEY_RING_FILE")
  ext["ossrhUsername"] = System.getenv("OSSRH_USERNAME")
  ext["ossrhPassword"] = System.getenv("OSSRH_PASSWORD")
}


fun getExtraString(name: String) = ext[name]?.toString()

publishing {
  // Configure maven central repository
  repositories {
    maven {
      name = "sonatype"
      setUrl("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
      credentials {
        username = getExtraString("ossrhUsername")
        password = getExtraString("ossrhPassword")
      }
    }
  }

  // Configure all publications
  publications.withType<MavenPublication> {


    // Provide artifacts information requited by Maven Central
    pom {
      name.set("Kotlin Lambda Return Inliner")
      description.set("Kotlin compiler plugin that optimises lambdas returned by inline functions and stored in local variables")
      url.set("https://github.com/kyay10/kotlin-lambda-return-inliner")

      licenses {
        license {
          name.set("Apache-2.0")
          url.set("http://www.apache.org/licenses/LICENSE-2.0")
        }
      }
      developers {
        developer {
          id.set("kyay10")
          name.set("Youssef Shoaib")
          email.set("canonballt@gmail.com")
        }
      }
      scm {
        url.set("https://github.com/kyay10/kotlin-lambda-return-inliner.git")
      }

    }
  }
}

// Signing artifacts. Signing.* extra properties values will be used

signing {
  sign(publishing.publications)
}
