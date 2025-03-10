// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.move.moveInner;

import com.intellij.java.refactoring.JavaRefactoringBundle;
import com.intellij.lang.Language;
import com.intellij.lang.jvm.JvmLanguage;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.move.MoveCallback;
import com.intellij.refactoring.move.MoveHandlerDelegate;
import com.intellij.refactoring.move.moveClassesOrPackages.JavaMoveClassesOrPackagesHandler;
import com.intellij.refactoring.move.moveMembers.MoveMembersHandler;
import com.intellij.refactoring.util.RadioUpDownListener;
import com.intellij.ui.components.JBBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class MoveInnerToUpperOrMembersHandler extends MoveHandlerDelegate {
  @Override
  public boolean canMove(final PsiElement[] elements, final @Nullable PsiElement targetContainer, @Nullable PsiReference reference) {
    if (elements.length != 1) return false;
    PsiElement element = elements [0];
    return isStaticInnerClass(element);
  }

  private static boolean isStaticInnerClass(final PsiElement element) {
    return element instanceof PsiClass && element.getParent() instanceof PsiClass &&
           ((PsiClass) element).hasModifierProperty(PsiModifier.STATIC);
  }

  @Override
  public void doMove(final Project project, final PsiElement[] elements, final PsiElement targetContainer, final MoveCallback callback) {
    SelectInnerOrMembersRefactoringDialog dialog = new SelectInnerOrMembersRefactoringDialog((PsiClass)elements[0], project);
    if (!dialog.showAndGet()) {
      return;
    }
    MoveHandlerDelegate delegate = dialog.getRefactoringHandler();
    if (delegate != null) {
      delegate.doMove(project, elements, targetContainer, callback);
    }
  }

  @Override
  public boolean tryToMove(final PsiElement element, final Project project, final DataContext dataContext, final PsiReference reference,
                           final Editor editor) {
    if (isStaticInnerClass(element) && !JavaMoveClassesOrPackagesHandler.isReferenceInAnonymousClass(reference)) {
      final PsiElement targetContainer = LangDataKeys.TARGET_PSI_ELEMENT.getData(dataContext);
      PsiClass aClass = (PsiClass)element;
      SelectInnerOrMembersRefactoringDialog dialog = new SelectInnerOrMembersRefactoringDialog(aClass, project);
      if (dialog.showAndGet()) {
        final MoveHandlerDelegate moveHandlerDelegate = dialog.getRefactoringHandler();
        if (moveHandlerDelegate != null) {
          moveHandlerDelegate.doMove(project, new PsiElement[]{aClass}, targetContainer, null);
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public boolean supportsLanguage(@NotNull Language language) {
    return language instanceof JvmLanguage;
  }

  @Override
  public @Nullable String getActionName(PsiElement @NotNull [] elements) {
    return JavaRefactoringBundle.message("move.inner.class.action.name");
  }

  private static class SelectInnerOrMembersRefactoringDialog extends DialogWrapper {
    private JRadioButton myRbMoveInner;
    private JRadioButton myRbMoveMembers;
    private final String myClassName;

    SelectInnerOrMembersRefactoringDialog(final PsiClass innerClass, Project project) {
      super(project, true);
      setTitle(RefactoringBundle.message("select.refactoring.title"));
      myClassName = innerClass.getName();
      init();
    }

    @Override
    protected JComponent createNorthPanel() {
      return new JLabel(RefactoringBundle.message("what.would.you.like.to.do"));
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
      return myRbMoveInner;
    }

    @Override
    protected String getDimensionServiceKey() {
      return "#com.intellij.refactoring.move.MoveHandler.SelectRefactoringDialog";
    }

    @Override
    protected JComponent createCenterPanel() {
      JPanel panel = new JPanel(new BorderLayout());
      myRbMoveInner = new JRadioButton();
      myRbMoveInner.setText(JavaRefactoringBundle.message("move.inner.class.to.upper.level", myClassName));
      myRbMoveInner.setSelected(true);
      myRbMoveMembers = new JRadioButton();
      myRbMoveMembers.setText(JavaRefactoringBundle.message("move.inner.class.to.another.class", myClassName));


      ButtonGroup gr = new ButtonGroup();
      gr.add(myRbMoveInner);
      gr.add(myRbMoveMembers);

      RadioUpDownListener.installOn(myRbMoveInner, myRbMoveMembers);

      JBBox box = JBBox.createVerticalBox();
      box.add(Box.createVerticalStrut(5));
      box.add(myRbMoveInner);
      box.add(myRbMoveMembers);
      panel.add(box, BorderLayout.CENTER);
      return panel;
    }

    public @Nullable MoveHandlerDelegate getRefactoringHandler() {
      if (myRbMoveInner.isSelected()) {
        return new MoveInnerToUpperHandler();
      }
      if (myRbMoveMembers.isSelected()) {
        return new MoveMembersHandler();
      }
      return null;
    }
  }
}
