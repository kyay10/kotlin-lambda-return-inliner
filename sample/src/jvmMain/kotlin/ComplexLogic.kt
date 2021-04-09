val shouldPerform = true
val shouldRunSpecial = true

inline fun <T> specialLogic(noinline block: () -> T): () -> T? = if (shouldRunSpecial) block else ({ null })
inline fun <T> performOrThrow(block: () -> T): T = if (shouldPerform) block() else TODO()
fun main() {
  println(performOrThrow(specialLogic { "hello" }))
}
