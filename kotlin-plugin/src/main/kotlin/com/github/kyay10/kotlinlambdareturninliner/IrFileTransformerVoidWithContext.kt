package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSymbol

open class IrFileTransformerVoidWithContext(val context: IrPluginContext) : IrElementTransformerVoidWithContext(),
  FileLoweringPass {
  protected lateinit var file: IrFile
  override fun lower(irFile: IrFile) {
    file = irFile
    irFile.transformChildrenVoid()
  }

  val declarationIrBuilder: DeclarationIrBuilder
    get() = declarationIrBuilder()

  fun declarationIrBuilder(
    generatorContext: IrGeneratorContext = context,
    symbol: IrSymbol = currentScope!!.scope.scopeOwnerSymbol,
    startOffset: Int = UNDEFINED_OFFSET, endOffset: Int = UNDEFINED_OFFSET
  ) = DeclarationIrBuilder(generatorContext, symbol, startOffset, endOffset)
}
