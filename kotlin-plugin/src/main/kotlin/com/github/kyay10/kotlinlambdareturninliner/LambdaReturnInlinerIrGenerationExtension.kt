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

package com.github.kyay10.kotlinlambdareturninliner

import com.github.kyay10.kotlinlambdareturninliner.internal.inline
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
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
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.cast

val INLINE_INVOKE_FQNAME = FqName("com.github.kyay10.kotlinlambdareturninliner.inlineInvoke")

class LambdaReturnInlinerIrGenerationExtension(
  private val project: Project,
  private val messageCollector: MessageCollector,
  private val compilerConfig: CompilerConfiguration
) : IrGenerationExtension {
  @OptIn(ExperimentalStdlibApi::class, ObsoleteDescriptorBasedAPI::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val tempsToAdd: MutableMap<IrFunction, MutableList<IrVariable>> = mutableMapOf()
    val deferredAddedFunctions: MutableMap<IrFunction, IrFile> = mutableMapOf()
    moduleFragment.lowerWith(InlineInvokeTransformer(pluginContext, deferredAddedFunctions))

    for ((deferredAddedFunction, file) in deferredAddedFunctions) {
      file.declarations.add(deferredAddedFunction)
      deferredAddedFunction.parent = file
    }

    val transformer = InlineHigherOrderFunctionsAndVariablesTransformer(pluginContext)
    moduleFragment.lowerWith(transformer)
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext(pluginContext) {
      val functionAccesses = mutableListOf<IrExpression>()
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
            declarationIrBuilder.irComposite {
              for (param in lambdaParamsWithBlockArgument) {
                val newArgumentValue = param.value.lastElement() as IrExpression
                accumulateStatementsExceptLast(param.value).forEach { +it }
                expression.changeArgument(param.index, newArgumentValue)
              }
              +expression
              functionAccesses.add(expression)
            }
          } else expression
        }
        return super.visitExpression(result)
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
          declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
              super.visitFunctionExpression(expression)
              return declarationIrBuilder.irBlock { +irNull() } // TODO: add configuration option for this and allow its usage for non-inline usages
            }
          })
        }
        return if (declaration.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) {
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
            it.forEach { variable -> variable.origin = IrDeclarationOrigin.DEFINED }
          }
        return super.visitFunctionNew(declaration)
      }
    })
    println(moduleFragment.dump()) //TODO: only dump in debug mode
    println(moduleFragment.dumpKotlinLike())
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
  private val inlineInvokeDefs = ArrayList<IrSimpleFunction?>(22)//inlineInvoke0)

  @OptIn(ExperimentalStdlibApi::class)
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    var returnExpression = expression
    val irFunction = expression.symbol.owner
    // Only replace invoke calls
    if (
      irFunction.name == OperatorNameConventions.INVOKE &&
      irFunction.safeAs<IrSimpleFunction>()?.isOperator == true &&
      expression.dispatchReceiver?.type?.isFunctionTypeOrSubtype() == true &&
      expression.extensionReceiver == null &&
      currentFunction?.irElement.safeAs<IrFunction>()?.fqNameWhenAvailable != INLINE_INVOKE_FQNAME
    ) {
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
              type = context.irBuiltIns.function(expression.valueArgumentsCount)
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
      returnExpression = declarationIrBuilder.irCall(
        replacementInlineInvokeFunction
      ).apply {
        putValueArgument(0, expression.dispatchReceiver)
        putTypeArgument(0, expression.type)
        for (i in 1..expression.valueArgumentsCount) {
          val currentValueArgument = expression.getValueArgument(i - 1)
          putValueArgument(i, currentValueArgument)
          putTypeArgument(i, currentValueArgument?.type)
        }
      }
    }
    return super.visitFunctionAccess(returnExpression)
  }
}

