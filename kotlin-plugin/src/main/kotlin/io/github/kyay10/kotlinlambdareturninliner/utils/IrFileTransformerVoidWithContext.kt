package io.github.kyay10.kotlinlambdareturninliner.utils

import io.github.kyay10.kotlinlambdareturninliner.INLINE_INVOKE_FQNAME
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import io.github.kyay10.kotlinlambdareturninliner.utils.isInvokeCall as _isInvokeCall

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

  val IrFunctionAccessExpression.isInvokeCall: Boolean get() = _isInvokeCall &&
    currentFunction?.irElement.safeAs<IrFunction>()?.fqNameWhenAvailable != INLINE_INVOKE_FQNAME
}
