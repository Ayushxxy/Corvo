package com.carvo.app;

import android.content.SharedPreferences;
import android.media.Image;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class PulseEngine {

    public static class FrameStats {
        public final double avgR;
        public final double avgG;
        public final double avgV;
        public final boolean fingerOn;

        public FrameStats(double r, double g, double v, boolean on) {
            this.avgR = r;
            this.avgG = g;
            this.avgV = v;
            this.fingerOn = on;
        }
    }

    public static class PulseResult {
        public final int bpm;
        public final double confidence;
        public final boolean locked;
        public final String note;
        public final double hrvMs;

        public PulseResult(int bpm, double confidence, boolean locked, String note) {
            this(bpm, confidence, locked, note, 0.0);
        }

        public PulseResult(int bpm, double confidence, boolean locked, String note, double hrvMs) {
            this.bpm = bpm;
            this.confidence = confidence;
            this.locked = locked;
            this.note = note;
            this.hrvMs = hrvMs;
        }
    }

    public static class PulseEntry {
        public final long ts;
        public final String d;
        public final int bpm;
        public final String tag;
        public final double hrvMs;

        public PulseEntry(long ts, String d, int bpm, String tag) {
            this(ts, d, bpm, tag, 0.0);
        }

        public PulseEntry(long ts, String d, int bpm, String tag, double hrvMs) {
            this.ts = ts;
            this.d = d;
            this.bpm = bpm;
            this.tag = tag;
            this.hrvMs = hrvMs;
        }
    }

    private static final int MAX_SAMPLES = 1050;
    private final long[] tsBuf = new long[MAX_SAMPLES];
    private final double[] rawBuf = new double[MAX_SAMPLES];
    private int count = 0;

    private double liveFast = 0.0;
    private double liveSlow = 0.0;
    private boolean liveInit = false;
    private long lastLiveTs = 0L;

    public void reset() {
        count = 0;
        liveInit = false;
        liveFast = 0.0;
        liveSlow = 0.0;
        lastLiveTs = 0L;
    }

    public int getCount() {
        return count;
    }

    /** Push one finger-covered frame (inverted red intensity: systolic blood peak = wave up). */
    public float pushSample(long tsMs, double avgR) {
        double sig = -avgR;
        if (count < MAX_SAMPLES) {
            tsBuf[count] = tsMs;
            rawBuf[count] = sig;
            count++;
        }
        if (!liveInit) {
            liveFast = sig;
            liveSlow = sig;
            liveLast(tsMs);
            liveInit = true;
            return 0f;
        }
        double dt = Math.max(0.008, Math.min(0.25, (tsMs - lastLiveTs) / 1000.0));
        lastLiveTs = tsMs;
        double aFast = dt / (0.045 + dt);
        double aSlow = dt / (0.45 + dt);
        liveFast += aFast * (sig - liveFast);
        liveSlow += aSlow * (sig - liveSlow);
        return (float) (liveFast - liveSlow);
    }

    private void liveLast(long tsMs) {
        lastLiveTs = tsMs;
    }

    /** Estimate live preview BPM from recent samples once >= 4s collected. */
    public int estimateLiveBpm() {
        if (count < 110) return 0;
        PulseResult r = analyzeSignal(tsBuf, rawBuf, count);
        return r.confidence >= 0.30 ? r.bpm : 0;
    }

    /** Final high-precision BPM verdict over the full 15s capture. */
    public PulseResult finalizeReading() {
        return analyzeSignal(tsBuf, rawBuf, count);
    }

    /** Zero-allocation YUV_420_888 center box red/green extractor (MediaTek + Qualcomm safe). */
    public static FrameStats extractYuvCenter(Image img) {
        if (img == null || img.getPlanes().length < 3) {
            return new FrameStats(0, 0, 0, false);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        Image.Plane py = img.getPlanes()[0];
        Image.Plane pu = img.getPlanes()[1];
        Image.Plane pv = img.getPlanes()[2];

        ByteBuffer by = py.getBuffer();
        ByteBuffer bu = pu.getBuffer();
        ByteBuffer bv = pv.getBuffer();

        int yRow = py.getRowStride(), yPix = py.getPixelStride();
        int uRow = pu.getRowStride(), uPix = pu.getPixelStride();
        int vRow = pv.getRowStride(), vPix = pv.getPixelStride();

        int x0 = w / 4, x1 = (w * 3) / 4;
        int y0 = h / 4, y1 = (h * 3) / 4;
        int step = Math.max(2, Math.min(w, h) / 60);

        double sumY = 0, sumU = 0, sumV = 0;
        int n = 0;
        for (int yy = y0; yy < y1; yy += step) {
            int cy = yy >> 1;
            for (int xx = x0; xx < x1; xx += step) {
                int cx = xx >> 1;
                int yi = yy * yRow + xx * yPix;
                int ui = cy * uRow + cx * uPix;
                int vi = cy * vRow + cx * vPix;
                if (yi < by.limit() && ui < bu.limit() && vi < bv.limit()) {
                    sumY += (by.get(yi) & 0xFF);
                    sumU += (bu.get(ui) & 0xFF);
                    sumV += (bv.get(vi) & 0xFF);
                    n++;
                }
            }
        }
        if (n == 0) return new FrameStats(0, 0, 0, false);
        double avgY = sumY / n;
        double avgU = sumU / n;
        double avgV = sumV / n;
        double avgR = avgY + 1.402 * (avgV - 128.0);
        double avgG = avgY - 0.344 * (avgU - 128.0) - 0.714 * (avgV - 128.0);
        boolean on = avgR > 110.0 && avgR > avgG * 1.35 && avgV > 135.0;
        return new FrameStats(avgR, avgG, avgV, on);
    }

    /** Dual-method bandpass + IBI median + normalized autocorrelation BPM engine. */
    public static PulseResult analyzeSignal(long[] ts, double[] raw, int n) {
        if (n < 90) {
            return new PulseResult(0, 0.0, false, "Hold steady longer");
        }
        // 1. Resample raw signal uniformly to 30 Hz first across real timestamps
        long durMs = ts[n - 1] - ts[0];
        if (durMs < 3500L) {
            return new PulseResult(0, 0.0, false, "Need more samples");
        }
        long startTs = ts[0] + 1000L;
        int sIdx = 0;
        while (sIdx < n - 30 && ts[sIdx] < startTs) sIdx++;

        double fs = 30.0;
        double gridMs = 1000.0 / fs;
        int m = (int) ((ts[n - 1] - ts[sIdx]) / gridMs);
        if (m < 75) {
            return new PulseResult(0, 0.0, false, "Signal too short");
        }
        double[] rawU = new double[m];
        int ptr = sIdx;
        for (int k = 0; k < m; k++) {
            double targetT = ts[sIdx] + k * gridMs;
            while (ptr + 1 < n - 1 && ts[ptr + 1] < targetT) ptr++;
            double t0 = ts[ptr], t1 = ts[Math.min(n - 1, ptr + 1)];
            double frac = (t1 > t0) ? Math.max(0.0, Math.min(1.0, (targetT - t0) / (t1 - t0))) : 0.0;
            rawU[k] = raw[ptr] + frac * (raw[Math.min(n - 1, ptr + 1)] - raw[ptr]);
        }

        // 2. Zero-phase 2-pass centered moving-average detrend (crushes <0.3Hz tremor/breathing drift
        //    while preserving full fundamental down to 42 BPM) + light 3-tap smoother
        double[] d1 = detrendCentered(rawU, 18); // 37 samples = 1.23s window
        double[] d2 = detrendCentered(d1, 15);    // 2nd pass kills slow drift -50 dB
        double[] u = new double[m];
        double mean = 0;
        for (int k = 0; k < m; k++) {
            double prev = d2[Math.max(0, k - 1)];
            double cur = d2[k];
            double nxt = d2[Math.min(m - 1, k + 1)];
            u[k] = 0.25 * prev + 0.5 * cur + 0.25 * nxt;
            mean += u[k];
        }
        mean /= m;
        double energy = 0;
        for (int k = 0; k < m; k++) {
            u[k] -= mean;
            energy += u[k] * u[k];
        }
        double rms = Math.sqrt(energy / m);
        if (rms < 1e-4) {
            return new PulseResult(0, 0.0, false, "Flat signal — touch lightly over camera + flash");
        }

        // 3. Normalized autocorrelation over lags 8..44; pick true local peaks only (42..200 BPM)
        int minLag = 9;
        int maxLag = Math.min(43, m / 2 - 2);
        double[] ac = new double[maxLag + 2];
        for (int lag = minLag - 1; lag <= maxLag + 1; lag++) {
            double num = 0, e1 = 0, e2 = 0;
            int cnt = m - lag;
            for (int k = 0; k < cnt; k++) {
                double a = u[k], b = u[k + lag];
                num += a * b;
                e1 += a * a;
                e2 += b * b;
            }
            ac[lag] = (e1 > 0 && e2 > 0) ? num / Math.sqrt(e1 * e2) : 0.0;
        }
        double rMax = -1.0;
        int globalBestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > rMax) {
                rMax = ac[lag];
                globalBestLag = lag;
            }
        }
        if (globalBestLag < 0 || rMax < 0.28) {
            return new PulseResult(0, Math.max(0.0, rMax), false,
                    "Weak pulse — cover flash + lens lightly & hold still");
        }

        // Sub-harmonic guard: choose shortest true local peak with >= 94% of rMax
        int bestLag = globalBestLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] >= 0.94 * rMax) {
                bestLag = lag;
                break;
            }
        }
        // Parabolic sub-sample refinement
        double exactLag = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double denom = 2.0 * (2.0 * ac[bestLag] - ac[bestLag - 1] - ac[bestLag + 1]);
            if (Math.abs(denom) > 1e-6) {
                exactLag += (ac[bestLag + 1] - ac[bestLag - 1]) / denom;
            }
        }
        double bpmAuto = (60.0 * fs) / exactLag;

        // 4. Time-domain IBI peak verification + sub-sample interpolation for HRV (RMSSD ms)
        double thresh = 0.28 * rms;
        List<Double> peakTimes = new ArrayList<Double>();
        double lastPeakT = -1000.0;
        for (int k = 1; k < m - 1; k++) {
            if (u[k] > thresh && u[k] > u[k - 1] && u[k] >= u[k + 1]) {
                double denom = u[k - 1] - 2.0 * u[k] + u[k + 1];
                double shift = Math.abs(denom) > 1e-6 ? 0.5 * (u[k - 1] - u[k + 1]) / denom : 0.0;
                shift = Math.max(-0.5, Math.min(0.5, shift));
                double pt = (k + shift) * gridMs;
                if (pt - lastPeakT >= 270.0) { // <= 222 BPM
                    peakTimes.add(pt);
                    lastPeakT = pt;
                }
            }
        }
        double bpmPeaks = 0.0;
        double hrvMs = 0.0;
        if (peakTimes.size() >= 4) {
            double[] ibi = new double[peakTimes.size() - 1];
            for (int i = 0; i < ibi.length; i++) ibi[i] = peakTimes.get(i + 1) - peakTimes.get(i);
            double[] sorted = ibi.clone();
            Arrays.sort(sorted);
            double med = sorted[sorted.length / 2];
            double sumClean = 0;
            int cleanN = 0;
            boolean[] validIbi = new boolean[ibi.length];
            for (int i = 0; i < ibi.length; i++) {
                double v = ibi[i];
                if (v >= 0.75 * med && v <= 1.25 * med) {
                    sumClean += v;
                    cleanN++;
                    validIbi[i] = true;
                }
            }
            if (cleanN >= 3) {
                bpmPeaks = 60000.0 / (sumClean / cleanN);
            }
            double sumSqDiff = 0;
            int pairN = 0;
            for (int i = 0; i < ibi.length - 1; i++) {
                if (validIbi[i] && validIbi[i + 1]) {
                    double dIbi = ibi[i + 1] - ibi[i];
                    if (Math.abs(dIbi) <= 220.0) {
                        sumSqDiff += dIbi * dIbi;
                        pairN++;
                    }
                }
            }
            if (pairN >= 6) {
                hrvMs = Math.max(5.0, Math.min(180.0, Math.sqrt(sumSqDiff / pairN)));
            }
        }

        double finalBpm = bpmAuto;
        double conf = rMax;
        if (bpmPeaks >= 40 && bpmPeaks <= 210) {
            double diff = Math.abs(bpmAuto - bpmPeaks);
            if (diff <= 10.0) {
                finalBpm = 0.65 * bpmAuto + 0.35 * bpmPeaks;
                conf = Math.min(1.0, rMax + 0.12);
            } else if (Math.abs(bpmPeaks - 2.0 * bpmAuto) <= 12.0 && bpmPeaks <= 200.0) {
                finalBpm = bpmPeaks;
            }
        }

        int rounded = (int) Math.round(Math.max(40.0, Math.min(210.0, finalBpm)));
        boolean locked = conf >= 0.42;
        if (!locked) hrvMs = 0.0;
        String note = locked
                ? "Signal locked (" + Math.round(conf * 100) + "% match)"
                : "Noisy wave — touch gently without pressing hard";
        return new PulseResult(rounded, conf, locked, note, hrvMs);
    }

    private static double[] detrendCentered(double[] x, int halfWin) {
        int n = x.length;
        double[] pref = new double[n + 1];
        for (int i = 0; i < n; i++) pref[i + 1] = pref[i] + x[i];
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - halfWin);
            int hi = Math.min(n - 1, i + halfWin);
            double avg = (pref[hi + 1] - pref[lo]) / (hi - lo + 1);
            out[i] = x[i] - avg;
        }
        return out;
    }

    // ================= PULSE LOG PERSISTENCE =================

    public static List<PulseEntry> loadLog(SharedPreferences prefs) {
        List<PulseEntry> out = new ArrayList<PulseEntry>();
        try {
            JSONArray a = new JSONArray(prefs.getString("pulse_log", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new PulseEntry(
                        o.getLong("ts"),
                        o.optString("d", ""),
                        o.getInt("bpm"),
                        o.optString("tag", "CHECK"),
                        o.optDouble("hrv", 0.0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveReading(SharedPreferences prefs, int bpm, String tag) {
        saveReading(prefs, bpm, tag, 0.0);
    }

    public static void saveReading(SharedPreferences prefs, int bpm, String tag, double hrvMs) {
        try {
            JSONArray a = new JSONArray(prefs.getString("pulse_log", "[]"));
            JSONObject o = new JSONObject();
            long now = System.currentTimeMillis();
            o.put("ts", now);
            o.put("d", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)));
            o.put("bpm", bpm);
            o.put("tag", tag);
            if (hrvMs >= 5.0) o.put("hrv", Math.round(hrvMs * 10.0) / 10.0);
            JSONArray next = new JSONArray();
            next.put(o); // newest first
            for (int i = 0; i < a.length() && next.length() < 100; i++) {
                next.put(a.get(i));
            }
            prefs.edit().putString("pulse_log", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void deleteReading(SharedPreferences prefs, long ts) {
        try {
            JSONArray a = new JSONArray(prefs.getString("pulse_log", "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("ts", -1) != ts) next.put(o);
            }
            prefs.edit().putString("pulse_log", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void mergeLog(SharedPreferences prefs, JSONArray imported) {
        if (imported == null || imported.length() == 0) return;
        try {
            JSONArray cur = new JSONArray(prefs.getString("pulse_log", "[]"));
            HashSet<Long> seen = new HashSet<Long>();
            for (int i = 0; i < cur.length(); i++) {
                seen.add(cur.getJSONObject(i).optLong("ts", 0L));
            }
            for (int i = 0; i < imported.length() && cur.length() < 120; i++) {
                JSONObject o = imported.getJSONObject(i);
                long ts = o.optLong("ts", 0L);
                if (ts > 0 && !seen.contains(ts) && o.optInt("bpm", 0) > 30) {
                    cur.put(o);
                    seen.add(ts);
                }
            }
            prefs.edit().putString("pulse_log", cur.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Returns median/average resting BPM from log (tags RESTING/HRV or calm readings < 100), else 0. */
    public static int restingBpm(SharedPreferences prefs) {
        List<PulseEntry> list = loadLog(prefs);
        int sumRest = 0, nRest = 0;
        int sumCalm = 0, nCalm = 0;
        for (int i = 0; i < list.size() && i < 30; i++) {
            PulseEntry e = list.get(i);
            if (("RESTING".equals(e.tag) || "HRV".equals(e.tag)) && e.bpm >= 38 && e.bpm <= 115) {
                sumRest += e.bpm;
                nRest++;
            } else if (!"POST-RUN".equals(e.tag) && e.bpm >= 40 && e.bpm <= 100) {
                sumCalm += e.bpm;
                nCalm++;
            }
        }
        if (nRest > 0) return Math.round((float) sumRest / nRest);
        if (nCalm > 0) return Math.round((float) sumCalm / nCalm);
        return 0;
    }

    /** Average morning/resting HRV (RMSSD ms) across calm readings with valid HRV, else 0. */
    public static double baselineHrv(SharedPreferences prefs) {
        List<PulseEntry> list = loadLog(prefs);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < list.size() && i < 20; i++) {
            PulseEntry e = list.get(i);
            if (!"POST-RUN".equals(e.tag) && e.hrvMs >= 5.0) {
                sum += e.hrvMs;
                n++;
            }
        }
        return n > 0 ? sum / n : 0.0;
    }

    /** Latest valid calm HRV reading for date d, or 0. */
    public static double latestHrvForDate(SharedPreferences prefs, String d) {
        for (PulseEntry e : loadLog(prefs)) {
            if (d.equals(e.d) && !"POST-RUN".equals(e.tag) && e.hrvMs >= 5.0) {
                return e.hrvMs;
            }
        }
        return 0.0;
    }
}
