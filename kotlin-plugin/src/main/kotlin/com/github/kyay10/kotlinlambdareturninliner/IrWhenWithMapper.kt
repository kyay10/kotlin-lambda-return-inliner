package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrType

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
