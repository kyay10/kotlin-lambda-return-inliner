@file:Suppress("OVERRIDE_BY_INLINE", "NOTHING_TO_INLINE")

typealias ListInTheProcessOfBuilding<T> = (list: MutableList<T>?, call: Int) -> Any?

inline val <T> ListInTheProcessOfBuilding<T>.size: Int get() = this(null, -1) as Int
inline val <T> ListInTheProcessOfBuilding<T>.materialize: List<T> get() = this(null, -2) as List<T>
inline fun <T> ListInTheProcessOfBuilding<T>.addTo(list: MutableList<T>) {
  this(list, -3)
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <T> ListInTheProcessOfBuilding(
  size: Int,
  noinline add: (MutableList<T>) -> Unit
): ListInTheProcessOfBuilding<T> {
  // variables captured in a closure are basically the same as a var in a class
  var materializedValue: List<T>? = null
  return { list, call ->
    when (call) {
      -3 -> add(list!!)
      -1 -> size
      -2 -> materializedValue
        ?: buildList(size) { add(this) }.also { materializedValue = it }
      else -> TODO()
    }
  }
}


inline operator fun <T> ListInTheProcessOfBuilding<T>.plus(noinline other: ListInTheProcessOfBuilding<T>): ListInTheProcessOfBuilding<T> =
  ListInTheProcessOfBuilding(size + other.size) {
    addTo(it)
    other.addTo(it)
  }

inline operator fun <T> ListInTheProcessOfBuilding<T>.plus(other: List<T>): ListInTheProcessOfBuilding<T> =
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
  val firstLetters =
    listA.map { it.first() } + listB.map { it.first() } + listC.map { it.first() } + listD.map { it.first() } + listE
  println(firstLetters.materialize)
}

/* noinline is needed just to get the code compiling, but hopefully with the compiler plugin that modifier won't be needed. I haven't figured out how to suppress that error though yet lol */
inline fun <T, R> List<T>.map(noinline transform: (T) -> R): ListInTheProcessOfBuilding<R> =
  ListInTheProcessOfBuilding(size) { mapTo(it, transform) }

