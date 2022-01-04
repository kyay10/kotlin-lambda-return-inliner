# kotlin-lambda-return-inliner

![Maven Central](https://img.shields.io/maven-central/v/io.github.kyay10.kotlin-null-defaults/kotlin-plugin?color=gree) (Compiler plugin)
![Maven Central](https://img.shields.io/maven-central/v/io.github.kyay10.kotlin-null-defaults/gradle-plugin?color=gree) (Gradle Plugin)

[![](https://jitpack.io/v/kyay10/kotlin-lambda-return-inliner.svg)](https://jitpack.io/#kyay10/kotlin-lambda-return-inliner)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v?color=gree&label=gradlePluginPortal&metadataUrl=https%3A%2F%2Fplugins.gradle.org%2Fm2%2Fio%2Fgithub%2Fkyay10%2Fkotlin-lambda-return-inliner%2Fio.github.kyay10.kotlin-lambda-return-inliner.gradle.plugin%2Fmaven-metadata.xml)](https://plugins.gradle.org/plugin/io.github.kyay10.kotlin-lambda-return-inliner)

A Kotlin compiler plugin that optimises lambdas returned by inlined functions and stored in local variables. This
optimisation can be used to emulate structs (and more) in Kotlin code at no cost whatsoever. This repo is currently in a
very, very experimental state, and so care should be taken with the usage of this plugin. I'm planning on eventually
stabilising this plugin and adding more features, but more on that to come later on. For now, enjoy!

Note: This plugin was created
using [Brian Norman's Kotlin IR Plugin Template](https://github.com/bnorm/kotlin-ir-plugin-template) and from guidance
from his wonderful article
series [Writing Your Second Kotlin Compiler Plugin](https://blog.bnorm.dev/writing-your-second-compiler-plugin-part-1) (
seriously like the articles were immensely helpful when I just knew absolutely nothing about IR)
