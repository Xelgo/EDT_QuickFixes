package com.quickfixes.edt.core;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * Builds the text edit that relocates each flagged method into its correct region, and the
 * {@link OpResult} describing the outcome. Pure computation over the editor document — the caller
 * applies the returned edit. Region scanning and method ranges come from {@link BslModuleText}.
 */
public final class MethodRegionMover
{
    private MethodRegionMover()
    {
    }

    /** The computed edit plus the human-readable outcome. */
    public static final class Plan
    {
        /** The combined edit to apply to the document (empty when {@link #moveCount} is 0). */
        public final MultiTextEdit edit;
        /** The outcome to show to the user. */
        public final OpResult result;
        /** Number of methods that will actually be moved. */
        public final int moveCount;
        /** Number of missing regions that will be created. */
        public final int createdRegionCount;

        Plan(MultiTextEdit edit, OpResult result, int moveCount, int createdRegionCount)
        {
            this.edit = edit;
            this.result = result;
            this.moveCount = moveCount;
            this.createdRegionCount = createdRegionCount;
        }
    }

    /**
     * @param document the editor document (read inside the same read boundary as {@code resource})
     * @param resource the parsed Xtext resource of the active module
     * @param flaggedLines 1-based lines flagged by the check
     * @param resolver the target-region rule
     * @return the move plan
     */
    public static Plan compute(IDocument document, XtextResource resource, Set<Integer> flaggedLines,
        TargetRegionResolver resolver)
    {
        return compute(document, resource, flaggedLines, resolver, false);
    }

    /**
     * @param document the editor document (read inside the same read boundary as {@code resource})
     * @param resource the parsed Xtext resource of the active module
     * @param flaggedLines 1-based lines flagged by the check
     * @param resolver the target-region rule
     * @param createMissingRegions whether missing target regions should be created and populated
     * @return the move plan
     */
    public static Plan compute(IDocument document, XtextResource resource, Set<Integer> flaggedLines,
        TargetRegionResolver resolver, boolean createMissingRegions)
    {
        OpResult result = new OpResult();
        MultiTextEdit root = new MultiTextEdit();
        if (resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof Module)
            || resolver == null)
        {
            return new Plan(root, result, 0, 0);
        }
        Module module = (Module)resource.getContents().get(0);
        List<String> lines = BslModuleText.readLines(document);
        List<BslModuleText.Region> regions = BslModuleText.scanRegions(lines);

        Map<Integer, StringBuilder> inserts = new TreeMap<>();
        Map<String, MissingRegion> missingRegions = new LinkedHashMap<>();
        int moveCount = 0;

        for (Method method : module.allMethods())
        {
            INode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null)
            {
                continue;
            }
            int startLine = node.getStartLine();
            int endLine = node.getEndLine();
            if (!intersectsFlagged(flaggedLines, startLine, endLine))
            {
                continue;
            }

