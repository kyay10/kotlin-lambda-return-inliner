@file:Suppress("MemberVisibilityCanBePrivate")

package io.github.kyay10.kotlinlambdareturninliner.internal

/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Copyright (C) 2021 Youssef Shoaib
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 *
 * Copied and modified from the Kotlin compiler source code at: https://github.com/JetBrains/kotlin/blob/1.4.30/compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/inline/FunctionInlining.kt
 */


import io.github.kyay10.kotlinlambdareturninliner.utils.equalsOrIsTypeParameterLike
import io.github.kyay10.kotlinlambdareturninliner.utils.flatMap
import io.github.kyay10.kotlinlambdareturninliner.utils.id
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.ir.allParametersCount
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.coroutinesIntrinsicsPackageFqName
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.isDispatchReceiver
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrReturnableBlockSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.js.descriptorUtils.getJetTypeFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.util.OperatorNameConventions

fun IrValueParameter.isInlineParameter(type: IrType = this.type) =
  index >= 0 /*&& !isNoinline removed because it forces inline functions to be unable to return a passed in lambda
  TODO: find a more elegant solution for this*/
    && !type.isNullable() && (type.isFunction() || type.isSuspendFunction())

interface InlineFunctionResolver {
  fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction
}

fun IrFunction.isTopLevelInPackage(name: String, packageName: String): Boolean {
  if (name != this.name.asString()) return false

  val containingDeclaration = parent as? IrPackageFragment ?: return false
  val packageFqName = containingDeclaration.fqName.asString()
  return packageName == packageFqName
}

fun IrFunction.isBuiltInIntercepted(languageVersionSettings: LanguageVersionSettings): Boolean =
  !languageVersionSettings.supportsFeature(LanguageFeature.ReleaseCoroutines) &&
    isTopLevelInPackage("intercepted", languageVersionSettings.coroutinesIntrinsicsPackageFqName().asString())

open class DefaultInlineFunctionResolver(open val context: IrPluginContext) : InlineFunctionResolver {
  override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction {
    val function = symbol.owner
    val languageVersionSettings = context.languageVersionSettings
    // TODO: Remove these hacks when coroutine intrinsics are fixed.
    return when {
      function.isBuiltInIntercepted(languageVersionSettings) ->
        error("Continuation.intercepted is not available with release coroutines")

      else -> (symbol.owner as? IrSimpleFunction)?.resolveFakeOverride() ?: symbol.owner
    }
  }
}

private val IrFunction.needsInlining get() = (this.isInline || (this as? IrSimpleFunction)?.isOperator == true) //&& !this.isExternal

