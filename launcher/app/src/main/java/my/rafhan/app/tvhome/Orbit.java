package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * A satellite's orbit from its two-line elements (TLE, as CelesTrak publishes them), and where
 * it is at any moment: SGP4, the model TLEs are made for, after Vallado et al., "Revisiting
 * Spacetrack Report #3" (2006), WGS-72 constants. The near-Earth form only, which is what the
 * Globe's satellites need (the ISS, Tiangong and Hubble go round in about 90 minutes). For
 * deep-space orbits (225 minutes and longer, like GPS) the Moon's and Sun's pull is left out:
 * a steady ~15 km for GPS. Free of Android, so it's unit tested against the reference
 * implementation.
 */
final class Orbit {
    private static final double RE = 6378.135, XKE = 60.0 / Math.sqrt(RE * RE * RE / 398600.8),
            J2 = 0.001082616, J3 = -0.00000253881, J4 = -0.00000165597, J3OJ2 = J3 / J2, TWO_PI = 2 * Math.PI;

    final String name;
    final int catalog;
    final long epochMs;
    private final double bstar, inclo, nodeo, ecco, argpo, mo, no;
    private final boolean simple;
    private final double con41, x1mth2, x7thm1, eta, cc1, cc4, cc5, mdot, argpdot, nodedot, omgcof, xmcof,
            nodecf, t2cof, xlcof, aycof, delmo, sinmao, d2, d3, d4, t3cof, t4cof, t5cof;

