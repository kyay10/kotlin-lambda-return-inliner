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

import com.github.kyay10.kotlinlambdareturninliner.internal.accumulateStatementsExceptLast
import com.github.kyay10.kotlinlambdareturninliner.internal.inline
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File

val INLINE_INVOKE_FQNAME = FqName("com.github.kyay10.kotlinlambdareturninliner.inlineInvoke")

open class IrFileTransformerVoidWithContext : IrElementTransformerVoidWithContext(), FileLoweringPass {
  protected lateinit var file: IrFile
  protected lateinit var fileSource: String
  override fun lower(irFile: IrFile) {
    file = irFile
    fileSource = File(irFile.path).readText()
      .replace("\r\n", "\n") // https://youtrack.jetbrains.com/issue/KT-41888

    irFile.transformChildrenVoid()
  }
}

class LambdaReturnInlinerIrGenerationExtension(
  private val messageCollector: MessageCollector,
) : IrGenerationExtension {
  @OptIn(ExperimentalStdlibApi::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    //println(moduleFragment.dump())
    val tempsToAdd: MutableMap<IrFunction, MutableList<IrVariable>> = mutableMapOf()
    val deferredAddedFunctions: MutableMap<IrFunction, IrFile> = mutableMapOf()
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext() {
      // Basically just searches for this function:
      // ```
      // inline fun <R> inlineInvoke(block: () -> R): R {
      //  return block()
      //}
      //```
      val inlineInvoke0 = pluginContext.referenceFunctions(INLINE_INVOKE_FQNAME)
        .first {
          it.owner.let { irFunction ->
            irFunction.valueParameters.size == 1 &&
              irFunction.typeParameters.size == 1 &&
              irFunction.returnType.cast<IrSimpleType>().classifier.owner == irFunction.typeParameters.first() &&
              irFunction.isInline &&
              irFunction.valueParameters.first().type.isFunctionTypeOrSubtype()
          }
        }.owner

      val inlineInvokeDefs = mutableListOf<IrSimpleFunction?>()//inlineInvoke0)

      override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        var returnExpression = expression
        val irFunction = expression.symbol.owner
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
                annotations = listOf(DeclarationIrBuilder(pluginContext, currentScope!!.scope.scopeOwnerSymbol).irCallConstructor(pluginContext.referenceConstructors(
                  JVM_SYNTHETIC_ANNOTATION_FQ_NAME
                ).first(), emptyList()))
                val initialTypeParameter = inlineInvoke0.typeParameters[0].deepCopyWithSymbols(this)
                typeParameters = initialTypeParameter + buildList {
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
                valueParameters = inlineInvoke0.valueParameters[0].deepCopyWithSymbols(this).apply {
                  type = pluginContext.irBuiltIns.function(expression.valueArgumentsCount)
                    .typeWith(
                      typeParameters.drop(1).map { it.createSimpleType() } + initialTypeParameter.createSimpleType())
                } + buildList {
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
                body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                  +irReturn(irCall(irFunction).apply {
                    dispatchReceiver = irGet(valueParameters[0])
                    for (valueParameter in valueParameters.drop(1))
                      putValueArgument(valueParameter.index - 1, irGet(valueParameter))
                  })
                }
              }.also {
                while(inlineInvokeDefs.size <= expression.valueArgumentsCount) inlineInvokeDefs.add(null)
                inlineInvokeDefs[expression.valueArgumentsCount] = it
                deferredAddedFunctions[it] = file
              }
          returnExpression = DeclarationIrBuilder(pluginContext, currentScope!!.scope.scopeOwnerSymbol).irCall(
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
    })

    for((deferredAddedFunction, file) in deferredAddedFunctions) {
      file.declarations.add(deferredAddedFunction)
      deferredAddedFunction.parent = file
    }

    val transformer = InlineHigherOrderFunctionsAndVariablesTransformer(pluginContext)
    moduleFragment.lowerWith(transformer)
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext() {
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
            DeclarationIrBuilder(pluginContext, currentScope!!.scope.scopeOwnerSymbol).irComposite {
              for (param in lambdaParamsWithBlockArgument) {
                val newArgumentValue = param.value.lastElement() as IrExpression
                accumulateStatementsExceptLast(param.value).forEach { +it }
                when (param.index) {
                  0 -> {
                    expression.dispatchReceiver = newArgumentValue
                  }
                  1 -> {
                    expression.extensionReceiver = newArgumentValue
                  }
                  else -> {
                    expression.putValueArgument(param.index - 2, newArgumentValue)
                  }
                }
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
      IrFileTransformerVoidWithContext() {
      override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        tempsToAdd[declaration] = tempsToAdd[declaration] ?: mutableListOf()
        return super.visitFunctionNew(declaration)
      }

      override fun visitVariable(declaration: IrVariable): IrStatement {
        if (declaration.type.isFunctionTypeOrSubtype()) {
          declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
              super.visitFunctionExpression(expression)
              return DeclarationIrBuilder(
                pluginContext,
                currentScope!!.scope.scopeOwnerSymbol
              ).irBlock { +irNull() }
            }
          })
        }
        return if (declaration.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) {
          super.visitVariable(declaration)
          DeclarationIrBuilder(
            pluginContext,
            currentScope!!.scope.scopeOwnerSymbol
          ).irBlock {
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
      IrFileTransformerVoidWithContext() {
      /*
      override fun visitVariable(declaration: IrVariable): IrStatement {
        return if (declaration.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE && declaration.type.isFunctionTypeOrSubtype()) DeclarationIrBuilder(
          pluginContext,
          currentScope!!.scope.scopeOwnerSymbol
        ).irBlock {}
        else super.visitVariable(declaration)
      }*/

      override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        tempsToAdd[declaration]?.takeIf { it.isNotEmpty() }
          ?.let {
            declaration.body!!.cast<IrBlockBody>().statements.addAll(0, it)
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
    println(moduleFragment.dump())
  }
}


class InlineHigherOrderFunctionsAndVariablesTransformer(
  private val context: IrPluginContext
) : IrFileTransformerVoidWithContext() {

  private val varMap: MutableMap<IrVariable, IrExpression> = mutableMapOf()
  override fun visitVariable(declaration: IrVariable): IrStatement {
    if (declaration.type.isFunctionTypeOrSubtype()) declaration.initializer?.let { varMap.put(declaration, it) }
    return super.visitVariable(declaration)
  }

  override fun visitGetValue(expression: IrGetValue): IrExpression {
    expression.symbol.owner.safeAs<IrVariable>()?.let { requestedVar ->
      varMap[requestedVar]?.let { expr ->
        val getTemps = mutableListOf<IrValueDeclaration>()
        val setTemps = mutableListOf<IrValueDeclaration>()
        val result = expression.symbol.owner.safeAs<IrVariable>()?.initializer?.let { initializer ->
          DeclarationIrBuilder(
            context,
            currentScope!!.scope.scopeOwnerSymbol
          ).irComposite {
            +(initializer.lastElement())
              .apply {
                transform(object : IrElementTransformerVoid() {
                  override fun visitGetValue(expression: IrGetValue): IrExpression {
                    if (expression.symbol.owner.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) getTemps += expression.symbol.owner
                    return super.visitGetValue(expression)
                  }

                  override fun visitSetValue(expression: IrSetValue): IrExpression {
                    if (expression.symbol.owner.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) setTemps += expression.symbol.owner
                    return super.visitSetValue(expression)
                  }
                }, null)
              }.deepCopyWithSymbols(this.parent).apply {
                /*transform(object : IrElementTransformerVoid() {
                  var getIndex = 0
                  var setIndex = 0
                  override fun visitGetValue(expression: IrGetValue): IrExpression {
                    if (expression.symbol.owner.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) return irGet(
                      getTemps[getIndex++]
                    )
                    return super.visitGetValue(expression)
                  }

                  override fun visitSetValue(expression: IrSetValue): IrExpression {
                    if (expression.symbol.owner.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE) return irSet(
                      setTemps[setIndex++].symbol,
                      expression.value
                    )
                    return super.visitSetValue(expression)
                  }
                }, null)*/
              }
          }
        }?.also { super.visitExpression(it) } ?: super.visitGetValue(expression)
        return result
      }
    }
    return super.visitGetValue(expression)
  }

  override fun visitCall(expression: IrCall): IrExpression {
    return super.visitCall(expression)
  }

  val inlinedFunctionAccesses = mutableListOf<IrExpression>()

  @OptIn(ExperimentalStdlibApi::class)
  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
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
        it?.type?.isFunctionTypeOrSubtype() ?: false
      }.any {
        val index = allParams.indexOf(it)
        index < 2 || originalFunction.valueParameters[index - 2].type.let { type -> type is IrDynamicType || type.isNullable() }
      }

      if (/*originalFunction.isInline &&*/ (paramsNeedInlining || expression.type.isFunctionTypeOrSubtype())) {
        val mapper = mutableListOf<IrExpression>()
        returnExpression =
          DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol).irBlock {
            at(expression)
            val inlinedVersion = inline(
              expression,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.allScopes,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.currentScope!!.scope,
              this@InlineHigherOrderFunctionsAndVariablesTransformer.context
            )

            //irSet(tem.symbol, inlinedVersion)
            if (expression.type.isFunctionTypeOrSubtype()) {
              lateinit var originalType: IrType
              val temp = /*currentFunction!!.scope.*/irTemporary(
                nameHint = "inlinedHigherOrderFunction${expression.symbol.owner.name}",
                //isMutable = true,
                irType = context.irBuiltIns.intType
              )//.apply { tempsToAdd[currentFunction!!.irElement.cast()]?.add(this) }
              +irSet(temp.symbol, irComposite {
                +inlinedVersion
                inlinedVersion.also {
                  /*it.transformChildrenVoid(object : IrElementTransformerVoid() {
                override fun visitReturn(expression: IrReturn): IrExpression {
                  if (expression.type != it.type)
                    return super.visitReturn(expression)
                  return super.visitReturn(expression)
                }
              })*/
                  originalType = it.type
                  it.type = context.irBuiltIns.intType
                  val returnExp = it.safeAs<IrReturnableBlock>()?.statements?.last().safeAs<IrReturn>()
                  returnExp?.let {
                    returnExp.value = irInt(mapper.size).also {
                      mapper.add(returnExp.value)
                      accumulateStatementsExceptLast(returnExp.value).forEach { initStep ->
                        +initStep
                        initStep.patchDeclarationParents(parent)
                      }
                    }
                    returnExp.type = context.irBuiltIns.intType
                  }
                }
              })
              +createWhenAccessForLambda(
                originalType,
                temp,
                mapper,
                this@InlineHigherOrderFunctionsAndVariablesTransformer.context
              )

              //+irComposite { +irGet(temp) }
            } else {
              +inlinedVersion
            }
            inlinedFunctionAccesses.add(inlinedVersion)
          }
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
  mapper: MutableList<IrExpression>,
  context: IrPluginContext,
): IrExpression {
  if (!originalType.isFunctionTypeOrSubtype())
    return irWhen(originalType, buildList {
      mapper.forEachIndexed { index, irExpression ->
        add(irBranch(irEquals(irGet(tempInt), irInt(index)),
          irComposite { +irExpression }
        ))
      }
      add(irElseBranch(irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol)))
    })
  val originalFun =
    mapper.firstOrNull()?.lastElement().safeAs<IrFunctionExpression>()?.function ?: return irWhen(
      originalType,
      buildList {
        mapper.forEachIndexed { index, irExpression ->
          add(irBranch(irEquals(irGet(tempInt), irInt(index)),
            irComposite { +irExpression }
          ))
        }
        add(irElseBranch(irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol)))
      })
  //val originalFun: IrFunction = TODO()
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
      +irReturn(irWhen(originalFun.returnType, buildList {
        mapper.forEachIndexed { index, irExpression ->
          add(irBranch(irEquals(irGet(tempInt), irInt(index)),
            irComposite {
              +irCall(context.referenceFunctions(kotlinPackageFqn.child(Name.identifier("run")))
                .first { it.owner.dispatchReceiverParameter == null }).apply {
                val lambdaReturnType = originalFun.returnType
                putTypeArgument(0, lambdaReturnType)
                putValueArgument(
                  0,
                  irExpression.lastElement().safeAs<IrFunctionExpression>()?.apply {
                    type = context.irBuiltIns.function(0).typeWith(lambdaReturnType)
                    irExpression.patchDeclarationParents(createdFunction)
                    function.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
                      override fun visitGetValue(expression: IrGetValue): IrExpression {
                        function.valueParameters.indexOfOrNull(expression.symbol.owner)?.let {
                          return irGet(createdFunction.valueParameters[it])
                        }
                        return super.visitGetValue(expression)
                      }
                    })/*
                    accumulateStatementsExceptLast(irExpression).forEach {
                      +it
                      it.patchDeclarationParents(createdFunction)
                    }*/
                    function.valueParameters = listOf()
                  })
              }
            }
          ))
        }
        add(irElseBranch(irCall(context.irBuiltIns.noWhenBranchMatchedExceptionSymbol)))
      }))
    }
  }
  return IrFunctionExpressionImpl(
    startOffset,
    endOffset,
    originalType,
    createdFunction,
    IrStatementOrigin.LAMBDA
  ).also { it }
}

tailrec fun IrStatement.lastElement(): IrStatement =
  if (this !is IrContainerExpression) this else this.statements.last().lastElement()

public fun <T> List<T>.indexOfOrNull(element: T): Int? {
  return indexOf(element).takeIf { it >= 0 }
}

@Suppress("UNCHECKED_CAST")
public fun <T : Any> Iterable<IndexedValue<T?>>.filterNotNull(): List<IndexedValue<T>> {
  return filter { it.value != null } as List<IndexedValue<T>>
}

fun IrModuleFragment.lowerWith(pass: FileLoweringPass) = pass.lower(this)

/**
 * Returns a list containing the original element and then the given [collection].
 */
public operator fun <T> T.plus(collection: Collection<T>): List<T> {
  val result = ArrayList<T>(collection.size + 1)
  result.add(this)
  result.addAll(collection)
  return result
}

fun IrTypeParameter.createSimpleType(
  hasQuestionMark: Boolean = false,
  arguments: List<IrTypeArgument> = emptyList(),
  annotations: List<IrConstructorCall> = emptyList(),
  abbreviation: IrTypeAbbreviation? = null
): IrSimpleType = IrSimpleTypeImpl(symbol, hasQuestionMark, arguments, annotations, abbreviation)
