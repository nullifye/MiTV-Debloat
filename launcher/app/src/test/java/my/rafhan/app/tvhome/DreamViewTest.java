package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DreamViewTest {
    @Test
    public void splitsALogLine() {
        DreamView.LogLine l = DreamView.parse(
                "10-30 06:30:18.272 I/Finsky  (32118): [846] UCtl: For package com.netflix.ninja");
        assertEquals("06:30:18", l.time);
        assertEquals('I', l.level);
        assertEquals("Finsky", l.tag);
        assertEquals("[846] UCtl: For package com.netflix.ninja", l.msg);

        DreamView.LogLine e = DreamView.parse("10-30 08:48:47.588 E/PlayCommon(28976): Metalog deleted");
        assertEquals('E', e.level);
        assertEquals("PlayCommon", e.tag);
    }

    @Test
    public void keepsLinesWithoutAHeaderAndSkipsMarkers() {
        assertNull(DreamView.parse("--------- beginning of main"));
        DreamView.LogLine l = DreamView.parse("\tat android.os.Looper.loop(Looper.java:214)");
        assertEquals(' ', l.level);
        assertEquals("at android.os.Looper.loop(Looper.java:214)", l.msg);
    }

    @Test
    public void hijriDatesReadLikeTheHomeScreen() {
        assertEquals("19 Jamadilawal 1448H", PrayerPanel.hijri("1448-05-19"));
    }
}