private class Inliner(
  val transformer: IrElementTransformerVoidWithContext,
  val callSite: IrFunctionAccessExpression,
  val callee: IrFunction,
  val currentScope: Scope,
  val parent: IrDeclarationParent?,
  val context: IrPluginContext
) {

  val copyIrElement = run {
    val typeParameters =
      if (callee is IrConstructor)
        callee.parentAsClass.typeParameters
      else callee.typeParameters
    val typeArguments =
      (0 until callSite.typeArgumentsCount).associate {
        typeParameters[it].symbol to callSite.getTypeArgument(it)
      }
    DeepCopyIrTreeWithSymbolsForInliner(typeArguments, parent)
  }

  val substituteMap = mutableMapOf<IrValueParameter, IrExpression>()

  fun inline() = inlineFunction(callSite, callee, true)

  /**
   * TODO: JVM inliner crashed on attempt inline this function from transform.kt with:
   *  j.l.IllegalStateException: Couldn't obtain compiled function body for
   *  public inline fun <reified T : org.jetbrains.kotlin.ir.IrElement> kotlin.collections.MutableList<T>.transform...
   */
  private inline fun <reified T : IrElement> MutableList<T>.transform(transformation: (T) -> IrElement) {
    forEachIndexed { i, item ->
      set(i, transformation(item) as T)
    }
  }


  private fun inlineFunction(
    callSite: IrFunctionAccessExpression,
    callee: IrFunction,
    performRecursiveInline: Boolean
  ): IrExpression {

    val copiedCallee = (copyIrElement.copy(callee) as IrFunction).apply {
      parent = callee.parent
      if (performRecursiveInline) {
        body?.transformChildrenVoid(transformer)
        valueParameters.forEachIndexed { index, param ->
          if (callSite.getValueArgument(index) == null) {
            // Default values can recursively reference [callee] - transform only needed.
            param.defaultValue = param.defaultValue?.transform(transformer, null)
          }
        }
      }
    }

    val evaluationStatements = evaluateArguments(callSite, copiedCallee)
    val statements = (copiedCallee.body as? IrBlockBody)?.statements ?: return callSite

    val irReturnableBlockSymbol = IrReturnableBlockSymbolImpl()
    val endOffset = callee.endOffset
    /* creates irBuilder appending to the end of the given returnable block: thus why we initialize
     * irBuilder with (..., endOffset, endOffset).
     */
    val irBuilder = DeclarationIrBuilder(context, irReturnableBlockSymbol, endOffset, endOffset)

    val transformer = ParameterSubstitutor()
    statements.transform { it.transform(transformer, data = null) }
    statements.addAll(0, evaluationStatements)

    return IrReturnableBlockImpl(
      startOffset = callSite.startOffset,
      endOffset = callSite.endOffset,
      type = callSite.type,
      symbol = irReturnableBlockSymbol,
      origin = null,
      statements = statements,
      inlineFunctionSymbol = callee.symbol
    ).apply {
      transformChildrenVoid(object : IrElementTransformerVoid() {
        override fun visitReturn(expression: IrReturn): IrExpression {
          expression.transformChildrenVoid(this)

          if (expression.returnTargetSymbol == copiedCallee.symbol)
            return irBuilder.irReturn(expression.value)
          return expression
        }
      })
      patchDeclarationParents(parent) // TODO: Why it is not enough to just run SetDeclarationsParentVisitor?
    }
  }

  //---------------------------------------------------------------------//

  private inner class ParameterSubstitutor : IrElementTransformerVoid() {

    override fun visitGetValue(expression: IrGetValue): IrExpression {
      val newExpression = super.visitGetValue(expression) as IrGetValue
      val argument = substituteMap[newExpression.symbol.owner] ?: return newExpression

      argument.transformChildrenVoid(this) // Default argument can contain subjects for substitution.

      return if (argument is IrGetValueWithoutLocation)
        argument.withLocation(newExpression.startOffset, newExpression.endOffset)
      else (copyIrElement.copy(argument) as IrExpression)
    }


    override fun visitCall(expression: IrCall): IrExpression {
      if (!isLambdaCall(expression))
        return super.visitCall(expression)

      val dispatchReceiver = expression.dispatchReceiver as IrGetValue
      val functionArgument = substituteMap[dispatchReceiver.symbol.owner] ?: return super.visitCall(expression)
      if ((dispatchReceiver.symbol.owner as? IrValueParameter)?.isNoinline == true)
        return super.visitCall(expression)

      return when {
        functionArgument is IrFunctionReference -> inlineFunctionReference(expression, functionArgument)
        functionArgument.isAdaptedFunctionReference() -> inlineAdaptedFunctionReference(
          expression,
          functionArgument as IrBlock
        )
        functionArgument is IrFunctionExpression -> inlineFunctionExpression(expression, functionArgument)
        else -> super.visitCall(expression)
      }
    }

    fun inlineFunctionExpression(irCall: IrCall, irFunctionExpression: IrFunctionExpression): IrExpression {
      // Inline the lambda. Lambda parameters will be substituted with lambda arguments.
      val newExpression = inlineFunction(
        irCall,
        irFunctionExpression.function,
        false
      )
      // Substitute lambda arguments with target function arguments.
      return newExpression.transform(this, null)
    }

    fun inlineAdaptedFunctionReference(irCall: IrCall, irBlock: IrBlock): IrExpression {
      val irFunction = irBlock.statements[0] as IrFunction
      irFunction.transformChildrenVoid(this)
      val irFunctionReference = irBlock.statements[1] as IrFunctionReference
      val inlinedFunctionReference = inlineFunctionReference(irCall, irFunctionReference)
      return IrBlockImpl(
        irCall.startOffset, irCall.endOffset,
        inlinedFunctionReference.type, origin = null,
        statements = listOf(irFunction, inlinedFunctionReference)
      )
    }

    fun inlineFunctionReference(irCall: IrCall, irFunctionReference: IrFunctionReference): IrExpression {
      irFunctionReference.transformChildrenVoid(this)

      val function = irFunctionReference.symbol.owner
      val functionParameters = function.explicitParameters
      val boundFunctionParameters = irFunctionReference.getArgumentsWithIr()
      val unboundFunctionParameters = functionParameters - boundFunctionParameters.map { it.first }
      val boundFunctionParametersMap = boundFunctionParameters.associate { it.first to it.second }

      var unboundIndex = 0
      val unboundArgsSet = unboundFunctionParameters.toSet()
      val valueParameters = irCall.getArgumentsWithIr().drop(1) // Skip dispatch receiver.

      val superType = irFunctionReference.type as IrSimpleType
      val superTypeArgumentsMap = irCall.symbol.owner.parentAsClass.typeParameters.associate { typeParam ->
        typeParam.symbol to superType.arguments[typeParam.index].typeOrNull!!
      }

      val immediateCall = with(irCall) {
        when (function) {
          is IrConstructor -> {
            val classTypeParametersCount = function.parentAsClass.typeParameters.size
            IrConstructorCallImpl.fromSymbolOwner(
              startOffset,
              endOffset,
              function.returnType,
              function.symbol,
              classTypeParametersCount
            )
          }
          is IrSimpleFunction ->
            IrCallImpl(
              startOffset,
              endOffset,
              function.returnType,
              function.symbol,
              function.typeParameters.size,
              function.valueParameters.size
            )
          else ->
            error("Unknown function kind : ${function.render()}")
        }
      }.apply {
        for (parameter in functionParameters) {
          val argument =
            if (parameter !in unboundArgsSet) {
              val arg = boundFunctionParametersMap[parameter]!!
              if (arg is IrGetValueWithoutLocation)
                arg.withLocation(irCall.startOffset, irCall.endOffset)
              else arg
            } else {
              if (unboundIndex == valueParameters.size && parameter.defaultValue != null)
                copyIrElement.copy(parameter.defaultValue!!.expression) as IrExpression
              else if (!parameter.isVararg) {
                assert(unboundIndex < valueParameters.size) {
                  "Attempt to use unbound parameter outside of the callee's value parameters"
                }
                valueParameters[unboundIndex++].second
              } else {
                val elements = mutableListOf<IrVarargElement>()
                while (unboundIndex < valueParameters.size) {
                  val (param, value) = valueParameters[unboundIndex++]
                  val substitutedParamType = param.type.substitute(superTypeArgumentsMap)
                  if (substitutedParamType == parameter.varargElementType!!)
                    elements += value
                  else
                    elements += IrSpreadElementImpl(irCall.startOffset, irCall.endOffset, value)
                }
                IrVarargImpl(
                  irCall.startOffset, irCall.endOffset,
                  parameter.type,
                  parameter.varargElementType!!,
                  elements
                )
              }
            }
          when (parameter) {
            function.dispatchReceiverParameter ->
              this.dispatchReceiver = argument.implicitCastIfNeededTo(function.dispatchReceiverParameter!!.type)

            function.extensionReceiverParameter ->
              this.extensionReceiver = argument.implicitCastIfNeededTo(function.extensionReceiverParameter!!.type)

            else ->
              putValueArgument(
                parameter.index,
                argument.implicitCastIfNeededTo(function.valueParameters[parameter.index].type)
              )
          }
        }
        assert(unboundIndex == valueParameters.size) { "Not all arguments of the callee are used" }
        for (index in 0 until irFunctionReference.typeArgumentsCount)
          putTypeArgument(index, irFunctionReference.getTypeArgument(index))
      }.implicitCastIfNeededTo(irCall.type)
      return /*this@FunctionInlining.visitExpression(*/super.visitExpression(immediateCall)/*) TODO: figure out what this line was meant to do, and if it is required, re-create the structure of the FunctionInlining class to make it work again.*/
    }

    override fun visitElement(element: IrElement) = element.accept(this, null)
  }

  private fun IrExpression.implicitCastIfNeededTo(type: IrType) =
    if (type == this.type)
      this
    else
      IrTypeOperatorCallImpl(startOffset, endOffset, type, IrTypeOperator.IMPLICIT_CAST, type, this)

  private fun isLambdaCall(irCall: IrCall): Boolean {
    val callee = irCall.symbol.owner
    val dispatchReceiver = callee.dispatchReceiverParameter ?: return false
    assert(!dispatchReceiver.type.isKFunction())

    return (dispatchReceiver.type.isFunction() || dispatchReceiver.type.isSuspendFunction())
      && callee.name == OperatorNameConventions.INVOKE
      && irCall.dispatchReceiver is IrGetValue
  }

  private fun IrExpression.isAdaptedFunctionReference() =
    this is IrBlock && this.origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE

  private inner class ParameterToArgument(
    val parameter: IrValueParameter,
    val argumentExpression: IrExpression
  ) {

    private val IrValueParameter.isExtensionReceiver: Boolean
      get() {
        val parent = this.parent
        return parent is IrFunction && parent.extensionReceiverParameter == this
      }
    val isInlinableLambdaArgument: Boolean
      get() = (parameter.isInlineParameter() || parameter.isDispatchReceiver || parameter.isExtensionReceiver) &&
        (argumentExpression is IrFunctionReference
          || argumentExpression is IrFunctionExpression
          || argumentExpression.isAdaptedFunctionReference())

    val isImmutableVariableLoad: Boolean
      get() = argumentExpression.let { argument ->
        argument is IrGetValue && !argument.symbol.owner.let { it is IrVariable && it.isVar }
      }
  }

  // callee might be a copied version of callsite.symbol.owner
  private fun buildParameterToArgument(
    callSite: IrFunctionAccessExpression,
    callee: IrFunction
  ): List<ParameterToArgument> {

    val parameterToArgument = mutableListOf<ParameterToArgument>()

    if (callSite.dispatchReceiver != null && callee.dispatchReceiverParameter != null)
      parameterToArgument += ParameterToArgument(
        parameter = callee.dispatchReceiverParameter!!,
        argumentExpression = callSite.dispatchReceiver!!
      )

    val valueArguments =
      callSite.symbol.owner.valueParameters.map { callSite.getValueArgument(it.index) }.toMutableList()

    if (callee.extensionReceiverParameter != null) {
      parameterToArgument += ParameterToArgument(
        parameter = callee.extensionReceiverParameter!!,
        argumentExpression = if (callSite.extensionReceiver != null) {
          callSite.extensionReceiver!!
        } else {
          // Special case: lambda with receiver is called as usual lambda:
          valueArguments.removeAt(0)!!
        }
      )
    } else if (callSite.extensionReceiver != null) {
      // Special case: usual lambda is called as lambda with receiver:
      valueArguments.add(0, callSite.extensionReceiver!!)
    }

    val parametersWithDefaultToArgument = mutableListOf<ParameterToArgument>()
    for (parameter in callee.valueParameters) {
      val argument = valueArguments[parameter.index]
      when {
        argument != null -> {
          parameterToArgument += ParameterToArgument(
            parameter = parameter,
            argumentExpression = argument
          )
        }

        // After ExpectDeclarationsRemoving pass default values from expect declarations
        // are represented correctly in IR.
        parameter.defaultValue != null -> {  // There is no argument - try default value.
          parametersWithDefaultToArgument += ParameterToArgument(
            parameter = parameter,
            argumentExpression = parameter.defaultValue!!.expression
          )
        }

        parameter.varargElementType != null -> {
          val emptyArray = IrVarargImpl(
            startOffset = callSite.startOffset,
            endOffset = callSite.endOffset,
            type = parameter.type,
            varargElementType = parameter.varargElementType!!
          )
          parameterToArgument += ParameterToArgument(
            parameter = parameter,
            argumentExpression = emptyArray
          )
        }

        else -> {
          val message = "Incomplete expression: call to ${callee.render()} " +
            "has no argument at index ${parameter.index}"
          throw Error(message)
        }
      }
    }
    // All arguments except default are evaluated at callsite,
    // but default arguments are evaluated inside callee.
    return parameterToArgument + parametersWithDefaultToArgument
  }

  //-------------------------------------------------------------------------//

  private fun evaluateArguments(functionReference: IrFunctionReference): List<IrStatement> {
    val arguments = functionReference.getArgumentsWithIr().map { ParameterToArgument(it.first, it.second) }
    val evaluationStatements = mutableListOf<IrStatement>()
    val substitutor = ParameterSubstitutor()
    val referenced = functionReference.symbol.owner
    arguments.forEach {
      val newArgument = if (it.isImmutableVariableLoad) {
        it.argumentExpression.transform( // Arguments may reference the previous ones - substitute them.
          substitutor,
          data = null
        )
      } else {
        val newVariable =
          currentScope.createTemporaryVariable(
            irExpression = it.argumentExpression.transform( // Arguments may reference the previous ones - substitute them.
              substitutor,
              data = null
            ),
            nameHint = callee.symbol.owner.name.toString(),
            isMutable = false
          )

        evaluationStatements.add(newVariable)

        IrGetValueWithoutLocation(newVariable.symbol)
      }
      when (it.parameter) {
        referenced.dispatchReceiverParameter -> functionReference.dispatchReceiver = newArgument
        referenced.extensionReceiverParameter -> functionReference.extensionReceiver = newArgument
        else -> functionReference.putValueArgument(it.parameter.index, newArgument)
      }
    }
    return evaluationStatements
  }

  private fun evaluateArguments(callSite: IrFunctionAccessExpression, callee: IrFunction): List<IrStatement> {
    val arguments = buildParameterToArgument(callSite, callee)
    val evaluationStatements = mutableListOf<IrStatement>()
    val substitutor = ParameterSubstitutor()
    arguments.forEach { argument ->
      /*
       * We need to create temporary variable for each argument except inlinable lambda arguments.
       * For simplicity and to produce simpler IR we don't create temporaries for every immutable variable,
       * not only for those referring to inlinable lambdas.
       */
      if (argument.isInlinableLambdaArgument) {
        substituteMap[argument.parameter] = argument.argumentExpression
        (argument.argumentExpression as? IrFunctionReference)?.let { evaluationStatements += evaluateArguments(it) }
        return@forEach
      }

      if (argument.isImmutableVariableLoad) {
        substituteMap[argument.parameter] =
          argument.argumentExpression.transform( // Arguments may reference the previous ones - substitute them.
            substitutor,
            data = null
          )
        return@forEach
      }

      // Arguments may reference the previous ones - substitute them.
      val variableInitializer = argument.argumentExpression.transform(substitutor, data = null)

      val newVariable =
        currentScope.createTemporaryVariable(
          irExpression = IrBlockImpl(
            variableInitializer.startOffset,
            variableInitializer.endOffset,
            variableInitializer.type,
            InlinerExpressionLocationHint(callee.symbol)
          ).apply {
            statements.add(variableInitializer)
          },
          nameHint = callee.symbol.owner.name.toString(),
          isMutable = false
        )

      evaluationStatements.add(newVariable)
      substituteMap[argument.parameter] = IrGetValueWithoutLocation(newVariable.symbol)
    }
    return evaluationStatements
  }
}