class InlineHigherOrderFunctionsAndVariablesTransformer(
  context: IrPluginContext
) : IrFileTransformerVoidWithContext(context) {

  private val varMap: MutableMap<IrVariable, IrExpression> = mutableMapOf()
  override fun visitVariable(declaration: IrVariable): IrStatement {
    if (declaration.type.isFunctionTypeOrSubtype()) declaration.initializer?.let { varMap.put(declaration, it) }
    return super.visitVariable(declaration)
  }

  override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
    val argument = expression.argument
    val initializer = argument.safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()?.initializer
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
              newMapper[index] = value?.takeIf { it.type.isSubtypeOf(expression.typeOperand, context.irBuiltIns) }
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
              newMapper[index] = value?.takeIf { it.type.isSubtypeOf(expression.typeOperand, context.irBuiltIns) }
                ?.let { valueIfInstance }
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
          +(initializer.lastElement().deepCopyWithSymbols(parent))
        }.also { super.visitExpression(it) }
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
        returnExpression =
          declarationIrBuilder.irBlock {
            at(expression)
            val inlinedVersion = inline(
              expression,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.allScopes,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.currentScope!!.scope,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.context
            )

            if (expression.type.isFunctionTypeOrSubtype()) {
              lateinit var originalType: IrType
              val temp = irTemporary(
                nameHint = "inlinedHigherOrderFunction${expression.symbol.owner.name}",
                irType = context.irBuiltIns.intType
              )
              +irSet(temp.symbol, irComposite {
                +inlinedVersion
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
                        expression.value.type = context.irBuiltIns.intType
                        expression.value.replaceLastElementAndIterateBranches({ replacer, irStatement ->
                          expression.value = replacer(irStatement).cast()
                        }) {
                          val lambdaNumber = mapper.size
                          mapper.add(it.cast())
                          irInt(lambdaNumber)
                        }
                        return super.visitReturn(expression)
                      }
                    })
                  }
                }
              })
              +createWhenAccessForLambda(
                originalType,
                temp,
                mapper,
                this@InlineHigherOrderFunctionsAndVariablesTransformer.context
              )
            } else {
              +inlinedVersion
            }
            inlinedFunctionAccesses.add(inlinedVersion)
          }
      } else if (expression.type == context.irBuiltIns.booleanType && (originalFunction.symbol == context.irBuiltIns.eqeqSymbol || originalFunction.symbol == context.irBuiltIns.eqeqeqSymbol)) {
      }
      return@run returnExpression
    }

    super.visitExpression(result)
    return result
  }
}

@OptIn(ExperimentalStdlibApi::class)
private fun IrBuilderWithScope.createWhenAccessForLambda(
  originalType: IrType,
  tempInt: IrVariable,
  mapper: List<IrExpression?>,
  context: IrPluginContext,
  elseBranch: IrElseBranch = irElseBranch(irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol))
): IrExpression {
  val fallBackWhenImpl by lazy {
    irWhenWithMapper(originalType, tempInt, mapper, buildList {
      mapper.forEachIndexed { index, irExpression ->
        if (irExpression != null) {
          add(irBranch(irEquals(irGet(tempInt), irInt(index)),
            irComposite { +irExpression }
          ))
        }
      }
      add(elseBranch)
    })
  }
  if (!originalType.isFunctionTypeOrSubtype())
    return fallBackWhenImpl

  //TODO: figure out how to correctly handle lambdas of different arities
  val originalFun =
    mapper.filterIsInstance<IrFunctionExpression>().takeIf { functionExpressions ->
      functionExpressions.firstOrNull()?.let { firstFunctionExpression ->
        functionExpressions.all { functionExpression -> functionExpression.function.valueParameters.size == firstFunctionExpression.function.valueParameters.size }
      } == true
    }?.first()?.function ?: return fallBackWhenImpl
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
      +irReturn(irWhenWithMapper(originalFun.returnType, tempInt, mapper, buildList {
        mapper.forEachIndexed { index, irExpression ->
          if (irExpression != null) {
            add(irBranch(irEquals(irGet(tempInt), irInt(index)),
              irComposite {
                +irCall(context.referenceFunctions(kotlinPackageFqn.child(Name.identifier("run")))
                  .first { it.owner.dispatchReceiverParameter == null }).apply {
                  val lambdaReturnType = originalFun.returnType
                  putTypeArgument(0, lambdaReturnType)
                  putValueArgument(
                    0,
                    irExpression.safeAs<IrFunctionExpression>()?.apply {
                      type = context.irBuiltIns.function(0).typeWith(lambdaReturnType)
                      irExpression.patchDeclarationParents(createdFunction)
                      function.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
                        override fun visitGetValue(expression: IrGetValue): IrExpression {
                          function.valueParameters.indexOfOrNull(expression.symbol.owner)?.let {
                            return irGet(createdFunction.valueParameters[it])
                          }
                          return super.visitGetValue(expression)
                        }
                      })
                      function.valueParameters = listOf()
                    })
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

