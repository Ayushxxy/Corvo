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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Locale;

public class CroSiteService extends Service implements LocationListener, SensorEventListener {

    public static final String ACT_START = "com.carvo.app.CROSITE_START";
    public static final String ACT_PAUSE = "com.carvo.app.CROSITE_PAUSE";
    public static final String ACT_RESUME = "com.carvo.app.CROSITE_RESUME";
    public static final String ACT_STOP = "com.carvo.app.CROSITE_STOP";

    private static final String CHANNEL_ID = "crosite_run";
    private static final int NOTIF_ID = 4040;

    private static final int T_TIMED = 1;
    private static final int TS_RUN = 1, TS_PAUSE = 2, TS_DONE = 3, TS_REST = 4;

    private CroSiteEngine engine;
    private LocationManager lm;
    private SensorManager sm;
    private PowerManager.WakeLock wl;
    private NotificationManager nm;
    private Handler hand;
    private boolean listenersOn = false;
    private boolean isFg = false;
    private int tickCount = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (engine == null) return;
            checkPocketTimerTransitions();
            tickCount++;
            if (tickCount % 2 == 0) {
                engine.save();
                updateNotif();
            }
            if (hand != null) hand.postDelayed(this, 1000L);
        }
    };

    public static void dispatch(Context ctx, String action) {
        try {
            Intent i = new Intent(ctx, CroSiteService.class);
            i.setAction(action);
            if (ACT_START.equals(action) && Build.VERSION.SDK_INT >= 26) {
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
        engine = new CroSiteEngine(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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
        } else if (ACT_START.equals(act)) {
            engine.beginRun();
            engine.setPaused(false);
            setSensors(true);
            updateNotif();
        } else if (ACT_PAUSE.equals(act)) {
            engine.setPaused(true);
            setSensors(false);
            updateNotif();
        } else if (ACT_RESUME.equals(act)) {
            engine.setPaused(false);
            setSensors(true);
            updateNotif();
        } else {
            // Sticky restart or sync
            SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
            int ts = tp.getInt("state", 0);
            if (ts == 0) {
                teardown();
                stopSelf();
                return START_NOT_STICKY;
            }
            engine.load();
            boolean shouldRun = (ts == TS_RUN);
            engine.setPaused(!shouldRun);
            setSensors(shouldRun);
            updateNotif();
        }
        hand.removeCallbacks(tick);
        hand.postDelayed(tick, 1000L);
        return START_STICKY;
    }

    /** Handles 60s LAP rest expiry and TIMED countdown finish even with screen off in pocket. */
    private void checkPocketTimerTransitions() {
        SharedPreferences tp = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        int ts = tp.getInt("state", 0);
        int mode = tp.getInt("mode", 0);
        long now = System.currentTimeMillis();

        if (ts == TS_REST) {
            long restUntil = tp.getLong("restUntil", 0L);
            if (restUntil > 0L && now >= restUntil) {
                tp.edit().putInt("state", TS_RUN).putLong("runSince", restUntil).apply();
                engine.setPaused(false);
                setSensors(true);
                updateNotif();
            } else if (!engine.isPaused()) {
                engine.setPaused(true);
                setSensors(false);
            }
        } else if (ts == TS_RUN && mode == T_TIMED) {
            int tMin = tp.getInt("timedMin", 30);
            long acc = tp.getLong("accMs", 0L);
            long since = tp.getLong("runSince", now);
            long totalMs = tMin * 60000L;
            if ((acc + Math.max(0L, now - since)) >= totalMs) {
                tp.edit().putInt("state", TS_DONE).putLong("accMs", totalMs).putLong("runSince", 0L).apply();
                engine.setPaused(true);
                setSensors(false);
                updateNotif();
            }
        }
    }

    private void setSensors(boolean on) {
        if (on && !listenersOn) {
            try {
                if (wl != null && !wl.isHeld()) wl.acquire(4L * 3600L * 1000L);
            } catch (Exception ignored) {
            }
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
                if (wl != null && wl.isHeld()) wl.release();
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
        int mode = tp.getInt("mode", 0);
        long acc = tp.getLong("accMs", 0L);
        long since = tp.getLong("runSince", 0L);
        long now = System.currentTimeMillis();
        long elapsed = acc + (ts == TS_RUN && since > 0 ? Math.max(0L, now - since) : 0L);
        long showMs = mode == T_TIMED ? Math.max(0L, tp.getInt("timedMin", 30) * 60000L - elapsed) : elapsed;
        long sec = showMs / 1000L;
        String timeStr = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        double km = engine != null ? engine.getDistKm() : 0.0;

        String title;
        String sub;
        if (ts == TS_REST) {
            long leftS = Math.max(0L, (tp.getLong("restUntil", now) - now) / 1000L);
            title = String.format(Locale.US, "REST %d:%02d \u00B7 %.2f KM", leftS / 60, leftS % 60, km);
            sub = "CroSite paused during lap rest break";
        } else if (ts == TS_PAUSE) {
            title = String.format(Locale.US, "PAUSED \u00B7 %.2f KM \u00B7 %s", km, timeStr);
            sub = "Tap to resume or save run";
        } else if (ts == TS_DONE) {
            title = String.format(Locale.US, "TIME'S UP \u00B7 %.2f KM", km);
            sub = "Tap to save your run";
        } else {
            title = String.format(Locale.US, "CROSITE \u00B7 %.2f KM \u00B7 %s", km, timeStr);
            sub = CroSiteEngine.isSatReady(this) ? "GPS locked" : "Acquiring satellites...";
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
        if (hand != null) hand.removeCallbacks(tick);
        setSensors(false);
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
        if (loc == null || engine == null) return;
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
        engine.onGpsFix(loc.getLatitude(), loc.getLongitude(), acc, System.currentTimeMillis());
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e == null || e.sensor.getType() != Sensor.TYPE_ACCELEROMETER || engine == null) return;
        engine.onAccel(e.values[0], e.values[1], e.values[2], System.currentTimeMillis());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
