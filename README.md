# kotlin-lambda-return-inliner

[![](https://jitpack.io/v/kyay10/kotlin-lambda-return-inliner.svg)](https://jitpack.io/#kyay10/kotlin-lambda-return-inliner)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/com/github/kyay10/kotlin-lambda-return-inliner/com.github.kyay10.kotlinlambdareturninliner/maven-metadata.xml.svg?colorB=007ec6&label=gradlePluginPortal)](https://plugins.gradle.org/plugin/com.github.kyay10.kotlin-lambda-return-inliner) (
Currently pending approval)

A Kotlin compiler plugin that optimises lambdas returned by inlined functions and stored in local variables. This
optimisation can be used to emulate structs (and more) in Kotlin code at no cost whatsoever. This repo is currently in a
very, very experimental state, and so care should be taken with the usage of this plugin. I'm planning on eventually
stablising this plugin and adding more featues, but more on that to come later on. For now, enjoy!

Note: This plugin was created
using [Brian Norman's Kotlin IR Plugin Template](https://github.com/bnorm/kotlin-ir-plugin-template) and from guidance
from his wonderful artricle
series [Writing Your Second Kotlin Compiler Plugin](https://blog.bnorm.dev/writing-your-second-compiler-plugin-part-1) (
seriously like the articles were immensely helpful when I just knew absolutely nothing about IR)
