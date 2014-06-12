package com.jetbrains.python.newProject;

import com.intellij.facet.ui.ValidationResult;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.util.List;

public abstract class PythonProjectGenerator {
  private final List<SettingsListener> myListeners = ContainerUtil.newArrayList();

  @Nullable
  public JComponent getSettingsPanel(File baseDir) throws ProcessCanceledException {
    return null;
  }

  public Object getProjectSettings() {
    return new PyNewProjectSettings();
  }

  public ValidationResult warningValitation() {
    return ValidationResult.OK;
  }

  public void addSettingsStateListener(@NotNull SettingsListener listener) {
    myListeners.add(listener);
  }

  public interface SettingsListener {
    void stateChanged();
  }

  public void fireStateChanged() {
    for (SettingsListener listener : myListeners) {
      listener.stateChanged();
    }
  }
}
