@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

import kotlin.contracts.*

class Marka
// Using a dirty trick to ensure that the type information about F and S gets passed with the lambda.
// Alternatively, one can just use an inline fun interface or an inline class with a lambda val or whatever
// the accepted part of this proposal would be. I cobbled this together using a plain old lambda just to
// demonstrate how crucial the core optimisation itself is and that an inline fun interface or an inline
// class with a lambda would just ensure type safety over this so that users don't misuse it.
typealias ZeroCostPair<F, S> = (PairCall, Input0) -> Output2<F, S>

// Enum to represent, well, which call the pair received. One can instead just use an Int or a Boolean even
// if performance is needed, but regardless of what you use if this proposal is implemented then the lambda
// will be inlined which means that any smart shrinker should optimise away the resulting "First == First"
// and "Second == Second" calls, or the JIT can even optimise them then cuz they're a constant expression.
enum class PairCall {
  First,
  Second
}

inline fun <A, B, R> inlineInvoke(crossinline block: (A, B) -> R, a: A, b: B): R {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  return block(a, b)
}

// Mimicking a constructor for the type.
inline fun <F, S> ZeroCostPair(first: F, second: S): ZeroCostPair<F, S> =
  lambda@{ call, _ -> // Those parameters are solely a trick to carry type information, and so they should be ignored
    return@lambda Output(
      when (call) {
        PairCall.First -> first
        PairCall.Second -> second
      }
    )
  }

// Again, the parameters are useless, so just pass in null for them since during runtime the JVM won't actually know what
// F and S are since they get erased.
// We can safely cast the result of invoking the function as F or S because we know that ZeroCostPairs created using
// the factory function always follow the pattern of returning an F if PairCall.First is passed in and likewise for S.
// However, if stricter type safety is needed (which it is if you're building a library), then inline classes with lambdas should
// be implemented so that one can make the constructor internal and then have a factory function that takes in F and S values
inline val <F, S> ZeroCostPair<F, S>.first get() = inlineInvoke(this, PairCall.First, Input).value as F
inline val <F, S> ZeroCostPair<F, S>.second get() = inlineInvoke(this, PairCall.Second, Input).value as S

fun main() {
  val test2 = Marka()
  val values = computeValues(25 + test2.hashCode(), "Hallo")
  println(values.second)
  println("The answer to life, the universe, and everything in between = ${values.first}")
  val test: Marka
  val castedValues: ZeroCostPair<Any, Any> = values
  computeValues(27, "Hello") { first, second ->
    println(second)
    test = Marka()
    val test5: Marka
    run {
      test5 = Marka()
      println(test5)
    }
    println(ZeroCostPair(5, 3).first)
    println(test)
    println("The answer to life, the universe, and everything = $first")
  }
  println(test)
}


inline fun computeValues(number: Int, text: String): ZeroCostPair<Int, String> {
  return ZeroCostPair(number + 5, "$text World!")
}

inline fun computeValues(number: Int, text: String, block: (Int, String) -> Unit) {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  block(number + 5, "$text World!")
}

sealed class Input22<in A, in B, in C, in D, in E, in F, in G, in H, in I, in J, in K, in L, in M, in N, in O, in P, in Q, in R, in S, in T, in U, in V>

typealias Input21<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U> = Input22<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Any?>
typealias Input20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T> = Input21<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Any?>
typealias Input19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S> = Input20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Any?>
typealias Input18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R> = Input19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Any?>
typealias Input17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q> = Input18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Any?>
typealias Input16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> = Input17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Any?>
typealias Input15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> = Input16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Any?>
typealias Input14<A, B, C, D, E, F, G, H, I, J, K, L, M, N> = Input15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Any?>
typealias Input13<A, B, C, D, E, F, G, H, I, J, K, L, M> = Input14<A, B, C, D, E, F, G, H, I, J, K, L, M, Any?>
typealias Input12<A, B, C, D, E, F, G, H, I, J, K, L> = Input13<A, B, C, D, E, F, G, H, I, J, K, L, Any?>
typealias Input11<A, B, C, D, E, F, G, H, I, J, K> = Input12<A, B, C, D, E, F, G, H, I, J, K, Any?>
typealias Input10<A, B, C, D, E, F, G, H, I, J> = Input11<A, B, C, D, E, F, G, H, I, J, Any?>
typealias Input9<A, B, C, D, E, F, G, H, I> = Input10<A, B, C, D, E, F, G, H, I, Any?>
typealias Input8<A, B, C, D, E, F, G, H> = Input9<A, B, C, D, E, F, G, H, Any?>
typealias Input7<A, B, C, D, E, F, G> = Input8<A, B, C, D, E, F, G, Any?>
typealias Input6<A, B, C, D, E, F> = Input7<A, B, C, D, E, F, Any?>
typealias Input5<A, B, C, D, E> = Input6<A, B, C, D, E, Any?>
typealias Input4<A, B, C, D> = Input5<A, B, C, D, Any?>
typealias Input3<A, B, C> = Input4<A, B, C, Any?>
typealias Input2<A, B> = Input3<A, B, Any?>
typealias Input1<A> = Input2<A, Any?>
typealias Input0 = Input1<Any?>

@PublishedApi
internal object Input : Input0()

@Suppress("NON_PUBLIC_PRIMARY_CONSTRUCTOR_OF_INLINE_CLASS")
inline class Output22<out A, out B, out C, out D, out E, out F, out G, out H, out I, out J, out K, out L, out M, out N, out O, out P, out Q, out R, out S, out T, out U, out V>
@PublishedApi internal constructor(val value: Any?)

typealias Output21<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U> = Output22<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Nothing>
typealias Output20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T> = Output21<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Nothing>
typealias Output19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S> = Output20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Nothing>
typealias Output18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R> = Output19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Nothing>
typealias Output17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q> = Output18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Nothing>
typealias Output16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> = Output17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Nothing>
typealias Output15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> = Output16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Nothing>
typealias Output14<A, B, C, D, E, F, G, H, I, J, K, L, M, N> = Output15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, Nothing>
typealias Output13<A, B, C, D, E, F, G, H, I, J, K, L, M> = Output14<A, B, C, D, E, F, G, H, I, J, K, L, M, Nothing>
typealias Output12<A, B, C, D, E, F, G, H, I, J, K, L> = Output13<A, B, C, D, E, F, G, H, I, J, K, L, Nothing>
typealias Output11<A, B, C, D, E, F, G, H, I, J, K> = Output12<A, B, C, D, E, F, G, H, I, J, K, Nothing>
typealias Output10<A, B, C, D, E, F, G, H, I, J> = Output11<A, B, C, D, E, F, G, H, I, J, Nothing>
typealias Output9<A, B, C, D, E, F, G, H, I> = Output10<A, B, C, D, E, F, G, H, I, Nothing>
typealias Output8<A, B, C, D, E, F, G, H> = Output9<A, B, C, D, E, F, G, H, Nothing>
typealias Output7<A, B, C, D, E, F, G> = Output8<A, B, C, D, E, F, G, Nothing>
typealias Output6<A, B, C, D, E, F> = Output7<A, B, C, D, E, F, Nothing>
typealias Output5<A, B, C, D, E> = Output6<A, B, C, D, E, Nothing>
typealias Output4<A, B, C, D> = Output5<A, B, C, D, Nothing>
typealias Output3<A, B, C> = Output4<A, B, C, Nothing>
typealias Output2<A, B> = Output3<A, B, Nothing>
typealias Output1<A> = Output2<A, Nothing>
typealias Output = Output1<Nothing>

@PublishedApi
internal val OutputUnit = Output(Unit)
