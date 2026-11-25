package com.carvo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CroSiteService extends Service implements LocationListener, SensorEventListener {

    public static final String ACT_START = "com.carvo.app.CROSITE_START";
    public static final String ACT_WARMUP = "com.carvo.app.CROSITE_WARMUP";
    public static final String ACT_PAUSE = "com.carvo.app.CROSITE_PAUSE";
    public static final String ACT_RESUME = "com.carvo.app.CROSITE_RESUME";
    public static final String ACT_STOP = "com.carvo.app.CROSITE_STOP";
    public static final String ACT_SYNC_COACH = "com.carvo.app.CROSITE_SYNC";

    private static final String CHANNEL_ID = "crosite_run";
    private static final int NOTIF_ID = 4040;

    private static final int T_RUN = 0, T_TARGET = 1;
    private static final int TS_IDLE = 0, TS_RUN = 1, TS_PAUSE = 2, TS_DONE = 3,
            TS_REST = 4, TS_WARMUP = 5, TS_COOLDOWN = 6;

    public static class WarmMove {
        public String name;
        public int sec;
        public WarmMove(String n, int s) { name = n; sec = s; }
    }

    public static List<WarmMove> loadWarmupMoves(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("carvo", Context.MODE_PRIVATE);
        String raw = p.getString("coach_warmup_list", "");
        List<WarmMove> out = new ArrayList<WarmMove>();
        if (raw.length() > 2) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String n = o.optString("n", "").trim();
                    int s = Math.max(5, Math.min(300, o.optInt("s", 20)));
                    if (n.length() > 0) out.add(new WarmMove(n, s));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            out.add(new WarmMove("Jumping Jacks", 20));
            out.add(new WarmMove("Ankle Rolls", 30));
            out.add(new WarmMove("Leg Swings", 30));
        }
        return out;
    }

    public static void saveWarmupMoves(Context ctx, List<WarmMove> list) {
        JSONArray a = new JSONArray();
        try {
            for (WarmMove m : list) {
                JSONObject o = new JSONObject();
                o.put("n", m.name);
                o.put("s", m.sec);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        ctx.getSharedPreferences("carvo", Context.MODE_PRIVATE)
                .edit().putString("coach_warmup_list", a.toString()).apply();
    }

    private CroSiteEngine engine;
    private LocationManager lm;
    private SensorManager sm;
    private PowerManager.WakeLock wl;
    private NotificationManager nm;
    private AudioManager am;
    private Handler hand;
    private boolean listenersOn = false;
    private boolean wakeHeld = false;
    private boolean isFg = false;
    private int tickCount = 0;

    // Offline Voice Coach (TTS) & Coach Milestones
    private SharedPreferences cp;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsInitPending = false;
    private AudioFocusRequest focusReq;
    private boolean holdDuck = false;
    private final List<String> pendingInitSpeaks = new ArrayList<String>();
    private int lastStateSeen = 0;
    private boolean restTenWarned = false;
    private int nextKmMark = 1;
    private long lastKmElMs = 0L;

    // Warm-up & Slow-down state
    private List<WarmMove> wuMoves = new ArrayList<WarmMove>();
    private int wuPreStep = 5;    // 5..1 before first move
    private int wuLaunchStep = 6; // 1.3s after last "<Move> done" -> 5..1 -> Go!
    private final Runnable wuTick = new Runnable() {
        @Override
        public void run() {
            if (hand == null) return;
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            if (tp.getInt("state", 0) != TS_WARMUP) return;
            int phase = tp.getInt("wuPhase", 0);
            long now = System.currentTimeMillis();
            if (phase == 0) {
                wuPreStep--;
                if (wuPreStep >= 1) {
                    if (!holdDuck) {
                        holdDuck = true;
                        requestDuckFocus();
                    }
                    speak(String.valueOf(wuPreStep), false);
                    updateNotif();
                    hand.postDelayed(this, 1000L);
                } else {
                    WarmMove first = wuMoves.get(0);
                    tp.edit().putInt("wuPhase", 1)
                            .putInt("wuIdx", 0)
                            .putLong("wuEndMs", now + first.sec * 1000L).apply();
                    speak(first.name + ", " + first.sec + " seconds.", true);
                    updateNotif();
                    hand.postDelayed(this, 250L);
                }
            } else if (phase == 1) {
                long endMs = tp.getLong("wuEndMs", 0L);
                if (now >= endMs) {
                    int curIdx = tp.getInt("wuIdx", 0);
                    WarmMove finished = curIdx < wuMoves.size() ? wuMoves.get(curIdx) : null;
                    String donePhrase = finished != null ? finished.name + " done." : "";
                    int nextIdx = curIdx + 1;
                    if (nextIdx < wuMoves.size()) {
                        WarmMove nxt = wuMoves.get(nextIdx);
                        tp.edit().putInt("wuIdx", nextIdx)
                                .putLong("wuEndMs", now + nxt.sec * 1000L).apply();
                        holdDuck = true;
                        requestDuckFocus();
                        speak((donePhrase.length() > 0 ? donePhrase + " " : "")
                                + "Next, " + nxt.name + ", " + nxt.sec + " seconds.", true);
                        updateNotif();
                        hand.postDelayed(this, 250L);
                    } else {
                        wuLaunchStep = 6;
                        tp.edit().putInt("wuPhase", 2)
                                .putLong("wuEndMs", now + 6300L).apply();
                        holdDuck = true;
                        requestDuckFocus();
                        if (donePhrase.length() > 0) speak(donePhrase, false);
                        updateNotif();
                        hand.postDelayed(this, 1300L);
                    }
                } else {
                    hand.postDelayed(this, 250L);
                }
            } else {
                wuLaunchStep--;
                if (wuLaunchStep >= 1) {
                    if (!holdDuck) {
                        holdDuck = true;
                        requestDuckFocus();
                    }
                    speak(String.valueOf(wuLaunchStep), false);
                    updateNotif();
                    hand.postDelayed(this, 1000L);
                } else {
                    speak("Go!", true);
                    tp.edit().putInt("state", TS_RUN)
                            .putLong("accMs", 0L)
                            .putLong("runSince", now).apply();
                    beginRunCore();
                    updateNotif();
                }
            }
        }
    };

    // Ultra-lightweight PCM Cadence Metronome
    private boolean metroOn = false;
    private int metroSpm = 172;
    private short[] clickPcm;
    private AudioTrack clickTrack;
    private final Runnable metroTick = new Runnable() {
        @Override
        public void run() {
            if (!metroOn || hand == null) return;
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            if (tp.getInt("state", 0) != TS_RUN) {
                metroOn = false;
                return;
            }
            playMetronomeClick();
            long interval = Math.max(280L, Math.min(600L, Math.round(60000.0 / Math.max(120, metroSpm))));
            hand.postDelayed(this, interval);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (engine == null) return;
            checkPocketTimerTransitions();
            checkCoachAnnouncements();
            tickCount++;
            if (CroSiteEngine.isEnabled(CroSiteService.this)) engine.save();
            if (tickCount % 2 == 0) {
                updateNotif();
            }
            if (hand != null) hand.postDelayed(this, 1000L);
        }
    };

    public static void dispatch(Context ctx, String action) {
        try {
            Intent i = new Intent(ctx, CroSiteService.class);
            i.setAction(action);
            if ((ACT_START.equals(action) || ACT_WARMUP.equals(action)) && Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else if (ACT_STOP.equals(action)) {
                ctx.stopService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cp = getSharedPreferences("carvo", MODE_PRIVATE);
        engine = new CroSiteEngine(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Corvo:CroSite");
            wl.setReferenceCounted(false);
        }
        hand = new Handler(Looper.getMainLooper());
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        String act = intent != null ? intent.getAction() : null;
        if (ACT_STOP.equals(act)) {
            teardown();
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACT_WARMUP.equals(act)) {
            hand.removeCallbacks(wuTick);
            pendingInitSpeaks.clear();
            wuMoves = loadWarmupMoves(this);
            ensureTts();
            acquireWake();
            setSensors(false);
            lastStateSeen = TS_WARMUP;
            wuPreStep = 5;
            holdDuck = true;
            requestDuckFocus();
            speak("5", false);
            updateNotif();
            hand.postDelayed(wuTick, 1000L);
        } else if (ACT_START.equals(act)) {
            hand.removeCallbacks(wuTick);
            pendingInitSpeaks.clear();
            holdDuck = false;
            if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
            releaseFocus();
            beginRunCore();
            updateNotif();
        } else if (ACT_PAUSE.equals(act)) {
            engine.setPaused(true);
            setSensors(false);
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            int ts = tp.getInt("state", TS_PAUSE);
            if (ts == TS_REST && lastStateSeen != TS_REST) {
                restTenWarned = false;
                speak("Lap saved. Walk sixty seconds.", false);
            }
            lastStateSeen = ts;
            syncCoachState(ts);
            updateNotif();
        } else if (ACT_RESUME.equals(act)) {
            engine.setPaused(false);
            setSensors(CroSiteEngine.isEnabled(this));
            if (lastStateSeen == TS_REST) speak("Go.", false);
            lastStateSeen = TS_RUN;
            syncCoachState(TS_RUN);
            updateNotif();
        } else if (ACT_SYNC_COACH.equals(act)) {
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            syncCoachState(tp.getInt("state", 0));
            updateNotif();
        } else {
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            int ts = tp.getInt("state", 0);
            if (ts == 0) {
                teardown();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ts == TS_WARMUP) {
                wuMoves = loadWarmupMoves(this);
                ensureTts();
                acquireWake();
                lastStateSeen = TS_WARMUP;
                int ph = tp.getInt("wuPhase", 0);
                int leftS = Math.max(1, Math.min(5, (int) Math.ceil(
                        (tp.getLong("wuEndMs", System.currentTimeMillis()) - System.currentTimeMillis()) / 1000.0)));
                if (ph == 0) wuPreStep = leftS;
                else if (ph == 2) wuLaunchStep = leftS;
                hand.removeCallbacks(wuTick);
                hand.postDelayed(wuTick, ph == 1 ? 250L : 1000L);
            } else {
                engine.load();
                loadCoachMarkers();
                boolean shouldRun = (ts == TS_RUN);
                lastStateSeen = ts;
                engine.setPaused(!shouldRun);
                setSensors(shouldRun && CroSiteEngine.isEnabled(this));
                syncCoachState(ts);
            }
            updateNotif();
        }
        hand.removeCallbacks(tick);
        hand.postDelayed(tick, 1000L);
        return START_STICKY;
    }

    private void beginRunCore() {
        if (CroSiteEngine.isEnabled(this)) {
            engine.beginRun();
            engine.setPaused(false);
            setSensors(true);
        } else {
            CroSiteEngine.resetAll(this);
            setSensors(false);
            acquireWake();
        }
        nextKmMark = 1;
        lastKmElMs = 0L;
        lastStateSeen = TS_RUN;
        restTenWarned = false;
        saveCoachMarkers();
        syncCoachState(TS_RUN);
    }

    private long elapsedRunMs() {
        SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        int ts = tp.getInt("state", 0);
        long acc = tp.getLong("accMs", 0L);
        long since = tp.getLong("runSince", 0L);
        return acc + (ts == TS_RUN && since > 0 ? Math.max(0L, System.currentTimeMillis() - since) : 0L);
    }

    private void checkPocketTimerTransitions() {
        SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        int ts = tp.getInt("state", 0);
        int mode = tp.getInt("mode", T_RUN);
        int targetSub = tp.getInt("targetSub", 0); // 0=TIME, 1=DISTANCE
        long now = System.currentTimeMillis();

        if (ts == TS_REST) {
            long restUntil = tp.getLong("restUntil", 0L);
            if (restUntil > 0L && now >= restUntil) {
                tp.edit().putInt("state", TS_RUN).putLong("runSince", restUntil).apply();
                if (CroSiteEngine.isEnabled(this)) {
                    engine.setPaused(false);
                    setSensors(true);
                }
                lastStateSeen = TS_RUN;
                syncCoachState(TS_RUN);
                speak("Go.", false);
                updateNotif();
            } else {
                if (!engine.isPaused()) {
                    engine.setPaused(true);
                    setSensors(false);
                }
                if (restUntil > 0L && !restTenWarned && (restUntil - now) <= 10500L && (restUntil - now) > 1000L) {
                    restTenWarned = true;
                    speak("Ten seconds.", false);
                }
            }
        } else if (ts == TS_RUN && mode == T_TARGET && targetSub == 0) {
            int tMin = tp.getInt("timedMin", 30);
            long el = elapsedRunMs();
            long totalMs = tMin * 60000L;
            if (el >= totalMs) {
                tp.edit().putInt("state", TS_DONE).putLong("accMs", totalMs).putLong("runSince", 0L).apply();
                engine.setPaused(true);
                setSensors(false);
                lastStateSeen = TS_DONE;
                syncCoachState(TS_DONE);
                speak("Target complete.", false);
                updateNotif();
            }
        } else if (ts == TS_RUN && mode == T_TARGET && targetSub == 1 && CroSiteEngine.isEnabled(this)) {
            double goalKm = readTargetDistKm(tp);
            if (engine.getDistKm() >= goalKm) {
                finishTargetDistance(tp, goalKm, now);
            }
        }
    }

    private void finishTargetDistance(SharedPreferences tp, final double goalKm, long now) {
        long finalEl = Math.max(3000L, elapsedRunMs());
        double finalMin = finalEl / 60000.0;
        int avgCad = engine.getAvgCadenceSpm();
        double[] ob = CroSiteEngine.readOutBack(this, finalMin);

        engine.setPaused(true);
        setSensors(false);
        metroOn = false;
        hand.removeCallbacks(metroTick);

        // Auto-save exact Target KM & exact Run Time immediately
        RunStore rs = new RunStore(this);
        String td = RunStore.today();
        rs.upsert(td, goalKm, finalMin);
        if (avgCad > 0 || ob[0] > 0) rs.setCroPro(td, avgCad, ob[0], ob[1]);
        CorvoWidget.refresh(this);

        long sec = finalEl / 1000L;
        String tStr = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
        String msg = String.format(Locale.US, "Saved %.2f km target in %s \u2713", goalKm, tStr);

        boolean ttsOn = cp != null && cp.getBoolean("coach_tts", false);
        long coolMs = ttsOn ? 5800L : 2000L;
        long resetMs = ttsOn ? 7400L : 2500L;

        lastStateSeen = TS_COOLDOWN;
        tp.edit().putInt("state", TS_COOLDOWN)
                .putLong("accMs", finalEl)
                .putLong("runSince", 0L)
                .putLong("coolUntil", now + coolMs)
                .putLong("autoSavedAt", now)
                .putString("autoSavedMsg", msg)
                .apply();
        updateNotif();

        // Smooth single music duck across slow-down: "Target saved. Sir, keep it slow. 3..2..1..Done!"
        if (ttsOn) {
            holdDuck = true;
            requestDuckFocus();
            speak("Target saved. Sir, keep it slow.", false);
            hand.postDelayed(new Runnable() { @Override public void run() { speak("3", false); } }, 2800L);
            hand.postDelayed(new Runnable() { @Override public void run() { speak("2", false); } }, 3800L);
            hand.postDelayed(new Runnable() { @Override public void run() { speak("1", false); } }, 4800L);
            hand.postDelayed(new Runnable() { @Override public void run() { speak("Done.", true); } }, 5800L);
        }

        hand.postDelayed(new Runnable() {
            @Override
            public void run() {
                SharedPreferences tp2 = getSharedPreferences("carvo_timer", MODE_PRIVATE);
                if (tp2.getInt("state", 0) == TS_COOLDOWN) {
                    tp2.edit().putInt("state", TS_IDLE)
                            .putLong("accMs", 0L)
                            .putLong("runSince", 0L)
                            .putLong("restUntil", 0L)
                            .putString("laps", "[]").apply();
                    teardown();
                    stopSelf();
                }
            }
        }, resetMs);
    }

    public static double readTargetDistKm(SharedPreferences tp) {
        if (!tp.contains("targetDistBits")) return 3.0;
        try {
            double v = Double.longBitsToDouble(tp.getLong("targetDistBits", Double.doubleToRawLongBits(3.0)));
            return (v >= 0.2 && v <= 100.0) ? v : 3.0;
        } catch (Exception e) {
            return 3.0;
        }
    }

    private void checkCoachAnnouncements() {
        if (engine == null || cp == null || !cp.getBoolean("coach_tts", false)) return;
        SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        if (tp.getInt("state", 0) != TS_RUN || !CroSiteEngine.isEnabled(this)) return;

        if (engine.consumeTurnaroundSpeak()) {
            speak("Turnaround detected. Heading back.", false);
        }
        double km = engine.getDistKm();
        int mode = tp.getInt("mode", T_RUN);
        int targetSub = tp.getInt("targetSub", 0);
        double goalKm = readTargetDistKm(tp);
        if (mode == T_TARGET && targetSub == 1 && (goalKm - nextKmMark < 0.15 || km >= goalKm - 0.02)) return;

        if (km >= nextKmMark) {
            long el = elapsedRunMs();
            long splitMs = Math.max(10000L, el - lastKmElMs);
            int splitSec = (int) Math.round(splitMs / 1000.0);
            int pm = splitSec / 60;
            int ps = splitSec % 60;
            int spm = engine.getLiveSpm(System.currentTimeMillis());
            StringBuilder sb = new StringBuilder();
            sb.append("Kilometer ").append(nextKmMark).append(". ");
            sb.append("Pace ").append(pm).append(" ").append(ps == 0 ? "flat" : String.valueOf(ps)).append(". ");
            if (mode == T_TARGET && targetSub == 1) {
                double rem = Math.max(0.0, goalKm - nextKmMark);
                if (rem >= 0.2) {
                    sb.append(String.format(Locale.US, "%.1f kilometers left. ", rem));
                }
            }
            if (spm > 0) sb.append("Cadence ").append(spm).append(".");
            speak(sb.toString(), false);
            nextKmMark++;
            lastKmElMs = el;
            saveCoachMarkers();
        }
    }

    private void saveCoachMarkers() {
        getSharedPreferences(CroSiteEngine.PREFS, MODE_PRIVATE).edit()
                .putInt("nextKmMark", nextKmMark)
                .putLong("lastKmElMs", lastKmElMs).apply();
    }

    private void loadCoachMarkers() {
        SharedPreferences p = getSharedPreferences(CroSiteEngine.PREFS, MODE_PRIVATE);
        nextKmMark = Math.max(1, p.getInt("nextKmMark", 1));
        lastKmElMs = p.getLong("lastKmElMs", 0L);
    }

    // ================= OFFLINE TEXT-TO-SPEECH + METRONOME =================

    private void syncCoachState(int timerState) {
        if (cp == null) return;
        if (cp.getBoolean("coach_tts", false)) ensureTts();
        boolean wantMetro = (timerState == TS_RUN) && cp.getBoolean("coach_metro", false);
        metroSpm = cp.getInt("coach_metro_spm", 172);
        if (wantMetro && !metroOn) {
            metroOn = true;
            hand.removeCallbacks(metroTick);
            hand.post(metroTick);
        } else if (!wantMetro && metroOn) {
            metroOn = false;
            hand.removeCallbacks(metroTick);
        }
    }

    private void ensureTts() {
        if (ttsReady || ttsInitPending) return;
        ttsInitPending = true;
        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsInitPending = false;
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        int res = tts.setLanguage(Locale.US);
                        if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setSpeechRate(0.85f);
                            ttsReady = true;
                            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                                @Override public void onStart(String id) {}
                                @Override public void onDone(String id) {
                                    if (!holdDuck || (id != null && id.endsWith("_end"))) {
                                        holdDuck = false;
                                        releaseFocus();
                                    }
                                }
                                @Override public void onError(String id) {
                                    if (!holdDuck || (id != null && id.endsWith("_end"))) {
                                        holdDuck = false;
                                        releaseFocus();
                                    }
                                }
                            });
                            if (!pendingInitSpeaks.isEmpty()) {
                                List<String> copy = new ArrayList<String>(pendingInitSpeaks);
                                pendingInitSpeaks.clear();
                                for (String s : copy) {
                                    boolean end = s.endsWith("|END");
                                    speak(end ? s.substring(0, s.length() - 4) : s, end);
                                }
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            ttsInitPending = false;
        }
    }

    private void speak(String text, boolean endHold) {
        if (cp == null || !cp.getBoolean("coach_tts", false)) return;
        if (!ttsReady || tts == null) {
            if (pendingInitSpeaks.size() < 6) {
                pendingInitSpeaks.add(text + (endHold ? "|END" : ""));
            }
            ensureTts();
            return;
        }
        if (!holdDuck) requestDuckFocus();
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        String id = "corvo_" + System.currentTimeMillis() + (endHold ? "_end" : "");
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id);
    }

    private void requestDuckFocus() {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(aa)
                            .build();
                }
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseFocus() {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) {
                am.abandonAudioFocusRequest(focusReq);
            } else {
                am.abandonAudioFocus(null);
            }
        } catch (Exception ignored) {
        }
    }

    private void playMetronomeClick() {
        try {
            if (clickPcm == null) {
                int len = 144;
                clickPcm = new short[len];
                for (int i = 0; i < len; i++) {
                    double env = Math.exp(-i / 28.0);
                    clickPcm[i] = (short) (Math.sin(2.0 * Math.PI * 980.0 * i / 8000.0) * env * 14000);
                }
            }
            if (clickTrack == null) {
                int minBuf = Math.max(clickPcm.length * 2,
                        AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
                clickTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 8000,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        minBuf, AudioTrack.MODE_STATIC);
                clickTrack.write(clickPcm, 0, clickPcm.length);
            }
            if (clickTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) clickTrack.stop();
            clickTrack.reloadStaticData();
            clickTrack.play();
        } catch (Exception ignored) {
        }
    }

    // ================= SENSORS & NOTIFICATION =================

    private void acquireWake() {
        try {
            if (wl != null && !wl.isHeld()) {
                wl.acquire(4L * 3600L * 1000L);
                wakeHeld = true;
            }
        } catch (Exception ignored) {
        }
    }

    private void setSensors(boolean on) {
        if (on && !listenersOn) {
            acquireWake();
            try {
                if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
                }
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
            try {
                if (sm != null) {
                    Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
                }
            } catch (Exception ignored) {
            }
            listenersOn = true;
        } else if (!on && listenersOn) {
            try {
                if (lm != null) lm.removeUpdates(this);
            } catch (Exception ignored) {
            }
            try {
                if (sm != null) sm.unregisterListener(this);
            } catch (Exception ignored) {
            }
            try {
                if (wl != null && wl.isHeld() && wakeHeld) {
                    wl.release();
                    wakeHeld = false;
                }
            } catch (Exception ignored) {
            }
            listenersOn = false;
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CroSite Live Tracker", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Silent live GPS and stride tracking during active runs");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void ensureForeground() {
        if (isFg) return;
        isFg = true;
        startForeground(NOTIF_ID, buildNotif());
    }

    private void updateNotif() {
        if (nm != null && isFg) {
            nm.notify(NOTIF_ID, buildNotif());
        }
    }

    private Notification buildNotif() {
        SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        int ts = tp.getInt("state", 0);
        int mode = tp.getInt("mode", T_RUN);
        int targetSub = tp.getInt("targetSub", 0);
        long el = elapsedRunMs();
        long showMs = (mode == T_TARGET && targetSub == 0)
                ? Math.max(0L, tp.getInt("timedMin", 30) * 60000L - el) : el;
        long sec = showMs / 1000L;
        String timeStr = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        boolean croOn = CroSiteEngine.isEnabled(this);
        double km = engine != null && croOn ? engine.getDistKm() : 0.0;
        int spm = engine != null && croOn ? engine.getLiveSpm(System.currentTimeMillis()) : 0;

        String title;
        String sub;
        if (ts == TS_WARMUP) {
            int phase = tp.getInt("wuPhase", 0);
            int idx = tp.getInt("wuIdx", 0);
            long leftS = Math.max(0L, (long) Math.ceil((tp.getLong("wuEndMs", System.currentTimeMillis()) - System.currentTimeMillis()) / 1000.0));
            if (phase == 0) {
                title = "PRE-WARMUP \u00B7 " + Math.max(1, Math.min(5, (int) leftS));
                sub = "Starting warm-up...";
            } else if (phase == 2) {
                title = "GET READY \u00B7 " + Math.max(1, Math.min(5, (int) leftS));
                sub = "Starting run automatically...";
            } else {
                String mv = idx < wuMoves.size() ? wuMoves.get(idx).name.toUpperCase(Locale.US) : "WARM-UP";
                title = "WARM-UP \u00B7 " + mv + " (" + leftS + "s)";
                sub = "Voice Coach warm-up in progress";
            }
        } else if (ts == TS_COOLDOWN) {
            title = "TARGET SAVED \u00B7 KEEP IT SLOW";
            sub = "3... 2... 1... Well done!";
        } else if (ts == TS_REST) {
            long now = System.currentTimeMillis();
            long leftS = Math.max(0L, (tp.getLong("restUntil", now) - now) / 1000L);
            title = String.format(Locale.US, "REST %d:%02d \u00B7 %.2f KM", leftS / 60, leftS % 60, km);
            sub = "CroSite paused during lap rest break";
        } else if (ts == TS_PAUSE) {
            title = String.format(Locale.US, "PAUSED \u00B7 %.2f KM \u00B7 %s", km, timeStr);
            sub = "Tap to resume or save run";
        } else if (ts == TS_DONE) {
            title = String.format(Locale.US, "TARGET COMPLETE \u00B7 %.2f KM", km);
            sub = "Tap to save your run";
        } else if (mode == T_TARGET && targetSub == 1) {
            double rem = Math.max(0.0, readTargetDistKm(tp) - km);
            title = String.format(Locale.US, "%.2f / %.2f KM \u00B7 %s", km, readTargetDistKm(tp), timeStr);
            sub = String.format(Locale.US, "%.2f km left%s", rem, spm > 0 ? " \u00B7 " + spm + " SPM" : "");
        } else if (!croOn) {
            title = "CORVO TIMER \u00B7 " + timeStr;
            sub = "Voice Coach active";
        } else {
            title = String.format(Locale.US, "CROSITE \u00B7 %.2f KM \u00B7 %s", km, timeStr);
            String base = CroSiteEngine.isSatReady(this) ? "GPS locked" : "Acquiring satellites...";
            sub = spm > 0 ? base + " \u00B7 " + spm + " SPM" : base;
        }

        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("open_tab", 3);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(sub)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void teardown() {
        metroOn = false;
        holdDuck = false;
        if (hand != null) {
            hand.removeCallbacksAndMessages(null);
        }
        setSensors(false);
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
        wakeHeld = false;
        releaseFocus();
        if (clickTrack != null) {
            try { clickTrack.release(); } catch (Exception ignored) {}
            clickTrack = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
            ttsReady = false;
            ttsInitPending = false;
        }
        if (engine != null) engine.endRun();
        if (isFg) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            isFg = false;
        }
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null || engine == null || !CroSiteEngine.isEnabled(this)) return;
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
        engine.onGpsFix(loc.getLatitude(), loc.getLongitude(), acc, System.currentTimeMillis(), elapsedRunMs());
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e == null || e.sensor.getType() != Sensor.TYPE_ACCELEROMETER
                || engine == null || !CroSiteEngine.isEnabled(this)) return;
        engine.onAccel(e.values[0], e.values[1], e.values[2], System.currentTimeMillis());
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
