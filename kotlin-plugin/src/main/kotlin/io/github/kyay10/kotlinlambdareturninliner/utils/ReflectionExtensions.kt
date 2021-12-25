@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner.utils

import java.lang.reflect.Field

inline val Class<*>.allDeclaredFields: Sequence<Field>
  get() =
    generateSequence(this) { it.superclass }
      .map { it.declaredFields }
      .flatMap { it.toList() }
