package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.backend.common.CodegenUtil
import org.jetbrains.kotlin.codegen.JvmCodegenUtil
import org.jetbrains.kotlin.codegen.getCallLabelForLambdaArgument
import org.jetbrains.kotlin.codegen.isValueArgumentForCallToMethodWithTypeCheckBarrier
import org.jetbrains.kotlin.codegen.range.getElementType
import org.jetbrains.kotlin.com.google.common.collect.ImmutableMap
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.synthetics.findClassDescriptor
import org.jetbrains.kotlin.psi2ir.isConstructorDelegatingToSuper
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.bindingContextUtil.*
import org.jetbrains.kotlin.resolve.calls.callUtil.*
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.resolvedCallUtil.getArgumentByParameterIndex
import org.jetbrains.kotlin.resolve.calls.resolvedCallUtil.hasThisOrNoDispatchReceiver
import org.jetbrains.kotlin.resolve.calls.smartcasts.getKotlinTypeForComparison
import org.jetbrains.kotlin.resolve.checkers.ExperimentalUsageChecker
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.expressions.KotlinTypeInfo
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.resolve.BindingContext as UncoolBindingContext

private const val UNINITIALIZED_STRING = "UNINITIALIZED_STRING"

inline class BindingContext(val originalContext: UncoolBindingContext) : UncoolBindingContext {
  override fun getDiagnostics(): Diagnostics {
    return originalContext.diagnostics
  }

  override fun <K : Any?, V : Any?> get(p0: ReadOnlySlice<K, V>?, p1: K): V? {
    return originalContext.get(p0, p1)
  }

  override fun <K : Any?, V : Any?> getKeys(p0: WritableSlice<K, V>?): Collection<K> {
    return originalContext.getKeys(p0)
  }

  override fun <K : Any?, V : Any?> getSliceContents(p0: ReadOnlySlice<K, V>): ImmutableMap<K, V> {
    return originalContext.getSliceContents(p0)
  }

  override fun getType(p0: KtExpression): KotlinType? {
    return originalContext.getType(p0)
  }

  override fun addOwnDataTo(p0: BindingTrace, p1: Boolean) {
    return originalContext.addOwnDataTo(p0, p1)
  }

  // JvmCodegenUtil
  val DeclarationDescriptor.isArgumentWhichWillBeInlined: Boolean
    get() = JvmCodegenUtil.isArgumentWhichWillBeInlined(
      originalContext,
      this
    )
  val VariableDescriptorWithAccessors.propertyDelegateType: KotlinType?
    get() = JvmCodegenUtil.getPropertyDelegateType(
      this,
      originalContext
    )

  // BindingUtils
  val KtPureClassOrObject.classDescriptor: ClassDescriptor
    get() = BindingUtils.getClassDescriptor(
      originalContext,
      this
    )
  val KtDeclarationWithBody.functionDescriptor: FunctionDescriptor
    get() = BindingUtils.getFunctionDescriptor(
      originalContext,
      this
    )
  val KtProperty.propertyDescriptor: PropertyDescriptor
    get() = BindingUtils.getPropertyDescriptor(
      originalContext,
      this
    )
  val KtPureClassOrObject.hasAncestorClass: Boolean
    get() = BindingUtils.hasAncestorClass(
      originalContext,
      this
    )
  val KtTypeReference.type: KotlinType get() = BindingUtils.getTypeByReference(originalContext, this)
  val KtParameter.propertyDescriptorForConstructorParameter: PropertyDescriptor?
    get() = BindingUtils.getPropertyDescriptorForConstructorParameter(
      originalContext,
      this
    )
  val KtReferenceExpression.descriptor: DeclarationDescriptor?
    get() = BindingUtils.getDescriptorForReferenceExpression(
      originalContext,
      this
    )
  val KtExpression.isVariableReassignment: Boolean
    get() = BindingUtils.isVariableReassignment(
      originalContext,
      this
    )
  val KtOperationExpression.callableDescriptor: CallableDescriptor?
    get() = BindingUtils.getCallableDescriptorForOperationExpression(
      originalContext,
      this
    )
  val PsiElement.elementDescriptor: DeclarationDescriptor
    get() = BindingUtils.getDescriptorForElement(
      originalContext,
      this
    )
  val KtExpression.compileTimeValue: Any? get() = BindingUtils.getCompileTimeValue(originalContext, this)
  fun KtExpression.getCompileTimeValue(constant: CompileTimeConstant<*>): Any? =
    BindingUtils.getCompileTimeValue(originalContext, this, constant)

  val KtExpression.iteratorFunction: ResolvedCall<FunctionDescriptor>
    get() = BindingUtils.getIteratorFunction(
      originalContext,
      this
    )
  val KtExpression.nextFunction: ResolvedCall<FunctionDescriptor>
    get() = BindingUtils.getNextFunction(
      originalContext,
      this
    )
  val KtExpression.hasNextCallable: ResolvedCall<FunctionDescriptor>
    get() = BindingUtils.getHasNextCallable(
      originalContext,
      this
    )
  val KtExpression.kotlinType: KotlinType get() = BindingUtils.getTypeForExpression(originalContext, this)
  fun KtArrayAccessExpression.getResolvedCallForArrayAccess(isGet: Boolean): ResolvedCall<FunctionDescriptor> =
    BindingUtils.getResolvedCallForArrayAccess(
      originalContext,
      this,
      isGet
    )

  val KtArrayAccessExpression.resolvedCallForArrayAccess: ResolvedCall<FunctionDescriptor>
    get() = getResolvedCallForArrayAccess(false)
  val KtPureClassOrObject.superCall: ResolvedCall<FunctionDescriptor>?
    get() = BindingUtils.getSuperCall(
      originalContext,
      this
    )

  // BindingContextUtils
  val KtElement.extractVariableFromResolvedCall: VariableDescriptor?
    get() = BindingContextUtils.extractVariableFromResolvedCall(
      originalContext,
      this
    )
  val KtElement.extractVariableDescriptorFromReference: VariableDescriptor?
    get() = BindingContextUtils.extractVariableDescriptorFromReference(
      originalContext,
      this
    )

  operator fun <K : Any, V> ReadOnlySlice<K, V>.get(key: K, messageIfNull: String = UNINITIALIZED_STRING): V =
    if (messageIfNull == UNINITIALIZED_STRING) BindingContextUtils.getNotNull(
      originalContext,
      this,
      key
    ) else BindingContextUtils.getNotNull(originalContext, this, key, messageIfNull)

  val KtExpression.recordedTypeInfo: KotlinTypeInfo?
    get() = BindingContextUtils.getRecordedTypeInfo(
      this,
      originalContext
    )
  val KtExpression.isExpressionWithValidReference: Boolean
    get() = BindingContextUtils.isExpressionWithValidReference(
      this,
      originalContext
    )
  val DeclarationDescriptor.isCapturedInClosure: Boolean
    get() = BindingContextUtils.isCapturedInClosure(
      originalContext,
      this
    )
  val DeclarationDescriptor.isCapturedInClosureWithExactlyOnceEffect: Boolean
    get() = BindingContextUtils.isCapturedInClosureWithExactlyOnceEffect(
      originalContext,
      this
    )
  val DeclarationDescriptor.isBoxedLocalCapturedInClosure: Boolean
    get() = BindingContextUtils.isBoxedLocalCapturedInClosure(
      originalContext,
      this
    )
  val ConstructorDescriptor.delegationConstructorCall: ResolvedCall<ConstructorDescriptor>?
    get() = BindingContextUtils.getDelegationConstructorCall(
      originalContext,
      this
    )

  // CompileTimeConstantUtils
  fun KtExpression?.canBeReducedToBooleanConstant(expectedValue: Boolean): Boolean =
    CompileTimeConstantUtils.canBeReducedToBooleanConstant(this, originalContext, expectedValue)

  // ModifiersChecker
  fun KtModifierListOwner?.resolveMemberModalityFromModifiers(
    defaultModality: Modality,
    containingDescriptor: DeclarationDescriptor
  ): Modality =
    ModifiersChecker.resolveMemberModalityFromModifiers(this, defaultModality, originalContext, containingDescriptor)

  fun KtModifierListOwner?.resolveModalityFromModifiers(
    defaultModality: Modality,
    containingDescriptor: DeclarationDescriptor,
    allowSealed: Boolean
  ): Modality = ModifiersChecker.resolveModalityFromModifiers(
    this,
    defaultModality,
    originalContext,
    containingDescriptor,
    allowSealed
  )

  // InlineUtil
  fun DeclarationDescriptor.checkNonLocalReturnUsage(
    containingFunctionDescriptor: DeclarationDescriptor?,
    containingFunction: PsiElement?
  ) = InlineUtil.checkNonLocalReturnUsage(this, containingFunctionDescriptor, containingFunction, originalContext)

  fun KtFunction.isInlinedArgument(checkNonLocalReturn: Boolean = false): Boolean =
    InlineUtil.isInlinedArgument(this, originalContext, checkNonLocalReturn)

  val KtFunction.isInlinedArgument: Boolean get() = isInlinedArgument()
  val KtFunction.inlineArgumentDescriptor: ValueParameterDescriptor?
    get() = InlineUtil.getInlineArgumentDescriptor(
      this,
      originalContext
    )

  // RangeCodegenUtil
  val KtForExpression.elementType: KotlinType get() = getElementType(this)

  // CodegenUtil
  fun KtExpression.getDelegatePropertyIfAny(classDescriptor: ClassDescriptor): PropertyDescriptor? =
    CodegenUtil.getDelegatePropertyIfAny(this, classDescriptor, originalContext)

  val KtFile.memberDescriptorsToGenerate: List<MemberDescriptor>
    get() = CodegenUtil.getMemberDescriptorsToGenerate(
      this,
      originalContext
    )
  val KtSuperTypeListEntry.superClass: ClassDescriptor?
    get() = CodegenUtil.getSuperClassBySuperTypeListEntry(
      this,
      originalContext
    )

  fun KtWhenExpression.isExhaustive(isStatement: Boolean): Boolean =
    CodegenUtil.isExhaustive(originalContext, this, isStatement)

  val PropertyDescriptor?.isFinalPropertyWithBackingField: Boolean
    get() = CodegenUtil.isFinalPropertyWithBackingField(
      this,
      originalContext
    )

  // Various Extensions
  val KtElement.isValueArgumentForCallToMethodWithTypeCheckBarrier: Boolean
    get() = isValueArgumentForCallToMethodWithTypeCheckBarrier(
      this,
      originalContext
    )
  val KtFunctionLiteral.callLabelForLambdaArgument: String? get() = getCallLabelForLambdaArgument(this, originalContext)
  val KtPureElement.classDescriptor: ClassDescriptor get() = findClassDescriptor(originalContext)
  val KtSecondaryConstructor.isConstructorDelegatingToSuper: Boolean
    get() = isConstructorDelegatingToSuper(
      originalContext
    )
  val PropertyDescriptor.hasBackingField: Boolean get() = hasBackingField(originalContext)
  val KtTypeElement.abbreviatedTypeOrType: KotlinType? get() = getAbbreviatedTypeOrType(originalContext)
  val KtTypeReference.abbreviatedTypeOrType: KotlinType? get() = getAbbreviatedTypeOrType(originalContext)
  val KtElement.enclosingDescriptor: DeclarationDescriptor get() = getEnclosingDescriptor(originalContext, this)
  val KtElement.enclosingFunctionDescriptor: FunctionDescriptor?
    get() = getEnclosingFunctionDescriptor(
      originalContext,
      this
    )
  val KtExpression.referenceTargets: Collection<DeclarationDescriptor> get() = getReferenceTargets(originalContext)
  val KtReturnExpression.targetFunction: KtCallableDeclaration? get() = getTargetFunction(originalContext)
  val KtReturnExpression.targetFunctionDescriptor: FunctionDescriptor?
    get() = getTargetFunctionDescriptor(
      originalContext
    )
  val KtExpression.isUsedAsExpression: Boolean get() = isUsedAsExpression(originalContext)
  val KtExpression.isUsedAsResultOfLambda: Boolean get() = isUsedAsResultOfLambda(originalContext)
  val KtExpression.isUsedAsStatement: Boolean get() = isUsedAsStatement(originalContext)
  fun KtCallElement.getArgumentByParameterIndex(index: Int): List<ValueArgument> =
    getArgumentByParameterIndex(index, originalContext)

  val ResolvedCall<*>.hasThisOrNoDispatchReceiver: Boolean get() = hasThisOrNoDispatchReceiver(originalContext)
  val KtExpression.kotlinTypeForComparison: KotlinType? get() = getKotlinTypeForComparison(originalContext)
  val KtElement.call: Call? get() = getCall(originalContext)
  val KtElement.callWithAssert: Call get() = getCallWithAssert(originalContext)
  val KtExpression.functionResolvedCallWithAssert: ResolvedCall<out FunctionDescriptor>
    get() = getFunctionResolvedCallWithAssert(
      originalContext
    )

  fun KtElement.getParentCall(strict: Boolean = true): Call? = getParentCall(originalContext, strict)
  val KtElement.parentCall: Call? get() = getParentCall()
  fun KtElement?.getParentResolvedCall(strict: Boolean = true): ResolvedCall<out CallableDescriptor>? =
    getParentResolvedCall(originalContext, strict)

  val KtElement?.parentResolvedCall: ResolvedCall<out CallableDescriptor>? get() = getParentResolvedCall()
  val KtExpression.propertyResolvedCallWithAssert: ResolvedCall<out PropertyDescriptor>
    get() = getPropertyResolvedCallWithAssert(
      originalContext
    )
  val Call?.resolvedCall: ResolvedCall<out CallableDescriptor>? get() = getResolvedCall(originalContext)
  val KtElement?.resolvedCall: ResolvedCall<out CallableDescriptor>? get() = getResolvedCall(originalContext)
  val KtElement.resolvedCallWithAssert: ResolvedCall<out CallableDescriptor>
    get() = getResolvedCallWithAssert(
      originalContext
    )
  val Call.resolvedCallWithAssert: ResolvedCall<out CallableDescriptor>
    get() = getResolvedCallWithAssert(
      originalContext
    )
  val KtExpression.typeThroughResolvingCall: KotlinType? get() = getType(originalContext)
  val KtExpression.variableResolvedCallWithAssert: ResolvedCall<out VariableDescriptor>
    get() = getVariableResolvedCallWithAssert(
      originalContext
    )

  fun Call.hasUnresolvedArguments(statementFilter: StatementFilter): Boolean =
    hasUnresolvedArguments(originalContext, statementFilter)

  fun PsiElement.isExperimentalityAccepted(
    annotationFqName: FqName,
    languageVersionSettings: LanguageVersionSettings
  ): Boolean = ExperimentalUsageChecker.run {
    isExperimentalityAccepted(
      annotationFqName,
      languageVersionSettings,
      originalContext
    )
  }

  val KtExpression.constant: CompileTimeConstant<*>?
    get() = org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator.run {
      getConstant(
        this@constant,
        originalContext
      )
    }

  val KtExpression.possiblyErrorConstant: CompileTimeConstant<*>?
    get() = org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator.run {
      getPossiblyErrorConstant(
        this@possiblyErrorConstant,
        originalContext
      )
    }
  fun KtExpression.getCompileTimeConstant(takeUpConstValsAsConst: Boolean, shouldInlineConstVals: Boolean): ConstantValue<*>? = org.jetbrains.kotlin.resolve.jvm.getCompileTimeConstant(this, originalContext, takeUpConstValsAsConst, shouldInlineConstVals)


}

@OptIn(ExperimentalContracts::class)
inline fun <R> withBindingContext(context: UncoolBindingContext, block: BindingContext.() -> R): R {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  return BindingContext(context).block()
}