private class IrGetValueWithoutLocation(
  override val symbol: IrValueSymbol,
  override val origin: IrStatementOrigin? = null
) : IrGetValue() {
  override val startOffset: Int get() = UNDEFINED_OFFSET
  override val endOffset: Int get() = UNDEFINED_OFFSET

  override var type: IrType
    get() = symbol.owner.type
    set(value) {
      symbol.owner.type = value
    }

  override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D) =
    visitor.visitGetValue(this, data)

/*  override fun copy(): IrGetValue {
    TODO("not implemented")
  }*/

  fun withLocation(startOffset: Int, endOffset: Int) =
    IrGetValueImpl(startOffset, endOffset, type, symbol, origin)
}

class InlinerExpressionLocationHint(val inlineAtSymbol: IrSymbol) : IrStatementOrigin {
  override fun toString(): String =
    "(${this.javaClass.simpleName} : $functionNameOrDefaultToString @${functionFileOrNull?.fileEntry?.name})"

  private val functionFileOrNull: IrFile?
    get() = (inlineAtSymbol as? IrFunction)?.file

  private val functionNameOrDefaultToString: String
    get() = (inlineAtSymbol as? IrFunction)?.name?.asString() ?: inlineAtSymbol.toString()
}


@OptIn(ObsoleteDescriptorBasedAPI::class)
fun IrElementTransformerVoidWithContext.inline(
  expression: IrFunctionAccessExpression,
  allScopes: MutableList<ScopeWithIr>,
  currentScope: Scope,
  context: IrPluginContext,
  shadowsContext: IrPluginContext?,
  renamesMap: MutableMap<String, String>
): IrExpression {
  expression.transformChildrenVoid(this)
  val (callee, isConstructor) = when (expression) {
    is IrCall -> expression.symbol.owner to false
    is IrConstructorCall -> expression.symbol.owner to true
    else -> return expression
  }
  val calleeFqName: FqName? = callee.fqNameWhenAvailable
  if (!callee.needsInlining)
    return expression
  if (Symbols.isLateinitIsInitializedPropertyGetter(callee.symbol))
    return expression
  if (Symbols.isTypeOfIntrinsic(callee.symbol))
    return expression

  val inlineFunctionResolver = DefaultInlineFunctionResolver(context)
  val actualCalleeCandidate = inlineFunctionResolver.getFunctionDeclaration(callee.symbol)

  @Suppress("NAME_SHADOWING")
  val actualCallee = (calleeFqName?.let { calleeFqName ->
    // if you have foo.bar.BazKt with a function foo.bar.BazKt.FooBar.test, then this deletes the BazKt if it is a file class name.
    // Note that this implementation ensures that if you have a foo.bar.BazKt.bar.BazKt.Test.BazKt where only the middle
    // one is a file class that only that middle one gets removed
    val calleFqNameSegments = calleeFqName.pathSegments().toMutableList()
    callee.parentClassOrNull?.takeIf { it.origin == IrDeclarationOrigin.FILE_CLASS || it.origin == IrDeclarationOrigin.JVM_MULTIFILE_CLASS || it.origin == IrDeclarationOrigin.SYNTHETIC_FILE_CLASS }
      ?.fqNameWhenAvailable?.let {
        calleFqNameSegments.removeAt(it.pathSegments().lastIndex)
      }
    calleFqNameSegments.map { segment ->
      listOfNotNull(segment.asString(),
        renamesMap[segment.asString()]?.let { KtPsiUtil.unquoteIdentifier(it) })
    }
      .fold(listOf(FqName.ROOT)) { acc, pathAlternatives ->
        acc.flatMap { parentFqName ->
          pathAlternatives.map { child ->
            parentFqName.child(Name.guessByFirstCharacter(child))
          }
        }
      }.reversed().asSequence()
      .flatMap {
        if (isConstructor) shadowsContext?.referenceConstructors(it)
          .orEmpty() + context.referenceConstructors(it) else shadowsContext?.referenceFunctions(it)
          .orEmpty() + context.referenceFunctions(it)
      }.map {
        inlineFunctionResolver.getFunctionDeclaration(it)
      }
  }.orEmpty().ifEmpty { sequenceOf(actualCalleeCandidate) }).firstOrNull {
    !it.isExternal && it.allParametersCount == actualCalleeCandidate.allParametersCount && it.allParameters.mapIndexed { index, parameter ->
      val actualParameter = actualCalleeCandidate.allParameters[index]
      actualParameter.type.equalsOrIsTypeParameterLike(parameter.type)
    }.all(::id) &&
      it.returnType.equalsOrIsTypeParameterLike(actualCalleeCandidate.returnType) &&
      it.typeParameters.size == actualCalleeCandidate.typeParameters.size &&
      it.typeParameters.mapIndexed { index, parameter ->
        val actualParameter = actualCalleeCandidate.typeParameters[index]
        actualParameter.name == parameter.name && actualParameter.variance == parameter.variance && actualParameter.superTypes.mapIndexed { superTypeIndex, superType ->
          val otherSuperType = parameter.superTypes[superTypeIndex]
          superType.equalsOrIsTypeParameterLike(otherSuperType)
        }.all(::id)
      }.all(::id)
  } ?: return expression

  val parent = allScopes.map { it.irElement }.filterIsInstance<IrDeclarationParent>().lastOrNull()
    ?: allScopes.map { it.irElement }.filterIsInstance<IrDeclaration>().lastOrNull()?.parent

  val inliner = Inliner(this, expression, actualCallee, currentScope, parent, context)
  return inliner.inline()//.transform(ReturnableBlockTransformer(context, currentScope.scope.scopeOwnerSymbol), null)
}

