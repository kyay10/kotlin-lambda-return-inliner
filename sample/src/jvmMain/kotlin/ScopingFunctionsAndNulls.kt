fun main() {
  val myFun: (String) -> String = { it + " ran through this lambda" }
  // Quite a few simple scoping functions that return the block's result
  println(myFun.let {
    it("test") + with(it) {
      this(" hello")
    } + it.run {
      this(" world")
    }
  })
  //Testing to see if nullable and generic returns of lambdas are optimised
  val myFunNullNormal = lambdaOrNullNormal(true, myFun)
  val myFunNotNullNormal = lambdaOrNullNormal(false, myFun)
  val myFunNullGeneric = lambdaOrNullGeneric(true, myFun)
  val myFunNotNullGeneric = lambdaOrNullGeneric(false, myFun)
  // Checking to see if safe navigation is optimised
  println(myFunNullNormal?.invoke("myFunNullNormal"))
  println(myFunNotNullNormal?.invoke("myFunNotNullNormal"))
  println(myFunNullGeneric?.invoke("myFunNullGeneric"))
  println(myFunNotNullGeneric?.invoke("myFunNotNullGeneric"))
  //Checking to see if scoping functions that return their receiver are optimised
  myFunNullNormal?.apply {
    println(this("myFunNullNormal with apply"))
  }
  myFunNotNullNormal?.apply {
    println(this("myFunNotNullNormal with apply"))
  }
  myFunNullGeneric?.also {
    println(it("myFunNullGeneric with also"))
  }
  myFunNotNullGeneric?.also {
    println(it("myFunNotNullGeneric with also"))
  }
  //Checking to see if direct invocation and nullable values inside a scoping function are both optimised
  lambdaOrNullNormal(true, myFun).let {
    if(it == null) println("null1") else println(it("null1"))
  }
  lambdaOrNullNormal(false, myFun).run {
    if(this == null) println("null2") else println(this("null2"))
  }
  lambdaOrNullGeneric(true, myFun).apply {
    if(this == null) println("null3") else println(this("null3"))
  }
  lambdaOrNullGeneric(false, myFun).also {
    if(it == null) println("null4") else println(it("null4"))
  }
}

inline fun lambdaOrNullNormal(shouldReturnNull: Boolean, noinline lambda: (String) -> String): ((String) -> String)? =
  lambda.takeIf { shouldReturnNull }

inline fun <T> lambdaOrNullGeneric(shouldReturnNull: Boolean, lambda: T): T? = lambda.takeIf { shouldReturnNull }
