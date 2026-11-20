package com.carvo.app;

import android.content.Context;
import android.content.SharedPreferences;

public class CroSiteEngine {

    public static final String PREFS = "carvo_crosite";
    public static final double MAX_RUN_SPEED_MPS = 10.5; // 37.8 km/h — sprints up to ~37 km/h accepted; teleports dropped
    public static final float MAX_ACC_M = 22.0f;

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

    public CroSiteEngine(Context ctx) {
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

    public static void resetAll(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean en = p.getBoolean("enabled", true);
        p.edit().clear().putBoolean("enabled", en).apply();
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
        save();
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
        if (acc <= 0f || acc > MAX_ACC_M) {
            satReady = false;
            lastAcc = acc;
            return false;
        }
        satReady = true;
        lastAcc = acc;
        if (!active || paused) return false;

        lastCleanGpsMs = nowMs;
        if (!hasAnchor) {
            anchorLat = lat;
            anchorLon = lon;
            anchorMs = nowMs;
            hasAnchor = true;
            bridgeSinceFixM = 0.0;
            stepsSinceAnchor = 0;
            badTeleport = 0;
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
        save();
        return true;
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
