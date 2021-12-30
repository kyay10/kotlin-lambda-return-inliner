package io.github.kyay10.kotlinlambdareturninliner.utils

import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrTypeAbbreviationImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.TypeRemapper

class DeepCopyTypeRemapper(
  private val symbolRemapper: SymbolRemapper
) : TypeRemapper {

  lateinit var deepCopy: DeepCopyIrTreeWithSymbols

  override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {
  }

  override fun leaveScope() {
  }

  override fun remapType(type: IrType): IrType {
    return if (type is IrSimpleType) {
      val newClassifier = symbolRemapper.getReferencedClassifier(type.classifier)
      if (newClassifier != type.classifier) {
        IrSimpleTypeImpl(
          null,
          symbolRemapper.getReferencedClassifier(type.classifier),
          type.hasQuestionMark,
          remapTypeArguments(type.arguments),
          type.annotations.mapOrOriginal { it.transform(deepCopy, null) as IrConstructorCall },
          type.abbreviation?.remapTypeAbbreviation()
        )
      } else type
    } else type
  }

  private fun remapTypeArgument(typeArgument: IrTypeArgument): IrTypeArgument =
    if (typeArgument is IrTypeProjection)
      makeTypeProjection(this.remapType(typeArgument.type), typeArgument.variance)
    else
      typeArgument

  private fun remapTypeArguments(typeArguments: List<IrTypeArgument>): List<IrTypeArgument> {
    var newArguments: MutableList<IrTypeArgument>? = null
    typeArguments.forEachIndexed { index, argument ->
      if (argument is IrTypeProjection) {
        if (newArguments == null)
          newArguments = typeArguments.toMutableList()
        @Suppress("ReplaceNotNullAssertionWithElvisReturn")
        newArguments!![index] = remapTypeArgument(argument)
      }
    }
    return newArguments ?: typeArguments
  }

  private fun IrTypeAbbreviation.remapTypeAbbreviation() =
    IrTypeAbbreviationImpl(
      symbolRemapper.getReferencedTypeAlias(typeAlias),
      hasQuestionMark,
      remapTypeArguments(arguments),
      annotations
    )
}

