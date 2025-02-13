/*
 * Copyright 2003-2014 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.inheritance;

import com.intellij.pom.java.JavaFeature;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.util.InheritanceUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class ExtendsAnnotationInspection extends BaseInspection {

  @Override
  public @NotNull String getID() {
    return "ClassExplicitlyAnnotation";
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  public @NotNull String buildErrorString(Object... infos) {
    final PsiClass containingClass = (PsiClass)infos[0];
    return containingClass.isInterface()
           ? InspectionGadgetsBundle.message("extends.annotation.interface.problem.descriptor", containingClass.getName())
           : InspectionGadgetsBundle.message("extends.annotation.problem.descriptor", containingClass.getName());
  }

  @Override
  public @NotNull Set<@NotNull JavaFeature> requiredFeatures() {
    return Set.of(JavaFeature.ANNOTATIONS);
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ExtendsAnnotationVisitor();
  }

  private static class ExtendsAnnotationVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitClass(@NotNull PsiClass aClass) {
      if (aClass.isAnnotationType() || InheritanceUtil.isInheritor(aClass, "javax.enterprise.util.AnnotationLiteral")) {
        return;
      }
      checkReferenceList(aClass.getExtendsList(), aClass);
      checkReferenceList(aClass.getImplementsList(), aClass);
    }

    private void checkReferenceList(PsiReferenceList referenceList,
                                    PsiClass containingClass) {
      if (referenceList == null) {
        return;
      }
      final PsiJavaCodeReferenceElement[] elements =
        referenceList.getReferenceElements();
      for (final PsiJavaCodeReferenceElement element : elements) {
        final PsiElement referent = element.resolve();
        if (!(referent instanceof PsiClass psiClass)) {
          continue;
        }
        if (psiClass.isAnnotationType()) {
          registerError(element, containingClass);
        }
      }
    }
  }
}