// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.startup.StartupActivity;

public final class UndoTreeStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) UndoTreeService.getInstance(project).initialize();
        });
    }
}
