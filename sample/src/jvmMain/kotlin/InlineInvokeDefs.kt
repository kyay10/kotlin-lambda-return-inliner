import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

//TODO: fix this hack by making the compiler plugin substitute any .invoke call with an inlineInvoke function created by the plugin
@OptIn(ExperimentalContracts::class)
inline fun <A, B, R> inlineInvoke(block: (A, B) -> R, a: A, b: B): R {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  return block(a, b)
}

//TODO: fix this hack by making the compiler plugin substitute any .invoke call with an inlineInvoke function created by the plugin
@OptIn(ExperimentalContracts::class)
inline fun <R> inlineInvoke(block: () -> R): R {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  return block()
}