            String name = method.getName();
            String target = resolver.resolve(method);
            if (target == null)
            {
                result.addFailure(name, "не удалось определить целевую область"); //$NON-NLS-1$
                continue;
            }
            BslModuleText.Region current = BslModuleText.narrowestContaining(regions, startLine);
            if (current != null && current.name.equalsIgnoreCase(target))
            {
                result.addFailure(name, "уже в области «" + target + "»"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            BslModuleText.Region targetRegion = BslModuleText.findByName(regions, target);
            if (targetRegion == null)
            {
                if (!createMissingRegions)
                {
                    result.addFailure(name, "область «" + target + "» отсутствует в модуле"); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }

                MissingRegion missingRegion = missingRegions.get(target.toLowerCase(Locale.ROOT));
                if (missingRegion == null)
                {
                    RegionCreator.Candidate candidate = RegionCreator.candidate(document, resolver.getModuleType(),
                        resolver.getScriptVariant(), target);
                    if (candidate == null)
                    {
                        result.addFailure(name,
                            "область «" + target + "» отсутствует и не может быть создана"); //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    }
                    missingRegion = new MissingRegion(candidate);
                    missingRegions.put(target.toLowerCase(Locale.ROOT), missingRegion);
                }

                if (moveToMissingRegion(document, lines, root, missingRegion, method, startLine, endLine, name,
                    target, result))
                {
                    moveCount++;
                }
                continue;
            }

            try
            {
                int[] range = BslModuleText.methodRange(document, lines, startLine, endLine);
                int delStart = range[0];
                int delEnd = range[1];
                int insertOffset = document.getLineOffset(targetRegion.endLine - 1);
                if (insertOffset >= delStart && insertOffset < delEnd)
                {
                    result.addFailure(name, "целевая область пересекается с методом"); //$NON-NLS-1$
                    continue;
                }
                // Method body + exactly one trailing blank line so moved methods are not glued together
                // (or to the closing #EndRegion).
                String text = document.get(delStart, delEnd - delStart).stripTrailing() + "\n\n"; //$NON-NLS-1$
                root.addChild(new DeleteEdit(delStart, delEnd - delStart));
                inserts.computeIfAbsent(Integer.valueOf(insertOffset), k -> new StringBuilder()).append(text);
                result.addSuccess(name, target);
                moveCount++;
            }
            catch (BadLocationException e)
            {
                result.addFailure(name, "не удалось вычислить диапазон метода"); //$NON-NLS-1$
            }
        }

        int createdRegionCount = 0;
        for (MissingRegion missingRegion : missingRegions.values())
        {
            if (missingRegion.body.length() == 0)
            {
                continue;
            }
            int offset = missingRegion.candidate.insertOffset;
            String block = populateRegionBlock(missingRegion.candidate.blockText, missingRegion.body.toString());
            inserts.computeIfAbsent(Integer.valueOf(offset), k -> new StringBuilder()).append(block);
            createdRegionCount++;
        }
        for (Map.Entry<Integer, StringBuilder> entry : inserts.entrySet())
        {
            int offset = entry.getKey().intValue();
            String lead = needsLeadingBlank(document, lines, offset) ? "\n" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            root.addChild(new InsertEdit(offset, lead + entry.getValue().toString()));
        }
        return new Plan(root, result, moveCount, createdRegionCount);
    }

    private static boolean moveToMissingRegion(IDocument document, List<String> lines, MultiTextEdit root,
        MissingRegion missingRegion, Method method, int startLine, int endLine, String name, String target,
        OpResult result)
    {
        try
        {
            int[] range = BslModuleText.methodRange(document, lines, startLine, endLine);
            int delStart = range[0];
            int delEnd = range[1];
            String text = document.get(delStart, delEnd - delStart).stripTrailing() + "\n\n"; //$NON-NLS-1$
            root.addChild(new DeleteEdit(delStart, delEnd - delStart));
            missingRegion.body.append(text);
            result.addSuccess(name, target + " (область создана)"); //$NON-NLS-1$
            return true;
        }
        catch (BadLocationException e)
        {
            result.addFailure(name, "не удалось вычислить диапазон метода"); //$NON-NLS-1$
            return false;
        }
    }

    private static String populateRegionBlock(String blockText, String body)
    {
        int contentOffset = blockText.indexOf("\n\n"); //$NON-NLS-1$
        if (contentOffset < 0)
        {
            return blockText + body;
        }
        contentOffset += 2;
        return blockText.substring(0, contentOffset) + body + blockText.substring(contentOffset);
    }

    private static boolean intersectsFlagged(Set<Integer> flaggedLines, int startLine, int endLine)
    {
        for (int line = startLine; line <= endLine; line++)
        {
            if (flaggedLines.contains(Integer.valueOf(line)))
            {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} if a blank line is needed before the insert (the preceding line is not blank). */
    private static boolean needsLeadingBlank(IDocument document, List<String> lines, int insertOffset)
    {
        try
        {
            int previousLine = document.getLineOfOffset(insertOffset) - 1;
            return previousLine >= 0 && previousLine < lines.size() && !lines.get(previousLine).trim().isEmpty();
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    private static final class MissingRegion
    {
        final RegionCreator.Candidate candidate;
        final StringBuilder body = new StringBuilder();

        MissingRegion(RegionCreator.Candidate candidate)
        {
            this.candidate = candidate;
        }
    }
}
