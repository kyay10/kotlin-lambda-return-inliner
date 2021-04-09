package com.github.kyay10.kotlinlambdareturninliner

import kotlin.jvm.JvmSynthetic

@Suppress("unused")
@JvmSynthetic
@Deprecated(
  "This is only used as a template for the compiler plugin and shouldn't be used outside of it",
  ReplaceWith("block()"),
  DeprecationLevel.HIDDEN
)
inline fun <R> inlineInvoke(block: () -> R): R {
  return block()
}

@Target(AnnotationTarget.CLASS)
annotation class LambdaBacked
