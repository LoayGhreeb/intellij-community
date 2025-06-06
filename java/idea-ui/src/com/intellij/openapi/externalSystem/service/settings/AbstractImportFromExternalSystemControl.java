// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.service.settings;

import com.intellij.ide.JavaUiBundle;
import com.intellij.ide.util.BrowseFilesListener;
import com.intellij.ide.util.projectWizard.NamePathComponent;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings;
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener;
import com.intellij.openapi.externalSystem.util.*;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.projectImport.ProjectFormatPanel;
import com.intellij.ui.HideableTitledPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

/**
 * A control which knows how to manage settings of external project being imported.
 */
public abstract class AbstractImportFromExternalSystemControl<
  ProjectSettings extends ExternalProjectSettings,
  L extends ExternalSystemSettingsListener<ProjectSettings>,
  SystemSettings extends AbstractExternalSystemSettings<SystemSettings, ProjectSettings, L>>
  extends AbstractSettingsControl
{
  private final @NotNull SystemSettings  mySystemSettings;
  private final @NotNull ProjectSettings myProjectSettings;

  private final @NotNull PaintAwarePanel           myComponent              = new PaintAwarePanel(new GridBagLayout());
  private final @NotNull NamePathComponent myLinkedProjectPathField;
  private final @Nullable HideableTitledPanel hideableSystemSettingsPanel;
  private final @NotNull ProjectFormatPanel myProjectFormatPanel;

  private final @NotNull ExternalSystemSettingsControl<ProjectSettings> myProjectSettingsControl;
  private final @NotNull ProjectSystemId                                myExternalSystemId;
  private final @Nullable ExternalSystemSettingsControl<SystemSettings>  mySystemSettingsControl;

  private boolean myShowProjectFormatPanel;
  private final JLabel myProjectFormatLabel;

  protected AbstractImportFromExternalSystemControl(@NotNull ProjectSystemId externalSystemId,
                                                    @NotNull SystemSettings systemSettings,
                                                    @NotNull ProjectSettings projectSettings)
  {
    this(externalSystemId, systemSettings, projectSettings, false);
  }

  @SuppressWarnings("AbstractMethodCallInConstructor")
  protected AbstractImportFromExternalSystemControl(@NotNull ProjectSystemId externalSystemId,
                                                    @NotNull SystemSettings systemSettings,
                                                    @NotNull ProjectSettings projectSettings,
                                                    boolean showProjectFormatPanel)
  {
    myExternalSystemId = externalSystemId;
    mySystemSettings = systemSettings;
    myProjectSettings = projectSettings;
    myProjectSettings.setupNewProjectDefault();

    myProjectSettingsControl = createProjectSettingsControl(projectSettings);
    mySystemSettingsControl = createSystemSettingsControl(systemSettings);
    myShowProjectFormatPanel = showProjectFormatPanel;

    String projectPathTitle = ExternalSystemBundle.message("settings.label.select.project", externalSystemId.getReadableName());
    ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(externalSystemId);
    assert manager != null;

    myLinkedProjectPathField = new NamePathComponent("", projectPathTitle, projectPathTitle, "", false, false);
    myLinkedProjectPathField.setNameComponentVisible(false);
    myLinkedProjectPathField.setNameValue("untitled");

    var fileChooserDescriptor = manager.getExternalProjectDescriptor().withTitle(projectPathTitle);
    var browseButtonActionListener = new BrowseFilesListener(myLinkedProjectPathField.getPathComponent(), fileChooserDescriptor);

    myLinkedProjectPathField.getPathPanel().setBrowseButtonActionListener(browseButtonActionListener);
    myLinkedProjectPathField.getPathComponent().getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        onLinkedProjectPathChange(myLinkedProjectPathField.getPath());
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        onLinkedProjectPathChange(myLinkedProjectPathField.getPath());
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        onLinkedProjectPathChange(myLinkedProjectPathField.getPath());
      }
    });
    myComponent.add(myLinkedProjectPathField, ExternalSystemUiUtil.getFillLineConstraints(0));
    myProjectSettingsControl.fillUi(myComponent, 0);

    myProjectFormatPanel = new ProjectFormatPanel();
    myProjectFormatLabel = new JLabel(JavaUiBundle.message("settings.label.project.format"));
    myComponent.add(myProjectFormatLabel, ExternalSystemUiUtil.getLabelConstraints(0));
    myComponent.add(myProjectFormatPanel.getStorageFormatComboBox(), ExternalSystemUiUtil.getFillLineConstraints(0));

    if (mySystemSettingsControl != null) {
      final PaintAwarePanel mySystemSettingsControlPanel = new PaintAwarePanel();
      mySystemSettingsControl.fillUi(mySystemSettingsControlPanel, 0);

      JPanel panel = new JPanel(new BorderLayout());
      panel.add(mySystemSettingsControlPanel, BorderLayout.CENTER);
      hideableSystemSettingsPanel = new HideableTitledPanel(
        ExternalSystemBundle.message("settings.title.system.settings"), false);
      hideableSystemSettingsPanel.setContentComponent(panel);
      hideableSystemSettingsPanel.setOn(false);
      myComponent.add(hideableSystemSettingsPanel, ExternalSystemUiUtil.getFillLineConstraints(0));
    } else {
      hideableSystemSettingsPanel = null;
    }
    ExternalSystemUiUtil.fillBottom(myComponent);
  }

  /**
   * This control is assumed to be used at least at two circumstances:
   * <pre>
   * <ul>
   *   <li>new ide project is being created on the external project basis;</li>
   *   <li>new ide module(s) is being added to the existing ide project on the external project basis;</li>
   * </ul>
   * </pre>
   * We need to differentiate these situations, for example, we don't want to allow linking an external project to existing ide
   * project if it's already linked.
   * <p/>
   * This property helps us to achieve that - when an ide project is defined, that means that new modules are being imported
   * to that ide project from external project; when this property is {@code null} that means that new ide project is being
   * created on the target external project basis.
   * 
   * @param currentProject  current ide project (if any)
   */
  public void setCurrentProject(@Nullable Project currentProject) {
    setProject(currentProject);
  }

  protected abstract void onLinkedProjectPathChange(@NotNull String path);

  /**
   * Creates a control for managing given project settings.
   *
   * @param settings  target external project settings
   * @return          control for managing given project settings
   */
  protected abstract @NotNull ExternalSystemSettingsControl<ProjectSettings> createProjectSettingsControl(@NotNull ProjectSettings settings);

  /**
   * Creates a control for managing given system-level settings (if any).
   *
   * @param settings  target system settings
   * @return          a control for managing given system-level settings;
   *                  {@code null} if current external system doesn't have system-level settings (only project-level settings)
   */
  protected abstract @Nullable ExternalSystemSettingsControl<SystemSettings> createSystemSettingsControl(@NotNull SystemSettings settings);

  public @NotNull JComponent getComponent() {
    return myComponent;
  }

  public @NotNull ExternalSystemSettingsControl<ProjectSettings> getProjectSettingsControl() {
    return myProjectSettingsControl;
  }

  public @Nullable ExternalSystemSettingsControl<SystemSettings> getSystemSettingsControl() {
    return mySystemSettingsControl;
  }

  public void setLinkedProjectPath(@NotNull String path) {
    myProjectSettings.setExternalProjectPath(path);
    myLinkedProjectPathField.setPath(path);
  }

  public @NotNull SystemSettings getSystemSettings() {
    return mySystemSettings;
  }

  public @NotNull ProjectSettings getProjectSettings() {
    return myProjectSettings;
  }

  public void setShowProjectFormatPanel(boolean showProjectFormatPanel) {
    myShowProjectFormatPanel = showProjectFormatPanel;
  }

  public void reset() {
    reset(null, null);
  }

  @Override
  public void reset(@Nullable WizardContext wizardContext, @Nullable Project project) {
    super.reset(wizardContext, project);
    myLinkedProjectPathField.setNameComponentVisible(false);
    myLinkedProjectPathField.setNameValue("untitled");
    myLinkedProjectPathField.setPath("");
    myProjectSettingsControl.reset(wizardContext);
    if (mySystemSettingsControl != null) {
      mySystemSettingsControl.reset(wizardContext);
    }
    if (hideableSystemSettingsPanel != null) {
      hideableSystemSettingsPanel.setOn(false);
    }
    myProjectFormatLabel.setVisible(myShowProjectFormatPanel);
    myProjectFormatPanel.setVisible(myShowProjectFormatPanel);
    myProjectFormatPanel.getPanel().setVisible(myShowProjectFormatPanel);
    myProjectFormatPanel.getStorageFormatComboBox().setVisible(myShowProjectFormatPanel);
  }

  public void apply() {
    String linkedProjectPath = myLinkedProjectPathField.getPath();
    //noinspection ConstantConditions
    myProjectSettings.setExternalProjectPath(ExternalSystemApiUtil.normalizePath(linkedProjectPath));
    myProjectSettingsControl.apply(myProjectSettings);
    if (mySystemSettingsControl != null) {
      mySystemSettingsControl.apply(mySystemSettings);
    }
  }

  public @Nullable ProjectFormatPanel getProjectFormatPanel() {
    return myShowProjectFormatPanel ? myProjectFormatPanel : null;
  }

  public boolean validate(WizardContext wizardContext, boolean defaultFormat) throws ConfigurationException {
    if (ApplicationManager.getApplication().isHeadlessEnvironment()) return true;

    if (!myProjectSettingsControl.validate(myProjectSettings)) return false;
    if (mySystemSettingsControl != null && !mySystemSettingsControl.validate(mySystemSettings)) return false;
    String linkedProjectPath = myLinkedProjectPathField.getPath();
    Project currentProject = getProject();
    if (StringUtil.isEmpty(linkedProjectPath)) {
      throw new ConfigurationException(JavaUiBundle.message("error.project.undefined"));
    }
    else if (currentProject != null) {
      ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(myExternalSystemId);
      assert manager != null;
      AbstractExternalSystemSettings<?, ?, ?> settings = manager.getSettingsProvider().fun(currentProject);
      if (settings.getLinkedProjectSettings(linkedProjectPath) != null) {
        throw new ConfigurationException(ExternalSystemBundle.message("error.project.already.registered"));
      }
    }

    return !(wizardContext.isCreatingNewProject() && !myLinkedProjectPathField.validateNameAndPath(wizardContext, defaultFormat));
  }
}
