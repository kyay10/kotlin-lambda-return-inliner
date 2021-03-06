val shouldPerform = true
val shouldRunSpecial = true

//TODO: make it possible for this function to use a single-check if(sRS) block else ({ null}) instead
inline fun <T> specialLogic(noinline block: () -> T): () -> T? = { if (shouldRunSpecial) inlineInvoke(block) else null }
inline fun <T> performOrThrow(block: () -> T): T = if (shouldPerform) inlineInvoke(block) else TODO()
fun main() {
  println(performOrThrow(specialLogic { "hello" }))
}
