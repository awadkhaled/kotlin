/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ir

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.symbols.impl.IrTypeParameterSymbolImpl
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.createParameterDeclarations
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.resolve.OverridingStrategy
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.types.KotlinType
import java.lang.reflect.Proxy

object JsIrBuilder {

    object SYNTHESIZED_STATEMENT : IrStatementOriginImpl("SYNTHESIZED_STATEMENT")
    object SYNTHESIZED_DECLARATION : IrDeclarationOriginImpl("SYNTHESIZED_DECLARATION")

    fun buildCall(target: IrFunctionSymbol, type: IrType? = null, typeArguments: List<IrType>? = null): IrCall =
        IrCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type ?: target.owner.returnType,
            target,
            target.descriptor,
            target.descriptor.typeParametersCount,
            SYNTHESIZED_STATEMENT
        ).apply {
            typeArguments?.let {
                assert(typeArguments.size == typeArgumentsCount)
                it.withIndex().forEach { (i, t) -> putTypeArgument(i, t) }
            }
        }

    fun buildReturn(targetSymbol: IrFunctionSymbol, value: IrExpression, type: IrType) =
        IrReturnImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, targetSymbol, value)

    fun buildThrow(type: IrType, value: IrExpression) = IrThrowImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, value)

    fun buildValueParameter(symbol: IrValueParameterSymbol, type: IrType? = null) =
        IrValueParameterImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, SYNTHESIZED_DECLARATION, symbol, type ?: symbol.owner.type, null)

    fun buildFunction(symbol: IrSimpleFunctionSymbol, returnType: IrType) =
        IrFunctionImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, SYNTHESIZED_DECLARATION, symbol).apply {
            this.returnType = returnType
        }

    fun buildGetObjectValue(type: IrType, classSymbol: IrClassSymbol) =
        IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, classSymbol)

    fun buildGetClass(expression: IrExpression, type: IrType) = IrGetClassImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, expression)

    fun buildGetValue(symbol: IrValueSymbol) = IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol.owner.type, symbol, SYNTHESIZED_STATEMENT)
    fun buildSetVariable(symbol: IrVariableSymbol, value: IrExpression, type: IrType) =
        IrSetVariableImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, symbol, value, SYNTHESIZED_STATEMENT)

    fun buildGetField(symbol: IrFieldSymbol, receiver: IrExpression?, superQualifierSymbol: IrClassSymbol? = null) =
        IrGetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol, symbol.owner.type, receiver, SYNTHESIZED_STATEMENT, superQualifierSymbol)

    fun buildSetField(symbol: IrFieldSymbol, receiver: IrExpression?, value: IrExpression, type: IrType, superQualifierSymbol: IrClassSymbol? = null) =
        IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol, receiver, value, type, SYNTHESIZED_STATEMENT, superQualifierSymbol)

    fun buildBlockBody(statements: List<IrStatement>) = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)

    fun buildBlock(type: IrType) = IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, SYNTHESIZED_STATEMENT)
    fun buildBlock(type: IrType, statements: List<IrStatement>) =
        IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, SYNTHESIZED_STATEMENT, statements)

    fun buildComposite(type: IrType, statements: List<IrStatement> = emptyList()) =
        IrCompositeImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, SYNTHESIZED_STATEMENT, statements)

    fun buildFunctionReference(type: IrType, symbol: IrFunctionSymbol) =
        IrFunctionReferenceImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, symbol, symbol.descriptor, 0, null)

    fun buildVar(symbol: IrVariableSymbol, initializer: IrExpression? = null, type: IrType? = null) =
        IrVariableImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, SYNTHESIZED_DECLARATION, symbol, type ?: symbol.owner.type)
            .apply { this.initializer = initializer }

    fun buildBreak(type: IrType, loop: IrLoop) = IrBreakImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, loop)
    fun buildContinue(type: IrType, loop: IrLoop) = IrContinueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, loop)

    fun buildIfElse(type: IrType, cond: IrExpression, thenBranch: IrExpression, elseBranch: IrExpression? = null): IrWhen = buildIfElse(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, cond, thenBranch, elseBranch, SYNTHESIZED_STATEMENT
    )

    fun buildIfElse(
        startOffset: Int,
        endOffset: Int,
        type: IrType,
        cond: IrExpression,
        thenBranch: IrExpression,
        elseBranch: IrExpression? = null,
        origin: IrStatementOrigin? = null
    ): IrWhen {
        val element = IrIfThenElseImpl(startOffset, endOffset, type, origin)
        element.branches.add(IrBranchImpl(cond, thenBranch))
        if (elseBranch != null) {
            val irTrue = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, cond.type, true)
            element.branches.add(IrElseBranchImpl(irTrue, elseBranch))
        }

        return element
    }

    fun buildWhen(type: IrType, branches: List<IrBranch>, origin: IrStatementOrigin = SYNTHESIZED_STATEMENT) =
        IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, origin, branches)

    fun buildTypeOperator(type: IrType, operator: IrTypeOperator, argument: IrExpression, toType: IrType, symbol: IrClassifierSymbol) =
        IrTypeOperatorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, operator, toType, symbol, argument)

    fun buildNull(type: IrType) = IrConstImpl.constNull(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type)
    fun buildBoolean(type: IrType, v: Boolean) = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, v)
    fun buildInt(type: IrType, v: Int) = IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, v)
    fun buildString(type: IrType, s: String) = IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, s)
    fun buildCatch(ex: IrVariableSymbol, block: IrBlockImpl) = IrCatchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, buildVar(ex), block)
}


