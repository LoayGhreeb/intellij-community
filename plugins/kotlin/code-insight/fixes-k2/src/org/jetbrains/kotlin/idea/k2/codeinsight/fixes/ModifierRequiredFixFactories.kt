// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.modcommand.ModCommandAction
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.quickfix.AddModifierFix
import org.jetbrains.kotlin.idea.refactoring.canRefactorElement
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtModifierListOwner

internal object ModifierRequiredFixFactories {

    val addInfixModifierFixFactory = KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.InfixModifierRequired ->
        listOfNotNull(createFixIfAvailable(diagnostic.functionSymbol, KtTokens.INFIX_KEYWORD))
    }

    val addOperatorModifierFixFactory = KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.OperatorModifierRequired ->
        listOfNotNull(createFixIfAvailable(diagnostic.functionSymbol, KtTokens.OPERATOR_KEYWORD))
    }
}

private fun createFixIfAvailable(
    functionSymbol: KaFunctionSymbol,
    modifier: KtModifierKeywordToken,
    ): ModCommandAction? {
    val element = (functionSymbol.psi as? KtModifierListOwner)?.takeIf {
        it.canRefactorElement()
    } ?: return null

    return AddModifierFix(element, modifier)
}
