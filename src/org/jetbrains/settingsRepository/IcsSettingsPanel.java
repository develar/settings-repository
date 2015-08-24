package org.jetbrains.settingsRepository;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;

public class IcsSettingsPanel extends DialogWrapper {
  private JPanel panel;
  private TextFieldWithBrowseButton urlTextField;
  private final Action[] syncActions;

  public IcsSettingsPanel(@Nullable Project project) {
    super(project, true);

    urlTextField.setText(SettingsRepositoryPackage.getIcsManager().getRepositoryManager().getUpstream());
    urlTextField.addBrowseFolderListener(new TextBrowseFolderListener(FileChooserDescriptorFactory.createSingleFolderDescriptor()));

    syncActions = SettingsRepositoryPackage.createMergeActions(project, urlTextField, getContentPane(), new Function0<Unit>() {
      @Override
      public Unit invoke() {
        doOKAction();
        return null;
      }
    });

    urlTextField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        SettingsRepositoryPackage.updateSyncButtonState(StringUtil.nullize(urlTextField.getText()), syncActions);
      }
    });

    urlTextField.requestFocusInWindow();
    SettingsRepositoryPackage.updateSyncButtonState(StringUtil.nullize(urlTextField.getText()), syncActions);

    setTitle(IcsBundle.message("settings.panel.title"));
    setResizable(false);
    init();
  }

  @Nullable
  @Override
  public JComponent getPreferredFocusedComponent() {
    return urlTextField;
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    return panel;
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    return syncActions;
  }
}