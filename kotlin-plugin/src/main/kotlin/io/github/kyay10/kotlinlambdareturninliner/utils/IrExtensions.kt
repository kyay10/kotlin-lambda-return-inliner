@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner.utils

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrElementBase
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.FqNameEqualityChecker
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.cast
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

tailrec fun IrStatement.lastElement(): IrStatement =
  if (this !is IrContainerExpression) this else this.statements.last().lastElement()

fun IrModuleFragment.lowerWith(pass: FileLoweringPass) = pass.lower(this)
fun IrTypeParameter.createSimpleType(
  hasQuestionMark: Boolean = false,
  arguments: List<IrTypeArgument> = emptyList(),
  annotations: List<IrConstructorCall> = emptyList(),
  abbreviation: IrTypeAbbreviation? = null
): IrSimpleType = IrSimpleTypeImpl(symbol, hasQuestionMark, arguments, annotations, abbreviation)

fun IrStatement.replaceLastElementAndIterateBranches(
  onThisNotContainer: (replacer: (IrStatement) -> IrStatement, IrStatement) -> Unit,
  newWhenType: IrType,
  replacer: (IrStatement) -> IrStatement,
): IrStatement {
  return replaceLastElement(onThisNotContainer) {
    if (it is IrWhen) {
      it.type = newWhenType
      it.branches.forEach { branch ->
        branch.result.replaceLastElementAndIterateBranches(
          { _, irStatement ->
            branch.result = replacer(
              irStatement
            ).cast()
          },
          newWhenType,
          replacer,
        )
      }
      it
    } else replacer(it)
  }
}

@OptIn(ExperimentalContracts::class)
inline fun IrStatement.replaceLastElement(
  onThisNotContainer: (replacer: (IrStatement) -> IrStatement, IrStatement) -> Unit,
  noinline replacer: (IrStatement) -> IrStatement
): IrStatement {
  contract {
    callsInPlace(replacer, InvocationKind.EXACTLY_ONCE)
  }
  return if (this is IrContainerExpression) replaceLastElement(replacer) else this.apply {
    onThisNotContainer(
      replacer,
      this
    )
  }
}

@OptIn(ExperimentalContracts::class)
tailrec fun IrContainerExpression.replaceLastElement(replacer: (IrStatement) -> IrStatement): IrStatement {
  contract {
    callsInPlace(replacer, InvocationKind.EXACTLY_ONCE)
  }
  val lastElement = statements.last()
  if (lastElement !is IrContainerExpression) statements[statements.size - 1] = replacer(lastElement)
  return if (lastElement !is IrContainerExpression) lastElement else lastElement.replaceLastElement(replacer)
}

fun IrMemberAccessExpression<*>.changeArgument(index: Int, valueArgument: IrExpression?) {
  when (index) {
    0 -> dispatchReceiver = valueArgument
    1 -> extensionReceiver = valueArgument
    else -> putValueArgument(index - 2, valueArgument)
  }
}

fun accumulateStatementsExceptLast(statement: IrStatement): List<IrStatement> =
  accumulateStatements(statement).apply { removeLast() }

tailrec fun accumulateStatements(
  statement: IrStatement,
  list: MutableList<IrStatement> = mutableListOf()
): MutableList<IrStatement> {
  return if (statement !is IrContainerExpression || statement.statements.isEmpty()) {
    list.apply { add(statement) }
  } else {
    list.addAll(statement.statements)
    list.removeLast()
    accumulateStatements(statement.statements.last(), list)
  }
}

inline fun <reified T : IrElement> MutableList<T?>.transformInPlace(transformation: (T) -> IrElement) {
  for (i in indices) {
    get(i)?.let { set(i, transformation(it) as T) }
  }
}

fun <T : IrElement, D> MutableList<T?>.transformInPlace(transformer: IrElementTransformer<D>, data: D) {
  for (i in indices) {
    // Cast to IrElementBase to avoid casting to interface and invokeinterface, both of which are slow.
    @Suppress("UNCHECKED_CAST")
    get(i)?.let { set(i, (it as IrElementBase).transform(transformer, data) as T) }
  }
}

val IrFunctionAccessExpression.isInvokeCall: Boolean
  get() {
    val irFunction = symbol.owner
    return irFunction.name == OperatorNameConventions.INVOKE &&
      irFunction.safeAs<IrSimpleFunction>()?.isOperator == true &&
      dispatchReceiver?.type?.isFunctionTypeOrSubtype == true &&
      extensionReceiver == null
  }

fun IrType.equalsOrIsTypeParameterLike(
  otherType: IrType
): Boolean =
  this == otherType
    || classifierOrNull.safeAs<IrTypeParameterSymbol>()?.owner?.name?.equals(otherType.classifierOrNull.safeAs<IrTypeParameterSymbol>()?.owner?.name) == true
    || (this is IrSimpleType && otherType is IrSimpleType && FqNameEqualityChecker.areEqual(
    this.classifier,
    otherType.classifier
  ) && arguments.allIndexed { index, argument ->
    otherType.arguments[index].typeOrNull?.let {
      argument.typeOrNull?.equalsOrIsTypeParameterLike(
        it
      )
    } == true
  })

inline fun <reified T : IrElement> T.deepCopyWithSymbols(
  symbolRemapper: DeepCopySymbolRemapper,
  initialParent: IrDeclarationParent? = null,
  createCopier: (SymbolRemapper, TypeRemapper) -> DeepCopyIrTreeWithSymbols = ::DeepCopyIrTreeWithSymbols
): T {
  acceptVoid(symbolRemapper)
  val typeRemapper = DeepCopyTypeRemapper(symbolRemapper)
  return transform(
    createCopier(symbolRemapper, typeRemapper).also { typeRemapper.deepCopy = it },
    null
  ).patchDeclarationParents(initialParent) as T
}

inline fun <reified T : IrElement> T.deepCopyWithSymbols(
  initialParent: IrDeclarationParent? = null,
  createCopier: (SymbolRemapper, TypeRemapper) -> DeepCopyIrTreeWithSymbols = ::DeepCopyIrTreeWithSymbols
): T {
  val symbolRemapper = DeepCopySymbolRemapper()
  acceptVoid(symbolRemapper)
  val typeRemapper = DeepCopyTypeRemapper(symbolRemapper)
  return transform(
    createCopier(symbolRemapper, typeRemapper).also { typeRemapper.deepCopy = it },
    null
  ).patchDeclarationParents(initialParent) as T
}

private val _isFunctionTypeOrSubtypeCache: MutableMap<IrClassifierSymbol, Boolean> = hashMapOf()
val IrType.isFunctionTypeOrSubtype: Boolean
  get() {
    val thisClassifier = classifierOrNull
    val superTypes = superTypes()
    _isFunctionTypeOrSubtypeCache[thisClassifier]?.let { return it }
    for (type in superTypes) {
      _isFunctionTypeOrSubtypeCache[type.classifierOrNull]?.let { return it }
    }

    val result = if (thisClassifier?.isFunction() == true) true else isFunctionTypeOrSubtype()
    thisClassifier?.let { _isFunctionTypeOrSubtypeCache[it] = result }
    if (thisClassifier?.isFunction() == false) {
      // Must be a function subtype
      // Add whichever supertype is a function type to the cache
      for (type in superTypes) {
        val typeClassifier = type.classifierOrNull
        if (typeClassifier?.isFunction() == true) {
          typeClassifier.let { _isFunctionTypeOrSubtypeCache[it] = result }
        }
      }
    }
    return result
  }
