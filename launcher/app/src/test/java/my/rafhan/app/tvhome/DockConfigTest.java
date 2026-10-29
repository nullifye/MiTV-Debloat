package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Reading and saving dock.txt. */
public class DockConfigTest {
    @Test
    public void parseDropsBlanksCommentsAndDuplicates() {
        List<String> lines = Arrays.asList(
                "# Dock apps, left to right",
                " com.netflix.ninja ",
                "",
                "com.google.android.youtube.tv",
                "com.netflix.ninja",
                "   # another comment");
        assertEquals(Arrays.asList("com.netflix.ninja", "com.google.android.youtube.tv"),
                DockConfig.parse(lines));
    }

    @Test
    public void parseKeepsOrderAndTheFirstOccurrence() {
        assertEquals(Arrays.asList("b", "a"), DockConfig.parse(Arrays.asList("b", "a", "b")));
    }

    @Test
    public void parseOfNothingIsEmpty() {
        assertTrue(DockConfig.parse(Collections.emptyList()).isEmpty());
    }

    @Test
    public void cleanDropsNullsEmptiesAndDuplicatesAndCapsAtMax() {
        List<String> input = Arrays.asList(null, "", "a", "a", "b", "c", "d", "e", "f", "g", "h");
        List<String> out = DockConfig.clean(input);
        assertEquals(DockConfig.MAX, out.size());
        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f", "g"), out);
    }

    @Test
    public void appsHiddenByFewerPerRowAreKeptAfterTheShownOnes() {
        // Saved a..f; with 4 per row the dock shows a..d, e and f are hidden.
        List<String> saved = Arrays.asList("a", "b", "c", "d", "e", "f");
        List<String> shownBefore = Arrays.asList("a", "b", "c", "d");
        java.util.Set<String> installed = new java.util.HashSet<>(Arrays.asList("a", "b", "c", "d", "e", "f", "x"));
        // Removing b and adding x keeps e and f for when the row grows again.
        assertEquals(Arrays.asList("a", "c", "d", "x", "e", "f"),
                DockConfig.keepHidden(Arrays.asList("a", "c", "d", "x"), saved, shownBefore, installed));
        // A removed app doesn't come back, and an uninstalled hidden one is dropped.
        installed.remove("f");
        assertEquals(Arrays.asList("a", "c", "d", "e"),
                DockConfig.keepHidden(Arrays.asList("a", "c", "d"), saved, shownBefore, installed));
    }

    @Test
    public void cleanLeavesAShortListAlone() {
        List<String> input = new ArrayList<>(Arrays.asList("a", "b"));
        assertEquals(input, DockConfig.clean(input));
    }
}