@ObsoleteDescriptorBasedAPI
class IrBasedDescriptorRemapper(val context: IrPluginContext) : DescriptorsRemapper {
  override fun remapDeclaredClass(descriptor: ClassDescriptor): ClassDescriptor? {
    return descriptor.fqNameOrNull()?.let { context.referenceClass(it)?.descriptor }
  }

  override fun remapDeclaredConstructor(descriptor: ClassConstructorDescriptor): ClassConstructorDescriptor? {
    return descriptor.fqNameOrNull()?.let {
      context.referenceConstructors(it).map(IrConstructorSymbol::descriptor).singleOrNull {
        it.valueParameters.size == descriptor.valueParameters.size && it.valueParameters.mapIndexed { index, param ->
          val otherParam = descriptor.valueParameters[index]
          param.name == otherParam.name && param.type.getJetTypeFqName(true) == otherParam.type.getJetTypeFqName(true)
        }
          .all(::id) && it.typeParameters.size == descriptor.typeParameters.size && it.typeParameters.mapIndexed { index, param ->
          val otherParam = descriptor.typeParameters[index]
          param.name == otherParam.name && param.defaultType.getJetTypeFqName(true) == otherParam.defaultType.getJetTypeFqName(
            true
          )
        }.all(::id)
    }
    }
  }