object SetDeclarationsParentVisitor : IrElementVisitor<Unit, IrDeclarationParent> {
    override fun visitElement(element: IrElement, data: IrDeclarationParent) {
        if (element !is IrDeclarationParent) {
            element.acceptChildren(this, data)
        }
    }

    override fun visitDeclaration(declaration: IrDeclaration, data: IrDeclarationParent) {
        declaration.parent = data
        super.visitDeclaration(declaration, data)
    }
}

fun IrDeclarationContainer.addChildren(declarations: List<IrDeclaration>) {
    declarations.forEach { this.addChild(it) }
}

fun IrDeclarationContainer.addChild(declaration: IrDeclaration) {
    this.declarations += declaration
    declaration.accept(SetDeclarationsParentVisitor, this)
}

fun IrTypeParameter.setSupers(symbolTable: SymbolTable) {
    assert(this.superClassifiers.isEmpty())
    this.descriptor.upperBounds.mapNotNullTo(this.superClassifiers) {
        it.constructor.declarationDescriptor?.let {
            if (it is TypeParameterDescriptor) {
                IrTypeParameterSymbolImpl(it) // Workaround for deserialized inline functions
            } else {
                symbolTable.referenceClassifier(it)
            }
        }
    }
}

fun IrClass.simpleFunctions(): List<IrSimpleFunction> = this.declarations.flatMap {
    when (it) {
        is IrSimpleFunction -> listOf(it)
        is IrProperty -> listOfNotNull(it.getter as IrSimpleFunction?, it.setter as IrSimpleFunction?)
        else -> emptyList()
    }
}

fun IrClass.setSuperSymbols(supers: List<IrClass>) {
    val s1 = this.superDescriptors().toSet()
    val s2 = supers.map { it.descriptor }.toSet()
    assert(s1 == s2)
    assert(this.superClasses.isEmpty())
    supers.mapTo(this.superClasses) { it.symbol }

    val superMembers = supers.flatMap {
        it.simpleFunctions()
    }.associateBy { it.descriptor }

    this.simpleFunctions().forEach {
        assert(it.overriddenSymbols.isEmpty())

        it.descriptor.overriddenDescriptors.mapTo(it.overriddenSymbols) {
            val superMember = superMembers[it.original] ?: error(it.original)
            superMember.symbol
        }
    }
}

