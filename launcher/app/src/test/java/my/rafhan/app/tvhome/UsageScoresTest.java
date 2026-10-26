package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** The week window, the per-day merge and the per-app totals behind "Most used". */
public class UsageScoresTest {
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 24);

    @Test
    public void weekStartIsThePreviousOrSameMonday() {
        assertEquals(LocalDate.of(2026, 10, 19), UsageScores.weekStart(DayOfWeek.MONDAY, SATURDAY));
        // On a Monday, the week starts that day.
        assertEquals(LocalDate.of(2026, 10, 19),
                UsageScores.weekStart(DayOfWeek.MONDAY, LocalDate.of(2026, 10, 19)));
    }

    @Test
    public void weekStartCanBeSunday() {
        assertEquals(LocalDate.of(2026, 10, 18), UsageScores.weekStart(DayOfWeek.SUNDAY, SATURDAY));
        assertEquals(LocalDate.of(2026, 10, 18),
                UsageScores.weekStart(DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 18)));
    }

    @Test
    public void mergeKeepsTheLargerFigurePerDayAndReportsChanges() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        Map<LocalDate, Map<String, Long>> saved = new HashMap<>();
        Map<LocalDate, Map<String, Long>> android = new HashMap<>();
        android.put(day, new HashMap<>(Map.of("a", 5L, "b", 10L)));

        assertTrue(UsageScores.merge(saved, android, day.minusDays(6), day, new HashMap<>()));
        assertEquals(Long.valueOf(5), saved.get(day).get("a"));

        // The same figures again change nothing.
        assertFalse(UsageScores.merge(saved, android, day.minusDays(6), day, new HashMap<>()));

        // A larger figure wins; a smaller one doesn't overwrite it.
        Map<LocalDate, Map<String, Long>> bigger = new HashMap<>();
        bigger.put(day, new HashMap<>(Map.of("a", 8L, "b", 4L)));
        assertTrue(UsageScores.merge(saved, bigger, day.minusDays(6), day, new HashMap<>()));
        assertEquals(Long.valueOf(8), saved.get(day).get("a"));
        assertEquals(Long.valueOf(10), saved.get(day).get("b"));
    }

    @Test
    public void mergeDropsDaysOutsideTheWindow() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        Map<LocalDate, Map<String, Long>> saved = new HashMap<>();
        saved.put(today.minusDays(10), new HashMap<>(Map.of("old", 1L)));
        assertTrue(UsageScores.merge(saved, Collections.emptyMap(), today.minusDays(6), today, new HashMap<>()));
        assertTrue(saved.isEmpty());
    }

    @Test
    public void mergeAddsADayButKeepsTheOthers() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        LocalDate yesterday = today.minusDays(1);
        Map<LocalDate, Map<String, Long>> saved = new HashMap<>();
        saved.put(yesterday, new HashMap<>(Map.of("y", 2L)));
        Map<LocalDate, Map<String, Long>> android = new HashMap<>();
        android.put(today, new HashMap<>(Map.of("t", 3L)));

        assertTrue(UsageScores.merge(saved, android, today.minusDays(6), today, new HashMap<>()));
        assertEquals(Long.valueOf(2), saved.get(yesterday).get("y"));
        assertEquals(Long.valueOf(3), saved.get(today).get("t"));
    }

    @Test
    public void totalsCountThisWeekOnlyAndLeaveOutExcludedApps() {
        LocalDate weekStart = LocalDate.of(2026, 10, 19);
        Map<LocalDate, Map<String, Long>> saved = new HashMap<>();
        saved.put(LocalDate.of(2026, 10, 20), new HashMap<>(Map.of("a", 5L, "b", 2L)));
        saved.put(LocalDate.of(2026, 10, 22), new HashMap<>(Map.of("a", 3L)));
        saved.put(LocalDate.of(2026, 10, 18), new HashMap<>(Map.of("a", 100L, "old", 7L))); // last week

        Map<String, Long> totals = UsageScores.totals(saved, weekStart, new HashSet<>(Arrays.asList("b")));
        assertEquals(Long.valueOf(8), totals.get("a")); // 5 + 3, not the 100 from last week
        assertFalse(totals.containsKey("b"));
        assertFalse(totals.containsKey("old"));
        assertEquals(1, totals.size());
    }

    @Test
    public void daysLeavingTheCopyGoIntoTheAllTimeTotal() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        Map<LocalDate, Map<String, Long>> saved = new HashMap<>();
        saved.put(today.minusDays(7), new HashMap<>(Map.of("a", 50L, "b", 7L)));
        saved.put(today, new HashMap<>(Map.of("a", 5L)));
        Map<String, Long> archive = new HashMap<>(Map.of("a", 1000L));
        assertTrue(UsageScores.merge(saved, Collections.emptyMap(), today.minusDays(6), today, archive));
        assertEquals(Long.valueOf(1050), archive.get("a"));
        assertEquals(Long.valueOf(7), archive.get("b"));
        // All time = archive + the days still in the copy, nothing excluded.
        Map<String, Long> all = UsageScores.allTime(archive, saved);
        assertEquals(Long.valueOf(1055), all.get("a"));
        assertEquals(Long.valueOf(7), all.get("b"));
        // A day that stays is never archived twice.
        assertFalse(UsageScores.merge(saved, Collections.emptyMap(), today.minusDays(6), today, archive));
        assertEquals(Long.valueOf(1050), archive.get("a"));
    }

    @Test
    public void scoreIsZeroForAnAppWithNoUse() {
        Map<String, Long> scores = Collections.singletonMap("a", 12L);
        assertEquals(12, UsageScores.score(scores, "a"));
        assertEquals(0, UsageScores.score(scores, "never"));
        assertEquals(0, UsageScores.score(Collections.emptyMap(), "a"));
    }
}
