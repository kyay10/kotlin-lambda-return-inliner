/*
 * Copyright (C) 2020 Brian Norman
 * Copyright (C) 2021 Youssef Shoaib
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate", "ReplaceNotNullAssertionWithElvisReturn")

package io.github.kyay10.kotlinlambdareturninliner

import io.github.kyay10.kotlinlambdareturninliner.internal.inline
import io.github.kyay10.kotlinlambdareturninliner.utils.*
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.contracts.parsing.isContractCallDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrReturnableBlockSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.types.typeUtil.isNothingOrNullableNothing
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

val INLINE_INVOKE_FQNAME = FqName(BuildConfig.INLINE_INVOKE_FQNAME)
val CONTAINS_COPIED_DECLARATIONS_ANNOTATION_FQNAME = FqName(BuildConfig.CONTAINS_COPIED_DECLARATIONS_ANNOTATION_FQNAME)

class LambdaReturnInlinerIrGenerationExtension(
  private val project: Project,
  private val messageCollector: MessageCollector,
  private val compilerConfig: CompilerConfiguration,
  private val renamesMap: MutableMap<String, String>
) : IrGenerationExtension {

  var shadowsContext: IrPluginContext? = null

  @OptIn(ExperimentalStdlibApi::class, ObsoleteDescriptorBasedAPI::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    if (compilerConfig[IS_GENERATING_SHADOWED_DECLARATIONS] == true) {
      shadowsContext = pluginContext
    }
    //println(moduleFragment.dump())
    //println(moduleFragment.dumpKotlinLike())
    val tempsToAdd: MutableMap<IrFunction, MutableList<IrVariable>> = mutableMapOf()
    val deferredAddedFunctions: MutableMap<IrFunction, IrFile> = mutableMapOf()

    val transformer = InlineHigherOrderFunctionsAndVariablesTransformer(pluginContext, renamesMap, shadowsContext)
    moduleFragment.lowerWith(transformer)
    moduleFragment.lowerWith(
      CollapseContainerExpressionsReturningFunctionExpressionsTransformer(
        pluginContext
      )
    )
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        if (expression.operator == IMPLICIT_COERCION_TO_UNIT && expression.argument.type.isFunctionTypeOrSubtype()) {
          expression.argument.replaceLastElementAndIterateBranches({ replacer, statement ->
            expression.argument = replacer(statement).cast()
          }, expression.argument.type) {
            if (it is IrFunctionExpression)
              declarationIrBuilder.irNull()
            else it
          }
        }
        return super.visitTypeOperator(expression)
      }
    })
    // Reinterpret WhenWithMapper
    moduleFragment.lowerWith(object :
      IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitWhen(expression: IrWhen): IrExpression {
        var returnExpression: IrExpression = expression
        if (expression is IrWhenWithMapper) {
          val elseBranch = expression.branches.firstIsInstanceOrNull<IrElseBranch>()
          returnExpression = (if (elseBranch != null) declarationIrBuilder.createWhenAccessForLambda(
            expression.type,
            expression.tempInt,
            expression.mapper,
            context,
            elseBranch
          ) else declarationIrBuilder.createWhenAccessForLambda(
            expression.type,
            expression.tempInt,
            expression.mapper,
            context,
          )).takeIf { it !is IrWhenWithMapper }
            ?: expression // Only reinterpret the IrWhenWithMapper if it would become a different kind of IrExpression
        }
        super.visitExpression(returnExpression)
        return returnExpression
      }
    })

    // Some reinterpreted whens will now contain run calls that have their block argument surrounded with
    // an IrContainerExpression, and so just ensure that they're collapsed so that they get inlined property
    moduleFragment.lowerWith(
      CollapseContainerExpressionsReturningFunctionExpressionsTransformer(
        pluginContext
      )
    )
    val variableToConstantValue = mutableMapOf<IrValueSymbol, IrExpression>()
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitSetValue(expression: IrSetValue): IrExpression {
        val result = super.visitSetValue(expression)
        if (expression.symbol.owner.isImmutable) {
          val trueValue = expression.value.lastElement().safeAs<IrExpression>()?.extractFromReturnIfNeeded()
          if (trueValue is IrGetEnumValue) {
            variableToConstantValue[expression.symbol] = trueValue
          }
          if (trueValue is IrConst<*>) {
            variableToConstantValue[expression.symbol] = trueValue
          }
        }
        return result
      }

      override fun visitVariable(declaration: IrVariable): IrStatement {
        val result = super.visitVariable(declaration)
        if (!declaration.isVar) {
          val trueValue = declaration.initializer?.lastElement()?.safeAs<IrExpression>()?.extractFromReturnIfNeeded()
          if (trueValue is IrGetEnumValue) {
            variableToConstantValue[declaration.symbol] = trueValue
          }
          if (trueValue is IrConst<*>) {
            variableToConstantValue[declaration.symbol] = trueValue
          }
        }
        return result
      }

      override fun visitGetValue(expression: IrGetValue): IrExpression {
        return variableToConstantValue[expression.symbol]?.deepCopyWithSymbols(
          currentDeclarationParent,
          ::WhenWithMapperAwareDeepCopyIrTreeWithSymbols
        ) ?: super.visitGetValue(expression)
      }

      override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        if (expression.isInvokeCall) {
          expression.dispatchReceiver.safeAs<IrFunctionExpression>()?.function?.let { function ->
            for (parameterIndex in 0 until expression.valueArgumentsCount) {
              val argument = expression.getValueArgument(parameterIndex)
              val parameterSymbol = function.valueParameters[parameterIndex].symbol
              val trueValue = argument?.lastElement()?.safeAs<IrExpression>()?.extractFromReturnIfNeeded()
              if (trueValue is IrGetEnumValue) {
                variableToConstantValue[parameterSymbol] = trueValue
              }
              if (trueValue is IrConst<*>) {
                variableToConstantValue[parameterSymbol] = trueValue
              }
            }
          }
        }
        return super.visitFunctionAccess(expression)
      }
    })

    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        return variableToConstantValue[expression.symbol]?.deepCopyWithSymbols(
          currentDeclarationParent,
          ::WhenWithMapperAwareDeepCopyIrTreeWithSymbols
        ) ?: super.visitGetValue(expression)
      }

      override fun visitWhen(expression: IrWhen): IrExpression {
        val result = super.visitWhen(expression)
        val branchIndicesToDelete = mutableListOf<Int>()
        val singlePossibleBranch = run {
          var previousBranchesAllFalse = true
          expression.branches.forEachIndexed { index, branch ->
            branch.condition.calculatePredeterminedEqualityIfPossible(
              context
            )?.let { predeterminedEquality ->
              if (predeterminedEquality) {
                if (previousBranchesAllFalse) {
                  return@run branch.result
                } else Unit
              } else branchIndicesToDelete.add(index)
            } ?: run { previousBranchesAllFalse = false }
          }
          null
        }
        if (singlePossibleBranch != null)
          return singlePossibleBranch
        for (branchIndexToDelete in branchIndicesToDelete) expression.branches.removeAt(branchIndexToDelete)
        return result
      }
    })
    moduleFragment.lowerWith(object :
      IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        tempsToAdd[declaration] = tempsToAdd[declaration] ?: mutableListOf()
        return super.visitFunctionNew(declaration)
      }

      override fun visitVariable(declaration: IrVariable): IrStatement {
        if (declaration.type.isFunctionTypeOrSubtype()) {
          declaration.initializer?.replaceLastElementAndIterateBranches({ replacer, statement ->
            declaration.initializer = replacer(statement).cast()
          }, declaration.type) {
            if (it is IrFunctionExpression)
              declarationIrBuilder.irNull() // TODO: add configuration option for this and allow its usage for non-inline usages
            else it
          }
        }
        return if (declaration.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || declaration.isVar) {
          super.visitVariable(declaration)
          declarationIrBuilder.irBlock {
            tempsToAdd[allScopes.first { it.irElement is IrFunction }.irElement.cast()]?.add(declaration)
            declaration.initializer?.let {
              +irSet(declaration.symbol, it)
              declaration.initializer = null
            }
          }
        } else super.visitVariable(declaration)
      }
    })
    moduleFragment.lowerWith(object :
      IrFileTransformerVoidWithContext(pluginContext) {
      override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        tempsToAdd[declaration]?.takeIf { it.isNotEmpty() }
          ?.let {
            declaration.body.cast<IrBlockBody>().statements.addAll(0, it)
            declaration.body?.patchDeclarationParents(declaration)

            // For some bizarre reason, if an IrVariable has origin IR_TEMPORARY_VARIABLE, then, because it's
            // accessed from inside lambdas, it stays as an ObjectRef or the corresponding primitive Ref.
            // For IrVariables without that origin, however, the ObjectRef gets optimised away, and that
            // optimisation is great for performance, so we change the origin here.
            //it.forEach { variable -> variable.origin = IrDeclarationOrigin.DEFINED }
          }
        return super.visitFunctionNew(declaration)
      }
    })
    moduleFragment.lowerWith(InlineInvokeTransformer(pluginContext, deferredAddedFunctions))
    for ((deferredAddedFunction, file) in deferredAddedFunctions) {
      file.declarations.add(deferredAddedFunction)
      deferredAddedFunction.parent = file
    }

    moduleFragment.files.removeIf { file ->
      file.annotations.any { it.isAnnotationWithEqualFqName(CONTAINS_COPIED_DECLARATIONS_ANNOTATION_FQNAME) }
    }
//    println(moduleFragment.dump()) //TODO: only dump in debug mode
//    println(moduleFragment.dumpKotlinLike())
  }
}

fun IrExpression.extractFromReturnIfNeeded(): IrExpression =
  if (this is IrReturn && this.returnTargetSymbol is IrReturnableBlockSymbol) value.lastElement().cast<IrExpression>()
    .extractFromReturnIfNeeded() else this

fun IrExpression.calculatePredeterminedEqualityIfPossible(context: IrPluginContext): Boolean? {
  val trueElement = lastElement().cast<IrExpression>().extractFromReturnIfNeeded()
  if (trueElement is IrConst<*> && trueElement.kind == IrConstKind.Boolean) return trueElement.cast<IrConst<Boolean>>().value
  if (trueElement is IrCall && (trueElement.symbol == context.irBuiltIns.eqeqSymbol || trueElement.symbol == context.irBuiltIns.eqeqeqSymbol)) {
    val lhs = trueElement.getValueArgument(0)?.extractCompileTimeConstantFromIrGetIfPossible()
    val rhs = trueElement.getValueArgument(1)?.extractCompileTimeConstantFromIrGetIfPossible()
    if (lhs is IrGetEnumValue && rhs is IrGetEnumValue) return lhs.symbol == rhs.symbol
    if (lhs is IrConst<*> && rhs is IrConst<*>) return lhs.value == rhs.value
  }
  return null
}

fun IrExpression.extractCompileTimeConstantFromIrGetIfPossible(): IrExpression =
  safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()
    ?.takeIf { !it.isVar && it.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE }?.initializer?.lastElement()
    ?.safeAs<IrExpression>()?.extractFromReturnIfNeeded()?.extractCompileTimeConstantFromIrGetIfPossible() ?: this

class CollapseContainerExpressionsReturningFunctionExpressionsTransformer(pluginContext: IrPluginContext) :
  IrFileTransformerVoidWithContext(pluginContext) {
  val functionAccesses = mutableListOf<IrExpression>()

  @OptIn(ExperimentalStdlibApi::class)
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    val result = expression.run {
      if (expression in functionAccesses)
        return@run expression
      val params = buildList {
        add(dispatchReceiver)
        add(extensionReceiver)
        for (i in 0 until valueArgumentsCount) add(getValueArgument(i))
      }
      val lambdaParamsWithBlockArgument = params.withIndex().filterNotNull()
        .filter { it.value.type.isFunctionTypeOrSubtype() && it.value !is IrFunctionExpression }
      if (lambdaParamsWithBlockArgument.isNotEmpty()) {
        declarationIrBuilder.irBlock {
          for ((index, value) in lambdaParamsWithBlockArgument) {
            val newArgumentValue = value.lastElement() as IrExpression
            accumulateStatementsExceptLast(value).forEach { +it }
            expression.changeArgument(index, newArgumentValue)
          }
          +expression
          functionAccesses.add(expression)
        }
      } else expression
    }
    return super.visitExpression(result)
  }
}

class InlineInvokeTransformer(
  pluginContext: IrPluginContext,
  private val deferredAddedFunctions: MutableMap<IrFunction, IrFile>
) : IrFileTransformerVoidWithContext(pluginContext) {
  // Basically just searches for this function:
  // ```
  // inline fun <R> inlineInvoke(block: () -> R): R {
  //  return block()
  //}
  //```
  private val inlineInvoke0 = context.referenceFunctions(INLINE_INVOKE_FQNAME)
    .first {
      it.owner.let { irFunction ->
        irFunction.valueParameters.size == 1 &&
          irFunction.typeParameters.size == 1 &&
          irFunction.returnType.cast<IrSimpleType>().classifier.owner == irFunction.typeParameters.first() &&
          irFunction.isInline &&
          irFunction.valueParameters.first().type.isFunctionTypeOrSubtype()
      }
    }.owner

  // Initialize with the magic number 22 since those are the most common arities.
  private val inlineInvokeDefs = ArrayList<IrSimpleFunction?>(22)

  @OptIn(ExperimentalStdlibApi::class)
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    var returnExpression: IrExpression = expression
    val irFunction = expression.symbol.owner
    // Only replace invoke calls
    if (expression.isInvokeCall) {
      val replacementInlineInvokeFunction =
        inlineInvokeDefs.getOrNull(expression.valueArgumentsCount)
          ?: inlineInvoke0.parent.cast<IrDeclaration>().factory.buildFun {
            updateFrom(inlineInvoke0)
            name = inlineInvoke0.name
            returnType = inlineInvoke0.returnType
            containerSource = null
          }.apply {
            annotations = listOf(
              declarationIrBuilder.irCallConstructor(
                context.referenceConstructors(
                  JVM_SYNTHETIC_ANNOTATION_FQ_NAME
                ).first(), emptyList()
              )
            )
            val initialTypeParameter = inlineInvoke0.typeParameters[0].deepCopyWithSymbols(this)
            typeParameters = buildList(expression.valueArgumentsCount) {
              add(initialTypeParameter)
              for (i in 1..expression.valueArgumentsCount) {
                add(buildTypeParameter(this@apply) {
                  updateFrom(initialTypeParameter)
                  index = i
                  name = Name.identifier(
                    currentScope!!.scope.inventNameForTemporary(
                      prefix = "type",
                      nameHint = i.toString()
                    )
                  )
                  superTypes.addAll(initialTypeParameter.superTypes)
                })
              }
            }
            val initialValueParameter = inlineInvoke0.valueParameters[0].deepCopyWithSymbols(this).apply {
              type = context.irBuiltIns.functionN(expression.valueArgumentsCount)
                .typeWith(
                  typeParameters.drop(1).map { it.createSimpleType() } + initialTypeParameter.createSimpleType())
            }
            valueParameters = buildList(expression.valueArgumentsCount) {
              add(initialValueParameter)
              for (i in 1..expression.valueArgumentsCount)
                add(buildValueParameter(this@apply) {
                  index = i
                  name = Name.identifier(
                    currentScope!!.scope.inventNameForTemporary(
                      prefix = "param",
                      nameHint = i.toString()
                    )
                  )
                  type = typeParameters[i].createSimpleType()
                })
            }
            body = declarationIrBuilder(symbol = symbol).irBlockBody {
              +irReturn(irCall(irFunction).apply {
                dispatchReceiver = irGet(valueParameters[0])
                for (valueParameter in valueParameters.drop(1))
                  putValueArgument(valueParameter.index - 1, irGet(valueParameter))
              })
            }
          }
            .also {
              while (inlineInvokeDefs.size <= expression.valueArgumentsCount) inlineInvokeDefs.add(null)
              inlineInvokeDefs[expression.valueArgumentsCount] = it
              deferredAddedFunctions[it] = file
            }
      // Without this implicit cast certain intrinsic replacement functions don't get replaced.
      // For example, this happened while initially testing inlining for ScopingFunctionsAndNulls because one of the
      // calls was adding the string result of an invoke to another string which caused the compiler to not replace that
      // String.plus(Object) with the Intrinsics.stringPlus call (which caused a very unique NoSuchMethodException that
      // was nowhere else to be found on the internet). I figured out the idea of using an implicit cast because
      // .apply{} on the string result caused the code to work, which (if you look at what FunctionInlining does) makes
      // an implicit cast if the real return type of the function isn't the same as the expected one.
      // TL;DR: Don't delete this implicit cast no matter what!
      returnExpression = declarationIrBuilder.run {
        typeOperator(
          expression.type, irCall(
            replacementInlineInvokeFunction
          ).apply {
            putValueArgument(0, expression.dispatchReceiver)
            putTypeArgument(0, expression.type)
            for (i in 1..expression.valueArgumentsCount) {
              val currentValueArgument = expression.getValueArgument(i - 1)
              putValueArgument(i, currentValueArgument)
              putTypeArgument(i, currentValueArgument?.type)
            }
          }, IMPLICIT_CAST, expression.type
        )
      }
    }
    return super.visitExpression(returnExpression)
  }
}

class InlineHigherOrderFunctionsAndVariablesTransformer(
  context: IrPluginContext,
  private val renamesMap: MutableMap<String, String>,
  val shadowsContext: IrPluginContext?
) : IrFileTransformerVoidWithContext(context) {

  private val varMap: MutableMap<IrVariable, IrExpression> = mutableMapOf()
  override fun visitVariable(declaration: IrVariable): IrStatement {
    val result = super.visitVariable(declaration)
    val initializerLastElement = declaration.initializer?.lastElement()
    if (declaration.type.isFunctionTypeOrSubtype() && (initializerLastElement is IrWhenWithMapper || initializerLastElement is IrFunctionExpression)) declaration.initializer?.let {
      varMap.put(
        declaration,
        it
      )
    }
    return result
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    val argument = expression.argument
    val initializer = argument.safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()?.initializer?.lastElement()
    if ((argument is IrGetValue && varMap[argument.symbol.owner.safeAs()] != null && initializer is IrWhenWithMapper) || argument is IrWhenWithMapper) {
      with(declarationIrBuilder) {
        val irWhenWithMapper: IrWhenWithMapper = when (argument) {
          is IrGetValue -> initializer.cast()
          is IrWhenWithMapper -> argument
          else -> throw IllegalStateException() // literally will never happen
        }
        val elseBranch: IrElseBranch
        val newMapper: MutableList<IrExpression?> = ArrayList(irWhenWithMapper.mapper.size)
        when (val operator = expression.operator) {
          CAST, IMPLICIT_CAST, IMPLICIT_DYNAMIC_CAST, REINTERPRET_CAST, SAFE_CAST, IMPLICIT_NOTNULL -> {
            irWhenWithMapper.mapper.forEachIndexed { index, value ->
              // We erase the types params because, to be honest, it really doesn't matter whether we're invoking
              // a Function0<String?>, a Function0<String> or even a Function0<Any> at the same exact time.
              // The type checking should be taken care of by the previous compilation steps,
              // and so we're just trying to ensure that we have the same function type that's all
              newMapper.add(
                index,
                value?.takeIf { it.type.erasedUpperBound.isSubclassOf(expression.typeOperand.erasedUpperBound) })
            }
            elseBranch = irElseBranch(
              when (operator) {
                SAFE_CAST -> irNull()
                IMPLICIT_NOTNULL -> irCall(
                  context.irBuiltIns.checkNotNullSymbol // Throwing an NPE isn't directly exposed, and so instead we "check" if null is not null, therefore always throwing an NPE
                ).apply {
                  putTypeArgument(0, context.irBuiltIns.nothingType)
                  putValueArgument(0, irNull())
                }
                else -> irCall(context.irBuiltIns.throwCceSymbol)
              }
            )
          }
          INSTANCEOF, NOT_INSTANCEOF -> {
            val valueIfInstance = irBoolean(operator == INSTANCEOF)
            val valueIfNotInstance: IrExpression = irBoolean(!valueIfInstance.value)
            irWhenWithMapper.mapper.forEachIndexed { index, value ->
              newMapper.add(
                index,
                value?.takeIf { it.type.erasedUpperBound.isSubclassOf(expression.typeOperand.erasedUpperBound) }
                  ?.let { valueIfInstance })
            }
            elseBranch = irElseBranch(valueIfNotInstance)
          }
          else -> {
            return super.visitTypeOperator(expression)
          }
        }
        return createWhenAccessForLambda(
          expression.type,
          irWhenWithMapper.tempInt,
          newMapper,
          this@InlineHigherOrderFunctionsAndVariablesTransformer.context,
          elseBranch
        )

      }
    } else {
      return super.visitTypeOperator(expression)
    }
  }

  override fun visitGetValue(expression: IrGetValue): IrExpression {
    expression.symbol.owner.safeAs<IrVariable>()?.let { requestedVar ->
      varMap[requestedVar]?.let { initializer ->
        return declarationIrBuilder.irComposite {
          +(initializer.lastElement().deepCopyWithSymbols(parent, ::WhenWithMapperAwareDeepCopyIrTreeWithSymbols))
        }//.also { super.visitExpression(it) }
      }
    }
    return super.visitGetValue(expression)
  }

  private val inlinedFunctionAccesses = mutableListOf<IrExpression>()

  @OptIn(ExperimentalStdlibApi::class, ObsoleteDescriptorBasedAPI::class)
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    if (expression.symbol.descriptor.isContractCallDescriptor())
      return declarationIrBuilder.irUnit() // Just don't bother at all with any contract call
    val result = run {
      if (expression in inlinedFunctionAccesses) return@run expression
      var returnExpression: IrExpression = expression
      val originalFunction = expression.symbol.owner
      val allParams =
        buildList {
          add(expression.dispatchReceiver)
          add(expression.extensionReceiver)
          for (i in 0 until expression.valueArgumentsCount) add(expression.getValueArgument(i))
        }
      val paramsNeedInlining = allParams.filter {
        (it?.type?.isFunctionTypeOrSubtype() ?: false)
      }.any {
        val index = allParams.indexOf(it)
        index < 2 || originalFunction.valueParameters[index - 2].type.let { type -> type is IrDynamicType || type.isNullable() }
      }

      if (originalFunction.isInline && (paramsNeedInlining || expression.type.isFunctionTypeOrSubtype())) {
        val mapper = mutableListOf<IrExpression>()
        var isFirstReturn = true
        returnExpression =
          declarationIrBuilder.irBlock {
            at(expression)
            val inlinedVersion = inline(
              expression,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.allScopes,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.currentScope!!,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.context,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.shadowsContext,
              renamesMap
            )

            if (expression.type.isFunctionTypeOrSubtype()) {
              lateinit var originalType: IrType
              val temp = irTemporary(
                nameHint = "inlinedHigherOrderFunction${expression.symbol.owner.name}",
                irType = context.irBuiltIns.intType,
                value = irComposite {
                  inlinedVersion.also {
                    originalType = it.type
                    it.type = context.irBuiltIns.intType
                    it.safeAs<IrReturnableBlock>()?.let {
                      @OptIn(ObsoleteDescriptorBasedAPI::class)
                      @Suppress("NestedLambdaShadowedImplicitParameter") // Intentional
                      it.transformChildrenVoid(object : IrElementTransformerVoid() {

                        override fun visitReturn(expression: IrReturn): IrExpression {
                          if (expression.returnTargetSymbol != it.symbol)
                            return super.visitReturn(expression)
                          if (isFirstReturn) {
                            isFirstReturn = false
                          }
                          expression.value.replaceLastElementAndIterateBranches({ replacer, irStatement ->
                            expression.value = replacer(irStatement).cast()
                          }, newWhenType = context.irBuiltIns.intType) {
                            val lambdaNumber = mapper.size
                            mapper.add(it.cast())
                            irInt(lambdaNumber)
                          }
                          expression.value.type = context.irBuiltIns.intType
                          return super.visitReturn(expression)
                        }
                      })
                      +inlinedVersion
                    } ?: it.let {
                      it.type = originalType
                      val lambdaNumber = mapper.size
                      val returnedValueTemporary = irTemporary(
                        nameHint = "returnedValue$lambdaNumber",
                        value = it,
                        irType = originalType
                      )
                      mapper.add(irGet(returnedValueTemporary))

                      +irInt(lambdaNumber)
                    }
                  }
                })
              +createWhenAccessForLambda(
                originalType,
                temp,
                mapper.map {
                  it.transform(this@InlineHigherOrderFunctionsAndVariablesTransformer, null)
                },
                this@InlineHigherOrderFunctionsAndVariablesTransformer.context
              )
            } else {
              +inlinedVersion
            }
            inlinedFunctionAccesses.add(inlinedVersion)
          }
      } else if (expression.type == context.irBuiltIns.booleanType && (originalFunction.symbol == context.irBuiltIns.eqeqSymbol || originalFunction.symbol == context.irBuiltIns.eqeqeqSymbol)) {
        val constArgument = allParams.firstIsInstanceOrNull<IrConst<*>>()

        val irWhenWithMapperArgument: IrWhenWithMapper? = allParams.firstOrNull { argument ->
          val initializer =
            argument.safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()?.initializer?.lastElement()
          (argument is IrGetValue && varMap[argument.symbol.owner.safeAs()] != null && initializer is IrWhenWithMapper) || argument is IrWhenWithMapper
        }?.let { argument ->
          val initializer =
            argument.safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()?.initializer?.lastElement()
          when (argument) {
            is IrGetValue -> initializer.cast()
            is IrWhenWithMapper -> argument
            else -> throw IllegalStateException() // literally will never happen
          }
        }
        if (constArgument != null && irWhenWithMapperArgument != null) {
          val newMapper = irWhenWithMapperArgument.mapper.map {
            val realValue = it?.lastElement()
            if (realValue is IrConst<*>)
              declarationIrBuilder.irBoolean(realValue.value == constArgument.value)
            else if (realValue is IrExpression && (constArgument.type.isSubtypeOf(
                realValue.type,
                IrTypeSystemContextImpl(context.irBuiltIns)
              ) || (constArgument.type.isNullable() && realValue.type.isNullable()))
            )
              declarationIrBuilder.irEquals(realValue, constArgument)
            else declarationIrBuilder.irFalse()
          }
          returnExpression = declarationIrBuilder.createWhenAccessForLambda(
            context.irBuiltIns.booleanType,
            irWhenWithMapperArgument.tempInt,
            newMapper,
            this@InlineHigherOrderFunctionsAndVariablesTransformer.context,
          )
        }
      }
      return@run returnExpression
    }

    super.visitExpression(result)
    return result
  }
}

@OptIn(ExperimentalStdlibApi::class, ObsoleteDescriptorBasedAPI::class)
private fun IrBuilderWithScope.createWhenAccessForLambda(
  originalType: IrType,
  tempInt: IrVariable,
  mapper: List<IrExpression?>,
  context: IrPluginContext,
  elseBranch: IrElseBranch = irElseBranch(irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol))
): IrExpression {
  val copiedMapper = mapper.map {
    it?.deepCopyWithSymbols(
      parent,
      createCopier = ::WhenWithMapperAwareDeepCopyIrTreeWithSymbols
    )
  }
  val fallBackWhenImpl by lazy {
    irWhenWithMapper(originalType, tempInt, copiedMapper, buildList {
      copiedMapper.forEachIndexed { index, irExpression ->
        if (irExpression != null) {
          add(
            irBranchWithMapper(
              irEquals(irGet(tempInt), irInt(index)),
              irExpression
            )
          )
        }
      }
      add(elseBranch)
    })
  }
  if (!originalType.isFunctionTypeOrSubtype())
    return fallBackWhenImpl

  val firstFunctionExpression =
    copiedMapper.map { it?.lastElement() }.filterIsInstance<IrFunctionExpression>().takeIf { functionExpressions ->
      functionExpressions.firstOrNull()?.let { firstFunctionExpression ->
        functionExpressions.all { functionExpression -> functionExpression.function.valueParameters.size == firstFunctionExpression.function.valueParameters.size }
      } == true
    }?.first()
  val originalFun =
    firstFunctionExpression?.function ?: return fallBackWhenImpl
  if (copiedMapper.any {
      // Not erasing the type parameters caused some issues specifically in ComplexLogic where a Function0<String>
      // was deemed incompatible with a Function0<String?> simply because the latter isn't strictly a subtype of
      // the former, but like that really does not matter since we just care about them both having the same function
      // arity and that's it, and so erasing the type params does the job well!
      it?.type?.erasedUpperBound?.isSubclassOf(
        firstFunctionExpression.type.erasedUpperBound
      ) == false && !it.type.toKotlinType().isNothingOrNullableNothing()
    }) {
    return fallBackWhenImpl
  }
  // Now we're sure that all the expressions in mapper are the same function type or an expression returning Nothing
  // or null, and so we can now safely assume that they all have the same number of arguments for invoke
  val createdFunction = context.irFactory.buildFun {
    updateFrom(originalFun)
    name = originalFun.name
    returnType = originalFun.returnType
  }

  createdFunction.apply {
    patchDeclarationParents(originalFun.parent)
    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
    originalFun.valueParameters.map { it.deepCopyWithSymbols(this) }
      .let { valueParameters = it }
    originalFun.extensionReceiverParameter?.deepCopyWithSymbols(this)
      .let { extensionReceiverParameter = it }
    originalFun.dispatchReceiverParameter?.deepCopyWithSymbols(this)
      .let { dispatchReceiverParameter = it }
    body = DeclarationIrBuilder(this@createWhenAccessForLambda.context, createdFunction.symbol).irBlockBody {
      +irReturn(irWhenWithMapper(originalFun.returnType, tempInt, copiedMapper, buildList {
        copiedMapper.forEachIndexed { index, irExpression ->
          if (irExpression != null) {
            irExpression.patchDeclarationParents(createdFunction)
            add(irBranchWithMapper(irEquals(irGet(tempInt), irInt(index)),
              if (irExpression is IrFunctionExpression) {
                irCall(context.referenceFunctions(kotlinPackageFqn.child(Name.identifier("run")))
                  .first { it.owner.dispatchReceiverParameter == null }).apply {

                  val lambdaReturnType = originalFun.returnType
                  putTypeArgument(0, lambdaReturnType)
                  putValueArgument(
                    0,
                    irExpression.safeAs<IrFunctionExpression>()?.apply {
                      type = context.irBuiltIns.functionN(0).typeWith(lambdaReturnType)
                      function.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
                        override fun visitGetValue(expression: IrGetValue): IrExpression {
                          function.valueParameters.indexOfOrNull(expression.symbol.owner)?.let {
                            return irGet(createdFunction.valueParameters[it])
                          }
                          return super.visitGetValue(expression)
                        }
                      })
                      function.valueParameters = listOf()
                    } ?: irExpression)
                }
              } else {
                irCall(
                  originalType.classOrNull?.functions?.filter { it.owner.name == OperatorNameConventions.INVOKE && it.owner.extensionReceiverParameter == null && it.owner.valueParameters.size == originalFun.valueParameters.size }!!
                    .first()
                ).apply {
                  dispatchReceiver = irExpression
                  valueParameters.map { irGet(it) }.forEachIndexed { index, irGetValue ->
                    putValueArgument(index, irGetValue)
                  }
                }
              }

            ))
          }
        }
        add(elseBranch)
      }))
    }
  }
  return IrFunctionExpressionImpl(
    startOffset,
    endOffset,
    originalType,
    createdFunction,
    IrStatementOrigin.LAMBDA
  )
}

