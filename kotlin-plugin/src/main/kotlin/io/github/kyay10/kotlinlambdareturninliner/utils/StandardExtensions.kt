package io.github.kyay10.kotlinlambdareturninliner.utils

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.experimental.ExperimentalTypeInference

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

inline fun <T> Boolean.ifTrue(block: () -> T): T? {
  return if(this) block() else null
}

operator fun String.times(amount: Int): String = repeat(amount)

/**
 * Appends all elements yielded from results of [transform] function being invoked on each element of original collection, to the given [destination].
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun <T, R, C : MutableCollection<in R>> List<T>.flatMapTo(destination: C, transform: (T) -> Iterable<R>): C {
  for (element in this) {
    val list = transform(element)
    destination.addAll(list)
  }
  return destination
}

/**
 * Returns a single list of all elements yielded from results of [transform] function being invoked on each element of original collection.
 *
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun <T, R> List<T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
  return flatMapTo(ArrayList(), transform)
}
/**
 * Appends all elements yielded from results of [transform] function being invoked on each element of original collection, to the given [destination].
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("flatMapArrayTo")
inline fun <T, R, C : MutableCollection<in R>> List<T>.flatMapTo(destination: C, transform: (T) -> Array<R>): C {
  for (element in this) {
    val list = transform(element)
    for (item in list) {
      destination.add(item)
    }
  }
  return destination
}

/**
 * Returns a single list of all elements yielded from results of [transform] function being invoked on each element of original collection.
 *
 */
@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("flatMapArrayTo")
inline fun <T, R> List<T>.flatMap(transform: (T) -> Array<R>): List<R> {
  return flatMapTo(ArrayList(), transform)
}

inline fun <T> id(t: T): T = t
