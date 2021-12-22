import io.github.kyay10.kotlinlambdareturninliner.LambdaBacked

// K is used to ensure that FunctorIn and FunctorOut conform to the same higher-kinded type. It's in because imagine
// that you internally produce a Some for example that you want to coerce to an Option, this allows you to do so safely
@LambdaBacked
interface FunctorIn<in KA : K, out A, K> {
  fun <B, KB : K> KA.fmap(fo: FunctorOut<B, KB, K>, mapper: (A) -> B): KB
}

@LambdaBacked
interface FunctorOut<in B, out KB : K, K> {
  fun K.toKB(): KB // this is just used for type safety and to push unsafe conversions to the XFunctorOutImpl
  fun @UnsafeVariance KB.toK(): K
}

@LambdaBacked
interface FunctorBoth<in KA : K, out A, in B, out KB : K, K> : FunctorIn<KA, A, K>,
  FunctorOut<B, KB, K>

@LambdaBacked
interface ApplicativeIn<in KA : K, out A, K> : FunctorIn<KA, A, K> {
}

@LambdaBacked
interface ApplicativeOut<in B, out KB : K, K> : FunctorOut<B, KB, K> {
  fun pure(b: B): KB
}

@LambdaBacked
interface ApplicativeBoth<in KA : K, out A, in B, out KB : K, KFUNC : K, K> : ApplicativeIn<KA, A, K>,
  ApplicativeOut<B, KB, K>, FunctorBoth<KA, A, B, KB, K> {
  fun KA.app(applied: KFUNC): KB
  fun pureFunc(b: (A) -> B): KFUNC = pure(b as B) as KFUNC

  fun KA.fmapFunc(mapper: (A) -> ((A) -> B)): KFUNC =
    this.fmap(this@ApplicativeBoth, mapper as (A) -> B) as KFUNC
}

@LambdaBacked
interface MonadIn<in KA : K, out A, K> : ApplicativeIn<KA, A, K> {
  fun <B, KB : K> KA.flatMap(fo: FunctorOut<B, KB, K>, mapper: (A) -> KB): KB
}

@LambdaBacked
interface MonadOut<in B, out KB : K, K> : ApplicativeOut<B, KB, K>

@LambdaBacked
interface MonadBoth<in KA : K, out A, in B, out KB : K, KFUNC : K, K> : MonadIn<KA, A, K>, MonadOut<B, KB, K>,
  ApplicativeBoth<KA, A, B, KB, KFUNC, K> {
  fun <B> KA.flatMapFunc(fo: FunctorOut<B, K, K>, mapper: (A) -> (KFUNC)): KFUNC =
    this.flatMap<B, K>(fo, mapper) as KFUNC
}

@LambdaBacked
interface OptionMonadIn<out A> : MonadIn<Option<@UnsafeVariance A>, A, Option<*>>

// For a class with an out type param, a K value using Any? should be used just like below.
// For a class with an in type param, a K value using Nothing should be used.
// For an invariant class, use a star projection
@LambdaBacked
interface OptionMonadOut<in B> : MonadOut<B, Option<@UnsafeVariance B>, Option<*>>
@LambdaBacked
interface OptionMonadBoth<out A, in B> : OptionMonadIn<A>, OptionMonadOut<B>,
  MonadBoth<Option<@UnsafeVariance A>, A, B, Option<@UnsafeVariance B>, Option<(@UnsafeVariance A) -> @UnsafeVariance B>, Option<*>>

// Can also use an inline class here possibly but whatever
sealed class Option<A> {
  inline fun <B> map(mapper: (A) -> B): Option<B> = when (this) {
    is Some -> Some(mapper(a))
    is None -> None as Option<B>
  }

  inline fun <B> bind(mapper: (A) -> Option<B>): Option<B> = when (this) {
    is Some -> when (val other = mapper(a)) {
      is Some -> Some(other.a)
      is None -> None
    }
    is None -> None
  } as Option<B>
}

data class Some<A>(val a: A) : Option<A>()
object None : Option<Nothing>()
@LambdaBacked
object OptionMonadBothImpl : OptionMonadBoth<Any?, Any?> {
  override fun Option<*>.toKB(): Option<Any?> {
    return this as Option<Any?>
  }

  override fun pure(b: Any?): Option<Any?> {
    return Some(b)
  }

