@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner.utils

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
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
  return if (this) block() else null
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
 * Returns a single list of all elements yielded
 * from results of [transform] function being invoked on each element of original collection.
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

fun <T> id(t: T): T = t

/**
 * Returns `true` if all elements match the given [predicate].
 */
inline fun <T> Iterable<T>.allIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
  if (this is Collection && isEmpty()) return true
  this.forEachIndexed { index, element ->
    if (!predicate(index, element)) return false
  }
  return true
}

/**
 * Applies the given [transform] function to each element of the original collection
 * and appends the results to the given [destination].
 */
inline fun <T, R, C : MutableCollection<in R>> List<T>.mapTo(destination: C, transform: (T) -> R): C {
  for (i in indices)
    destination.add(transform(this[i]))
  return destination
}

/**
 * Returns a list containing the results of applying the given [transform] function
 * to each element in the original collection.
 */
inline fun <T, R> List<T>.map(transform: (T) -> R): List<R> {
  return mapTo(ArrayList(collectionSizeOrDefault(10)), transform)
}

/**
 * Returns a list containing the results of applying the given [transform] function
 * to each element in the original collection, or the original list if it doesn't have items
 */
inline fun <T> List<T>.mapOrOriginal(transform: (T) -> T): List<T> {
  return if (isEmpty()) this else mapTo(ArrayList(collectionSizeOrDefault(10)), transform)
}

/**
 * Returns the size of this iterable if it is known, or the specified [default] value otherwise.
 */
fun <T> Iterable<T>.collectionSizeOrDefault(default: Int): Int = if (this is Collection<*>) this.size else default

/**
 * Performs the given [action] on each element, providing sequential index with the element.
 * @param [action] function that takes the index of an element and the element itself
 * and performs the action on the element.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> List<T>.forEachIndexed(action: (index: Int, T) -> Unit) {
  contract {
    callsInPlace(action, InvocationKind.UNKNOWN)
  }
  for (i in indices) action(i, this[i])
}

/**
 * Performs the given [action] on each element.
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.HidesMembers
inline fun <T> List<T>.forEach(action: (T) -> Unit) {
  for (i in indices) action(this[i])
}

/**
 * Builds a new [MutableList] by populating a [MutableList] using the given [builderAction]
 * and returning it.
 *
 * The list passed as a receiver to the [builderAction] is valid only inside that function.
 * Using it outside of the function produces an unspecified behavior.
 *
 * The returned list is serializable (JVM).
 *
 * [capacity] is used to hint the expected number of elements added in the [builderAction].
 *
 * @throws IllegalArgumentException if the given [capacity] is negative.
 */
@OptIn(ExperimentalTypeInference::class, ExperimentalContracts::class)
@SinceKotlin("1.6")
inline fun <E> buildMutableList(
  capacity: Int = 0,
  @BuilderInference builderAction: MutableList<E>.() -> Unit
): MutableList<E> {
  contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
  return ArrayList<E>(capacity).apply(builderAction)
}
