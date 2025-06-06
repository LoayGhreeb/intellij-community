// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.java18api;

import com.intellij.codeInsight.PsiEquivalenceUtil;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LambdaCanBeMethodReferenceInspection;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.codeInspection.util.IteratorDeclaration;
import com.intellij.codeInspection.util.LambdaGenerationUtil;
import com.intellij.java.JavaBundle;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandQuickFix;
import com.intellij.openapi.project.Project;
import com.intellij.pom.java.JavaFeature;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.util.CommonJavaInlineUtil;
import com.intellij.refactoring.util.LambdaRefactoringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.*;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.intellij.codeInspection.options.OptPane.checkbox;
import static com.intellij.codeInspection.options.OptPane.pane;
import static com.intellij.util.ObjectUtils.tryCast;

public final class Java8ListReplaceAllInspection extends AbstractBaseJavaLocalInspectionTool {
  private static final CallMatcher LIST_SET = CallMatcher.instanceCall(CommonClassNames.JAVA_UTIL_LIST, "set").parameterTypes("int", "E");

  @SuppressWarnings("PublicField") public boolean dontWarnInCaseOfMultilineLambda = true;

  @Override
  public @NotNull OptPane getOptionsPane() {
    return pane(
      checkbox("dontWarnInCaseOfMultilineLambda", JavaBundle.message("checkbox.don.t.warn.in.case.of.multiline.lambda")));
  }

    @Override
  public @NotNull Set<@NotNull JavaFeature> requiredFeatures() {
    return Set.of(JavaFeature.ADVANCED_COLLECTIONS_API);
  }

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      private static boolean isRedundantOperation(PsiExpression replacement,
                                                  PsiStatement body,
                                                  CountingLoop countingLoop,
                                                  IndexedContainer container) {
        PsiLocalVariable counter = countingLoop.getCounter();
        if (ExpressionUtils.isReferenceTo(container.extractIndexFromGetExpression(replacement), counter)) return true;
        if (!(replacement instanceof PsiReferenceExpression)) return false;
        PsiVariable variable = tryCast(((PsiReferenceExpression)replacement).resolve(), PsiVariable.class);
        if (variable == null) return false;
        PsiExpression initializer = variable.getInitializer();
        PsiExpression index = container.extractIndexFromGetExpression(initializer);
        return ExpressionUtils.isReferenceTo(index, counter) && ControlFlowUtil.isEffectivelyFinal(variable, body);
      }

      private static boolean isMultilineLambda(PsiStatement body, PsiStatement[] statements) {
        if (statements.length == 1) return false;
        if (statements.length > 3) return true;
        if (getVariableToInline(statements[0], body) == null) return true;
        return statements.length == 3 && getVariableToInline(statements[1], body) == null;
      }

