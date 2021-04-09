@file:Suppress("OVERRIDE_BY_INLINE", "NOTHING_TO_INLINE")

import java.util.*
import java.util.function.Consumer
import java.util.function.IntFunction
import java.util.stream.Stream

// Could be named much much better, but I'm trying to make this type explicitly visible here
enum class ListInTheProcessOfBuildingCall {
  AddTo, Size, Materialize
}

inline class ListInTheProcessOfBuilding<T> @PublishedApi internal constructor(@PublishedApi internal val lambda: (list: MutableList<T>?, call: ListInTheProcessOfBuildingCall) -> Any?) :
  List<T> {
  override inline fun contains(element: T): Boolean = materialize.contains(element)

  override inline fun containsAll(elements: Collection<T>): Boolean = materialize.containsAll(elements)

  override inline fun get(index: Int): T = materialize.get(index)

  override inline fun indexOf(element: T): Int = materialize.indexOf(element)

  override inline fun isEmpty(): Boolean = materialize.isEmpty()
  override inline fun iterator(): Iterator<T> = materialize.iterator()

  override inline fun lastIndexOf(element: T): Int = materialize.lastIndexOf(element)

  override inline fun listIterator(): ListIterator<T> = materialize.listIterator()

  override inline fun listIterator(index: Int): ListIterator<T> = materialize.listIterator(index)

  override inline fun spliterator(): Spliterator<T> = materialize.spliterator()
  override inline fun subList(fromIndex: Int, toIndex: Int): List<T> = materialize.subList(fromIndex, toIndex)

  override inline fun forEach(action: Consumer<in T>?) = materialize.forEach(action)

  override inline fun parallelStream(): Stream<T> = materialize.parallelStream()

  override inline fun stream(): Stream<T> = materialize.stream()

  override inline fun <T : Any?> toArray(generator: IntFunction<Array<T>>?): Array<T> = materialize.toArray(generator)

  override inline fun toString(): String = "ListInTheProcessOfBuilding(materialize=$materialize)"

  override inline val size: Int get() = lambda(null, ListInTheProcessOfBuildingCall.Size) as Int
  inline val materialize: List<T> get() = lambda(null, ListInTheProcessOfBuildingCall.Materialize) as List<T>
  inline fun addTo(list: MutableList<T>) {
    lambda(list, ListInTheProcessOfBuildingCall.AddTo)
  }
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <T> ListInTheProcessOfBuilding(
  size: Int,
  noinline add: (MutableList<T>) -> Unit
): ListInTheProcessOfBuilding<T> {
  // variables captured in a closure are basically the same as a var in a class
  var materializedValue: List<T>? = null
  return ListInTheProcessOfBuilding { list, call ->
    when (call) {
      ListInTheProcessOfBuildingCall.AddTo -> add(list!!)
      ListInTheProcessOfBuildingCall.Size -> size
      ListInTheProcessOfBuildingCall.Materialize -> materializedValue
        ?: buildList { add(this) }.also { materializedValue = it }
    }
  }
}


operator fun <T> ListInTheProcessOfBuilding<T>.plus(other: ListInTheProcessOfBuilding<T>): ListInTheProcessOfBuilding<T> =
  ListInTheProcessOfBuilding(size + other.size) {
    addTo(it)
    other.addTo(it)
  }

operator fun <T> ListInTheProcessOfBuilding<T>.plus(other: List<T>): ListInTheProcessOfBuilding<T> =
  ListInTheProcessOfBuilding(size + other.size) {
    addTo(it)
    it.addAll(other)
  }


fun main() {
  val listA = listOf("foo", "bar")
  val listB = listOf("alpha", "beta", "gamma")
  val listC = listOf("function", "property", "object", "class")
  val listD = listOf("test", "best", "nest")
  val listE = listOf('x', 'y')
  // Note that you lose non-local returns here because of noinline.
  val firstLetters: List<Char> =
    listA.map { it.first() } + listB.map { it.first() } + listC.map { it.first() } + listD.map { it.first() } + listE
  println(firstLetters)
}

/* noinline is needed just to get the code compiling, but hopefully with the compiler plugin that modifier won't be needed. I haven't figured out how to suppress that error though yet lol */
inline fun <T, R> List<T>.map(noinline transform: (T) -> R): ListInTheProcessOfBuilding<R> =
  ListInTheProcessOfBuilding(size) { mapTo(it, transform) }

