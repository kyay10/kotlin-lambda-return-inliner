package com.github.kyay10.kotlinlambdareturninliner

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun <T> List<T>.indexOfOrNull(element: T): Int? {
  return indexOf(element).takeIf { it >= 0 }
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> Iterable<IndexedValue<T?>>.filterNotNull(): List<IndexedValue<T>> {
  return filter { it.value != null } as List<IndexedValue<T>>
}

/**
 * Returns a list containing the original element and then the given [collection].
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
operator fun <T> @kotlin.internal.Exact T.plus(collection: Collection<T>): List<T> {
  val result = ArrayList<T>(collection.size + 1)
  result.add(this)
  result.addAll(collection)
  return result
}

@OptIn(ExperimentalContracts::class)
inline fun <reified T : Any> Any?.safeAs(): T? {
  // I took this from org.jetbrains.kotlin.utils.addToStdlib and added the contract to allow for safe casting when a null
  // check is done on the returned value of this function
  contract {
    returnsNotNull() implies (this@safeAs is T)
  }
  return this as? T
}

infix fun <A, B> A?.toNotNull(that: B?): Pair<A, B>? = this?.let { a -> that?.let { b -> Pair(a, b) } }

fun <T> Array<out T>?.toListOrEmpty(): List<T> = this?.toList() ?: emptyList()
