package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The names in Settings → Phone remote's list of paired phones. */
public class RemoteDevicesTest {
    private static final String CHROME = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";

    @Test
    public void theNameTypedOnThePhoneWins() {
        assertEquals("Rafhan's Pixel", RemoteDevices.name(CHROME, "Rafhan's Pixel"));
        assertEquals("Kitchen tablet", RemoteDevices.name(CHROME, " Kitchen tablet "));
    }

    @Test
    public void chromesKIsNotAName() {
        assertEquals("Android phone", RemoteDevices.name(CHROME, ""));
        assertEquals("Android phone", RemoteDevices.name(CHROME, "K"));
    }

    @Test
    public void olderBrowsersAndIphones() {
        assertEquals("SM-A515F", RemoteDevices.name(
                "Mozilla/5.0 (Linux; Android 11; SM-A515F Build/RP1A.200720.012; wv) AppleWebKit/537.36", null));
        assertEquals("iPhone", RemoteDevices.name(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15", ""));
        assertEquals("Phone", RemoteDevices.name("", ""));
    }
}
