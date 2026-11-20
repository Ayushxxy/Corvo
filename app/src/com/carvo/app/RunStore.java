package com.carvo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RunStore {

    public static class Run {
        public final String d;
        public final double km, min;
        public final int m1, m2;      // mood before/after (-1 = none, 0..3)
        public final String pin;      // "" = not pinned, else pin title
        public final double[] laps;   // v3.7: lap minutes in order (null = none)
        public final int bpm;         // v4.0/4.1: optional post-run heart rate (0 = none)

        Run(String d, double km, double min) {
            this(d, km, min, -1, -1, "", null, 0);
        }

        Run(String d, double km, double min, int m1, int m2, String pin) {
            this(d, km, min, m1, m2, pin, null, 0);
        }

        Run(String d, double km, double min, int m1, int m2, String pin, double[] laps) {
            this(d, km, min, m1, m2, pin, laps, 0);
        }

        Run(String d, double km, double min, int m1, int m2, String pin, double[] laps, int bpm) {
            this.d = d;
            this.km = km;
            this.min = min;
            this.m1 = m1;
            this.m2 = m2;
            this.pin = pin;
            this.laps = laps;
            this.bpm = bpm;
        }
    }

    private static final long DAY_MS = 86400000L;
    private final SharedPreferences prefs;
    private final List<Run> runs = new ArrayList<Run>();

    // effective-dated rest day history: [{from, day}] — day: Calendar.DAY_OF_WEEK (1=SUN..7=SAT)
    // LEGACY engine — ab sirf rest_switch se PEHLE ke dates ke liye (frozen history).
    private final ArrayList<String> restFrom = new ArrayList<String>();
    private final ArrayList<Integer> restDays = new ArrayList<Integer>();

    // FLEX rest (uska funda): default weekdays har hafte auto (max 2) +
    // per-week explicit sets (aaj/future tick, past locked, max 2/week).
    // d >= restSwitch: us week ka set hai to wahi, warna default weekday.
    private String restSwitch;
    private int restDefaults;
    private final java.util.HashMap<String, ArrayList<String>> restMoves =
            new java.util.HashMap<String, ArrayList<String>>();

    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat PRETTY =
            new SimpleDateFormat("EEE, d MMM", Locale.US);

    private final Context ctx;

    public RunStore(Context c) {
        ctx = c.getApplicationContext();
        prefs = c.getSharedPreferences("carvo", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString("runs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                runs.add(new Run(o.getString("d"), o.getDouble("km"), o.getDouble("min"),
                        o.optInt("m1", -1), o.optInt("m2", -1), o.optString("pin", ""),
                        parseLaps(o.optJSONArray("laps")), o.optInt("bpm", 0)));
            }
        } catch (Exception ignored) {
        }
        loadRestHist();
        loadFlexRest();
        sort();
    }

    private static double[] parseLaps(org.json.JSONArray a) {
        if (a == null || a.length() == 0) return null;
        double[] out = new double[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optDouble(i, 0.0);
        return out;
    }

    // ================= REST DAY ENGINE =================

    private void loadRestHist() {
        String h = prefs.getString("restday_hist", null);
        if (h == null) {
            // Default weekly rest day: Sunday
            h = "[{\"from\":\"1970-01-01\",\"day\":1}]";
            prefs.edit().putString("restday_hist", h).apply();
        }
        try {
            JSONArray a = new JSONArray(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                restFrom.add(o.getString("from"));
                // "mask" = bitmask (1<<DAY_OF_WEEK), raw padho. Purana "day"
                // (1=SUN..7=SAT single weekday) normalize SIRF jab mask absent ho
                // — warna naye masks 2=SUN/4=MON/6=SUN+MON har load pe shift te the.
                int v = o.optInt("mask", -1);
                if (v < 0) {
                    v = o.optInt("day", 0);
                    if (v >= 1 && v <= 7) v = 1 << v; // legacy single day -> mask
                }
                restDays.add(v);
            }
            // v3.5.1 tak setRestDay same-date entries stack kar deta tha aur
            // restDayOn unme se pehli uthata tha — collapse karke LATEST rakho
            for (int i = restFrom.size() - 1; i > 0; i--) {
                if (restFrom.get(i).equals(restFrom.get(i - 1))) {
                    restFrom.remove(i - 1);
                    restDays.remove(i - 1);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void loadFlexRest() {
        restSwitch = prefs.getString("rest_switch", null);
        if (restSwitch == null) {
            restSwitch = today();
            restDefaults = restDayOn(restSwitch); // migration: aaj jo effective hai wahi default
            prefs.edit().putString("rest_switch", restSwitch)
                    .putInt("rest_defaults", restDefaults).apply();
        } else {
            restDefaults = prefs.getInt("rest_defaults", 0);
        }
        try {
            JSONObject m = new JSONObject(prefs.getString("rest_moves", "{}"));
            java.util.Iterator<String> it = m.keys();
            while (it.hasNext()) {
                String wk = it.next();
                JSONArray a = m.getJSONArray(wk);
                ArrayList<String> ds = new ArrayList<String>();
                for (int i = 0; i < a.length(); i++) ds.add(a.getString(i));
                restMoves.put(wk, ds);
            }
        } catch (Exception ignored) {
        }
    }

    private void persistMoves() {
        try {
            JSONObject m = new JSONObject();
            for (java.util.Map.Entry<String, ArrayList<String>> e : restMoves.entrySet()) {
                m.put(e.getKey(), new JSONArray(e.getValue()));
            }
            prefs.edit().putString("rest_moves", m.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Rest day in effect on date d (latest entry with from <= d; same-date tie: last wins). */
    public int restDayOn(String d) {
        String best = null;
        int day = 1 << 7; // SATURDAY — default era before first entry
        for (int i = 0; i < restFrom.size(); i++) {
            String f = restFrom.get(i);
            if (f.compareTo(d) <= 0 && (best == null || f.compareTo(best) >= 0)) {
                best = f;
                day = restDays.get(i);
            }
        }
        return day;
    }

    public boolean isRestDay(String d) {
        if (d.compareTo(restSwitch) >= 0) {
            ArrayList<String> mv = restMoves.get(weekMonday(d));
            if (mv != null) return mv.contains(d);
            return ((1 << dowOf(d)) & restDefaults) != 0;
        }
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY.parse(d));
            return ((1 << c.get(Calendar.DAY_OF_WEEK)) & restDayOn(d)) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** java weekday (1=SUN..7=SAT) of a yyyy-MM-dd. */
    public static int dowOf(String d) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY.parse(d));
            return c.get(Calendar.DAY_OF_WEEK);
        } catch (Exception e) {
            return 1;
        }
    }

    public int restDefaultsMask() {
        return restDefaults;
    }

    public void setRestDefaults(int mask) {
        restDefaults = mask;
        prefs.edit().putInt("rest_defaults", mask).apply();
    }

    /** Current week ka explicit set save karo (dialog). Empty = is hafte koi rest nahi. */
    public void setRestWeek(ArrayList<String> dates) {
        restMoves.put(weekMonday(today()), new ArrayList<String>(dates));
        persistMoves();
    }

    /** Week (Monday start) ke 7 din me se jo currently rest hain — past included. */
    public ArrayList<String> weekRestDates(String weekStart) {
        ArrayList<String> out = new ArrayList<String>();
        String d = weekStart;
        for (int i = 0; i < 7; i++) {
            if (isRestDay(d)) out.add(d);
            d = dateShift(d, 1);
        }
        return out;
    }

    // ================= RECOVERY BRAIN =================
    // Verdict: 0 PRIMED · 1 STEADY · 2 CAUTION · 3 REST CALL · 4 LEARNING (v3.7)
    public static final String[] DAY_NAMES_FULL = {
            "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"
    };

    public static class Recovery {
        public int verdict;
        public boolean hasData;
        public double load;      // v3.7: this week km / last week km (0 = unknown)
        public int hardStreak;   // consecutive runs >= 5% faster than 14d avg pace
        public int runDays;      // consecutive days with runs
        public int moodTrend;    // +1 rising, 0 flat/none, -1 falling
        public boolean spike;    // v3.7: yesterday >= 2.5x last week's daily avg
    }

    private static long daysBack(String d, String ref) {
        try {
            return (DAY.parse(ref).getTime() - DAY.parse(d).getTime()) / DAY_MS;
        } catch (Exception e) {
            return 999;
        }
    }

    public Recovery recovery() {
        Recovery r = new Recovery();
        r.verdict = 1;
        String t = today();
        double thisWk = 0, prevWk = 0;
        int runs28 = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            long back = daysBack(run.d, t);
            if (back >= 0 && back < 7) thisWk += run.km;
            if (back >= 7 && back < 14) prevWk += run.km;
            if (back >= 0 && back < 28) runs28++;
        }
        r.hasData = runs28 > 0;
        r.runDays = 0;
        String d = get(t) == null ? dateShift(t, -1) : t;
        while (get(d) != null) {
            r.runDays++;
            d = dateShift(d, -1);
        }
        double sMin = 0, sKm = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            long back = daysBack(run.d, t);
            if (back >= 0 && back < 14 && run.km > 0) {
                sMin += run.min;
                sKm += run.km;
            }
        }
        r.hardStreak = 0;
        if (sKm > 0) {
            double avg = sMin / sKm;
            for (int i = runs.size() - 1; i >= 0; i--) {
                Run run = runs.get(i);
                if (run.km <= 0) break;
                if (run.min / run.km <= avg * 0.95) r.hardStreak++;
                else break;
            }
        }
        int neg = 0, pos = 0, cnt = 0;
        for (int i = runs.size() - 1; i >= 0 && cnt < 2; i--) {
            Run run = runs.get(i);
            if (run.m1 < 0 || run.m2 < 0) continue;
            int del = run.m2 - run.m1;
            if (del < 0) neg++;
            else if (del > 0) pos++;
            cnt++;
        }
        if (cnt == 2 && neg == 2) r.moodTrend = -1;
        else if (pos >= 1 && neg == 0) r.moodTrend = 1;
        // v3.7 LEARNING gate — cold start (pehli runs pe koi verdict nahi) + no baseline week
        if (runs28 < 4 || prevWk < 3.0) {
            r.verdict = 4;
            return r;
        }
        r.load = thisWk / prevWk;
        Run y = get(dateShift(t, -1));
        r.spike = y != null && y.km >= Math.max(3.0, 2.5 * prevWk / 7.0);
        int flags = 0;
        if (r.load >= 1.3) flags++;
        if (r.hardStreak >= 2) flags++;
        if (r.runDays >= 4) flags++;
        if (r.moodTrend < 0) flags++;
        if (r.spike || r.load > 1.6 || r.runDays >= 6) r.verdict = 3;
        else if (r.load > 1.3 || r.runDays >= 5 || r.hardStreak >= 2 || flags >= 2) r.verdict = 2;
        else if (flags == 0 && r.moodTrend > 0 && r.load >= 0.8) r.verdict = 0;
        return r;
    }

    // ================= CORE =================

    private void sort() {
        Collections.sort(runs, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                return a.d.compareTo(b.d);
            }
        });
    }

    private void persist() {
        JSONArray a = new JSONArray();
        for (Run r : runs) {
            JSONObject o = new JSONObject();
            try {
                o.put("d", r.d);
                o.put("km", r.km);
                o.put("min", r.min);
                o.put("m1", r.m1);
                o.put("m2", r.m2);
                o.put("pin", r.pin);
                if (r.laps != null && r.laps.length > 0) {
                    org.json.JSONArray lp = new org.json.JSONArray();
                    for (double v : r.laps) lp.put(v);
                    o.put("laps", lp);
                }
                if (r.bpm > 0) o.put("bpm", r.bpm);
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        prefs.edit().putString("runs", a.toString()).apply();
    }

    public static String today() {
        return DAY.format(new Date());
    }

    static String dayBack(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -n);
        return DAY.format(c.getTime());
    }

    /** Shift an arbitrary yyyy-MM-dd by n days (negative = back). */
    public static String dateShift(String d, int n) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY.parse(d));
            c.add(Calendar.DAY_OF_YEAR, n);
            return DAY.format(c.getTime());
        } catch (Exception e) {
            return d;
        }
    }

    /** Monday of the week containing d. */
    public static String weekMonday(String d) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY.parse(d));
            int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            c.add(Calendar.DAY_OF_YEAR, -back);
            return DAY.format(c.getTime());
        } catch (Exception e) {
            return d;
        }
    }

    public static String pretty(String d) {
        try {
            return PRETTY.format(DAY.parse(d)).toUpperCase(Locale.US);
        } catch (Exception e) {
            return d;
        }
    }

    public static String shortDate(String d) {
        try {
            return new SimpleDateFormat("d MMM", Locale.US)
                    .format(DAY.parse(d)).toUpperCase(Locale.US);
        } catch (Exception e) {
            return d;
        }
    }

    public static String[] dayLabels(int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -(n - 1 - i));
            String s = new SimpleDateFormat("EEE", Locale.US)
                    .format(c.getTime()).toUpperCase(Locale.US);
            out[i] = s.substring(0, 1);
        }
        return out;
    }

    public Run get(String d) {
        for (Run r : runs) {
            if (r.d.equals(d)) return r;
        }
        return null;
    }

    /** Insert/replace km+min for a date — mood and pin are always preserved. */
    public void upsert(String d, double km, double min) {
        upsertWithLaps(d, km, min, null);
    }

    /** v3.7: upsert with lap data (null = keep existing laps). */
    public void upsertWithLaps(String d, double km, double min, double[] laps) {
        Run old = get(d);
        double[] lp = laps != null ? laps : (old != null ? old.laps : null);
        int bpm = old != null ? old.bpm : 0;
        Run n = old == null
                ? new Run(d, km, min, -1, -1, "", lp, 0)
                : new Run(d, km, min, old.m1, old.m2, old.pin, lp, bpm);
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).d.equals(d)) runs.remove(i);
        }
        runs.add(n);
        sort();
        persist();
    }

    public void setMood(String d, int slot, int val) {
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.d.equals(d)) {
                Run n = slot == 0
                        ? new Run(r.d, r.km, r.min, val, r.m2, r.pin, r.laps, r.bpm)
                        : new Run(r.d, r.km, r.min, r.m1, val, r.pin, r.laps, r.bpm);
                runs.set(i, n);
                persist();
                return;
            }
        }
    }

    // ================= PIN =================

    /** Toggle pin. Returns the new pin title ("" = unpinned). */
    public String togglePin(String d) {
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.d.equals(d)) {
                String t = r.pin.length() > 0 ? "" : autoTitle(r);
                runs.set(i, new Run(r.d, r.km, r.min, r.m1, r.m2, t, r.laps, r.bpm));
                persist();
                return t;
            }
        }
        return "";
    }

    private String autoTitle(Run r) {
        boolean first10 = r.km >= 10;
        boolean first5 = r.km >= 5;
        double maxKm = 0, minPace = Double.MAX_VALUE;
        for (Run x : runs) {
            if (x.d.compareTo(r.d) >= 0) continue; // strictly earlier runs only
            if (x.km >= 10) first10 = false;
            if (x.km >= 5) first5 = false;
            maxKm = Math.max(maxKm, x.km);
            if (x.km > 0) minPace = Math.min(minPace, x.min / x.km);
        }
        if (first10) return "FIRST 10K";
        if (first5) return "FIRST 5K";
        double p = r.min / r.km;
        if (r.km > maxKm && maxKm > 0) return "NEW LONGEST";
        if (p < minPace && minPace < Double.MAX_VALUE) {
            long t = Math.round(p * 60.0);
            return String.format(Locale.US, "BEST PACE %d:%02d/KM", t / 60, (int) (t % 60));
        }
        return String.format(Locale.US, "%.1f KM RUN", r.km);
    }

    /** Pinned runs, newest first. */
    public List<Run> pinned() {
        List<Run> out = new ArrayList<Run>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).pin.length() > 0) out.add(runs.get(i));
        }
        return out;
    }

    // ================= STREAK (rest-day aware) =================

    public int currentStreak() {
        return currentStreakAsOf(today());
    }

    /** Streak as seen from date td (Time Machine past views). */
    public int currentStreakAsOf(String td) {
        int i = get(td) != null ? 0 : 1; // a run today counts; empty day gets grace/rest skip
        int s = 0;
        while (i < 3650) {
            String d = dateShift(td, -i);
            if (get(d) != null) {
                s++; // a logged run counts on ANY day — even a rest day
                i++;
                continue;
            }
            if (isRestDay(d)) {
                i++; // rest day without a run: streak protected, not broken
                continue;
            }
            break;
        }
        return s;
    }

    /** Read-only past view: only runs on/before d (Time Machine). */
    public RunStore viewAsOf(String d) {
        RunStore v = new RunStore(ctx);
        v.runs.clear();
        for (Run r : runs) {
            if (r.d.compareTo(d) <= 0) v.runs.add(r);
        }
        return v;
    }

    public int bestStreak() {
        int best = 0, cur = 0;
        String prev = null;
        for (Run r : runs) {
            if (prev != null && !allRestBetween(prev, r.d)) cur = 0;
            cur++;
            if (cur > best) best = cur;
            prev = r.d;
        }
        return best;
    }

    private boolean allRestBetween(String a, String b) {
        long ea = epoch(a), eb = epoch(b);
        if (eb - ea <= DAY_MS) return true;
        for (long t = ea + DAY_MS; t < eb; t += DAY_MS) {
            if (!isRestDay(DAY.format(new Date(t)))) return false;
        }
        return true;
    }

    private static long epoch(String d) {
        try {
            return DAY.parse(d).getTime();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    public static long epochDay(String d) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY.parse(d));
            c.set(Calendar.HOUR_OF_DAY, 12); // noon-local: IST (+5:30) me din shift hota tha
            return c.getTimeInMillis() / DAY_MS;
        } catch (Exception e) {
            return Long.MIN_VALUE / DAY_MS;
        }
    }

    public static String fromEpochDay(long ed) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ed * DAY_MS + DAY_MS / 2);
        return DAY.format(c.getTime());
    }

    public double[] lastDays(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Run r = get(dayBack(n - 1 - i));
            out[i] = r == null ? 0 : r.km;
        }
        return out;
    }

    public List<Run> recent(int n) {
        List<Run> out = new ArrayList<Run>();
        for (int i = runs.size() - 1; i >= 0 && out.size() < n; i--) {
            out.add(runs.get(i));
        }
        return out;
    }

    private List<Run> counted() {
        List<Run> out = new ArrayList<Run>();
        for (Run r : runs) {
            out.add(r);
        }
        return out;
    }

    // ================= STATS (every logged run counts — rest days excluded) =================

    public double totalKm() {
        double s = 0;
        for (Run r : runs) s += r.km;
        return s;
    }

    public double totalMin() {
        double s = 0;
        for (Run r : runs) s += r.min;
        return s;
    }

    public int count() {
        int c = 0;
        for (Run r : runs) c++;
        return c;
    }

    public double bestSpeed() {
        double b = 0;
        for (Run r : runs) {
            if (r.min > 0) b = Math.max(b, r.km / (r.min / 60.0));
        }
        return b;
    }

    public double longest() {
        double b = 0;
        for (Run r : runs) {
            b = Math.max(b, r.km);
        }
        return b;
    }

    public double bestPace() {
        double b = Double.MAX_VALUE;
        for (Run r : runs) {
            if (r.km > 0) b = Math.min(b, r.min / r.km);
        }
        return b == Double.MAX_VALUE ? 0 : b;
    }

    /** All-time average pace (min/km) — total time / total distance. */
    public double avgPace() {
        double km = totalKm();
        return km > 0 ? totalMin() / km : 0;
    }

    /** All-time average speed (km/h). */
    public double avgSpeed() {
        double m = totalMin();
        return m > 0 ? totalKm() / (m / 60.0) : 0;
    }

    // ================= WEEK HELPERS =================

    private boolean inWeek(String d, String monday) {
        long e = epoch(d), m = epoch(monday);
        return e >= m && e <= m + 6 * DAY_MS;
    }

    public double weekKm(String monday) {
        double s = 0;
        for (Run r : runs) {
            if (inWeek(r.d, monday)) s += r.km;
        }
        return s;
    }

    public double weekMin(String monday) {
        double s = 0;
        for (Run r : runs) {
            if (inWeek(r.d, monday)) s += r.min;
        }
        return s;
    }

    public int weekRuns(String monday) {
        int c = 0;
        for (Run r : runs) {
            if (inWeek(r.d, monday)) c++;
        }
        return c;
    }

    /** Paces (min/km) of counted runs in the week, ascending by date. */
    public double[] weekPaces(String monday) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (Run r : runs) {
            if (inWeek(r.d, monday) && r.km > 0) {
                out.add(r.min / r.km);
            }
        }
        double[] o = new double[out.size()];
        for (int i = 0; i < o.length; i++) o[i] = out.get(i);
        return o;
    }

    // ================= MONTH HELPERS =================

    private static boolean inMonth(String d, String ym) {
        return d.length() >= 7 && d.substring(0, 7).equals(ym);
    }

    public double monthKm(String ym) {
        double s = 0;
        for (Run r : runs) {
            if (inMonth(r.d, ym)) s += r.km;
        }
        return s;
    }

    public double monthMin(String ym) {
        double s = 0;
        for (Run r : runs) {
            if (inMonth(r.d, ym)) s += r.min;
        }
        return s;
    }

    public int monthRuns(String ym) {
        int c = 0;
        for (Run r : runs) {
            if (inMonth(r.d, ym)) c++;
        }
        return c;
    }

    /** Paces (min/km) of counted runs in the month, ascending by date. */
    public double[] monthPaces(String ym) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (Run r : runs) {
            if (inMonth(r.d, ym) && r.km > 0) {
                out.add(r.min / r.km);
            }
        }
        double[] o = new double[out.size()];
        for (int i = 0; i < o.length; i++) o[i] = out.get(i);
        return o;
    }

    /** Distinct yyyy-MM keys with counted runs, newest first, capped at 8. */
    public List<String> monthsWithRuns() {
        List<String> out = new ArrayList<String>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            String ym = r.d.length() >= 7 ? r.d.substring(0, 7) : r.d;
            if (!out.contains(ym)) out.add(ym);
            if (out.size() >= 8) break;
        }
        return out;
    }

    // ================= YEAR HELPERS =================

    private static boolean inYear(String d, String y) {
        return d.length() >= 4 && d.substring(0, 4).equals(y);
    }

    public double yearKm(String y) {
        double s = 0;
        for (Run r : runs) {
            if (inYear(r.d, y)) s += r.km;
        }
        return s;
    }

    public double yearMin(String y) {
        double s = 0;
        for (Run r : runs) {
            if (inYear(r.d, y)) s += r.min;
        }
        return s;
    }

    public int yearRuns(String y) {
        int c = 0;
        for (Run r : runs) {
            if (inYear(r.d, y)) c++;
        }
        return c;
    }

    /** Paces (min/km) of counted runs in the year, ascending by date. */
    public double[] yearPaces(String y) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (Run r : runs) {
            if (inYear(r.d, y) && r.km > 0) out.add(r.min / r.km);
        }
        double[] o = new double[out.size()];
        for (int i = 0; i < o.length; i++) o[i] = out.get(i);
        return o;
    }

    /** Counted km in the last 14 days (Future Machine rhythm). */
    public double kmLast14() {
        String from = dateShift(today(), -13), td = today();
        double s = 0;
        for (Run r : runs) {
            if (r.d.compareTo(from) >= 0 && r.d.compareTo(td) <= 0) s += r.km;
        }
        return s;
    }

    /** Counted run days in the last 30 days (radar rhythm axis). */
    public int runDaysLast30() {
        String from = dateShift(today(), -29), td = today();
        int c = 0;
        for (Run r : runs) {
            if (r.d.compareTo(from) >= 0 && r.d.compareTo(td) <= 0) c++;
        }
        return c;
    }

    // ================= RUN SCORE (always computed, never stored) =================

    private static int clampI(double v) {
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    /**
     * Composite score of the run on date d:
     * distance 0.3 (vs 10-run avg), pace 0.4 (vs 10-run avg),
     * consistency 0.2 (7-day coverage), improvement 0.1 (vs previous run).
     */
    public int scoreOf(String d) {
        double[] p = scoreParts(d);
        if (p == null) return 0;
        int raw = (int) Math.round(0.3 * p[0] + 0.4 * p[1] + 0.2 * p[2] + 0.1 * p[3]);
        return Math.max(40, Math.min(98, raw));
    }

    /** Latest counted run date, or null. */
    public String latestCountedDate() {
        List<Run> c = counted();
        return c.isEmpty() ? null : c.get(c.size() - 1).d;
    }

    /**
     * Score components for date d (breakdown dialog):
     * {distF, paceF, consF, impF, km, min, avgKm, avgPace, prevPace, days7}
     * Null when there is no counted run on d.
     */
    public double[] scoreParts(String d) {
        Run t = get(d);
        if (t == null || t.km <= 0) return null;
        ArrayDeque<double[]> q = new ArrayDeque<double[]>(); // [km, min]
        double s10k = 0, s10m = 0;
        double prevPace = -1;
        boolean found = false;
        for (Run r : runs) {
            if (r.d.equals(d)) {
                found = true;
                break;
            }
            q.add(new double[]{r.km, r.min});
            s10k += r.km;
            s10m += r.min;
            if (q.size() > 10) {
                double[] rm = q.removeFirst();
                s10k -= rm[0];
                s10m -= rm[1];
            }
            prevPace = r.min / r.km;
        }
        if (!found) return null;
        double tp = t.min / t.km;
        int distF = 60, paceF = 60, impF = 60;
        double avgKm = 0, avgP = 0;
        if (!q.isEmpty()) {
            avgKm = s10k / q.size();
            avgP = s10m / s10k;
            distF = clampI(60 + (t.km / avgKm - 1) * 100);
            paceF = clampI(60 + (avgP / tp - 1) * 200);
            if (prevPace > 0) impF = clampI(60 + (prevPace - tp) * 160);
        }
        int cnt = 0;
        for (int i = 0; i < 7; i++) {
            String dd = dateShift(d, -i);
            Run r = get(dd);
            if (r != null) cnt++;
        }
        int consF = cnt * 100 / 7;
        return new double[]{distF, paceF, consF, impF, t.km, t.min, avgKm, avgP, prevPace, cnt};
    }

    public int latestScore() {
        List<Run> c = counted();
        if (c.isEmpty()) return 0;
        return scoreOf(c.get(c.size() - 1).d);
    }

    public int bestScore() {
        int b = 0;
        for (Run r : counted()) b = Math.max(b, scoreOf(r.d));
        return b;
    }

    public int avgScore() {
        List<Run> c = counted();
        if (c.isEmpty()) return 0;
        int s = 0;
        for (Run r : c) s += scoreOf(r.d);
        return s / c.size();
    }

    /** Last n scores, oldest first (for the score strip). */
    public int[] lastScores(int n) {
        List<Run> c = counted();
        int from = Math.max(0, c.size() - n);
        int[] out = new int[c.size() - from];
        for (int i = from; i < c.size(); i++) out[i - from] = scoreOf(c.get(i).d);
        return out;
    }

    /** Last n paces, oldest first (for the trend chart). */
    public double[] lastPaces(int n) {
        List<Run> c = counted();
        int from = Math.max(0, c.size() - n);
        double[] out = new double[c.size() - from];
        for (int i = from; i < c.size(); i++) out[i - from] = c.get(i).min / c.get(i).km;
        return out;
    }

    // ================= MOOD INSIGHT =================

    /**
     * Returns {moodIndex, secPerKm, dir} where dir=1 means faster, or null.
     * Needs 3+ runs with the same pre-mood and a meaningful difference (12+ sec/km).
     */
    public int[] moodInsight() {
        double totKm = 0, totMin = 0;
        double[] gKm = new double[4], gMin = new double[4];
        int[] gN = new int[4];
        for (Run r : runs) {
            totKm += r.km;
            totMin += r.min;
            if (r.m1 >= 0 && r.m1 < 4) {
                gKm[r.m1] += r.km;
                gMin[r.m1] += r.min;
                gN[r.m1]++;
            }
        }
        if (totKm <= 0) return null;
        double overall = totMin / totKm;
        int bi = -1;
        double bd = 0;
        for (int i = 0; i < 4; i++) {
            if (gN[i] >= 3 && gKm[i] > 0) {
                double diff = overall - (gMin[i] / gKm[i]); // positive = faster than overall
                if (Math.abs(diff) > Math.abs(bd)) {
                    bd = diff;
                    bi = i;
                }
            }
        }
        if (bi < 0 || Math.abs(bd) < 0.2) return null; // under ~12 sec/km: skip
        int sec = (int) Math.round(Math.abs(bd) * 60.0);
        return new int[]{bi, sec, bd > 0 ? 1 : 0};
    }

    // ================= SMART CHIPS =================

    /** Up to n recent distinct distances (rounded to 0.1 km), newest first. */
    public double[] recentKm(int n) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (int i = runs.size() - 1; i >= 0 && out.size() < n; i--) {
            Run r = runs.get(i);
            double v = Math.round(r.km * 10) / 10.0;
            boolean dup = false;
            for (double x : out) {
                if (Math.abs(x - v) < 0.05) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(v);
        }
        double[] o = new double[out.size()];
        for (int i = 0; i < o.length; i++) o[i] = out.get(i);
        return o;
    }

    // ================= v3.7: IDENTITY / CAL / LAPS =================

    /** Attach lap data to an existing run. */
    public void setLaps(String d, double[] laps) {
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.d.equals(d)) {
                runs.set(i, new Run(r.d, r.km, r.min, r.m1, r.m2, r.pin, laps, r.bpm));
                persist();
                return;
            }
        }
    }

    /** Attach post-run BPM to an existing run. */
    public void setBpm(String d, int bpm) {
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            if (r.d.equals(d)) {
                runs.set(i, new Run(r.d, r.km, r.min, r.m1, r.m2, r.pin, r.laps, bpm));
                persist();
                return;
            }
        }
    }

    /** Lap minutes of the run on d, or null. */
    public double[] lapsOf(String d) {
        Run r = get(d);
        return r == null ? null : r.laps;
    }

    /** Fastest lap (minutes) across all runs, or 0. */
    public double fastestLapEver() {
        double b = 0;
        for (Run r : runs) {
            if (r.laps == null) continue;
            for (double v : r.laps) if (v > 0 && (b == 0 || v < b)) b = v;
        }
        return b;
    }

    /** First ever run date, or null. */
    public String firstRunDate() {
        return runs.isEmpty() ? null : runs.get(0).d;
    }

    /** Est. calories of a run (weight kg x km x 1.03); 0 when no weight set. */
    public double calOf(Run r) {
        float w = prefs.getFloat("body_w", 0f);
        return (r != null && w > 0) ? w * r.km * 1.03 : 0;
    }

    /** % of mood-logged runs where mood rose (m2 > m1); -1 = not enough data. */
    public int moodLiftPct() {
        int tot = 0, up = 0;
        for (Run r : runs) {
            if (r.m1 >= 0 && r.m2 >= 0) {
                tot++;
                if (r.m2 > r.m1) up++;
            }
        }
        return tot >= 3 ? Math.round(up * 100f / tot) : -1;
    }

    /** Weekday name with the best avg pace (needs 2+ runs there and 2+ active days); null = no verdict. */
    public String strongestDay() {
        double[] km = new double[7], min = new double[7];
        int[] n = new int[7];
        int days = 0;
        boolean[] seen = new boolean[7];
        for (Run r : runs) {
            int w = dowOf(r.d) - 1;
            if (w < 0 || w > 6) continue;
            if (r.km > 0) { km[w] += r.km; min[w] += r.min; n[w]++; }
            if (!seen[w]) { seen[w] = true; days++; }
        }
        if (days < 2) return null;
        int best = -1;
        double bp = 0;
        for (int i = 0; i < 7; i++) {
            if (n[i] >= 2 && km[i] > 0) {
                double p = min[i] / km[i];
                if (best < 0 || p < bp) { bp = p; best = i; }
            }
        }
        return best < 0 ? null : DAY_NAMES_FULL[best];
    }

    public double bestKmWeek(String mon) {
        double b = 0;
        for (Run r : runs) if (inWeek(r.d, mon)) b = Math.max(b, r.km);
        return b;
    }

    public double bestKmMonth(String ym) {
        double b = 0;
        for (Run r : runs) if (inMonth(r.d, ym)) b = Math.max(b, r.km);
        return b;
    }

    public double bestKmYear(String y) {
        double b = 0;
        for (Run r : runs) if (inYear(r.d, y)) b = Math.max(b, r.km);
        return b;
    }

    // ================= EXPORT / IMPORT =================

    public String toJSON() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Run r : runs) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("d", r.d);
                o.put("km", r.km);
                o.put("min", r.min);
                o.put("m1", r.m1);
                o.put("m2", r.m2);
                o.put("pin", r.pin);
                if (r.laps != null && r.laps.length > 0) {
                    org.json.JSONArray lp = new org.json.JSONArray();
                    for (double v : r.laps) lp.put(v);
                    o.put("laps", lp);
                }
                double cal = calOf(r);
                if (cal > 0) o.put("cal", Math.round(cal));
                if (r.bpm > 0) o.put("bpm", r.bpm);
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        org.json.JSONObject prof = new org.json.JSONObject();
        try {
            String un = prefs.getString("userName", "");
            if (un.length() > 0) prof.put("userName", un);
            String db = prefs.getString("dob", "");
            if (db.length() > 0) prof.put("dob", db);
            String ct = prefs.getString("city", "");
            if (ct.length() > 0) prof.put("city", ct);
            if (prefs.getFloat("body_h", 0f) > 0) prof.put("body_h", prefs.getFloat("body_h", 0f));
            if (prefs.getFloat("body_w", 0f) > 0) prof.put("body_w", prefs.getFloat("body_w", 0f));
            if (prefs.contains("bf_female")) prof.put("bf_female", prefs.getBoolean("bf_female", false));
            if (prefs.contains("bf_waist")) prof.put("bf_waist", prefs.getFloat("bf_waist", 80f));
            if (prefs.contains("bf_neck")) prof.put("bf_neck", prefs.getFloat("bf_neck", 36f));
            if (prefs.contains("bf_hip")) prof.put("bf_hip", prefs.getFloat("bf_hip", 92f));
            if (prefs.getInt("str_push", 0) > 0) prof.put("str_push", prefs.getInt("str_push", 0));
            if (prefs.getInt("str_squat", 0) > 0) prof.put("str_squat", prefs.getInt("str_squat", 0));
            if (prefs.getInt("str_pull", 0) > 0) prof.put("str_pull", prefs.getInt("str_pull", 0));
            if (prefs.getInt("str_plank", 0) > 0) prof.put("str_plank", prefs.getInt("str_plank", 0));
            if (prefs.contains("water_hot")) prof.put("water_hot", prefs.getBoolean("water_hot", false));
            if (prefs.contains("crosite_stride_k")) prof.put("crosite_stride_k", prefs.getFloat("crosite_stride_k", 1.0f));
        } catch (Exception ignored) {
        }
        org.json.JSONObject root = new org.json.JSONObject();
        try {
            root.put("version", 4);
            root.put("runs", a);
            if (prof.length() > 0) root.put("profile", prof);
            String pl = prefs.getString("pulse_log", "[]");
            org.json.JSONArray pls = new org.json.JSONArray(pl);
            if (pls.length() > 0) root.put("pulse_log", pls);
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    public int importMerge(String json) {
        int c = 0;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray a = root.getJSONArray("runs");
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String d = o.getString("d");
                double km = o.getDouble("km");
                double min = o.getDouble("min");
                if (d != null && d.length() == 10 && km > 0 && km <= 200
                        && min > 0 && min <= 600) {
                    upsert(d, km, min);
                    if (o.optInt("m1", -1) >= 0) setMood(d, 0, o.optInt("m1", -1));
                    if (o.optInt("m2", -1) >= 0) setMood(d, 1, o.optInt("m2", -1));
                    double[] lp = parseLaps(o.optJSONArray("laps"));
                    if (lp != null) setLaps(d, lp);
                    if (o.optInt("bpm", 0) > 0) setBpm(d, o.optInt("bpm", 0));
                    String pinStr = o.optString("pin", "");
                    if (pinStr.length() > 0) {
                        Run cur = get(d);
                        if (cur != null && cur.pin.length() == 0) {
                            for (int ri = 0; ri < runs.size(); ri++) {
                                if (runs.get(ri).d.equals(d)) {
                                    Run r2 = runs.get(ri);
                                    runs.set(ri, new Run(r2.d, r2.km, r2.min, r2.m1, r2.m2, pinStr, r2.laps, r2.bpm));
                                    persist();
                                    break;
                                }
                            }
                        }
                    }
                    c++;
                }
            }
            org.json.JSONObject prof = root.optJSONObject("profile");
            if (prof != null) {
                SharedPreferences.Editor pe = prefs.edit();
                if (prof.has("userName")) pe.putString("userName", prof.optString("userName", ""));
                if (prof.has("dob")) pe.putString("dob", prof.optString("dob", ""));
                if (prof.has("city")) pe.putString("city", prof.optString("city", ""));
                if (prof.has("body_h")) pe.putFloat("body_h", (float) prof.optDouble("body_h", 0));
                if (prof.has("body_w")) pe.putFloat("body_w", (float) prof.optDouble("body_w", 0));
                if (prof.has("bf_female")) pe.putBoolean("bf_female", prof.optBoolean("bf_female", false));
                if (prof.has("bf_waist")) pe.putFloat("bf_waist", (float) prof.optDouble("bf_waist", 80));
                if (prof.has("bf_neck")) pe.putFloat("bf_neck", (float) prof.optDouble("bf_neck", 36));
                if (prof.has("bf_hip")) pe.putFloat("bf_hip", (float) prof.optDouble("bf_hip", 92));
                if (prof.has("str_push")) pe.putInt("str_push", prof.optInt("str_push", 0));
                if (prof.has("str_squat")) pe.putInt("str_squat", prof.optInt("str_squat", 0));
                if (prof.has("str_pull")) pe.putInt("str_pull", prof.optInt("str_pull", 0));
                if (prof.has("str_plank")) pe.putInt("str_plank", prof.optInt("str_plank", 0));
                if (prof.has("water_hot")) pe.putBoolean("water_hot", prof.optBoolean("water_hot", false));
                if (prof.has("crosite_stride_k")) pe.putFloat("crosite_stride_k", (float) prof.optDouble("crosite_stride_k", 1.0));
                pe.apply();
            }
            org.json.JSONArray pls = root.optJSONArray("pulse_log");
            if (pls != null && pls.length() > 0) {
                PulseEngine.mergeLog(prefs, pls);
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    public static String monthTitle(String ym) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("MMMM yyyy", Locale.US);
            return out.format(in.parse(ym)).toUpperCase(Locale.US);
        } catch (Exception e) {
            return ym;
        }
    }
}
