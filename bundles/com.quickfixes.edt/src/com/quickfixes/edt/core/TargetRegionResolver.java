package com.quickfixes.edt.core;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.resource.BslEventsService;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.DecorationExtInfo;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.GroupExtInfo;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.lcore.util.CaseInsensitiveString;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com.quickfixes.edt.Activator;

/**
 * Decides the correct module-structure region for a method (the "where should it go" rule),
 * reproducing the logic of the 1C:Code Style module-structure checks:
 * <ul>
 * <li>Form modules: the method is matched against the form event-handler bindings
 * ({@link BslEventsService#getEventHandlersContainer(Module)}); the concrete region depends on the
 * owning form element (the form itself, a header item, a table item, or a command). A non-handler
 * goes to the private region.</li>
 * <li>Other modules: an event handler ({@link Method#isEvent()}) goes to the event-handlers region
 * (except common modules); otherwise an exported method goes to the public region and a
 * non-exported one to the private region.</li>
 * </ul>
 * Region names are localized via {@link ModuleStructureSection} using the project's script variant.
 */
public final class TargetRegionResolver
{
    private final ModuleType moduleType;
    private final ScriptVariant scriptVariant;
    private final Map<CaseInsensitiveString, List<EObject>> formEventHandlers;

    TargetRegionResolver(ModuleType moduleType, ScriptVariant scriptVariant,
        Map<CaseInsensitiveString, List<EObject>> formEventHandlers)
    {
        this.moduleType = moduleType;
        this.scriptVariant = scriptVariant;
        this.formEventHandlers = formEventHandlers;
    }

    /**
     * Builds a resolver for the given module, pulling the script variant from the project and,
     * for form modules, the event-handler bindings from the platform's {@link BslEventsService}.
     *
     * @param module the BSL module
     * @param projectManager the EDT project manager (for the script variant)
     * @param eventsService the BSL events service (may be {@code null}; only used for forms)
     * @return a resolver
     */
    public static TargetRegionResolver forModule(Module module, IV8ProjectManager projectManager,
        BslEventsService eventsService)
    {
        ScriptVariant scriptVariant = ScriptVariant.ENGLISH;
        if (projectManager != null)
        {
            IV8Project project = projectManager.getProject(module);
            if (project != null && project.getScriptVariant() != null)
            {
                scriptVariant = project.getScriptVariant();
            }
        }
        ModuleType moduleType = module.getModuleType();
        Map<CaseInsensitiveString, List<EObject>> handlers = null;
        if (moduleType == ModuleType.FORM_MODULE && eventsService != null)
        {
            try
            {
                handlers = eventsService.getEventHandlersContainer(module);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to read form event handlers", e); //$NON-NLS-1$
            }
        }
        return new TargetRegionResolver(moduleType, scriptVariant, handlers);
    }

    /**
     * @param method the method to place
     * @return the target region name, or {@code null} if it cannot be determined
     */
    public String resolve(Method method)
    {
        if (moduleType == ModuleType.FORM_MODULE)
        {
            return resolveForForm(method);
        }
        if (method.isEvent() && moduleType != ModuleType.COMMON_MODULE)
        {
            return ModuleStructureSection.EVENT_HANDLERS.getName(scriptVariant);
        }
        ModuleStructureSection section =
            method.isExport() ? ModuleStructureSection.PUBLIC : ModuleStructureSection.PRIVATE;
        return section.getName(scriptVariant);
    }

    /** @return the module type this resolver was built for. */
    public ModuleType getModuleType()
    {
        return moduleType;
    }

    /** @return the script variant this resolver uses for localized region names. */
    public ScriptVariant getScriptVariant()
    {
        return scriptVariant;
    }

    private String resolveForForm(Method method)
    {
        List<EObject> containers = formEventHandlers == null ? null
            : formEventHandlers.get(new CaseInsensitiveString(method.getName()));
        if (containers == null || containers.isEmpty())
        {
            // Not bound to any form event: a plain helper goes to the private region
            // (form modules have no public region in the standard structure).
            return ModuleStructureSection.PRIVATE.getName(scriptVariant);
        }
        for (EObject container : containers)
        {
            if (container instanceof FormCommandHandlerContainer)
            {
                return ModuleStructureSection.FORM_COMMAND_EVENT_HANDLERS.getName(scriptVariant);
            }
            if (container instanceof EventHandlerContainer)
            {
                String region = resolveFormItemRegion((EventHandlerContainer)container);
                if (region != null)
                {
                    return region;
                }
            }
        }
        return null;
    }

    /**
     * Walks up from the event-handler container to the owning form element and maps it to a region.
     * A table has priority (an element inside a table belongs to the table-items region); the form
     * itself only wins when no inner element owns the handler.
     */
    private String resolveFormItemRegion(EventHandlerContainer container)
    {
        EObject owner = null;
        for (EObject e = container; e != null; e = e.eContainer())
        {
            if (e instanceof Table)
            {
                return ModuleStructureSection.FORM_TABLE_ITEMS_EVENT_HANDLERS.getName(scriptVariant)
                    + ((Table)e).getName();
            }
            else if (e instanceof FormField || e instanceof DecorationExtInfo || e instanceof GroupExtInfo)
            {
                owner = e;
            }
            else if (e instanceof Form && owner == null)
            {
                return ModuleStructureSection.FORM_EVENT_HANDLERS.getName(scriptVariant);
            }
        }
        if (owner != null)
        {
            return ModuleStructureSection.FORM_HEADER_ITEMS_EVENT_HANDLERS.getName(scriptVariant);
        }
        return null;
    }
}
