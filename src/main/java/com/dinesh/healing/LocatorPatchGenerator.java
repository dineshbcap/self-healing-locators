package com.dinesh.healing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a healing report into a ready-to-review patch against a
 * locators_&lt;platform&gt;.properties file - "here's your tech debt" becomes
 * "here's the diff that fixes it". Phase 4 of the roadmap.
 *
 * Operates on the properties file as plain text (never via {@link java.util.Properties}
 * load/store) so comments, ordering, and blank lines survive untouched - only the
 * value side of a matched {@code key=strategy=value} line is rewritten.
 *
 * Call {@link #apply} once per platform properties file, passing the matching
 * {@link Platform} - locator keys are shared across both platform files by
 * convention (see locators_android.properties / locators_ios.properties), so
 * without that filter an Android heal would silently overwrite the iOS file's
 * line for the same key. Records from the other platform are reported back as
 * {@code wrongPlatformKeys} rather than applied blind (older reports predating
 * the platform field are let through unfiltered - there's nothing to check).
 *
 * Only {@link HealingReporter.HealingRecord}s carrying a non-blank
 * {@code healedStrategy}/{@code healedValue} (see {@link HealingCache#recover}) can be
 * applied - anything else (e.g. an older report predating that field) is reported as
 * skipped rather than guessed at. When the same key appears more than once in the
 * report (e.g. a cache hit re-recording an earlier heal in the same run), the last
 * record wins - later entries reflect the more current state.
 */
public final class LocatorPatchGenerator {

    /**
     * Matches any bare {@code key=...} properties line. The key class excludes '=',
     * so this always splits on the FIRST '=' - "login.submitButton.description=..."
     * and "login.submitButton=..." naturally capture as two different key strings,
     * no special-casing needed to avoid the description line.
     */
    private static final Pattern KEY_LINE = Pattern.compile("([A-Za-z0-9_.-]+)=(.*)");

    /** One line actually rewritten. */
    public record Change(String locatorKey, int lineNumber, String oldLine, String newLine) {
    }

    /**
     * @param patchedText     the properties file content with every matched key's value
     *                        replaced, otherwise byte-for-byte identical to the input
     * @param changes         one entry per line rewritten, in file order
     * @param unmatchedKeys   healed keys for the target platform that never appeared as a bare
     *                        {@code key=...} line in this file (typo, or key removed since)
     * @param skippedKeys     healed keys present in the report without usable strategy/value data
     * @param wrongPlatformKeys healed keys that belong to the other platform - locator keys are
     *                        shared across both platform files by convention, so without this
     *                        check an Android heal would silently overwrite the iOS file's line
     *                        for the same key (or vice versa)
     */
    public record PatchResult(String patchedText, List<Change> changes, List<String> unmatchedKeys,
                              List<String> skippedKeys, List<String> wrongPlatformKeys) {
    }

    private LocatorPatchGenerator() {
    }

    public static PatchResult apply(String propertiesText, List<HealingReporter.HealingRecord> records,
                                    Platform targetPlatform) {
        Map<String, HealingReporter.HealingRecord> byKey = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> wrongPlatform = new ArrayList<>();
        String targetPlatformJson = targetPlatform.name().toLowerCase(java.util.Locale.ROOT);
        for (HealingReporter.HealingRecord record : records) {
            if (record.platform() != null && !record.platform().isBlank()
                    && !targetPlatformJson.equals(record.platform())) {
                wrongPlatform.add(record.locatorKey());
                continue;
            }
            if (record.healedStrategy() == null || record.healedStrategy().isBlank()
                    || record.healedValue() == null || record.healedValue().isBlank()) {
                skipped.add(record.locatorKey());
                continue;
            }
            byKey.put(record.locatorKey(), record); // last write wins for a repeated key
        }

        String[] lines = propertiesText.split("\n", -1);
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            HealingReporter.HealingRecord record = byKey.remove(m.group(1));
            if (record == null) {
                continue; // not a healed key (or a .description line, which is a different key string)
            }
            String key = record.locatorKey();
            String newLine = key + "=" + record.healedStrategy() + "=" + record.healedValue();
            changes.add(new Change(key, i + 1, line, newLine));
            lines[i] = newLine;
        }

        List<String> unmatched = new ArrayList<>(byKey.keySet());
        return new PatchResult(String.join("\n", lines), changes, unmatched, skipped, wrongPlatform);
    }

    /**
     * Renders a unified diff (git apply / patch -p1 compatible) from the exact line
     * changes {@link #apply} already computed - no general-purpose text-diff algorithm
     * needed since every change is a known single-line replacement in place.
     */
    public static String unifiedDiff(String originalText, List<Change> changes, String fileLabel) {
        if (changes.isEmpty()) {
            return "";
        }
        String[] originalLines = originalText.split("\n", -1);
        int context = 3;
        StringBuilder diff = new StringBuilder()
                .append("--- a/").append(fileLabel).append('\n')
                .append("+++ b/").append(fileLabel).append('\n');

        List<List<Change>> hunks = groupIntoHunks(changes, context);
        for (List<Change> hunk : hunks) {
            int firstLine = hunk.get(0).lineNumber();
            int lastLine = hunk.get(hunk.size() - 1).lineNumber();
            int start = Math.max(1, firstLine - context);
            int end = Math.min(originalLines.length, lastLine + context);
            int hunkLineCount = end - start + 1;

            diff.append("@@ -").append(start).append(',').append(hunkLineCount)
                    .append(" +").append(start).append(',').append(hunkLineCount).append(" @@\n");

            Map<Integer, Change> changeByLine = new LinkedHashMap<>();
            for (Change c : hunk) {
                changeByLine.put(c.lineNumber(), c);
            }
            for (int lineNo = start; lineNo <= end; lineNo++) {
                Change c = changeByLine.get(lineNo);
                if (c != null) {
                    diff.append('-').append(c.oldLine()).append('\n');
                    diff.append('+').append(c.newLine()).append('\n');
                } else {
                    diff.append(' ').append(originalLines[lineNo - 1]).append('\n');
                }
            }
        }
        return diff.toString();
    }

    /** Merges changes into hunks when their context windows overlap, like real diff output. */
    private static List<List<Change>> groupIntoHunks(List<Change> changes, int context) {
        List<List<Change>> hunks = new ArrayList<>();
        List<Change> current = new ArrayList<>();
        for (Change change : changes) {
            if (!current.isEmpty()
                    && change.lineNumber() - current.get(current.size() - 1).lineNumber() > 2 * context) {
                hunks.add(current);
                current = new ArrayList<>();
            }
            current.add(change);
        }
        if (!current.isEmpty()) {
            hunks.add(current);
        }
        return hunks;
    }
}