  override fun remapDeclaredEnumEntry(descriptor: ClassDescriptor): ClassDescriptor? {
    return descriptor.fqNameOrNull()?.let { context.referenceClass(it)?.descriptor }
  }

  override fun remapDeclaredExternalPackageFragment(descriptor: PackageFragmentDescriptor): PackageFragmentDescriptor {
    return context.moduleDescriptor.getPackage(descriptor.fqName).fragments.firstOrNull { it::class == descriptor::class }
      ?: descriptor
  }

  override fun remapDeclaredFilePackageFragment(descriptor: PackageFragmentDescriptor): PackageFragmentDescriptor {
    return context.moduleDescriptor.getPackage(descriptor.fqName).fragments.firstOrNull { it::class == descriptor::class }
      ?: descriptor
  }

  override fun remapDeclaredSimpleFunction(descriptor: FunctionDescriptor): FunctionDescriptor? {
    return descriptor.fqNameOrNull()?.let {
      context.referenceFunctions(it).map(IrFunctionSymbol::descriptor).singleOrNull {
        it.valueParameters.size == descriptor.valueParameters.size && it.valueParameters.mapIndexed { index, param ->
          val otherParam = descriptor.valueParameters[index]
          param.name == otherParam.name && param.type.getJetTypeFqName(true) == otherParam.type.getJetTypeFqName(true)
            && it.returnType?.getJetTypeFqName(true) == otherParam.returnType?.getJetTypeFqName(true)
        }.all(::id)
          && it.typeParameters.size == descriptor.typeParameters.size && it.typeParameters.mapIndexed { index, param ->
          val otherParam = descriptor.typeParameters[index]
          param.name == otherParam.name && param.defaultType.getJetTypeFqName(true) == otherParam.defaultType.getJetTypeFqName(
            true
          )
        }.all(::id)
    }
    }
  }

  override fun remapDeclaredTypeAlias(descriptor: TypeAliasDescriptor): TypeAliasDescriptor? {
    return descriptor.fqNameOrNull()?.let { context.referenceTypeAlias(it)?.descriptor }
  }
}
