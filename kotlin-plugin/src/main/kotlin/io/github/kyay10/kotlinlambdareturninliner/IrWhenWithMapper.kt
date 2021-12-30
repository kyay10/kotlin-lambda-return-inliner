@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.github.kyay10.kotlinlambdareturninliner

import io.github.kyay10.kotlinlambdareturninliner.utils.buildMutableList
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.SymbolRenamer
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor

fun IrBuilderWithScope.irWhenWithMapper(
  type: IrType,
  tempInt: IrVariable,
  mapper: List<IrExpression?>,
  branches: MutableList<IrBranch>
): IrWhenWithMapperImpl {
  val irWhenWithMapper = IrWhenWithMapperImpl(startOffset, endOffset, type, tempInt, mapper, null, branches)
  branches.forEachIndexed { index, irBranch ->
    if (irBranch is IrBranchWithMapper) {
      irBranch.index = index
      irBranch.associatedWhen = irWhenWithMapper
    }
  }
  return irWhenWithMapper
}

fun IrBuilderWithScope.irBranchWithMapper(
  condition: IrExpression,
  result: IrExpression,
  associatedWhen: IrWhenWithMapper? = null,
  index: Int = -1
) =
  IrBranchWithMapperImpl(
    startOffset,
    endOffset,
    condition,
    result,
    associatedWhen,
    index
  )

class IrWhenWithMapperImpl(
  override val startOffset: Int,
  override val endOffset: Int,
  override var type: IrType,
  override val tempInt: IrVariable,
  mapper: List<IrExpression?>,
  override val origin: IrStatementOrigin? = null,
  override val branches: MutableList<IrBranch> = ArrayList()
) : IrWhenWithMapper() {
  override val mapper: MutableList<IrExpression?> = mapper.toMutableList()
}

abstract class IrWhenWithMapper : IrWhen() {
  abstract val mapper: MutableList<IrExpression?>
  abstract val tempInt: IrVariable
}

abstract class IrBranchWithMapper : IrBranch() {
  abstract var associatedWhen: IrWhenWithMapper?
  abstract var index: Int
}

open class IrBranchWithMapperImpl(
  override val startOffset: Int,
  override val endOffset: Int,
  override var condition: IrExpression,
  result: IrExpression,
  override var associatedWhen: IrWhenWithMapper?,
  override var index: Int = -1,
) : IrBranchWithMapper() {

  override var result: IrExpression = result
    set(value) {
      field = value
      associatedWhen?.mapper?.takeIf { index >= 0 }?.set(index, value)
    }

  constructor(condition: IrExpression, result: IrExpression, associatedWhen: IrWhenWithMapper?, index: Int = -1) :
    this(condition.startOffset, condition.endOffset, condition, result, associatedWhen, index)

  override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R =
    visitor.visitBranch(this, data)

  override fun <D> transform(transformer: IrElementTransformer<D>, data: D): IrBranch =
    transformer.visitBranch(this, data)
}

open class WhenWithMapperAwareDeepCopyIrTreeWithSymbols(
  symbolRemapper: SymbolRemapper,
  typeRemapper: TypeRemapper,
  symbolRenamer: SymbolRenamer
) : DeepCopyIrTreeWithSymbols(symbolRemapper, typeRemapper, symbolRenamer) {

  val copiedWhens: MutableMap<IrWhenWithMapper, IrWhenWithMapper> = mutableMapOf()

  constructor(symbolRemapper: SymbolRemapper, typeRemapper: TypeRemapper) : this(
    symbolRemapper,
    typeRemapper,
    SymbolRenamer.DEFAULT
  )

  override fun visitWhen(expression: IrWhen): IrWhen {
    return if (expression is IrWhenWithMapper) {
      IrWhenWithMapperImpl(
        expression.startOffset, expression.endOffset,
        expression.type.remapType(),
        expression.tempInt,
        expression.mapper,
        mapStatementOrigin(expression.origin),
        buildMutableList(expression.branches.size) {
          for (branch in expression.branches) {
            add(branch.transform())
          }
        }
      ).copyAttributes(expression).also { newWhen ->
        copiedWhens[expression] = newWhen
      }
    } else {
      super.visitWhen(expression)
    }
  }

  override fun visitBranch(branch: IrBranch): IrBranch {
    return if (branch is IrBranchWithMapper) {
      IrBranchWithMapperImpl(
        branch.startOffset, branch.endOffset,
        branch.condition.transform(),
        branch.result.transform(), branch.associatedWhen?.let { copiedWhens[it] },
        branch.index
      )
    } else
      super.visitBranch(branch)
  }
}
