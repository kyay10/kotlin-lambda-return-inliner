# kotlin-lambda-return-inliner

A Kotlin compiler plugin that optimises lambdas returned by inlined functions and stored in local variables. This optimisation can be used to emulate structs (and more) in Kotlin code at no cost whatsoever. This repo is currently in a very, very experimental state, and so care should be taken with the usage of this plugin. I'm planning on eventually stablising this plugin and adding more featues, but more on that to come later on. For now, enjoy!

Note: This plugin was created using [Brian Norman's Kotlin IR Plugin Template](https://github.com/bnorm/kotlin-ir-plugin-template) and from guidance from his wonderful artricle series [Writing Your Second Kotlin Compiler Plugin](https://blog.bnorm.dev/writing-your-second-compiler-plugin-part-1) (seriously like the articles were immensely helpful when I just knew absolutely nothing about IR)