  override fun Option<Any?>.toK(): Option<Any?> {
    return this
  }

  // Functor out should most likely be OptionFunctorOutImpl, but we can't just assume that it'll be that and we can't
  // require this subclass to only accept OptionFunctorOut interface instances because that breaks the contract of
  // FunctorIn, and so we instead use K to indicate that this FunctorOut knows how to transform our K to a KB safely.
  override fun <B, KB : Option<*>> Option<Any?>.fmap(fo: FunctorOut<B, KB, Option<*>>, mapper: (Any?) -> B) =
    with(fo) { map(mapper).toKB() }

  override fun Option<Any?>.app(
    applied: Option<(Any?) -> Any?>
  ): Option<Any?> {
    return when (applied) {
      is Some -> when (this@app) {
        is Some -> {
          val appliedFunction = applied.a
          val passedInValue = this@app.a
          Some(appliedFunction(passedInValue))
        }
        else -> None
      }
      else -> None
    }.toKB()
  }

  override fun <B, KB : Option<*>> Option<Any?>.flatMap(fo: FunctorOut<B, KB, Option<*>>, mapper: (Any?) -> KB): KB {
    return with(fo) {
      bind { mapper(it).toK() }.toKB()
    }
  }
}

inline fun <A> optionApplicativeIn() = OptionMonadBothImpl as OptionMonadIn<A>
inline fun <B> optionApplicativeOut() = OptionMonadBothImpl as OptionMonadOut<B>
inline fun <A, B> optionApplicativeBoth() = OptionMonadBothImpl as OptionMonadBoth<A, B>
fun main() {
  // Note that some of these options' types are Some, and yet they still work with the normal OptionalFunctorX implementation
  val optionLife = Some(42)
  val optionExistence: Option<Any> = None as Option<Any>
  val optionName = Some("name")
  val optionUnit = Some(Unit)
  println("hello world")
  // Using with to emulate the @with(value) decorator
  with(optionApplicativeIn<Any>()) { // This has to be first because subtypes need to come at the very end
    with(optionApplicativeIn<Unit>()) {
      with(optionApplicativeIn<String>()) {
        with(optionApplicativeBoth<Int, String>()) {
          with(optionApplicativeOut<String>()) OFS@{
            with(optionApplicativeOut<Int>()) {
              // All of these require 2 receivers and so they can't be 100% prototyped right now but they should work as expected
              // Use Ctrl+Shift+P in IDEA to check the types of both the its and the return types of fmap and performMap
              println(optionLife.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionExistence.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionName.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionUnit.fmap(this@OFS) { it.toString() + " is a Some" })
              println(performMap(this@OFS, optionLife))
              println(performMap(this, optionLife))
              // Note that this performMap just needs a higher kinded container that has a CharSequence and not
              // specifically a String, but because the types are declared with the proper variance it's automatically
              // inferred by the compiler that this String value will satisfy the CharSequence constraint
              println(performMap(this@OFS, optionName))
              println(optionLife.flatMap(this@OFS) { life ->
                optionName.flatMap(this@OFS) { name ->
                  optionUnit.flatMap(this@OFS) { unit ->
                    optionExistence.flatMap(this@OFS) { existence ->
                      Some("$life + $name + $unit + $existence")
                    }
                  }
                }
              })
              println(optionLife.app(optionLife.flatMapFunc(this@OFS) {
                Some { "$it was flatMapFunced" }
              }))
            }
          }
        }
      }
    }
  }
}

fun <KA : K, KB : K, K> FunctorIn<KA, CharSequence, K>.performMap(fo: FunctorOut<String, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it.toString() + " is a CharSequence" }
}

@JvmName("fmapIntToString")
fun <KA : K, KB : K, K> FunctorIn<KA, Int, K>.performMap(fo: FunctorOut<String, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it.toString() + " is an Int" }
}

@JvmName("fmapInt")
fun <KA : K, KB : K, K> FunctorIn<KA, Int, K>.performMap(fo: FunctorOut<Int, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it + 5 }
}