fun IrSimpleFunction.setOverrides(symbolTable: SymbolTable) {
    assert(this.overriddenSymbols.isEmpty())

    this.descriptor.overriddenDescriptors.mapTo(this.overriddenSymbols) {
        symbolTable.referenceSimpleFunction(it.original)
    }

    this.typeParameters.forEach { it.setSupers(symbolTable) }
}


private fun IrClass.superDescriptors() =
    this.descriptor.typeConstructor.supertypes.map { it.constructor.declarationDescriptor as ClassDescriptor }

fun IrClass.setSuperSymbols(symbolTable: SymbolTable) {
    assert(this.superClasses.isEmpty())
    this.superDescriptors().mapTo(this.superClasses) { symbolTable.referenceClass(it) }
    this.simpleFunctions().forEach {
        it.setOverrides(symbolTable)
    }
    this.typeParameters.forEach {
        it.setSupers(symbolTable)
    }
}

private fun createFakeOverride(descriptor: CallableMemberDescriptor, startOffset: Int, endOffset: Int): IrDeclaration {

    fun FunctionDescriptor.createFunction(): IrFunction = IrFunctionImpl(
        startOffset, endOffset,
        IrDeclarationOrigin.FAKE_OVERRIDE, this
    ).apply {
        createParameterDeclarations()
    }

    return when (descriptor) {
        is FunctionDescriptor -> descriptor.createFunction()
        is PropertyDescriptor ->
            IrPropertyImpl(startOffset, endOffset, IrDeclarationOrigin.FAKE_OVERRIDE, descriptor).apply {
                // TODO: add field if getter is missing?
                getter = descriptor.getter?.createFunction() as IrSimpleFunction?
                setter = descriptor.setter?.createFunction() as IrSimpleFunction?
            }
        else -> TODO(descriptor.toString())
    }
}


fun IrClass.setSuperSymbolsAndAddFakeOverrides(supers: List<IrClass>) {
    val overriddenSuperMembers = this.declarations.map { it.descriptor }
        .filterIsInstance<CallableMemberDescriptor>().flatMap { it.overriddenDescriptors.map { it.original } }

    val unoverriddenSuperMembers = supers.flatMap {
        it.declarations.mapNotNull {
            when (it) {
                is IrSimpleFunction -> it.descriptor
                is IrProperty -> it.descriptor
                else -> null
            }
        }
    } - overriddenSuperMembers

    val irClass = this

    val overridingStrategy = object : OverridingStrategy() {
        override fun addFakeOverride(fakeOverride: CallableMemberDescriptor) {
            irClass.addChild(createFakeOverride(fakeOverride, startOffset, endOffset))
        }

        override fun inheritanceConflict(first: CallableMemberDescriptor, second: CallableMemberDescriptor) {
            error("inheritance conflict in synthesized class ${irClass.descriptor}:\n  $first\n  $second")
        }

        override fun overrideConflict(fromSuper: CallableMemberDescriptor, fromCurrent: CallableMemberDescriptor) {
            error("override conflict in synthesized class ${irClass.descriptor}:\n  $fromSuper\n  $fromCurrent")
        }
    }

    unoverriddenSuperMembers.groupBy { it.name }.forEach { (name, members) ->
        OverridingUtil.generateOverridesInFunctionGroup(
            name,
            members,
            emptyList(),
            this.descriptor,
            overridingStrategy
        )
    }

    this.setSuperSymbols(supers)
}

inline fun <reified T> stub(name: String): T {
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
            _ /* proxy */, method, _ /* methodArgs */ ->
        if (method.name == "toString" && method.parameterCount == 0) {
            "${T::class.simpleName} stub for $name"
        } else {
            error("${T::class.simpleName}.${method.name} is not supported for $name")
        }
    } as T
}
