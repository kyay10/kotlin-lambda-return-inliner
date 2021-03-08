import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

inline fun <T> identity(noinline block: () -> T): () -> T = block
inline fun <T> perform(block: () -> T): T = block()
fun main() {
  println(perform(identity { "hello" }))
}

