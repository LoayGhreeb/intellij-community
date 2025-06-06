// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lang.properties.codeInspection.unused;

import com.intellij.lang.properties.psi.Property;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

final class LoggerConfigPropertyUsageProvider implements ImplicitPropertyUsageProvider {
  private static final @NonNls String[] LOGGER_PROPERTIES_KEYWORDS = new String[]{"log4j", "commons-logging", "logging"};

  @Override
  public boolean isUsed(@NotNull Property property) {
    final String propertiesFileName = property.getPropertiesFile().getName();
    for (String keyword : LOGGER_PROPERTIES_KEYWORDS) {
      if (propertiesFileName.startsWith(keyword)) {
        return true;
      }
    }
    return false;
  }
}
