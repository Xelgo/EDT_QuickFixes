package com.quickfixes.edt.handlers;

import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.BslEventsService;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.quickfixes.edt.Activator;
import com.quickfixes.edt.EdtServices;
import com.quickfixes.edt.core.FlaggedMethodCollector;
import com.quickfixes.edt.core.MethodRegionMover;
import com.quickfixes.edt.core.TargetRegionResolver;
import com.quickfixes.edt.ui.ResultDialog;

/**
 * "Quick Fixes &rarr; Move methods to correct regions": collects the methods in the active module
 * flagged by {@code module-structure-method-in-regions}, moves each into its correct region (when
 * that region exists), and reports the outcome.
 */
public class MoveMethodsToRegionsHandler extends AbstractHandler
{
    private static final String TITLE = "Quick Fixes"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null)
        {
            MessageDialog.openInformation(shell, TITLE, "Нет активного редактора."); //$NON-NLS-1$
            return null;
        }
        // For a form/object module the active editor is a multi-page editor; the BSL module is an
        // embedded XtextEditor reachable via the adapter.
        XtextEditor xtextEditor = editor.getAdapter(XtextEditor.class);
        if (xtextEditor == null)
        {
            MessageDialog.openInformation(shell, TITLE, "Активный редактор — не модуль BSL."); //$NON-NLS-1$
            return null;
        }
        final IXtextDocument document = xtextEditor.getDocument();

        // Resolve the .bsl file from the Xtext editor itself (NOT the outer editor's input, which for
        // a form/object editor is the form/object, not a file). Fall back to the resource URI.
        IFile file = resolveModuleFile(xtextEditor, document);
        if (file == null || !file.exists())
        {
            MessageDialog.openInformation(shell, TITLE, "Не удалось определить файл модуля."); //$NON-NLS-1$
            return null;
        }
        IProject project = file.getProject();

        EdtServices services = Activator.getDefault().getServices();
        IMarkerManager markerManager = services.getMarkerManager();
        ICheckRepository checkRepository = services.getCheckRepository();
        final IV8ProjectManager projectManager = services.getV8ProjectManager();
        if (markerManager == null || checkRepository == null || projectManager == null)
        {
            MessageDialog.openError(shell, TITLE, "Сервисы EDT недоступны."); //$NON-NLS-1$
            return null;
        }

        Set<Integer> flaggedLines =
            FlaggedMethodCollector.collectFlaggedLines(markerManager, checkRepository, project, file);
        if (flaggedLines.isEmpty())
        {
            MessageDialog.openInformation(shell, TITLE,
                "В текущем модуле нет методов в некорректных областях " //$NON-NLS-1$
                    + "(module-structure-method-in-regions / module-structure-form-event-regions)."); //$NON-NLS-1$
            return null;
        }

        final BslEventsService eventsService = services.getBslEventsService();
        final Set<Integer> lines = flaggedLines;
        MessageDialogWithToggle options = MessageDialogWithToggle.openOkCancelConfirm(shell, TITLE,
            "Перенести методы в корректные области?", //$NON-NLS-1$
            "Создавать отсутствующие области", true, null, null); //$NON-NLS-1$
        if (options.getReturnCode() != Window.OK)
        {
            return null;
        }
        final boolean createMissingRegions = options.getToggleState();

        MethodRegionMover.Plan plan =
            document.readOnly(new IUnitOfWork<MethodRegionMover.Plan, XtextResource>()
            {
                @Override
                public MethodRegionMover.Plan exec(XtextResource resource) throws Exception
                {
                    TargetRegionResolver resolver = null;
                    if (!resource.getContents().isEmpty()
                        && resource.getContents().get(0) instanceof Module)
                    {
                        resolver = TargetRegionResolver.forModule(
                            (Module)resource.getContents().get(0), projectManager, eventsService);
                    }
                    return MethodRegionMover.compute(document, resource, lines, resolver, createMissingRegions);
                }
            });

        if (plan.moveCount > 0)
        {
            IRewriteTarget rewriteTarget = editor.getAdapter(IRewriteTarget.class);
            if (rewriteTarget == null)
            {
                rewriteTarget = xtextEditor.getAdapter(IRewriteTarget.class);
            }
            if (rewriteTarget != null)
            {
                rewriteTarget.beginCompoundChange();
            }
            try
            {
                plan.edit.apply(document);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to apply method move edit", e); //$NON-NLS-1$
                MessageDialog.openError(shell, TITLE,
                    "Не удалось применить перенос: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }
            finally
            {
                if (rewriteTarget != null)
                {
                    rewriteTarget.endCompoundChange();
                }
            }
        }

        new ResultDialog(shell, "Перенос методов в корректные области", //$NON-NLS-1$
            "Перенесено: " + plan.result.getSuccesses().size() //$NON-NLS-1$
                + ", создано областей: " + plan.createdRegionCount //$NON-NLS-1$
                + ", не удалось: " + plan.result.getFailures().size(), //$NON-NLS-1$
            "Метод", "Область / причина", plan.result).open(); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /**
     * Resolves the {@code .bsl} file backing the Xtext editor: first via the editor input, then via
     * the parsed resource's platform URI (robust for embedded form/object module editors).
     */
    private static IFile resolveModuleFile(XtextEditor xtextEditor, IXtextDocument document)
    {
        IFile file = ResourceUtil.getFile(xtextEditor.getEditorInput());
        if (file != null)
        {
            return file;
        }
        try
        {
            URI uri = document.readOnly(resource -> resource.getURI());
            if (uri != null && uri.isPlatformResource())
            {
                IPath path = IPath.fromOSString(uri.toPlatformString(true));
                return ResourcesPlugin.getWorkspace().getRoot().getFile(path);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to resolve module file from resource URI", e); //$NON-NLS-1$
        }
        return null;
    }
}
