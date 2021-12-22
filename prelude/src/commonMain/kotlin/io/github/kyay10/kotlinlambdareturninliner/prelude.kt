@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner

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

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.TYPE_PARAMETER,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.FIELD,
  AnnotationTarget.LOCAL_VARIABLE,
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
  AnnotationTarget.TYPE,
  AnnotationTarget.EXPRESSION,
  AnnotationTarget.FILE,
  AnnotationTarget.TYPEALIAS
)
@Retention(AnnotationRetention.SOURCE)
@Deprecated(
  "This is only used by the compiler plugin to mark certain files and shouldn't be used outside of it",
  ReplaceWith(""),
  DeprecationLevel.WARNING
)
internal annotation class ContainsCopiedDeclarations
