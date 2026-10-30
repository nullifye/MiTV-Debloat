package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.InputStream;
import java.util.Base64;
import java.util.List;

/** Against NOAA's GFS 10 m wind (00 UTC, 30 Oct 2026), whose values matched Open-Meteo's GFS within 0.2 m/s. */
public class GribTest {
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = GribTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    @Test
    public void readsComplexPackingWithSpatialDifferencing() throws Exception {
        // The eastward wind over the whole globe, 1°, template 5.3 (as NOMADS' grib filter sends it).
        List<Grib.Field> f = Grib.read(resource("gfs-ugrd10m.grb2"));
        assertEquals(1, f.size());
        Grib.Field u = f.get(0);
        assertEquals(0, u.discipline);
        assertEquals(2, u.category);
        assertEquals(2, u.number); // UGRD
        assertEquals(360, u.nx);
        assertEquals(181, u.ny);
        // Rows from 90° N, columns from 0° E.
        assertEquals(-0.11f, u.values[(90 - 3) * 360 + 102], 0.01f);  // Kuala Lumpur
        assertEquals(-2.29f, u.values[(90 - 52) * 360], 0.01f);       // London
        assertEquals(14.76f, u.values[(90 + 50) * 360], 0.01f);       // Southern Ocean
    }

    @Test
    public void readsSimplePacking() {
        // A 5 × 5 piece around Malaysia (5° N → 1° N, 100° E → 104° E): both components, template 5.0.
        byte[] b = Base64.getDecoder().decode("R1JJQgAAAAIAAAAAAAAA0wAAABUBAAcAAAIBAQfqCh4AAAAAAQAAAEgDAAAAABkAAAAABgAAAAAAAAAAAAAAAAAAAAAAAAUAAAAFAAAAAP////8AD0JABfXhADAATEtABjLqAAAPQkAAD0JAQAAAACIEAAAAAAICAgBRAAAAAQAAAABnAAAAAAr/AAAAAAAAAAAVBQAAABkAAMMxiDAAAAACCgAAAAAGBv8AAAAlBzaQM2VXYE9T/kg4UGMMMSnQRwjAJMsjWWEACoLA1l5ANzc3N0dSSUIAAAACAAAAAAAAANMAAAAVAQAHAAACAQEH6goeAAAAAAEAAABIAwAAAAAZAAAAAAYAAAAAAAAAAAAAAAAAAAAAAAAFAAAABQAAAAD/////AA9CQAX14QAwAExLQAYy6gAAD0JAAA9CQEAAAAAiBAAAAAACAwIAUQAAAAEAAAAAZwAAAAAK/wAAAAAAAAAAFQUAAAAZAADDJACAAAAAAgoAAAAABgb/AAAAJQc5iYMtFz0RQ91jGQ26Yk0yzHFc3DBKcjAAG4WCqKolADc3Nzc=");
        List<Grib.Field> f = Grib.read(b);
        assertEquals(2, f.size());
        assertEquals(5, f.get(0).nx);
        assertEquals(3, f.get(1).number); // VGRD
        assertEquals(-0.11f, f.get(0).values[2 * 5 + 2], 0.01f); // 3° N 102° E, as in the global field
        assertEquals(0.39f, f.get(1).values[2 * 5 + 2], 0.01f);
    }

    @Test
    public void skipsWhatItCantRead() {
        assertEquals(0, Grib.read(new byte[0]).size());
        assertEquals(0, Grib.read("<html>404</html>".getBytes()).size());
        assertEquals(-5, Grib.signed(new byte[]{(byte) 0x80, 5}, 0, 2));
        assertEquals(5, Grib.signed(new byte[]{0, 5}, 0, 2));
    }
}