/*
import kotlin.internal.Exact

fun interface Applicative<G, KG> {
  fun pure(g: G): KG
}

fun interface Functor<in KA, out A, in B, out KB> {
  fun KA.map(mapper: (A) -> B): KB
}

fun interface OptionFunctor<out A, in B> : Functor<Option<@UnsafeVariance A>, A, B, Option<@UnsafeVariance B>>

// Can also use an inline class here possibly but whatever
sealed class Option<out A> {
  inline fun <B> map(mapper: (A) -> B): Option<B> = when (this) {
    is Some -> Some(mapper(a))
    is None -> None
  }
}

data class Some<A>(val a: A) : Option<A>()
object None : Option<Nothing>()
object OptionFunctorImpl : OptionFunctor<Any?, Any?> {
  override fun Option<Any?>.map(mapper: (Any?) -> Any?): Option<Any?> = this.map(mapper)
}

inline fun <A, B> optionFunctor() = OptionFunctorImpl as OptionFunctor<A, B>
fun main() {
  val optionLife: Option<Int> = Some(42)
  val optionExistence: Option<Any> = None
  val optionName: Option<String> = Some("name")
  val optionUnit = Some(Unit)
  println("hello world")
  with(optionFunctor<CharSequence, String>()) {
    with(optionFunctor<CharSequence, CharSequence>()) {
      with(optionFunctor<Int, String>()) {
        println(optionLife.map { it.toString() + " is a Some" })
        println(optionExistence.map { it.toString() + " is a Some" })
        println(optionName.map { it.toString() + " is a Some" })
        println(optionUnit.map { it.toString() + " is a Some" })
        println(performFmap(optionLife))
        println(performFmap(optionName))
      }
    }
  }
}

fun <F : Functor<KA, CharSequence, String, KB>, KA, KB> F.performFmap(ka: @Exact KA): KB = with(this) {
  ka.map { it.toString() + " is special1" }
}

@JvmName("fmapInt")
fun <F : Functor<KA, Int, String, KB>, KA, KB> F.performFmap(ka: @Exact KA): KB = with(this) {
  ka.map { it.toString() + " is special2" }
}

 */
