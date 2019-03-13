/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.builtins.PlatformToKotlinClassMap
import org.jetbrains.kotlin.container.*
import org.jetbrains.kotlin.resolve.calls.checkers.*
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparatorClashesResolver
import org.jetbrains.kotlin.resolve.checkers.*
import org.jetbrains.kotlin.resolve.lazy.DelegationFilter
import org.jetbrains.kotlin.types.DynamicTypesSettings
import org.jetbrains.kotlin.types.DynamicTypesSettingsClashesResolver
import org.jetbrains.kotlin.types.expressions.FunctionWithBigAritySupportClashesResolver

private val DEFAULT_DECLARATION_CHECKERS = listOf(
    DataClassDeclarationChecker(),
    ConstModifierChecker,
    UnderscoreChecker,
    InlineParameterChecker,
    InfixModifierChecker(),
    SinceKotlinAnnotationValueChecker,
    RequireKotlinAnnotationValueChecker,
    ReifiedTypeParameterAnnotationChecker(),
    DynamicReceiverChecker,
    DelegationChecker(),
    KClassWithIncorrectTypeArgumentChecker,
    SuspendLimitationsChecker,
    InlineClassDeclarationChecker,
    PropertiesWithBackingFieldsInsideInlineClass(),
    AnnotationClassTargetAndRetentionChecker(),
    ReservedMembersAndConstructsForInlineClass(),
    ResultClassInReturnTypeChecker(),
    LocalVariableTypeParametersChecker()
)

private val DEFAULT_CALL_CHECKERS = listOf(
    CapturingInClosureChecker(), InlineCheckerWrapper(), SafeCallChecker(),
    DeprecatedCallChecker, CallReturnsArrayOfNothingChecker(), InfixCallChecker(), OperatorCallChecker(),
    ConstructorHeaderCallChecker, ProtectedConstructorCallChecker, ApiVersionCallChecker,
    CoroutineSuspendCallChecker, BuilderFunctionsCallChecker, DslScopeViolationCallChecker, MissingDependencyClassChecker,
    CallableReferenceCompatibilityChecker(), LateinitIntrinsicApplicabilityChecker,
    UnderscoreUsageChecker, AssigningNamedArgumentToVarargChecker(), ImplicitNothingAsTypeParameterCallChecker,
    PrimitiveNumericComparisonCallChecker, LambdaWithSuspendModifierCallChecker,
    UselessElvisCallChecker(), ResultTypeWithNullableOperatorsChecker(), NullableVarargArgumentCallChecker,
    NamedFunAsExpressionChecker, ContractNotAllowedCallChecker, ReifiedTypeParameterSubstitutionChecker(), TypeOfChecker
)
private val DEFAULT_TYPE_CHECKERS = emptyList<AdditionalTypeChecker>()
private val DEFAULT_CLASSIFIER_USAGE_CHECKERS = listOf(
    DeprecatedClassifierUsageChecker(), ApiVersionClassifierUsageChecker, MissingDependencyClassChecker.ClassifierUsage,
    OptionalExpectationUsageChecker()
)
private val DEFAULT_ANNOTATION_CHECKERS = listOf<AdditionalAnnotationChecker>()

private val DEFAULT_CLASH_RESOLVERS = listOf<PlatformExtensionsClashResolver<*>>(
    IdentifierCheckerClashesResolver(),
    TypeSpecificityComparatorClashesResolver(),
    DynamicTypesSettingsClashesResolver(),
    FunctionWithBigAritySupportClashesResolver()
)


abstract class PlatformConfiguratorBase(
    private val dynamicTypesSettings: DynamicTypesSettings? = null,
    additionalDeclarationCheckers: List<DeclarationChecker> = emptyList(),
    additionalCallCheckers: List<CallChecker> = emptyList(),
    additionalTypeCheckers: List<AdditionalTypeChecker> = emptyList(),
    additionalClassifierUsageCheckers: List<ClassifierUsageChecker> = emptyList(),
    additionalAnnotationCheckers: List<AdditionalAnnotationChecker> = emptyList(),
    additionalClashResolvers: List<PlatformExtensionsClashResolver<*>> = emptyList(),
    private val identifierChecker: IdentifierChecker? = null,
    private val overloadFilter: OverloadFilter? = null,
    private val platformToKotlinClassMap: PlatformToKotlinClassMap? = null,
    private val delegationFilter: DelegationFilter? = null,
    private val overridesBackwardCompatibilityHelper: OverridesBackwardCompatibilityHelper? = null,
    private val declarationReturnTypeSanitizer: DeclarationReturnTypeSanitizer? = null
) : PlatformConfigurator {
    private val declarationCheckers: List<DeclarationChecker> = DEFAULT_DECLARATION_CHECKERS + additionalDeclarationCheckers
    private val callCheckers: List<CallChecker> = DEFAULT_CALL_CHECKERS + additionalCallCheckers
    private val typeCheckers: List<AdditionalTypeChecker> = DEFAULT_TYPE_CHECKERS + additionalTypeCheckers
    private val classifierUsageCheckers: List<ClassifierUsageChecker> =
        DEFAULT_CLASSIFIER_USAGE_CHECKERS + additionalClassifierUsageCheckers
    private val annotationCheckers: List<AdditionalAnnotationChecker> = DEFAULT_ANNOTATION_CHECKERS + additionalAnnotationCheckers
    private val clashResolvers: List<PlatformExtensionsClashResolver<*>> = DEFAULT_CLASH_RESOLVERS + additionalClashResolvers

    override val platformSpecificContainer = composeContainer(this::class.java.simpleName) {
        useInstanceIfNotNull(dynamicTypesSettings)
        declarationCheckers.forEach { useInstance(it) }
        callCheckers.forEach { useInstance(it) }
        typeCheckers.forEach { useInstance(it) }
        classifierUsageCheckers.forEach { useInstance(it) }
        annotationCheckers.forEach { useInstance(it) }
        clashResolvers.forEach { useClashResolver(it) }
        useInstanceIfNotNull(identifierChecker)
        useInstanceIfNotNull(overloadFilter)
        useInstanceIfNotNull(platformToKotlinClassMap)
        useInstanceIfNotNull(delegationFilter)
        useInstanceIfNotNull(overridesBackwardCompatibilityHelper)
        useInstanceIfNotNull(declarationReturnTypeSanitizer)
    }

    override fun configureModuleDependentCheckers(container: StorageComponentContainer) {
        container.useImpl<ExperimentalMarkerDeclarationAnnotationChecker>()
    }
}

fun createContainer(id: String, compilerServices: PlatformDependentCompilerServices, init: StorageComponentContainer.() -> Unit) =
    composeContainer(id, compilerServices.platformConfigurator.platformSpecificContainer, init)
