package com.quickfixes.edt.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;

import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * Computes which standard regions a module is missing and builds the edit that inserts the chosen
 * ones, each at its canonical position relative to the existing top-level regions. Pure insertions —
 * existing regions and methods are left untouched; nothing is duplicated.
 *
 * <p>Two steps so the user can choose: {@link #candidates} lists the regions that could be created (in
 * canonical order, all "available"); {@link #buildPlan} builds the insert edit for the selected subset.
 * For a form module the suffixed form-table-handlers region is offered once per table:
 * {@code ОбработчикиСобытийЭлементовТаблицыФормы&lt;TableName&gt;}.</p>
 */
public final class RegionCreator
{
    private RegionCreator()
    {
    }

    /** A region that can be created: its name, where to insert it, and the text block. */
    public static final class Candidate
    {
        /** The region name (with table suffix for form-table handlers). */
        public final String name;
        /** Insert offset in the current document. */
        public final int insertOffset;
        /** The {@code #Region…#EndRegion} text to insert. */
        public final String blockText;

        Candidate(String name, int insertOffset, String blockText)
        {
            this.name = name;
            this.insertOffset = insertOffset;
            this.blockText = blockText;
        }
    }

    /** The computed edit plus the outcome. */
    public static final class Plan
    {
        /** The combined insert edit (empty when {@link #createCount} is 0). */
        public final MultiTextEdit edit;
        /** The outcome (created regions). */
        public final OpResult result;
        /** Number of regions to be created. */
        public final int createCount;

        Plan(MultiTextEdit edit, OpResult result, int createCount)
        {
            this.edit = edit;
            this.result = result;
            this.createCount = createCount;
        }
    }

    /**
     * @param document the editor document
     * @param moduleType the module type
     * @param scriptVariant the project's script variant (RU/EN)
     * @param formTableNames table names for a form module (may be {@code null}/empty otherwise)
     * @return the missing standard regions that can be created, in canonical order
     */
    public static List<Candidate> candidates(IDocument document, ModuleType moduleType, ScriptVariant scriptVariant,
        List<String> formTableNames)
    {
        List<Candidate> candidates = new ArrayList<>();
        List<ModuleStructureSection> sections = ModuleStructure.sectionsFor(moduleType);
        if (sections.isEmpty())
        {
            return candidates;
        }
        List<String> tables = formTableNames == null ? List.of() : formTableNames;
        List<String> lines = BslModuleText.readLines(document);
        List<BslModuleText.Region> top = BslModuleText.topLevelRegions(BslModuleText.scanRegions(lines));
        boolean ru = scriptVariant == ScriptVariant.RUSSIAN;

        for (int i = 0; i < sections.size(); i++)
        {
            ModuleStructureSection section = sections.get(i);
            if (section.isSuffixed())
            {
                String base = section.getName(scriptVariant);
                for (String tableName : tables)
                {
                    addCandidate(base + tableName, i, sections, top, document, scriptVariant, ru, candidates);
                }
            }
            else
            {
                addCandidate(section.getName(scriptVariant), i, sections, top, document, scriptVariant, ru,
                    candidates);
            }
        }
        return candidates;
    }

    /**
     * @param document the editor document
     * @param moduleType the module type
     * @param scriptVariant the project's script variant (RU/EN)
     * @param name exact region name to create
     * @return a candidate for the missing region, or {@code null} if the region is not standard,
     *         already exists, or its insertion point cannot be computed
     */
    public static Candidate candidate(IDocument document, ModuleType moduleType, ScriptVariant scriptVariant,
        String name)
    {
        List<ModuleStructureSection> sections = ModuleStructure.sectionsFor(moduleType);
        if (sections.isEmpty() || name == null)
        {
            return null;
        }

        List<String> lines = BslModuleText.readLines(document);
        List<BslModuleText.Region> top = BslModuleText.topLevelRegions(BslModuleText.scanRegions(lines));
        if (existsTop(top, name))
        {
            return null;
        }

        int sectionIndex = ModuleStructure.indexOfRegion(sections, name, scriptVariant);
        if (sectionIndex < 0)
        {
            return null;
        }

        try
        {
            boolean ru = scriptVariant == ScriptVariant.RUSSIAN;
            int insertOffset = insertionOffset(sectionIndex, sections, top, document, scriptVariant);
            String block = (ru ? "#Область " : "#Region ") + name + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (ru ? "#КонецОбласти" : "#EndRegion") + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return new Candidate(name, insertOffset, block);
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    private static void addCandidate(String name, int sectionIndex, List<ModuleStructureSection> sections,
        List<BslModuleText.Region> top, IDocument document, ScriptVariant scriptVariant, boolean ru,
        List<Candidate> candidates)
    {
        if (existsTop(top, name))
        {
            return;
        }
        int insertOffset;
        try
        {
            insertOffset = insertionOffset(sectionIndex, sections, top, document, scriptVariant);
        }
        catch (BadLocationException e)
        {
            return;
        }
        String block = (ru ? "#Область " : "#Region ") + name + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (ru ? "#КонецОбласти" : "#EndRegion") + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        candidates.add(new Candidate(name, insertOffset, block));
    }

    /**
     * Builds the insert edit for the selected candidates.
     *
     * @param document the editor document
     * @param ordered all candidates (from {@link #candidates}), used to keep canonical order
     * @param selected the candidates the user chose
     * @return the plan
     */
    public static Plan buildPlan(IDocument document, List<Candidate> ordered, Set<Candidate> selected)
    {
        OpResult result = new OpResult();
        MultiTextEdit root = new MultiTextEdit();
        Map<Integer, StringBuilder> inserts = new TreeMap<>();
        int createCount = 0;
        for (Candidate candidate : ordered)
        {
            if (!selected.contains(candidate))
            {
                continue;
            }
            inserts.computeIfAbsent(Integer.valueOf(candidate.insertOffset), k -> new StringBuilder())
                .append(candidate.blockText);
            result.addSuccess(candidate.name, "создана"); //$NON-NLS-1$
            createCount++;
        }
        for (Map.Entry<Integer, StringBuilder> entry : inserts.entrySet())
        {
            int offset = entry.getKey().intValue();
            String text = entry.getValue().toString();
            if (offset > 0)
            {
                try
                {
                    if (document.getChar(offset - 1) != '\n')
                    {
                        text = "\n" + text; //$NON-NLS-1$
                    }
                }
                catch (BadLocationException e)
                {
                    // keep text as is
                }
            }
            root.addChild(new InsertEdit(offset, text));
        }
        return new Plan(root, result, createCount);
    }

    private static boolean existsTop(List<BslModuleText.Region> top, String name)
    {
        for (BslModuleText.Region region : top)
        {
            if (region.name.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    private static int insertionOffset(int sectionIndex, List<ModuleStructureSection> sections,
        List<BslModuleText.Region> top, IDocument document, ScriptVariant scriptVariant) throws BadLocationException
    {
        for (BslModuleText.Region region : top)
        {
            int idx = ModuleStructure.indexOfRegion(sections, region.name, scriptVariant);
            if (idx > sectionIndex)
            {
                return document.getLineOffset(region.startLine - 1);
            }
        }
        if (!top.isEmpty())
        {
            BslModuleText.Region last = top.get(top.size() - 1);
            return last.endLine < document.getNumberOfLines() ? document.getLineOffset(last.endLine)
                : document.getLength();
        }
        return document.getLength();
    }
}
