package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PlayUpdatesTest {
    private static final String LOG =
            "10-30 06:30:17.456 I/Finsky  (32118): [2] AU: Cannot update com.google.android.backdrop because package is disabled\n"
            + "10-30 06:30:18.272 I/Finsky  (32118): [846] UCtl: For package com.netflix.ninja, first update discovery timestamp is set to 2026-10-28T03:21:51.772571Z\n"
            + "10-30 06:30:18.324 I/Finsky  (32118): [846] UCtl: For package flar2.homebutton, first update discovery timestamp is set to 2026-10-26T23:32:00.488100Z\n"
            + "10-30 06:30:17.987 I/Finsky  (32118): [845] UCtl: For package com.google.android.backdrop, first update discovery timestamp is set to 2026-10-22T17:37:51.638Z\n";

    @Test
    public void parsesThePlayStoresPendingUpdates() {
        Map<String, Long> m = PlayUpdates.parse(LOG);
        assertEquals(Arrays.asList("com.netflix.ninja", "flar2.homebutton", "com.google.android.backdrop"),
                Arrays.asList(m.keySet().toArray()));
        assertEquals(1793157711772L, (long) m.get("com.netflix.ninja"));
    }

    @Test
    public void appsDropOffOnceUpdatedDisabledOrForgotten() {
        long now = 1793300000000L;
        Map<String, Long> logged = PlayUpdates.parse(LOG);
        Map<String, Long> installed = new HashMap<>();
        installed.put("com.netflix.ninja", 1700000000000L);  // installed before the update was found
        installed.put("flar2.homebutton", 1793200000000L);   // updated after it was found: done
        installed.put("com.google.android.backdrop", -1L);   // disabled
        Map<String, PlayUpdates.Pending> kept = PlayUpdates.merge(new HashMap<>(), logged, now, installed);
        assertEquals(Arrays.asList("com.netflix.ninja"), Arrays.asList(kept.keySet().toArray()));

        // Kept while the log no longer has it (it only holds a day or two)...
        Map<String, PlayUpdates.Pending> later = PlayUpdates.merge(kept, new HashMap<>(), now + 60 * 60_000L, installed);
        assertTrue(later.containsKey("com.netflix.ninja"));
        // ...but forgotten after 3 days without a mention.
        assertTrue(PlayUpdates.merge(kept, new HashMap<>(), now + 4 * 24 * 60 * 60_000L, installed).isEmpty());
    }
}
