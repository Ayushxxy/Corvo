package com.carvo.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class CroSiteEngine {

    public static final String PREFS = "carvo_crosite";
    public static final double MAX_RUN_SPEED_MPS = 10.5; // 37.8 km/h — sprints up to ~37 km/h accepted; teleports dropped
    public static final float MAX_ACC_M = 22.0f;

    private final File trailFile;
    private final SharedPreferences tp;
    private final SharedPreferences cp;

    // Live runtime state
    private double grav = 9.81;
    private boolean gravInit = false;
    private long lastStepMs = 0L;
    private int consecSteps = 0;
    private double avgStepMs = 370.0; // ~162 SPM baseline
    private int stepsSinceAnchor = 0;
    private int badTeleport = 0;

    private double distM = 0.0;
    private double gpsM = 0.0;
    private double bridgeM = 0.0;
    private double bridgeSinceFixM = 0.0;
    private long steps = 0L;

    private boolean active = false;
    private boolean paused = false;
    private boolean hasAnchor = false;
    private double anchorLat = 0.0;
    private double anchorLon = 0.0;
    private long anchorMs = 0L;
    private long lastCleanGpsMs = 0L;
    private boolean satReady = false;
    private float lastAcc = 0f;
    private double strideK = 1.0;

    // CroSite Pro: Live Cadence + Auto Turnaround (OUT vs BACK)
    private double cadSum = 0.0;
    private long cadN = 0L;
    private boolean hasOrigin = false;
    private double originLat = 0.0, originLon = 0.0;
    private double maxOriginM = 0.0;
    private double peakDistM = 0.0;
    private long peakElMs = 0L;
    private boolean turnFound = false;
    private boolean turnPendingSpeak = false;
    private double turnDistM = 0.0;
    private long turnElMs = 0L;
    private double outPace = 0.0;
    private double peakLat = 0.0, peakLon = 0.0;
    private double turnLat = 0.0, turnLon = 0.0;

    // Live GPS fix + trail persistence for offline CroMapView
    private boolean hasLiveFix = false;
    private double liveLat = 0.0, liveLon = 0.0;
    private float liveAcc = 0f;
    private boolean hasLastTrailPt = false;
    private double lastTrailLat = 0.0, lastTrailLon = 0.0;
    private int trailCount = 0;

    public CroSiteEngine(Context ctx) {
        trailFile = new File(ctx.getFilesDir(), "crosite_trail.bin");
        tp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cp = ctx.getSharedPreferences("carvo", Context.MODE_PRIVATE);
        load();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true);
    }

    public static void setEnabled(Context ctx, boolean en) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", en).apply();
    }

    public static double readDistKm(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return getD(p, "distM", 0.0) / 1000.0;
    }

    public static int readLiveSpm(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong("lastStepWall", 0L);
        if (System.currentTimeMillis() - last > 3000L) return 0;
        return p.getInt("liveSpm", 0);
    }

    public static int readAvgSpm(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long n = p.getLong("cadN", 0L);
        if (n < 15L) return 0;
        return (int) Math.round(getD(p, "cadSum", 0.0) / n);
    }

    public static double readStrideM(Context ctx) {
        return getD(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), "strideM", 0.0);
    }

    public static boolean isTurnFound(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("turnFound", false);
    }

    public static double[] readOutBack(Context ctx, double totalRunMin) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("turnFound", false)) return new double[]{0, 0};
        double distM = getD(p, "distM", 0.0);
        double tDist = getD(p, "turnDistM", 0.0);
        long tEl = p.getLong("turnElMs", 0L);
        double op = getD(p, "outPace", 0.0);
        double backDistKm = (distM - tDist) / 1000.0;
        double backMin = totalRunMin - (tEl / 60000.0);
        if (op < 2.0 || op > 20.0 || backDistKm < 0.12 || backMin <= 0.3) return new double[]{0, 0};
        double bp = backMin / backDistKm;
        if (bp < 2.0 || bp > 20.0) return new double[]{0, 0};
        return new double[]{op, bp};
    }

    public static boolean isSatReady(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("satReady", false);
    }

    public static float lastAccuracy(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("lastAcc", 0f);
    }

    public static void setWarmupSat(Context ctx, boolean ready, float acc) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("satReady", ready).putFloat("lastAcc", acc).apply();
    }

    public static void setWarmupFix(Context ctx, double lat, double lon, float acc, boolean ready) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("satReady", ready)
                .putFloat("lastAcc", acc)
                .putBoolean("hasLiveFix", true)
                .putFloat("liveAcc", acc);
        putD(ed, "liveLat", lat);
        putD(ed, "liveLon", lon);
        ed.apply();
    }

    public static double[] readLiveFix(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("hasLiveFix", false)) return null;
        return new double[]{
                getD(p, "liveLat", 0.0),
                getD(p, "liveLon", 0.0),
                p.getFloat("liveAcc", 0f)
        };
    }

    public static double[] readTurnLatLon(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("turnFound", false)) return null;
        double lat = getD(p, "turnLat", 0.0);
        double lon = getD(p, "turnLon", 0.0);
        if (lat == 0.0 && lon == 0.0) return null;
        return new double[]{lat, lon};
    }

    public static double[] readTrail(Context ctx) {
        File f = new File(ctx.getFilesDir(), "crosite_trail.bin");
        if (!f.exists() || f.length() < 16) return null;
        long len = f.length();
        int n = (int) Math.min(4000L, len / 16L);
        if (n < 1) return null;
        double[] out = new double[n * 2];
        DataInputStream dis = null;
        try {
            dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 8192));
            for (int i = 0; i < n; i++) {
                out[i * 2] = dis.readDouble();
                out[i * 2 + 1] = dis.readDouble();
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (dis != null) dis.close(); } catch (Exception ignored) {}
        }
    }

    public static void resetAll(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean en = p.getBoolean("enabled", true);
        boolean hasFix = p.getBoolean("hasLiveFix", false);
        double lLat = getD(p, "liveLat", 0.0);
        double lLon = getD(p, "liveLon", 0.0);
        float lAcc = p.getFloat("liveAcc", 0f);
        SharedPreferences.Editor ed = p.edit().clear().putBoolean("enabled", en);
        if (hasFix) {
            ed.putBoolean("hasLiveFix", true).putFloat("liveAcc", lAcc);
            putD(ed, "liveLat", lLat);
            putD(ed, "liveLon", lLon);
        }
        ed.apply();
        File tf = new File(ctx.getFilesDir(), "crosite_trail.bin");
        if (tf.exists()) tf.delete();
    }

    private float heightCm() {
        try {
            float h = cp.getFloat("body_h", 0f);
            return h >= 100f && h <= 240f ? h : 172f;
        } catch (Exception e) {
            return 172f;
        }
    }

    public synchronized void load() {
        active = tp.getBoolean("active", false);
        paused = tp.getBoolean("paused", false);
        distM = getD(tp, "distM", 0.0);
        gpsM = getD(tp, "gpsM", 0.0);
        bridgeM = getD(tp, "bridgeM", 0.0);
        bridgeSinceFixM = getD(tp, "bridgeGap", 0.0);
        steps = tp.getLong("steps", 0L);
        hasAnchor = tp.getBoolean("hasAnchor", false);
        anchorLat = getD(tp, "anchorLat", 0.0);
        anchorLon = getD(tp, "anchorLon", 0.0);
        anchorMs = tp.getLong("anchorMs", 0L);
        lastCleanGpsMs = tp.getLong("lastCleanGpsMs", 0L);
        satReady = tp.getBoolean("satReady", false);
        lastAcc = tp.getFloat("lastAcc", 0f);
        cadSum = getD(tp, "cadSum", 0.0);
        cadN = tp.getLong("cadN", 0L);
        hasOrigin = tp.getBoolean("hasOrigin", false);
        originLat = getD(tp, "originLat", 0.0);
        originLon = getD(tp, "originLon", 0.0);
        maxOriginM = getD(tp, "maxOriginM", 0.0);
        peakDistM = getD(tp, "peakDistM", 0.0);
        peakElMs = tp.getLong("peakElMs", 0L);
        turnFound = tp.getBoolean("turnFound", false);
        turnDistM = getD(tp, "turnDistM", 0.0);
        turnElMs = tp.getLong("turnElMs", 0L);
        outPace = getD(tp, "outPace", 0.0);
        peakLat = getD(tp, "peakLat", 0.0);
        peakLon = getD(tp, "peakLon", 0.0);
        turnLat = getD(tp, "turnLat", 0.0);
        turnLon = getD(tp, "turnLon", 0.0);
        hasLiveFix = tp.getBoolean("hasLiveFix", false);
        liveLat = getD(tp, "liveLat", 0.0);
        liveLon = getD(tp, "liveLon", 0.0);
        liveAcc = tp.getFloat("liveAcc", 0f);
        hasLastTrailPt = tp.getBoolean("hasLastTrailPt", false);
        lastTrailLat = getD(tp, "lastTrailLat", 0.0);
        lastTrailLon = getD(tp, "lastTrailLon", 0.0);
        trailCount = tp.getInt("trailCount", 0);
        try {
            strideK = cp.getFloat("crosite_stride_k", 1.0f);
        } catch (Exception e) {
            strideK = 1.0;
        }
        if (strideK < 0.75 || strideK > 1.35) strideK = 1.0;
    }

    public synchronized void save() {
        SharedPreferences.Editor e = tp.edit();
        e.putBoolean("active", active);
        e.putBoolean("paused", paused);
        putD(e, "distM", distM);
        putD(e, "gpsM", gpsM);
        putD(e, "bridgeM", bridgeM);
        putD(e, "bridgeGap", bridgeSinceFixM);
        e.putLong("steps", steps);
        e.putBoolean("hasAnchor", hasAnchor);
        putD(e, "anchorLat", anchorLat);
        putD(e, "anchorLon", anchorLon);
        e.putLong("anchorMs", anchorMs);
        e.putLong("lastCleanGpsMs", lastCleanGpsMs);
        e.putBoolean("satReady", satReady);
        e.putFloat("lastAcc", lastAcc);
        putD(e, "cadSum", cadSum);
        e.putLong("cadN", cadN);
        e.putInt("liveSpm", consecSteps >= 2 ? (int) Math.round(60000.0 / Math.max(260.0, avgStepMs)) : 0);
        e.putLong("lastStepWall", lastStepMs);
        putD(e, "strideM", rawStepStrideM(heightCm(), avgStepMs) * strideK);
        e.putBoolean("hasOrigin", hasOrigin);
        putD(e, "originLat", originLat);
        putD(e, "originLon", originLon);
        putD(e, "maxOriginM", maxOriginM);
        putD(e, "peakDistM", peakDistM);
        e.putLong("peakElMs", peakElMs);
        e.putBoolean("turnFound", turnFound);
        putD(e, "turnDistM", turnDistM);
        e.putLong("turnElMs", turnElMs);
        putD(e, "outPace", outPace);
        putD(e, "peakLat", peakLat);
        putD(e, "peakLon", peakLon);
        putD(e, "turnLat", turnLat);
        putD(e, "turnLon", turnLon);
        e.putBoolean("hasLiveFix", hasLiveFix);
        putD(e, "liveLat", liveLat);
        putD(e, "liveLon", liveLon);
        e.putFloat("liveAcc", liveAcc);
        e.putBoolean("hasLastTrailPt", hasLastTrailPt);
        putD(e, "lastTrailLat", lastTrailLat);
        putD(e, "lastTrailLon", lastTrailLon);
        e.putInt("trailCount", trailCount);
        e.apply();
        cp.edit().putFloat("crosite_stride_k", (float) strideK).apply();
    }

    public synchronized void beginRun() {
        distM = 0.0;
        gpsM = 0.0;
        bridgeM = 0.0;
        bridgeSinceFixM = 0.0;
        steps = 0L;
        stepsSinceAnchor = 0;
        badTeleport = 0;
        consecSteps = 0;
        lastStepMs = 0L;
        active = true;
        paused = false;
        hasAnchor = false;
        lastCleanGpsMs = 0L;
        cadSum = 0.0;
        cadN = 0L;
        hasOrigin = false;
        originLat = 0.0;
        originLon = 0.0;
        maxOriginM = 0.0;
        peakDistM = 0.0;
        peakElMs = 0L;
        turnFound = false;
        turnPendingSpeak = false;
        turnDistM = 0.0;
        turnElMs = 0L;
        outPace = 0.0;
        peakLat = 0.0;
        peakLon = 0.0;
        turnLat = 0.0;
        turnLon = 0.0;
        hasLastTrailPt = false;
        lastTrailLat = 0.0;
        lastTrailLon = 0.0;
        trailCount = 0;
        if (trailFile.exists()) trailFile.delete();
        save();
    }

    public synchronized int getLiveSpm(long nowMs) {
        if (!active || paused || (nowMs - lastStepMs) > 3000L || consecSteps < 2) return 0;
        return (int) Math.round(60000.0 / Math.max(260.0, avgStepMs));
    }

    public synchronized int getAvgCadenceSpm() {
        return cadN >= 15L ? (int) Math.round(cadSum / cadN) : 0;
    }

    public synchronized boolean consumeTurnaroundSpeak() {
        if (!turnPendingSpeak) return false;
        turnPendingSpeak = false;
        return true;
    }

    public synchronized void setPaused(boolean p) {
        if (paused == p) return;
        paused = p;
        // Clear anchor across PAUSE / 60s REST so walking during rest never creates a line jump
        hasAnchor = false;
        bridgeSinceFixM = 0.0;
        stepsSinceAnchor = 0;
        consecSteps = 0;
        badTeleport = 0;
        save();
    }

    public synchronized void endRun() {
        active = false;
        paused = false;
        hasAnchor = false;
        save();
    }

    public synchronized double getDistKm() {
        return distM / 1000.0;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized boolean isMoving(long nowMs) {
        return consecSteps >= 2 && (nowMs - lastStepMs) <= 1600L;
    }

    /** Layer 1 + Layer 3: Accelerometer step guard and cadence-adaptive stride bridge. */
    public synchronized boolean onAccel(float x, float y, float z, long nowMs) {
        if (!active || paused) return false;
        double mag = Math.sqrt(x * x + y * y + z * z);
        if (!gravInit) {
            grav = mag;
            gravInit = true;
            return false;
        }
        grav = 0.90 * grav + 0.10 * mag;
        double dyn = Math.abs(mag - grav);
        if (dyn > 2.2 && (nowMs - lastStepMs) >= 260L) {
            long dtStep = nowMs - lastStepMs;
            if (lastStepMs > 0L && dtStep <= 1500L) {
                avgStepMs = 0.75 * avgStepMs + 0.25 * dtStep;
                consecSteps = Math.min(1000, consecSteps + 1);
                double spm = Math.max(110.0, Math.min(225.0, 60000.0 / dtStep));
                cadSum += spm;
                cadN++;
            } else {
                consecSteps = 1;
            }
            lastStepMs = nowMs;
            steps++;
            stepsSinceAnchor++;
            // Layer 3 bridge: only if GPS went missing for >4s (flyover / trees) up to 60s
            if (lastCleanGpsMs > 0L && (nowMs - lastCleanGpsMs) > 4000L
                    && (nowMs - lastCleanGpsMs) <= 60000L && isMoving(nowMs)) {
                double stepLen = rawStepStrideM(heightCm(), avgStepMs) * strideK;
                bridgeM += stepLen;
                bridgeSinceFixM += stepLen;
                distM += stepLen;
            }
            return true;
        }
        return false;
    }

    /** Layer 2: Motion-gated, sprint-safe satellite distance calculation. */
    public synchronized boolean onGpsFix(double lat, double lon, float acc, long nowMs) {
        return onGpsFix(lat, lon, acc, nowMs, 0L);
    }

    public synchronized boolean onGpsFix(double lat, double lon, float acc, long nowMs, long elapsedRunMs) {
        if (acc <= 0f || acc > MAX_ACC_M) {
            satReady = false;
            lastAcc = acc;
            return false;
        }
        satReady = true;
        lastAcc = acc;
        hasLiveFix = true;
        liveLat = lat;
        liveLon = lon;
        liveAcc = acc;
        if (!active || paused) {
            save();
            return false;
        }

        lastCleanGpsMs = nowMs;
        if (!hasOrigin) {
            originLat = lat;
            originLon = lon;
            hasOrigin = true;
            appendTrailPoint(lat, lon);
        }
        if (!hasAnchor) {
            anchorLat = lat;
            anchorLon = lon;
            anchorMs = nowMs;
            hasAnchor = true;
            bridgeSinceFixM = 0.0;
            stepsSinceAnchor = 0;
            badTeleport = 0;
            appendTrailPoint(lat, lon);
            save();
            return true;
        }

        double d = haversineM(anchorLat, anchorLon, lat, lon);
        double dt = Math.max(0.5, (nowMs - anchorMs) / 1000.0);
        double v = d / dt;

        if (v > MAX_RUN_SPEED_MPS) {
            badTeleport++;
            if (badTeleport >= 3) {
                // Previous anchor was bad — quietly re-anchor without adding teleport distance
                anchorLat = lat;
                anchorLon = lon;
                anchorMs = nowMs;
                bridgeSinceFixM = 0.0;
                stepsSinceAnchor = 0;
                badTeleport = 0;
                save();
            }
            return false;
        }
        badTeleport = 0;

        // Motion guard: reject standing GPS cloud jitter when accelerometer detects no steps
        if (!isMoving(nowMs) && d < 25.0) {
            return false;
        }

        // Micro-jitter gate: wait for >= 1.5m real displacement before advancing anchor
        if (d < 1.5) {
            return false;
        }

        // Accept segment & reconcile any Layer-3 bridge distance cleanly (zero double-count!)
        if (bridgeSinceFixM > 0.0) {
            double corr = d - bridgeSinceFixM;
            distM = Math.max(0.0, distM + corr);
            gpsM += d;
            bridgeM = Math.max(0.0, bridgeM - bridgeSinceFixM);
            bridgeSinceFixM = 0.0;
        } else {
            distM += d;
            gpsM += d;
        }

        // Self-calibrate stride multiplier on clean steady GPS segments
        if (dt >= 2.0 && dt <= 15.0 && acc <= 15.0f && stepsSinceAnchor >= 4
                && v >= 1.8 && v <= 6.5) {
            double pred = stepsSinceAnchor * rawStepStrideM(heightCm(), avgStepMs);
            if (pred > 2.0) {
                double ratio = Math.max(0.75, Math.min(1.35, d / pred));
                strideK = 0.94 * strideK + 0.06 * ratio;
            }
        }

        anchorLat = lat;
        anchorLon = lon;
        anchorMs = nowMs;
        stepsSinceAnchor = 0;
        appendTrailPoint(lat, lon);

        // Auto Turnaround (OUT vs BACK split, zero maps needed)
        if (hasOrigin && !turnFound) {
            double rad = haversineM(originLat, originLon, lat, lon);
            if (rad >= maxOriginM) {
                maxOriginM = rad;
                peakDistM = distM;
                peakElMs = elapsedRunMs;
                peakLat = lat;
                peakLon = lon;
            } else if (maxOriginM >= 150.0 && rad <= maxOriginM - 65.0
                    && peakDistM >= 150.0 && peakElMs >= 30000L) {
                turnFound = true;
                turnPendingSpeak = true;
                turnDistM = peakDistM;
                turnElMs = peakElMs;
                turnLat = peakLat != 0.0 ? peakLat : lat;
                turnLon = peakLon != 0.0 ? peakLon : lon;
                outPace = (peakElMs / 60000.0) / (peakDistM / 1000.0);
            }
        }

        save();
        return true;
    }

    private void appendTrailPoint(double lat, double lon) {
        if (trailCount >= 4000) return;
        if (hasLastTrailPt && haversineM(lastTrailLat, lastTrailLon, lat, lon) < 3.0) return;
        DataOutputStream dos = null;
        try {
            dos = new DataOutputStream(new FileOutputStream(trailFile, true));
            dos.writeDouble(lat);
            dos.writeDouble(lon);
            dos.flush();
            hasLastTrailPt = true;
            lastTrailLat = lat;
            lastTrailLon = lon;
            trailCount++;
        } catch (Exception ignored) {
        } finally {
            try { if (dos != null) dos.close(); } catch (Exception ignored) {}
        }
    }

    public static double rawStepStrideM(double heightCm, double stepMs) {
        double spm = Math.max(110.0, Math.min(210.0, 60000.0 / Math.max(260.0, stepMs)));
        double hM = (heightCm >= 100.0 && heightCm <= 240.0 ? heightCm : 172.0) / 100.0;
        return hM * (0.45 + 0.0032 * Math.max(0.0, spm - 110.0));
    }

    public static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static void putD(SharedPreferences.Editor e, String k, double v) {
        e.putLong(k, Double.doubleToRawLongBits(v));
    }

    private static double getD(SharedPreferences p, String k, double def) {
        if (!p.contains(k)) return def;
        try {
            return Double.longBitsToDouble(p.getLong(k, Double.doubleToRawLongBits(def)));
        } catch (Exception e) {
            return def;
        }
    }
}
