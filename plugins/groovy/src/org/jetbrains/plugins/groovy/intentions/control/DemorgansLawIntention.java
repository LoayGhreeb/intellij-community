// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.intentions.control;

import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.intentions.GroovyIntentionsBundle;
import org.jetbrains.plugins.groovy.intentions.base.GrPsiUpdateIntention;
import org.jetbrains.plugins.groovy.intentions.base.PsiElementPredicate;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrBinaryExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.impl.utils.BoolUtils;
import org.jetbrains.plugins.groovy.lang.psi.impl.utils.ComparisonUtils;
import org.jetbrains.plugins.groovy.lang.psi.impl.utils.ParenthesesUtils;

public final class DemorgansLawIntention extends GrPsiUpdateIntention {

  @Override
  public @NotNull String getText(@NotNull PsiElement element) {
    final GrBinaryExpression binaryExpression = (GrBinaryExpression)element;
    final IElementType tokenType = binaryExpression.getOperationTokenType();
    if (GroovyTokenTypes.mLAND.equals(tokenType)) {
      return GroovyIntentionsBundle.message("demorgans.intention.name1");
    }
    else {
      return GroovyIntentionsBundle.message("demorgans.intention.name2");
    }
  }

  @Override
  public @NotNull PsiElementPredicate getElementPredicate() {
    return new ConjunctionPredicate();
  }

  @Override
  protected void processIntention(@NotNull PsiElement element, @NotNull ActionContext context, @NotNull ModPsiUpdater updater) {
    GrBinaryExpression exp = (GrBinaryExpression)element;
    final IElementType tokenType = exp.getOperationTokenType();
    PsiElement parent = exp.getParent();
    while (isConjunctionExpression(parent, tokenType)) {
      exp = (GrBinaryExpression)parent;
      assert exp != null;
      parent = exp.getParent();
    }
    final String newExpression = convertConjunctionExpression(exp, tokenType);
    replaceExpressionWithNegatedExpressionString(newExpression, exp);
  }

  private static String convertConjunctionExpression(GrBinaryExpression exp, IElementType tokenType) {
    final GrExpression lhs = exp.getLeftOperand();
    final String lhsText;
    if (isConjunctionExpression(lhs, tokenType)) {
      lhsText = convertConjunctionExpression((GrBinaryExpression)lhs, tokenType);
    }
    else {
      lhsText = convertLeafExpression(lhs);
    }
    final GrExpression rhs = exp.getRightOperand();
    final String rhsText;
    if (isConjunctionExpression(rhs, tokenType)) {
      rhsText = convertConjunctionExpression((GrBinaryExpression)rhs, tokenType);
    }
    else {
      rhsText = convertLeafExpression(rhs);
    }

    final String flippedConjunction;
    if (tokenType.equals(GroovyTokenTypes.mLAND)) {
      flippedConjunction = "||";
    }
    else {
      flippedConjunction = "&&";
    }

    return lhsText + flippedConjunction + rhsText;
  }

  private static String convertLeafExpression(GrExpression condition) {
    if (BoolUtils.isNegation(condition)) {
      final GrExpression negated = BoolUtils.getNegated(condition);
      if (ParenthesesUtils.getPrecedence(negated) > ParenthesesUtils.OR_PRECEDENCE) {
        return '(' + negated.getText() + ')';
      }
      return negated.getText();
    }
    else if (ComparisonUtils.isComparison(condition)) {
      final GrBinaryExpression binaryExpression = (GrBinaryExpression)condition;
      final IElementType sign = binaryExpression.getOperationTokenType();
      final String negatedComparison = ComparisonUtils.getNegatedComparison(sign);
      final GrExpression lhs = binaryExpression.getLeftOperand();
      final GrExpression rhs = binaryExpression.getRightOperand();
      assert rhs != null;
      return lhs.getText() + negatedComparison + rhs.getText();
    }
    else if (ParenthesesUtils.getPrecedence(condition) > ParenthesesUtils.PREFIX_PRECEDENCE) {
      return "!(" + condition.getText() + ')';
    }
    else {
      return '!' + condition.getText();
    }
  }

  private static boolean isConjunctionExpression(PsiElement exp, IElementType conjunctionType) {
    if (!(exp instanceof GrBinaryExpression binExp)) return false;
    final IElementType tokenType = binExp.getOperationTokenType();
    return conjunctionType.equals(tokenType);
  }
}