      @Override
      public void visitForStatement(@NotNull PsiForStatement statement) {
        super.visitForStatement(statement);
        PsiStatement body = statement.getBody();
        PsiStatement[] statements = ControlFlowUtils.unwrapBlock(body);
        PsiMethodCallExpression maybeSetCall = getLastMethodCall(statements);
        if (!LIST_SET.test(maybeSetCall)) return;
        if (dontWarnInCaseOfMultilineLambda && isMultilineLambda(body, statements)) return;
        PsiExpression index = PsiUtil.skipParenthesizedExprDown(maybeSetCall.getArgumentList().getExpressions()[0]);
        CountingLoop countingLoop = CountingLoop.from(statement);
        if (countingLoop == null) return;
        PsiLocalVariable counter = countingLoop.getCounter();
        if (countingLoop.isIncluding() ||
            countingLoop.isDescending() ||
            !ExpressionUtils.isZero(countingLoop.getInitializer()) ||
            !ExpressionUtils.isReferenceTo(index, counter)) {
          return;
        }
        IndexedContainer container = IndexedContainer.fromLengthExpression(countingLoop.getBound());
        if (container == null) return;
        List<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(counter, body);
        if (!ContainerUtil.all(references, reference -> reference == index || container.extractGetExpressionFromIndex(reference) != null)) {
          return;
        }
        if (isRedundantOperation(maybeSetCall.getArgumentList().getExpressions()[1], body, countingLoop, container)) return;
        if (!container.isQualifierEquivalent(ExpressionUtils.getEffectiveQualifier(maybeSetCall.getMethodExpression()))) return;
        Predicate<PsiVariable> variableAllowedPredicate = v -> PsiEquivalenceUtil.areElementsEquivalent(v, counter);
        if (!LambdaGenerationUtil.canBeUncheckedLambda(body, variableAllowedPredicate)) return;
        holder.registerProblem(statement.getFirstChild(), QuickFixBundle.message("java.8.list.replaceall.inspection.description"),
                               new ReplaceWithReplaceAllQuickFix());
      }
    };
  }

  private static class ReplaceWithReplaceAllQuickFix extends PsiUpdateModCommandQuickFix {
    @Override
    public @Nls @NotNull String getFamilyName() {
      return QuickFixBundle.message("java.8.list.replaceall.inspection.fix.name");
    }

    @Override
    protected void applyFix(@NotNull Project project, @NotNull PsiElement element, @NotNull ModPsiUpdater updater) {
      if (!(element.getParent() instanceof PsiForStatement statement)) return;
      PsiStatement body = statement.getBody();
      if (body == null) return;
      PsiStatement[] statements = ControlFlowUtils.unwrapBlock(body);
      PsiMethodCallExpression call = getLastMethodCall(statements);
      if (!LIST_SET.test(call)) return;
      PsiExpression qualifier = ExpressionUtils.getEffectiveQualifier(call.getMethodExpression());
      if (qualifier == null) return;
      CountingLoop countingLoop = CountingLoop.from(statement);
      if (countingLoop == null) return;
      IndexedContainer container = IndexedContainer.fromLengthExpression(countingLoop.getBound());
      if (container == null) return;
      List<PsiMethodCallExpression> getCalls = collectGetCalls(body, container, countingLoop.getCounter());
      String paramName = generateParameterName(body, getCalls);
      getCalls.forEach(getCall -> {
        new CommentTracker().replaceAndRestoreComments(getCall, paramName);
      });
      if (statements.length == 2) {
        inlineVariable(getVariableToInline(statements[0], body), body);
      }
      else if (statements.length == 3) {
        inlineVariable(getVariableToInline(statements[0], body), body);
        inlineVariable(getVariableToInline(statements[1], body), body);
      }
      CommentTracker ct = new CommentTracker();
      PsiExpression replacementExpression = call.getArgumentList().getExpressions()[1];
      PsiElement returnStatement =
        new CommentTracker().replaceAndRestoreComments(call.getParent(), "return " + ct.textWithComments(replacementExpression) + ";");
      String codeBlockText = body instanceof PsiBlockStatement ? ct.text(body) : "{ " + ct.text(returnStatement) + " }";
      String replaceAllParameter = paramName + " -> " + codeBlockText;
      String text = ct.text(qualifier) + ".replaceAll(" + replaceAllParameter + ");";
      PsiElement result = ct.replaceAndRestoreComments(statement, text);
      LambdaCanBeMethodReferenceInspection.replaceAllLambdasWithMethodReferences(result);
      simplifyToExpressionLambda(result);
      CodeStyleManager.getInstance(project).reformat(result);
    }

    private static @NotNull List<PsiMethodCallExpression> collectGetCalls(PsiStatement body,
                                                                          IndexedContainer container,
                                                                          PsiLocalVariable counter) {
      return StreamEx.of(PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class))
        .filter(call -> ExpressionUtils.isReferenceTo(container.extractIndexFromGetExpression(call), counter)).toList();
    }

    private static @NotNull String generateParameterName(PsiStatement body, List<PsiMethodCallExpression> getCalls) {
      if (getCalls.isEmpty()) return "ignored";
      return new VariableNameGenerator(body, VariableKind.PARAMETER).byExpression(getCalls.get(0)).generate(true);
    }

    private static void inlineVariable(PsiLocalVariable variable, PsiStatement body) {
      if (variable == null) return;
      if (!ControlFlowUtil.isEffectivelyFinal(variable, body)) return;
      List<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(variable, body);
      PsiExpression initializer = variable.getInitializer();
      if (initializer == null) return;
      references.forEach(reference -> CommonJavaInlineUtil.getInstance().inlineVariable(variable, initializer, reference, null));
      variable.delete();
    }

    private static void simplifyToExpressionLambda(PsiElement element) {
      PsiExpressionStatement expressionStatement = tryCast(element, PsiExpressionStatement.class);
      if (expressionStatement == null) return;
      PsiMethodCallExpression call = tryCast(expressionStatement.getExpression(), PsiMethodCallExpression.class);
      if (call == null) return;
      PsiExpression arg = ArrayUtil.getFirstElement(call.getArgumentList().getExpressions());
      PsiLambdaExpression lambdaExpression = tryCast(arg, PsiLambdaExpression.class);
      if (lambdaExpression == null) return;
      LambdaRefactoringUtil.simplifyToExpressionLambda(lambdaExpression);
    }
  }

  private static @Nullable PsiMethodCallExpression getLastMethodCall(PsiStatement[] statements) {
    PsiExpressionStatement expressionStatement = tryCast(ArrayUtil.getLastElement(statements), PsiExpressionStatement.class);
    if (expressionStatement == null) return null;
    return tryCast(expressionStatement.getExpression(), PsiMethodCallExpression.class);
  }

  private static @Nullable PsiLocalVariable getVariableToInline(PsiStatement statement, PsiStatement body) {
    PsiLocalVariable localVariable = IteratorDeclaration.getDeclaredVariable(statement);
    if (localVariable == null) return null;
    List<PsiReferenceExpression> references = VariableAccessUtils.getVariableReferences(localVariable, body);
    return references.size() != 1 ? null : localVariable;
  }
}