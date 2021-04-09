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
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.jvm.JvmGeneratorExtensions
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.contracts.parsing.isContractCallDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.jvm.JvmLibraryResolver
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.library.UnresolvedLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.library.resolver.impl.libraryResolver
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.psi2ir.generators.GeneratorExtensions
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.resolve.jvm.annotations.JVM_SYNTHETIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.util.Logger
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addToStdlib.cast
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

val INLINE_INVOKE_FQNAME = FqName("com.github.kyay10.kotlinlambdareturninliner.inlineInvoke")

class LambdaReturnInlinerIrGenerationExtension(
  val project: Project,
  private val messageCollector: MessageCollector,
  private val compilerConfig: CompilerConfiguration
) : IrGenerationExtension {
  @OptIn(ExperimentalStdlibApi::class, ObsoleteDescriptorBasedAPI::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val translator = Psi2IrTranslator(
      pluginContext.languageVersionSettings,
      Psi2IrConfiguration()
    )
    val genContext = translator.createGeneratorContext(moduleFragment.descriptor,
      pluginContext.bindingContext,
      pluginContext.symbolTable as SymbolTable,
      when {
        pluginContext.platform?.any { it is JvmPlatform } == true -> JvmGeneratorExtensions()
        else -> GeneratorExtensions()
      })
    val facade = KotlinJavaPsiFacade.getInstance(project)
    /* translator.generateModuleFragment(genContext, listOf(PsiFileFactory.getInstance(project).createFileFromText(KotlinLanguage.INSTANCE, """
       fun main() {
         println("hello world")
       }
     """.trimIndent()) as KtFile), emptyList(), emptyList())*/
    //translator.generateModuleFragment(genContext, listOf())
    /*  val resolvedKlibs = jvmResolveLibraries(buildList {
        addAll(jvmLibrariesProvidedByDefault)
        compilerConfig.get(JVMConfigurationKeys.KLIB_PATHS)?.let { addAll(it) }
      }, messageCollector.toLogger()).getFullList()
  */
    /*val test = jsResolveLibraries(configureLibraries("/home/digiadmin/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-common/1.4.31/6dd50665802f54ba9bc3f70ecb20227d1bc81323/kotlin-stdlib-common-1.4.31.jar")/*jvmLibrariesProvidedByDefault.toList()*/, object: Logger {
      override fun error(message: String) {

      }

      override fun fatal(message: String): Nothing {
        TODO("Not yet implemented")
      }

      override fun log(message: String) {

      }

      override fun warning(message: String) {

      }
    })
    println(test)*/
    /*val resolvedLibs = jvmResolveLibraries(emptyList(), listOf(KotlinPathsFromHomeDir(PathUtil.pathUtilJar.parentFile.parentFile).stdlibPath.absolutePath), object: Logger {
      override fun error(message: String) {

      }

      override fun fatal(message: String): Nothing {
        TODO(KotlinPathsFromHomeDir(PathUtil.pathUtilJar.parentFile.parentFile).stdlibPath.absolutePath)
      }

      override fun log(message: String) {

      }

      override fun warning(message: String) {

      }
    })
*///      pluginContext.cast<IrPluginContextImpl>().linker.cast<KotlinIrLinker>().deserializeFullModule(, resolvedLibs.first())

    /*((((((((((((moduleFragment as IrModuleFragmentImpl).files as java.util.ArrayList<*>)[4] as IrFileImpl).declarations as java.util.ArrayList<*>)[0] as IrFunctionImpl).body as IrBlockBodyImpl).statements as java.util.ArrayList<*>)[1] as IrCallImpl).symbol as IrSimpleFunctionPublicSymbolImpl).descriptor as DeserializedSimpleFunctionDescriptor).containingDeclaration as LazyJavaPackageFragment).jPackage as JavaPackageImpl).psiElement.directories*/
    //println(moduleFragment.dump())
    //((((((((((((((((((((moduleFragment as IrModuleFragmentImpl).files as java.util.ArrayList<*>)[4] as IrFileImpl).declarations as java.util.ArrayList<*>)[0] as IrFunctionImpl).body as IrBlockBodyImpl).statements as java.util.ArrayList<*>)[10] as IrTypeOperatorCallImpl).argument as IrBlockImpl).statements as java.util.ArrayList<*>)[1] as IrIfThenElseImpl).branches as org.jetbrains.kotlin.utils.SmartList<*>)[1] as IrElseBranchImpl).result as IrCallImpl).symbol as IrSimpleFunctionPublicSymbolImpl).descriptor as DeserializedSimpleFunctionDescriptor).containingDeclaration as LazyJavaPackageFragment).jPackage as JavaPackageImpl).psi.directories.distinctBy { it.toString() }[1] as PsiDirectoryImpl).myFile as CoreJarVirtualFile).myChildren[7].contentsToByteArray(false)
    val tempsToAdd: MutableMap<IrFunction, MutableList<IrVariable>> = mutableMapOf()
    val deferredAddedFunctions: MutableMap<IrFunction, IrFile> = mutableMapOf()
    moduleFragment.lowerWith(object : IrFileTransformerVoidWithContext(pluginContext) {
      // Basically just searches for this function:
      // ```
      // inline fun <R> inlineInvoke(block: () -> R): R {
      //  return block()
      //}
      //```
      val inlineInvoke0 = context.referenceFunctions(INLINE_INVOKE_FQNAME)
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
      val inlineInvokeDefs = ArrayList<IrSimpleFunction?>(22)//inlineInvoke0)

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
                  type = context.irBuiltIns.function(expression.valueArgumentsCount)
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
    })

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
              return /*expression*/ declarationIrBuilder.irBlock { +irNull() }
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
      varMap[requestedVar]?.let { expr ->
        val getTemps = mutableListOf<IrValueDeclaration>()
        val setTemps = mutableListOf<IrValueDeclaration>()
        val result = expression.symbol.owner.safeAs<IrVariable>()?.initializer?.let { initializer ->
          declarationIrBuilder.irComposite {
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


  val inlinedFunctionAccesses = mutableListOf<IrExpression>()


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
                  if (false) {
                    val returnExp = it.safeAs<IrReturnableBlock>()?.statements?.last().safeAs<IrReturn>()
                    returnExp?.let {
                      returnExp.value = irInt(mapper.size).also {
                        mapper.add(returnExp.value.lastElement().cast())
                        accumulateStatementsExceptLast(returnExp.value).forEach { initStep ->
                          +initStep
                          initStep.patchDeclarationParents(parent)
                        }
                      }
                      returnExp.type = context.irBuiltIns.intType
                    }
                  } else {
                    it.safeAs<IrReturnableBlock>()?.let {
                      @OptIn(ObsoleteDescriptorBasedAPI::class)
                      @Suppress("NestedLambdaShadowedImplicitParameter") //Intentional
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
      else if(expression.type == context.irBuiltIns.booleanType && (originalFunction.symbol == context.irBuiltIns.eqeqSymbol || originalFunction.symbol == context.irBuiltIns.eqeqeqSymbol) )
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
  fun fallBackWhenImpl() = irWhenWithMapper(originalType, tempInt, mapper, buildList {
    mapper.forEachIndexed { index, irExpression ->
      if (irExpression != null) {
        add(irBranch(irEquals(irGet(tempInt), irInt(index)),
          irComposite { +irExpression }
        ))
      }
    }
    add(elseBranch)
  })
  if (!originalType.isFunctionTypeOrSubtype())
    return fallBackWhenImpl()

  //TODO: figure out how to correctly handle lambdas of different arities
  val originalFun =
    mapper.filterIsInstance<IrFunctionExpression>().takeIf { functionExpressions ->
      functionExpressions.firstOrNull()?.let { firstFunctionExpression ->
        functionExpressions.all { functionExpression -> functionExpression.function.valueParameters.size == firstFunctionExpression.function.valueParameters.size }
      } == true
    }?.first()?.function ?: return fallBackWhenImpl()
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
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
operator fun <T> @kotlin.internal.Exact T.plus(collection: Collection<T>): List<T> {
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

fun IrStatement.replaceLastElementAndIterateBranches(
  onThisNotContainer: (replacer: (IrStatement) -> IrStatement, IrStatement) -> Unit,
  replacer: (IrStatement) -> IrStatement
): IrStatement {
  val value = replaceLastElement(onThisNotContainer) {
    if (it is IrWhen) {
      it.branches.forEach { branch ->
        branch.result.replaceLastElementAndIterateBranches({ _, irStatement ->
          branch.result = replacer(
            irStatement
          ).cast()
        }, replacer)
      }
      it
    } else replacer(it)
  }
  return value
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


@OptIn(ExperimentalContracts::class)
inline fun <reified T : Any> Any?.safeAs(): T? {
  // I took this from org.jetbrains.kotlin.utils.addToStdlib and added the contract to allow for safecasting when a null
  // check is done on the returned value of this function
  contract {
    returnsNotNull() implies (this@safeAs is T)
  }
  return this as? T
}

fun jvmResolveLibraries(
  libraries: List<String>,
  absoluteLibraries: List<String>,
  logger: Logger
): KotlinLibraryResolveResult {
  val unresolvedLibraries =
    libraries.map { UnresolvedLibrary(it, null) } + absoluteLibraries.map { UnresolvedLibrary(it, null) }
  val libraryAbsolutePaths = libraries.map { org.jetbrains.kotlin.konan.file.File(it).absolutePath } + absoluteLibraries
  // Configure the resolver to only work with absolute paths for now.
  val libraryResolver = JvmLibraryResolver(
    repositories = emptyList(),
    directLibs = libraryAbsolutePaths,
    distributionKlib = null,
    localKotlinDir = null,
    skipCurrentDir = false,
    logger = logger
  ).libraryResolver()
  val resolvedLibraries =
    libraryResolver.resolveWithDependencies(
      unresolvedLibraries = unresolvedLibraries,
      noStdLib = false,
      noDefaultLibs = false,
      noEndorsedLibs = false
    )
  return resolvedLibraries
}

fun IrBuilderWithScope.irWhenWithMapper(
  type: IrType,
  tempInt: IrVariable,
  mapper: List<IrExpression?>,
  branches: List<IrBranch>
) =
  IrWhenWithMapperImpl(startOffset, endOffset, type, tempInt, mapper, null, branches)

class IrWhenWithMapperImpl(
  override val startOffset: Int,
  override val endOffset: Int,
  override var type: IrType,
  override val tempInt: IrVariable,
  override val mapper: List<IrExpression?>,
  override val origin: IrStatementOrigin? = null,
) : IrWhenWithMapper() {
  constructor(
    startOffset: Int,
    endOffset: Int,
    type: IrType,
    tempInt: IrVariable,
    mapper: List<IrExpression?>,
    origin: IrStatementOrigin?,
    branches: List<IrBranch>
  ) : this(startOffset, endOffset, type, tempInt, mapper, origin) {
    this.branches.addAll(branches)
  }

  override val branches: MutableList<IrBranch> = ArrayList()
}

abstract class IrWhenWithMapper : IrWhen() {
  abstract val mapper: List<IrExpression?>
  abstract val tempInt: IrVariable
}