/*
// K is used to ensure that FunctorIn and FunctorOut conform to the same higher-kinded type. It's in because imagine
// that you internally produce a Some for example that you want to coerce to an Option, this allows you to do so safely
interface FunctorIn<in KA, out A, in K> {
  fun <B, KB> KA.fmap(fo: FunctorOut<B, KB, @UnsafeVariance K>, mapper: (A) -> B): KB
}

interface FunctorOut<in B, out KB, in K> {
  fun K.toKB(): KB // this is just used for type safety and to push unsafe conversions to the XFunctorOutImpl
  fun @UnsafeVariance KB.toK(): @UnsafeVariance K
}

interface ApplicativeIn<in KA, out A, in K>: FunctorIn<KA, A, K> {
  fun <B, KB, KFUNC> KA.app(fo: ApplicativeOut<B, KB, KFUNC, @UnsafeVariance K>, applied: KFUNC): KB
}
interface ApplicativeOut<in B, out KB, in KFUNC, in K>: FunctorOut<B, KB, K> {
  fun pure(b: B): KB
  operator fun KFUNC.invoke(a: Any?): KB
}

interface MonadIn<in KA, out A, in K>: ApplicativeIn<KA, A, K> {
  fun <B, KB> KA.flatMap(fo: FunctorOut<B, KB, @UnsafeVariance K>, mapper: (A) -> KB): KB
}

interface OptionMonadIn<out A> : MonadIn<Option<@UnsafeVariance A>, A, Option<Any?>>

// For a class with an out type param, a K value using Any? should be used just like below.
// For a class with an in type param, a K value using Nothing should be used.
// For an invariant class, use Any? or Nothing or Unit even and unsafely cast that to KB
interface OptionApplicativeOut<in B> : ApplicativeOut<B, Option<@UnsafeVariance B>, Option<(Any?) -> B>, Option<Any?>>

// Can also use an inline class here possibly but whatever
sealed class Option<out A> {
  inline fun <B> map(mapper: (A) -> B): Option<B> = when (this) {
    is Some -> Some(mapper(a))
    is None -> None
  }
  inline fun <B> bind(mapper: (A) -> Option<B>): Option<B> = when (this) {
    is Some -> when(val other = mapper(a)){
      is Some -> Some(other.a)
      is None -> None
    }
    is None -> None
  }
}

data class Some<A>(val a: A) : Option<A>()
object None : Option<Nothing>()
object OptionMonadInImpl : OptionMonadIn<Any?> {
  // Functor out should most likely be OptionFunctorOutImpl, but we can't just assume that it'll be that and we can't
  // require this subclass to only accept OptionFunctorOut interface instances because that breaks the contract of
  // FunctorIn, and so we instead use K to indicate that this FunctorOut knows how to transform our K to a KB safely.
  override fun <B, KB> Option<Any?>.fmap(fo: FunctorOut<B, KB, Option<Any?>>, mapper: (Any?) -> B) =
    with(fo) { map(mapper).toKB() }

  override fun <B, KB, KFUNC> Option<Any?>.app(fo: ApplicativeOut<B, KB, KFUNC, Option<Any?>>, applied: KFUNC): KB {
    with(fo){
      return when(this@app) {
        is Some -> applied(a)
        is None -> None.toKB()
      }
    }
  }

  override fun <B, KB> Option<Any?>.flatMap(fo: FunctorOut<B, KB, Option<Any?>>, mapper: (Any?) -> KB): KB {
    return with(fo) {
      bind { mapper(it).toK() }.toKB()
    }
  }
}

object OptionApplicativeOutImpl : OptionApplicativeOut<Any?> {
  override fun Option<Any?>.toKB(): Option<Any?> {
    return this
  }

  override fun pure(b: Any?): Option<Any?> {
    return Some(b)
  }

  override fun Option<(Any?) -> Any?>.invoke(a: Any?): Option<Any?> {
    return when(this) {
      is Some -> Some(this.a(a))
      is None -> None
    }
  }

  override fun Option<Any?>.toK(): Option<Any?> {
    return this
  }
}

inline fun <A> optionApplicativeIn() = OptionMonadInImpl as OptionMonadIn<A>
inline fun <B> optionApplicativeOut() = OptionApplicativeOutImpl as OptionApplicativeOut<B>
fun main() {
  // Note that some of these options' types are Some, and yet they still work with the normal OptionalFunctorX implementation
  val optionLife = Some(42)
  val optionExistence: Option<Any> = None
  val optionName = Some("name")
  val optionUnit = Some(Unit)
  println("hello world")
  // Using with to emulate the @with(value) decorator
  with(optionApplicativeIn<Any>()) { // This has to be first because subtypes need to come at the very end
    with(optionApplicativeIn<Unit>()) {
      with(optionApplicativeIn<String>()) {
        with(optionApplicativeIn<Int>()) {
          with(optionApplicativeOut<String>()) OFS@{
            with(optionApplicativeOut<Int>()) {
              // All of these require 2 receivers and so they can't be 100% prototyped right now but they should work as expected
              // Use Ctrl+Shift+P in IDEA to check the types of both the its and the return types of fmap and performMap
              println(optionLife.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionExistence.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionName.fmap(this@OFS) { it.toString() + " is a Some" })
              println(optionUnit.fmap(this@OFS) { it.toString() + " is a Some" })
              println(performMap(this@OFS, optionLife))
              println(performMap(this, optionLife))
              optionUnit.flatMap(this@OFS) { unit ->

              }
              // Note that this performMap just needs a higher kinded container that has a CharSequence and not
              // specifically a String, but because the types are declared with the proper variance it's automatically
              // inferred by the compiler that this String value will satisfy the CharSequence constraint
              println(performMap(this@OFS, optionName))
              println(optionLife.flatMap(this@OFS) { life ->
                optionName.flatMap (this@OFS){ name ->
                  optionUnit.flatMap(this@OFS) { unit ->

                  } } })
            }
          }
        }
      }
    }
  }
}

fun <KA, KB, K> FunctorIn<KA, CharSequence, K>.performMap(fo: FunctorOut<String, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it.toString() + " is a CharSequence" }
}

@JvmName("fmapIntToString")
fun <KA, KB, K> FunctorIn<KA, Int, K>.performMap(fo: FunctorOut<String, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it.toString() + " is an Int" }
}

@JvmName("fmapInt")
fun <KA, KB, K> FunctorIn<KA, Int, K>.performMap(fo: FunctorOut<Int, KB, K>, ka: KA): KB {
  return ka.fmap(fo) { it + 5 }
}

 */