    /** The two lines' numbers, read by column as the format fixes them. Throws on a broken TLE. */
    Orbit(String name, String line1, String line2) {
        this.name = name.trim();
        catalog = Integer.parseInt(line1.substring(2, 7).trim());
        int yy = Integer.parseInt(line1.substring(18, 20).trim());
        double day = Double.parseDouble(line1.substring(20, 32).trim());
        epochMs = java.time.LocalDate.of(yy < 57 ? 2000 + yy : 1900 + yy, 1, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + Math.round((day - 1) * 86_400_000);
        bstar = exponent(line1.substring(53, 61));
        inclo = Math.toRadians(Double.parseDouble(line2.substring(8, 16).trim()));
        nodeo = Math.toRadians(Double.parseDouble(line2.substring(17, 25).trim()));
        ecco = Double.parseDouble("0." + line2.substring(26, 33).trim());
        argpo = Math.toRadians(Double.parseDouble(line2.substring(34, 42).trim()));
        mo = Math.toRadians(Double.parseDouble(line2.substring(43, 51).trim()));
        double noKozai = Double.parseDouble(line2.substring(52, 63).trim()) * TWO_PI / 1440; // rad/min

        // Un-Kozai the mean motion (initl).
        double eccsq = ecco * ecco, omeosq = 1 - eccsq, rteosq = Math.sqrt(omeosq);
        double cosio = Math.cos(inclo), cosio2 = cosio * cosio;
        double ak = Math.pow(XKE / noKozai, 2.0 / 3);
        double d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1 - del * del - del * (1.0 / 3 + 134 * del * del / 81));
        del = d1 / (adel * adel);
        no = noKozai / (1 + del);
        double ao = Math.pow(XKE / no, 2.0 / 3), sinio = Math.sin(inclo), po = ao * omeosq;
        double con42 = 1 - 5 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po, rp = ao * (1 - ecco);

        // The drag and gravity coefficients (sgp4init).
        boolean low = rp < 220 / RE + 1;
        simple = low || TWO_PI / no >= 225; // deep space: no higher drag terms either
        double sfour = 78 / RE + 1, qzms24 = Math.pow((120 - 78) / RE, 4), perige = (rp - 1) * RE;
        if (perige < 156) {
            sfour = perige < 98 ? 20 : perige - 78;
            qzms24 = Math.pow((120 - sfour) / RE, 4);
            sfour = sfour / RE + 1;
        }
        double pinvsq = 1 / posq, tsi = 1 / (ao - sfour);
        eta = ao * ecco * tsi;
        double etasq = eta * eta, eeta = ecco * eta, psisq = Math.abs(1 - etasq);
        double coef = qzms24 * Math.pow(tsi, 4), coef1 = coef / Math.pow(psisq, 3.5);
        double cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq))
                + 0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)));
        cc1 = bstar * cc2;
        double cc3 = ecco > 1.0e-4 ? -2 * coef * tsi * J3OJ2 * no * sinio / ecco : 0;
        x1mth2 = 1 - cosio2;
        cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq)
                - J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta))
                + 0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * Math.cos(2 * argpo)));
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq);
        double cosio4 = cosio2 * cosio2;
        double temp1 = 1.5 * J2 * pinvsq * no, temp2 = 0.5 * temp1 * J2 * pinvsq,
                temp3 = -0.46875 * J4 * pinvsq * pinvsq * no;
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4);
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4)
                + temp3 * (3 - 36 * cosio2 + 49 * cosio4);
        double xhdot1 = -temp1 * cosio;
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio;
        omgcof = bstar * cc3 * Math.cos(argpo);
        xmcof = ecco > 1.0e-4 ? -2.0 / 3 * coef * bstar / eeta : 0;
        nodecf = 3.5 * omeosq * xhdot1 * cc1;
        t2cof = 1.5 * cc1;
        xlcof = -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (Math.abs(cosio + 1) > 1.5e-12 ? 1 + cosio : 1.5e-12);
        aycof = -0.5 * J3OJ2 * sinio;
        delmo = Math.pow(1 + eta * Math.cos(mo), 3);
        sinmao = Math.sin(mo);
        x7thm1 = 7 * cosio2 - 1;
        if (!simple) {
            double cc1sq = cc1 * cc1;
            d2 = 4 * ao * tsi * cc1sq;
            double temp = d2 * tsi * cc1 / 3;
            d3 = (17 * ao + sfour) * temp;
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1;
            t3cof = d2 + 2 * cc1sq;
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq));
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq));
        } else {
            d2 = d3 = d4 = t3cof = t4cof = t5cof = 0;
        }
    }

    /** " 10765-3" → 0.10765e-3: TLE's assumed-decimal exponent notation. */
    private static double exponent(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        int sign = 1;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            if (s.charAt(0) == '-') sign = -1;
            s = s.substring(1);
        }
        int e = Math.max(s.lastIndexOf('-'), s.lastIndexOf('+'));
        if (e <= 0) return sign * Double.parseDouble("0." + s);
        return sign * Double.parseDouble("0." + s.substring(0, e)) * Math.pow(10, Integer.parseInt(s.substring(e).replace("+", "")));
    }

    /**
     * Where it is (km, TEME: x to the March equinox, z to the north pole) {@code minutes} after
     * the TLE's epoch; null if the orbit has decayed in the model.
     */
    double[] position(double minutes) {
        double t = minutes, t2 = t * t;
        double xmdf = mo + mdot * t, argpdf = argpo + argpdot * t, nodedf = nodeo + nodedot * t;
        double argpm = argpdf, mm = xmdf, nodem = nodedf + nodecf * t2;
        double tempa = 1 - cc1 * t, tempe = bstar * cc4 * t, templ = t2cof * t2;
        if (!simple) {
            double delomg = omgcof * t, delm = xmcof * (Math.pow(1 + eta * Math.cos(xmdf), 3) - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t, t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double am = Math.pow(XKE / no, 2.0 / 3) * tempa * tempa;
        double nm = XKE / Math.pow(am, 1.5);
        double em = ecco - tempe;
        if (em >= 1 || em < -0.001 || am < 0.95) return null;
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + no * templ;
        double xlm = mm + argpm + nodem;
        nodem = nodem % TWO_PI;
        argpm = argpm % TWO_PI;
        xlm = xlm % TWO_PI;

        // Long-period periodics.
        double sinip = Math.sin(inclo), cosip = Math.cos(inclo);
        double axnl = em * Math.cos(argpm), temp = 1 / (am * (1 - em * em));
        double aynl = em * Math.sin(argpm) + temp * aycof;
        double xl = xlm + temp * xlcof * axnl;

        // Kepler's equation.
        double u = (xl - nodem) % TWO_PI, eo1 = u, tem5 = 9999.9, sineo1 = 0, coseo1 = 0;
        for (int k = 0; Math.abs(tem5) >= 1.0e-12 && k < 10; k++) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / (1 - coseo1 * axnl - sineo1 * aynl);
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0 ? 0.95 : -0.95;
            eo1 += tem5;
        }

        // Short-period periodics.
        double ecose = axnl * coseo1 + aynl * sineo1, esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl, pl = am * (1 - el2);
        if (pl < 0) return null;
        double rl = am * (1 - ecose), betal = Math.sqrt(1 - el2);
        temp = esine / (1 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp), cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu), sin2u = (cosu + cosu) * sinu, cos2u = 1 - 2 * sinu * sinu;
        temp = 1 / pl;
        double temp1 = 0.5 * J2 * temp, temp2 = temp1 * temp;
        double mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodem + 1.5 * temp2 * cosip * sin2u, xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u;
        if (mrt < 1) return null;

        double sinsu = Math.sin(su), cossu = Math.cos(su), snod = Math.sin(xnode), cnod = Math.cos(xnode),
                sini = Math.sin(xinc), cosi = Math.cos(xinc);
        double xmx = -snod * cosi, xmy = cnod * cosi;
        double r = mrt * RE;
        return new double[]{r * (xmx * sinsu + cnod * cossu), r * (xmy * sinsu + snod * cossu), r * sini * sinsu};
    }

    /** Actual satellite position in Globe's Earth-fixed axes, in Earth radii. For a trail,
     * rotate every past position into the globe's frame at the current viewing time. */
    double[] earthFixed(long atMs, long viewMs) {
        double[] r = position((atMs - epochMs) / 60_000.0);
        if (r == null) return null;
        double sidereal = Math.toRadians(Globe.siderealDegrees(viewMs));
        double c = Math.cos(sidereal), s = Math.sin(sidereal);
        return new double[]{(-r[0] * s + r[1] * c) / RE, r[2] / RE,
                (r[0] * c + r[1] * s) / RE};
    }

    /** The spot under the satellite at this moment: {latitude, longitude} in degrees, or null. */
    double[] below(long epochMs) {
        double[] r = position((epochMs - this.epochMs) / 60_000.0);
        if (r == null) return null;
        double lon = Math.toDegrees(Math.atan2(r[1], r[0])) - Globe.siderealDegrees(epochMs);
        // Geodetic latitude on the WGS-84 ellipsoid (a few iterations): what maps are drawn in.
        double p = Math.hypot(r[0], r[1]), e2 = 0.00669437999014, a = 6378.137;
        double lat = Math.atan2(r[2], p);
        for (int i = 0; i < 4; i++) {
            double s = Math.sin(lat), n = a / Math.sqrt(1 - e2 * s * s);
            lat = Math.atan2(r[2] + e2 * n * s, p);
        }
        return new double[]{Math.toDegrees(lat), Globe.wrap(lon)};
    }

    /** Every satellite in a CelesTrak TLE file (name line, then its two lines); broken ones skipped. */
    static List<Orbit> parse(String text) {
        List<Orbit> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i + 2 < lines.length; i++) {
            if (!lines[i + 1].startsWith("1 ") || !lines[i + 2].startsWith("2 ")) continue;
            try {
                out.add(new Orbit(lines[i], lines[i + 1], lines[i + 2]));
                i += 2;
            } catch (RuntimeException ignored) { // a cut-off or garbled entry
            }
        }
        return out;
    }
}
