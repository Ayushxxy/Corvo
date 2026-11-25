package com.carvo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ToolsUI {

    public interface Host {
        void onProfileChanged();
        boolean hasCameraPerm();
        void requestCameraPerm();
    }

    private static final int TOOL_NONE = -1;
    private static final int TOOL_PULSE = 0;
    private static final int TOOL_STRENGTH = 1;
    private static final int TOOL_BMI = 2;
    private static final int TOOL_BF = 3;
    private static final int TOOL_PACE = 4;
    private static final int TOOL_WATER = 5;
    private static final int TOOL_VO2 = 6;
    private static final int TOOL_BOLT = 7;

    private final Activity act;
    private final RunStore store;
    private final SharedPreferences prefs;
    private final Host host;
    private final LinearLayout container;

    private static final String[] TOOL_TITLES = {
            "CAMERA PULSE", "STRENGTH LEVEL", "BMI", "BODY FAT %",
            "PACE & SPLIT", "HYDRATION", "VO2 MAX & ZONES", "BOLT BREATH"
    };
    private static final String[] TOOL_SHORT = {
            "PULSE", "STRENGTH", "BMI", "BODY FAT",
            "PACE", "HYDRATION", "VO2 MAX", "BOLT"
    };

    private int activeTool = TOOL_NONE;

    // Camera Pulse PPG + HRV + HRR-60 state (v5.0)
    private PulseCameraController pulseCam;
    private PulseWaveView pulseWave;
    private TextureView pulseTex;
    private TextView pulseBpmTv, pulseStatusTv;
    private View pulseProgFill;
    private Button pulseBtn;
    private LinearLayout pulseLogBox;
    private String pulseTag = "RESTING";
    private int lastMeasuredBpm = 0;
    private double lastMeasuredHrv = 0.0;
    private int lastHrrDrop = 0;
    private String lastMeasureNote = "TAP START & COVER REAR CAMERA + FLASH LIGHTLY";
    private boolean pendingCamStart = false;
    private int hrrPhase = 0; // 0=idle, 1=peak HR0 (12s), 2=36s rest wait, 3=recovery HR60 (12s)
    private int hrrPeakBpm = 0;
    private long hrrWaitUntil = 0L;
    private final Runnable hrrTick = new Runnable() {
        @Override
        public void run() {
            if (hrrPhase != 2) return;
            long leftMs = hrrWaitUntil - System.currentTimeMillis();
            if (leftMs <= 0L) {
                hrrPhase = 3;
                if (pulseStatusTv != null) {
                    pulseStatusTv.setText("PLACE FINGER LIGHTLY \u00B7 MEASURING HR-60...");
                    pulseStatusTv.setTextColor(0xFF00E5A0);
                }
                startPulseCamInternal(12000L);
                return;
            }
            int s = (int) Math.ceil(leftMs / 1000.0);
            if (pulseStatusTv != null) {
                pulseStatusTv.setText("BREATHE CALMLY \u00B7 PEAK " + hrrPeakBpm + " BPM \u00B7 SCAN 2 IN " + s + "s");
                pulseStatusTv.setTextColor(0xFF00B4D8);
            }
            if (pulseProgFill != null && pulseProgFill.getParent() instanceof View) {
                int pw = ((View) pulseProgFill.getParent()).getWidth();
                if (pw > 0) {
                    pulseProgFill.getLayoutParams().width = Math.round(pw * Math.max(0f, Math.min(1f, (36000L - leftMs) / 36000f)));
                    pulseProgFill.requestLayout();
                }
            }
            hand.postDelayed(this, 250L);
        }
    };

    // Live Plank timer inside Strength Level Part B
    private boolean plankRunning = false;
    private long plankStartMs = 0L;
    private TextView plankTimeTv;
    private Button plankBtn;
    // BOLT Breath Lab state (v5.0)
    private boolean boltRunning = false;
    private long boltStartMs = 0L;
    private TextView boltTimeTv;
    private Button boltBtn;
    private boolean paceBreathRunning = false;
    private long paceBreathStartMs = 0L;
    private View paceBreathBar;
    private int paceBreathBarMaxW = 0;
    private TextView paceBreathPhaseTv;
    private Button paceBreathBtn;

    private final Handler hand = new Handler(Looper.getMainLooper());
    private final Runnable plankTick = new Runnable() {
        @Override
        public void run() {
            if (!plankRunning || plankTimeTv == null) return;
            long sec = Math.max(0L, (System.currentTimeMillis() - plankStartMs) / 1000L);
            plankTimeTv.setText(fmtClockSec((int) sec));
            hand.postDelayed(this, 250L);
        }
    };
    private final Runnable boltTick = new Runnable() {
        @Override
        public void run() {
            if (boltRunning && boltTimeTv != null) {
                double sec = Math.max(0.0, (System.currentTimeMillis() - boltStartMs) / 1000.0);
                boltTimeTv.setText(String.format(Locale.US, "%.1f s", sec));
            }
            if (paceBreathRunning && paceBreathPhaseTv != null && paceBreathBar != null) {
                long el = (System.currentTimeMillis() - paceBreathStartMs) % 10000L;
                float frac;
                if (el < 4000L) {
                    frac = el / 4000f;
                    paceBreathPhaseTv.setText("INHALE SLOWLY · " + ((4000L - el) / 1000L + 1) + "s");
                    paceBreathPhaseTv.setTextColor(0xFF00E5A0);
                } else {
                    frac = 1f - ((el - 4000L) / 6000f);
                    paceBreathPhaseTv.setText("EXHALE RELAXED · " + ((10000L - el) / 1000L + 1) + "s");
                    paceBreathPhaseTv.setTextColor(0xFF00B4D8);
                }
                if (paceBreathBarMaxW <= 0 && paceBreathBar.getParent() instanceof View) {
                    paceBreathBarMaxW = ((View) paceBreathBar.getParent()).getWidth();
                }
                if (paceBreathBarMaxW > 0) {
                    android.view.ViewGroup.LayoutParams lp = paceBreathBar.getLayoutParams();
                    lp.width = Math.max(dp(6), Math.round(paceBreathBarMaxW * Math.max(0.03f, Math.min(1f, frac))));
                    paceBreathBar.setLayoutParams(lp);
                }
            }
            if (boltRunning || paceBreathRunning) hand.postDelayed(this, 60L);
        }
    };

    public ToolsUI(Activity act, RunStore store, SharedPreferences prefs, LinearLayout container, Host host) {
        this.act = act;
        this.store = store;
        this.prefs = prefs;
        this.container = container;
        this.host = host;
    }

    public boolean isDetailOpen() {
        return activeTool != TOOL_NONE;
    }

    public boolean handleBack() {
        if (activeTool != TOOL_NONE) {
            stopPlankIfRunning(false);
            stopBoltIfRunning();
            stopPulseCam();
            activeTool = TOOL_NONE;
            render();
            return true;
        }
        return false;
    }

    public void onTabHidden() {
        stopPlankIfRunning(false);
        stopBoltIfRunning();
        stopPulseCam();
    }

    private void stopBoltIfRunning() {
        boltRunning = false;
        paceBreathRunning = false;
        hand.removeCallbacks(boltTick);
    }

    public void openPulsePostRun() {
        stopPlankIfRunning(false);
        stopBoltIfRunning();
        stopPulseCam();
        activeTool = TOOL_PULSE;
        pulseTag = "POST-RUN";
        lastMeasuredBpm = 0;
        lastMeasuredHrv = 0.0;
        lastHrrDrop = 0;
        lastMeasureNote = "READY \u00B7 TAP START TO MEASURE POST-RUN BPM";
        render();
    }

    public void openPulseHrr60() {
        stopPlankIfRunning(false);
        stopBoltIfRunning();
        stopPulseCam();
        activeTool = TOOL_PULSE;
        pulseTag = "HRR-60";
        lastMeasuredBpm = 0;
        lastMeasuredHrv = 0.0;
        lastHrrDrop = 0;
        lastMeasureNote = "READY \u00B7 12S PEAK BPM \u2192 36S CALM REST \u2192 12S RECOVERY BPM";
        render();
    }

    public void onCameraPermResult(boolean granted) {
        if (pendingCamStart) {
            pendingCamStart = false;
            if (granted && activeTool == TOOL_PULSE) {
                startPulseCam();
            } else if (!granted) {
                Toast.makeText(act, "Camera access needed for finger pulse", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void render() {
        if (container == null) return;
        container.removeAllViews();
        if (activeTool == TOOL_NONE) {
            stopPulseCam();
            renderGrid();
        } else {
            renderDetailHeader();
            switch (activeTool) {
                case TOOL_PULSE: renderPulseDetail(); break;
                case TOOL_STRENGTH: renderStrengthDetail(); break;
                case TOOL_BMI: renderBmiDetail(); break;
                case TOOL_BF: renderBodyFatDetail(); break;
                case TOOL_PACE: renderPaceDetail(); break;
                case TOOL_WATER: renderHydrationDetail(); break;
                case TOOL_VO2: renderVo2Detail(); break;
                case TOOL_BOLT: renderBoltDetail(); break;
            }
        }
    }

    // ================= 2x4 TOOLS PERFORMANCE GRID =================

    private void renderGrid() {
        // Header
        LinearLayout hdr = new LinearLayout(act);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(act);
        dot.setBackgroundResource(R.drawable.dot);
        hdr.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView title = new TextView(act);
        title.setText("TOOLS");
        title.setTextSize(11);
        title.setLetterSpacing(0.25f);
        title.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        hdr.addView(title, tlp);
        TextView sub = new TextView(act);
        sub.setText("8 BUILT-IN ENGINES");
        sub.setTextSize(9);
        sub.setLetterSpacing(0.15f);
        sub.setTextColor(0xFF4A5268);
        hdr.addView(sub);
        container.addView(hdr);

        List<PulseEngine.PulseEntry> pLog = PulseEngine.loadLog(prefs);
        String pulseBadge = pLog.isEmpty() ? "MEASURE" : pLog.get(0).bpm + " BPM";

        int[] str = computeStrength();
        String strBadge = str[0] + " · " + strengthTier(str[0]);

        double bmi = computeBmi();
        String bmiBadge = bmi > 0 ? String.format(Locale.US, "%.1f", bmi) : "SET";

        double bf = computeBodyFat();
        String bfBadge = bf > 0 ? String.format(Locale.US, "%.1f%%", bf) : "TAP";

        double paceMin = getF("pace_km", 5f) > 0 ? getF("pace_sec", 1500f) / 60.0 / getF("pace_km", 5f) : 5.0;
        String paceBadge = fmtPaceMin(paceMin);

        double waterL = computeWaterLiters()[0];
        String waterBadge = String.format(Locale.US, "%.1f L", waterL);

        double vo2 = computeVo2Max();
        String vo2Badge = vo2 > 0 ? String.format(Locale.US, "%.1f", vo2) : "RUN 12M";

        int lastBolt = latestBoltSec();
        String boltBadge = lastBolt > 0 ? lastBolt + "s" : "TEST";

        // 4 rows x 2 cards (keep original 6 tools in rows 1-3, VO2 & BOLT last)
        addCardRow(
                makeToolCard(TOOL_PULSE, R.drawable.ic_tool_pulse, 0xFFFF5C5C,
                        "CAMERA PULSE", "BPM · HRV · HRR-60", pulseBadge,
                        pLog.isEmpty() ? 0xFFFF5C5C : 0xFF00E5A0),
                makeToolCard(TOOL_STRENGTH, R.drawable.ic_tool_strength, 0xFF00E5A0,
                        "STRENGTH LEVEL", "Cardio + Bodyweight", strBadge, 0xFF00E5A0)
        );
        addCardRow(
                makeToolCard(TOOL_BMI, R.drawable.ic_tool_bmi, 0xFF00B4D8,
                        "BMI", "Synced with THE FILE", bmiBadge, bmi > 0 ? bmiColor(bmi) : 0xFF8A94A6),
                makeToolCard(TOOL_BF, R.drawable.ic_tool_bf, 0xFFFFB454,
                        "BODY FAT %", "US Navy Waist & Neck", bfBadge, bf > 0 ? 0xFFFFB454 : 0xFF8A94A6)
        );
        addCardRow(
                makeToolCard(TOOL_PACE, R.drawable.ic_tool_pace, 0xFF00E5A0,
                        "PACE & SPLIT", "Target Pace & Splits", paceBadge, 0xFF00E5A0),
                makeToolCard(TOOL_WATER, R.drawable.ic_tool_water, 0xFF00B4D8,
                        "HYDRATION", "Daily + Run Target", waterBadge, 0xFF00B4D8)
        );
        addCardRow(
                makeToolCard(TOOL_VO2, R.drawable.ic_tool_vo2, 0xFF00E5A0,
                        "VO2 MAX & ZONES", "5 Pace Zones + Races", vo2Badge, vo2 > 0 ? 0xFF00E5A0 : 0xFF8A94A6),
                makeToolCard(TOOL_BOLT, R.drawable.ic_tool_bolt, 0xFF00B4D8,
                        "BOLT BREATH", "CO2 Score & Pacer", boltBadge, lastBolt > 0 ? 0xFF00B4D8 : 0xFF8A94A6)
        );

        TextView foot = new TextView(act);
        foot.setText("100% OFFLINE · SYNCED WITH YOUR RUNS & FILE");
        foot.setGravity(Gravity.CENTER);
        foot.setTextSize(9);
        foot.setLetterSpacing(0.12f);
        foot.setTextColor(0xFF4A5268);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(20);
        container.addView(foot, flp);
    }

    private void addCardRow(View left, View right) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lLp.rightMargin = dp(6);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rLp.leftMargin = dp(6);
        row.addView(left, lLp);
        row.addView(right, rLp);
        container.addView(row, rowLp);
    }

    private View makeToolCard(final int id, int iconRes, int iconTint,
                              String title, String sub, String badge, int badgeColor) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(attrRes(R.attr.cardBg));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTool = id;
                render();
            }
        });

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        // Tinted icon pill
        FrameLayout iconWrap = new FrameLayout(act);
        GradientDrawable ig = new GradientDrawable();
        ig.setCornerRadius(dp(10));
        ig.setColor((iconTint & 0x00FFFFFF) | 0x20000000);
        iconWrap.setBackground(ig);
        ImageView iv = new ImageView(act);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconWrap.addView(iv, ivLp);
        top.addView(iconWrap, new LinearLayout.LayoutParams(dp(36), dp(36)));

        View sp = new View(act);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView bg = new TextView(act);
        bg.setText(badge);
        bg.setTextSize(10);
        bg.setTypeface(Typeface.DEFAULT_BOLD);
        bg.setLetterSpacing(0.06f);
        bg.setTextColor(badgeColor);
        bg.setPadding(dp(7), dp(3), dp(7), dp(3));
        GradientDrawable bgg = new GradientDrawable();
        bgg.setCornerRadius(dp(8));
        bgg.setColor((badgeColor & 0x00FFFFFF) | 0x1A000000);
        bg.setBackground(bgg);
        top.addView(bg);

        card.addView(top);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setTextColor(0xFFF2F5FA);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(14);
        card.addView(t, tLp);

        TextView s = new TextView(act);
        s.setText(sub);
        s.setTextSize(10);
        s.setTextColor(0xFF66708A);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        card.addView(s, sLp);

        return card;
    }

    public void stepTool(int delta) {
        if (activeTool == TOOL_NONE) return;
        stopPlankIfRunning(false);
        stopBoltIfRunning();
        stopPulseCam();
        activeTool = (activeTool + delta + 8) % 8;
        render();
        if (container != null) {
            container.setTranslationX(delta > 0 ? dp(22) : -dp(22));
            container.setAlpha(0.45f);
            container.animate().translationX(0f).alpha(1f).setDuration(150L).start();
        }
    }

    private void renderDetailHeader() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(act);
        back.setText("\u2190  TOOLS");
        back.setTextSize(11);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setLetterSpacing(0.14f);
        back.setTextColor(0xFF00E5A0);
        back.setPadding(dp(12), dp(8), dp(14), dp(8));
        back.setBackgroundResource(attrRes(R.attr.inputBg));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBack();
            }
        });
        row.addView(back);

        View sp = new View(act);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView curTitle = new TextView(act);
        curTitle.setText(activeTool >= 0 && activeTool < TOOL_TITLES.length ? TOOL_TITLES[activeTool] : "");
        curTitle.setTextSize(11);
        curTitle.setTypeface(Typeface.DEFAULT_BOLD);
        curTitle.setLetterSpacing(0.14f);
        curTitle.setTextColor(0xFFF2F5FA);
        row.addView(curTitle);
        container.addView(row);

        // Slim prev / index / next switcher bar (tap OR swipe left/right anywhere)
        int prevIdx = (activeTool + 7) % 8;
        int nextIdx = (activeTool + 1) % 8;
        LinearLayout nav = new LinearLayout(act);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);

        TextView prevBtn = new TextView(act);
        prevBtn.setText("\u2039  " + TOOL_SHORT[prevIdx]);
        prevBtn.setTextSize(10);
        prevBtn.setTypeface(Typeface.DEFAULT_BOLD);
        prevBtn.setLetterSpacing(0.08f);
        prevBtn.setTextColor(0xFF8A94A6);
        prevBtn.setPadding(dp(4), dp(4), dp(8), dp(4));
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepTool(-1);
            }
        });
        nav.addView(prevBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView idxTv = new TextView(act);
        idxTv.setText((activeTool + 1) + " / 8  \u00B7  SWIPE");
        idxTv.setTextSize(9);
        idxTv.setLetterSpacing(0.14f);
        idxTv.setTextColor(0xFF4A5268);
        idxTv.setGravity(Gravity.CENTER);
        nav.addView(idxTv);

        TextView nextBtn = new TextView(act);
        nextBtn.setText(TOOL_SHORT[nextIdx] + "  \u203A");
        nextBtn.setTextSize(10);
        nextBtn.setTypeface(Typeface.DEFAULT_BOLD);
        nextBtn.setLetterSpacing(0.08f);
        nextBtn.setTextColor(0xFF8A94A6);
        nextBtn.setGravity(Gravity.RIGHT);
        nextBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepTool(1);
            }
        });
        nav.addView(nextBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        container.addView(nav, nlp);
    }

    // ================= 1. CAMERA PULSE (CAMERA2 + FLASH PPG + PULSE LOG) =================

    private void renderPulseDetail() {
        boolean running = (pulseCam != null && pulseCam.isRunning()) || hrrPhase != 0;

        LinearLayout hero = makeCard(dp(14));
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(label("CAMERA PULSE · BPM · HRV · HRR-60", 0xFFFF5C5C));
        String heroTxt = lastHrrDrop > 0
                ? "\u2212" + lastHrrDrop + " HRR"
                : (lastMeasuredBpm > 0
                        ? lastMeasuredBpm + " BPM" + (lastMeasuredHrv >= 5.0 ? " · " + Math.round(lastMeasuredHrv) + "ms" : "")
                        : "\u2014");
        pulseBpmTv = bigHero(heroTxt, lastMeasuredBpm > 0 || lastHrrDrop > 0 ? 0xFF00E5A0 : 0xFFF2F5FA);
        titleCol.addView(pulseBpmTv);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Circular camera lens preview ring
        FrameLayout camRing = new FrameLayout(act);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(0xFF0B0E13);
        ringBg.setStroke(dp(2), running ? 0xFFFF5C5C : 0xFF232C42);
        camRing.setBackground(ringBg);
        pulseTex = new TextureView(act);
        FrameLayout.LayoutParams texLp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        camRing.addView(pulseTex, texLp);
        top.addView(camRing, new LinearLayout.LayoutParams(dp(54), dp(54)));

        hero.addView(top);

        pulseStatusTv = subText(lastMeasureNote, running ? 0xFFFFB454 : 0xFF8A94A6, 6);
        hero.addView(pulseStatusTv);

        // Progress bar (0..15s)
        FrameLayout progBg = new FrameLayout(act);
        GradientDrawable pbG = new GradientDrawable();
        pbG.setCornerRadius(dp(4));
        pbG.setColor(0xFF1B2230);
        progBg.setBackground(pbG);
        pulseProgFill = new View(act);
        GradientDrawable pfG = new GradientDrawable();
        pfG.setCornerRadius(dp(4));
        pfG.setColor(0xFFFF5C5C);
        pulseProgFill.setBackground(pfG);
        progBg.addView(pulseProgFill, new FrameLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        progLp.topMargin = dp(10);
        hero.addView(progBg, progLp);

        // Real-time scrolling PPG waveform
        pulseWave = new PulseWaveView(act);
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(115));
        waveLp.topMargin = dp(12);
        hero.addView(pulseWave, waveLp);

        // Context tag pills: RESTING (15s), HRV (25s), POST-RUN (15s), HRR-60 (60s recovery drop)
        LinearLayout tagRow = new LinearLayout(act);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(12);
        final String[] tags = {"RESTING", "HRV", "POST-RUN", "HRR-60"};
        for (int i = 0; i < tags.length; i++) {
            final String tg = tags[i];
            TextView pill = pillToggle(tg, tg.equals(pulseTag), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pulseTag = tg;
                    if (!running) {
                        if ("HRV".equals(tg)) lastMeasureNote = "25S MORNING READINESS SCAN (RMSSD MS)";
                        else if ("HRR-60".equals(tg)) lastMeasureNote = "12S PEAK BPM \u2192 36S CALM REST \u2192 12S RECOVERY BPM";
                        else lastMeasureNote = "TAP START & COVER REAR CAMERA + FLASH LIGHTLY";
                        render();
                    }
                }
            });
            pill.setTextSize(9.5f);
            pill.setLetterSpacing(0.04f);
            pill.setMaxLines(1);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) pLp.leftMargin = dp(5);
            tagRow.addView(pill, pLp);
        }
        hero.addView(tagRow, trLp);

        // Start / Stop button
        pulseBtn = new Button(act);
        String btnLabel = running ? "STOP SCAN"
                : ("HRV".equals(pulseTag) ? "START 25S HRV"
                        : ("HRR-60".equals(pulseTag) ? "START HRR-60 TEST" : "START 15S PULSE"));
        pulseBtn.setText(btnLabel);
        pulseBtn.setTextSize(13);
        pulseBtn.setTypeface(Typeface.DEFAULT_BOLD);
        pulseBtn.setLetterSpacing(0.12f);
        pulseBtn.setStateListAnimator(null);
        pulseBtn.setBackgroundResource(R.drawable.btn_primary);
        pulseBtn.setTextColor(0xFF07121C);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        btnLp.topMargin = dp(12);
        pulseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean activeNow = (pulseCam != null && pulseCam.isRunning()) || hrrPhase != 0;
                if (activeNow) {
                    stopPulseCam();
                    lastMeasureNote = "SCAN STOPPED \u00B7 TAP START TO MEASURE";
                    render();
                } else {
                    if (host != null && !host.hasCameraPerm()) {
                        pendingCamStart = true;
                        host.requestCameraPerm();
                        return;
                    }
                    startPulseCam();
                }
            }
        });
        hero.addView(pulseBtn, btnLp);
        hero.addView(subText("Tip: Cover camera + flash LIGHTLY without pressing hard.", 0xFF4A5268, 8));
        container.addView(hero);

        // PULSE LOG CARD (Stored right inside Pulse in TOOLS)
        LinearLayout logCard = makeCard(dp(12));
        int restAvg = PulseEngine.restingBpm(prefs);
        double hrvBase = PulseEngine.baselineHrv(prefs);
        LinearLayout lh = new LinearLayout(act);
        lh.setOrientation(LinearLayout.HORIZONTAL);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(label("PULSE LOG", 0xFF8A94A6), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (restAvg > 0 || hrvBase >= 5.0) {
            String summary = (restAvg > 0 ? "REST " + restAvg + " BPM" : "")
                    + (restAvg > 0 && hrvBase >= 5.0 ? " · " : "")
                    + (hrvBase >= 5.0 ? "HRV " + Math.round(hrvBase) + "ms" : "");
            lh.addView(label(summary, 0xFF00E5A0));
        }
        logCard.addView(lh);
        pulseLogBox = new LinearLayout(act);
        pulseLogBox.setOrientation(LinearLayout.VERTICAL);
        logCard.addView(pulseLogBox);
        renderPulseLogRows();
        container.addView(logCard);
    }

    private void renderPulseLogRows() {
        if (pulseLogBox == null) return;
        pulseLogBox.removeAllViews();
        List<PulseEngine.PulseEntry> list = PulseEngine.loadLog(prefs);
        if (list.isEmpty()) {
            pulseLogBox.addView(subText("No BPM readings logged yet.", 0xFF4A5268, 8));
            return;
        }
        SimpleDateFormat tf = new SimpleDateFormat("d MMM · HH:mm", Locale.US);
        int maxShow = Math.min(20, list.size());
        for (int i = 0; i < maxShow; i++) {
            final PulseEngine.PulseEntry e = list.get(i);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(10);

            TextView bpmTv = new TextView(act);
            bpmTv.setText(e.bpm + " BPM");
            bpmTv.setTextSize(15);
            bpmTv.setTypeface(Typeface.DEFAULT_BOLD);
            bpmTv.setTextColor(("RESTING".equals(e.tag) || "HRV".equals(e.tag)) ? 0xFF00E5A0
                    : ("POST-RUN".equals(e.tag) ? 0xFFFFB454 : 0xFF00B4D8));
            bpmTv.setWidth(dp(82));

            TextView meta = new TextView(act);
            String hrvTxt = e.hrvMs >= 5.0 ? " · HRV " + Math.round(e.hrvMs) + "ms" : "";
            meta.setText(e.tag + hrvTxt + "  \u00B7  " + tf.format(new Date(e.ts)).toUpperCase(Locale.US));
            meta.setTextSize(10);
            meta.setTextColor(0xFF8A94A6);
            row.addView(bpmTv);
            row.addView(meta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(act);
            del.setText("\u00D7");
            del.setTextSize(16);
            del.setTextColor(0xFF4A5268);
            del.setPadding(dp(8), dp(2), dp(4), dp(2));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PulseEngine.deleteReading(prefs, e.ts);
                    renderPulseLogRows();
                    if (host != null) host.onProfileChanged();
                }
            });
            row.addView(del);
            pulseLogBox.addView(row, rLp);
        }
    }

    private void startPulseCam() {
        stopPulseCam();
        lastMeasuredBpm = 0;
        lastMeasuredHrv = 0.0;
        lastHrrDrop = 0;
        if ("HRR-60".equals(pulseTag)) {
            hrrPhase = 1;
            hrrPeakBpm = 0;
            startPulseCamInternal(12000L);
        } else if ("HRV".equals(pulseTag)) {
            startPulseCamInternal(25000L);
        } else {
            startPulseCamInternal(15000L);
        }
    }

    private void startPulseCamInternal(long durationMs) {
        if (pulseTex == null) return;
        if (pulseCam != null) { pulseCam.stop(); pulseCam = null; }
        if (pulseBpmTv != null && hrrPhase != 3) {
            pulseBpmTv.setText("\u2014");
            pulseBpmTv.setTextColor(0xFFF2F5FA);
        }
        if (pulseWave != null) {
            pulseWave.clear();
            pulseWave.setWaveColor(0xFFFF5C5C);
        }
        if (pulseBtn != null) pulseBtn.setText("STOP SCAN");
        if (pulseStatusTv != null) {
            pulseStatusTv.setText(hrrPhase == 1 ? "HRR-60 PHASE 1 · MEASURING PEAK BPM..."
                    : (hrrPhase == 3 ? "HRR-60 PHASE 2 · MEASURING 60S RECOVERY..." : "STARTING FLASH & CAMERA..."));
            pulseStatusTv.setTextColor(0xFFFFB454);
        }

        pulseCam = new PulseCameraController(act, new PulseCameraController.Listener() {
            @Override
            public void onWaveSample(float waveVal, boolean fingerOn, float prog, String status, int liveBpm) {
                if (pulseWave != null) {
                    pulseWave.setWaveColor(fingerOn ? (liveBpm > 0 ? 0xFF00E5A0 : 0xFFFF5C5C) : 0xFF66708A);
                    if (fingerOn) pulseWave.push(waveVal);
                }
                if (pulseStatusTv != null) {
                    pulseStatusTv.setText(status);
                    pulseStatusTv.setTextColor(fingerOn ? (liveBpm > 0 ? 0xFF00E5A0 : 0xFFFFB454) : 0xFFFF5C5C);
                }
                if (pulseBpmTv != null && liveBpm > 0) {
                    pulseBpmTv.setText(liveBpm + " BPM");
                    pulseBpmTv.setTextColor(0xFF00E5A0);
                }
                if (pulseProgFill != null && pulseProgFill.getParent() instanceof View) {
                    int pw = ((View) pulseProgFill.getParent()).getWidth();
                    if (pw > 0) {
                        pulseProgFill.getLayoutParams().width = Math.round(pw * prog);
                        pulseProgFill.requestLayout();
                    }
                }
            }

            @Override
            public void onComplete(PulseEngine.PulseResult result) {
                if (pulseCam != null) { pulseCam.stop(); pulseCam = null; }
                if (hrrPhase == 1) {
                    if (result.locked) {
                        hrrPeakBpm = result.bpm;
                        hrrPhase = 2;
                        hrrWaitUntil = System.currentTimeMillis() + 36000L;
                        PulseEngine.saveReading(prefs, result.bpm, "POST-RUN");
                        String td = RunStore.today();
                        if (store.get(td) != null) store.setBpm(td, result.bpm);
                        hand.removeCallbacks(hrrTick);
                        hand.post(hrrTick);
                        return;
                    } else {
                        hrrPhase = 0;
                        lastMeasureNote = "Weak peak scan — hold gently and retry HRR-60";
                        render();
                        return;
                    }
                } else if (hrrPhase == 3) {
                    hrrPhase = 0;
                    if (result.locked) {
                        int drop = Math.max(1, hrrPeakBpm - result.bpm);
                        lastMeasuredBpm = result.bpm;
                        lastHrrDrop = drop;
                        String tier = drop >= 40 ? "ATHLETIC" : (drop >= 25 ? "FIT" : (drop >= 12 ? "NORMAL" : "TIRED"));
                        lastMeasureNote = String.format(Locale.US, "HRR-60: \u2212%d BPM (%d \u2192 %d) · %s",
                                drop, hrrPeakBpm, result.bpm, tier);
                        PulseEngine.saveReading(prefs, result.bpm, "CHECK");
                        String td = RunStore.today();
                        if (store.get(td) != null) store.setHrr60(td, drop);
                        if (host != null) host.onProfileChanged();
                        Toast.makeText(act, "Saved HRR-60: -" + drop + " BPM \u2713", Toast.LENGTH_SHORT).show();
                    } else {
                        lastMeasureNote = "Weak 60s scan (peak was " + hrrPeakBpm + " BPM)";
                    }
                    render();
                    return;
                }

                if (result.locked) {
                    lastMeasuredBpm = result.bpm;
                    lastMeasuredHrv = !"POST-RUN".equals(pulseTag) ? result.hrvMs : 0.0;
                    String hrvMsg = lastMeasuredHrv >= 5.0 ? " · HRV " + Math.round(lastMeasuredHrv) + "ms" : "";
                    lastMeasureNote = "SAVED " + result.bpm + " BPM" + hrvMsg + " (" + pulseTag + ")";
                    PulseEngine.saveReading(prefs, result.bpm, pulseTag, lastMeasuredHrv);
                    if ("POST-RUN".equals(pulseTag)) {
                        String td = RunStore.today();
                        if (store.get(td) != null) store.setBpm(td, result.bpm);
                    }
                    if (host != null) host.onProfileChanged();
                    Toast.makeText(act, "Saved " + result.bpm + " BPM" + hrvMsg + " \u2713", Toast.LENGTH_SHORT).show();
                } else {
                    lastMeasuredBpm = 0;
                    lastMeasuredHrv = 0.0;
                    lastMeasureNote = result.note;
                    Toast.makeText(act, "Weak signal — retry gently", Toast.LENGTH_SHORT).show();
                }
                render();
            }

            @Override
            public void onError(String message) {
                stopPulseCam();
                lastMeasureNote = message;
                Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
                render();
            }
        });
        pulseCam.start(pulseTex, durationMs);
    }

    private void stopPulseCam() {
        hrrPhase = 0;
        hand.removeCallbacks(hrrTick);
        if (pulseCam != null) {
            pulseCam.stop();
            pulseCam = null;
        }
    }

    // ================= 2. STRENGTH LEVEL (A + B COMBINED) =================

    private void renderStrengthDetail() {
        int[] sc = computeStrength();
        int total = sc[0], cardio = sc[1], body = sc[2];
        int col = total >= 80 ? 0xFF00E5A0 : (total >= 50 ? 0xFF00B4D8 : 0xFFFFB454);

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("STRENGTH LEVEL · A + B COMBINED", 0xFF8A94A6));
        hero.addView(bigHero(total + " · " + strengthTier(total), col));
        hero.addView(makeBarView(total / 100f, col));
        hero.addView(subText("CARDIO " + cardio + " (50%)   ·   BODYWEIGHT " + body + " (50%)", 0xFFF2F5FA, 10));
        container.addView(hero);

        // PART A: CARDIO ENGINE (AUTO FROM CORVO RUNS)
        LinearLayout partA = makeCard(dp(12));
        partA.addView(label("PART A · CARDIO ENGINE (AUTO)", 0xFF00E5A0));

        double vo2 = estimateVo2Max();
        double pace30 = avgPace30Days();
        int days30 = runDaysLast30();
        int restBpm = PulseEngine.restingBpm(prefs);

        LinearLayout metrics = new LinearLayout(act);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(10);
        metrics.addView(miniStat(vo2 > 0 ? String.format(Locale.US, "%.1f", vo2) : "\u2014", "VO2MAX"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(miniStat(pace30 > 0 ? fmtPaceMin(pace30) : "\u2014", "30D PACE"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(miniStat(String.valueOf(days30), "30D RUNS"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(miniStat(restBpm > 0 ? String.valueOf(restBpm) : "\u2014", "REST BPM"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        partA.addView(metrics, mLp);

        String proj = raceProjectionText();
        partA.addView(subText(proj, 0xFF8A94A6, 12));
        partA.addView(subText(restBpm > 0
                ? "Auto-calculated from Corvo runs + Resting BPM heart efficiency."
                : "Log a Resting BPM in Camera Pulse to add heart efficiency bonus.", 0xFF4A5268, 6));
        container.addView(partA);

        // PART B: BODYWEIGHT ARMOR (PUSHUPS / SQUATS / PULLUPS REPS + PLANK TIMER)
        LinearLayout partB = makeCard(dp(12));
        partB.addView(label("PART B · BODYWEIGHT ARMOR", 0xFF00B4D8));

        final int push = prefs.getInt("str_push", 0);
        final int squat = prefs.getInt("str_squat", 0);
        final int pull = prefs.getInt("str_pull", 0);
        final int plank = prefs.getInt("str_plank", 0);

        addIntStepperRow(partB, "PUSHUPS", push + " REPS", "str_push", push, 1, 0, 300);
        addIntStepperRow(partB, "SQUATS", squat + " REPS", "str_squat", squat, 5, 0, 500);
        addIntStepperRow(partB, "PULLUPS", pull + " REPS", "str_pull", pull, 1, 0, 100);

        // PLANK TIMER ROW
        LinearLayout plankCard = new LinearLayout(act);
        plankCard.setOrientation(LinearLayout.VERTICAL);
        plankCard.setBackgroundResource(attrRes(R.attr.inputBg));
        plankCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(10);

        LinearLayout pTop = new LinearLayout(act);
        pTop.setOrientation(LinearLayout.HORIZONTAL);
        pTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView pLbl = new TextView(act);
        pLbl.setText("PLANK HOLD");
        pLbl.setTextSize(11);
        pLbl.setTypeface(Typeface.DEFAULT_BOLD);
        pLbl.setLetterSpacing(0.12f);
        pLbl.setTextColor(0xFF8A94A6);
        pTop.addView(pLbl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        plankTimeTv = new TextView(act);
        plankTimeTv.setText(plankRunning
                ? fmtClockSec((int) Math.max(0L, (System.currentTimeMillis() - plankStartMs) / 1000L))
                : fmtClockSec(plank));
        plankTimeTv.setTextSize(22);
        plankTimeTv.setTypeface(Typeface.DEFAULT_BOLD);
        plankTimeTv.setTextColor(plankRunning ? 0xFF00E5A0 : 0xFFF2F5FA);
        plankTimeTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (plankRunning) return;
                promptIntEdit("PLANK HOLD (SECONDS)", "str_plank", plank, 0, 900);
            }
        });
        pTop.addView(plankTimeTv);
        plankCard.addView(pTop);

        plankBtn = new Button(act);
        plankBtn.setText(plankRunning ? "STOP & SAVE HOLD" : "START PLANK TIMER");
        plankBtn.setTextSize(12);
        plankBtn.setTypeface(Typeface.DEFAULT_BOLD);
        plankBtn.setLetterSpacing(0.12f);
        plankBtn.setStateListAnimator(null);
        plankBtn.setBackgroundResource(R.drawable.btn_primary);
        plankBtn.setTextColor(0xFF07121C);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        pbLp.topMargin = dp(10);
        plankBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!plankRunning) {
                    plankRunning = true;
                    plankStartMs = System.currentTimeMillis();
                    plankBtn.setText("STOP & SAVE HOLD");
                    plankTimeTv.setTextColor(0xFF00E5A0);
                    hand.removeCallbacks(plankTick);
                    hand.post(plankTick);
                } else {
                    stopPlankIfRunning(true);
                    render();
                }
            }
        });
        plankCard.addView(plankBtn, pbLp);
        plankCard.addView(subText("Best saved: " + fmtClockSec(plank) + " · Tap clock above to edit manually", 0xFF4A5268, 6));
        partB.addView(plankCard, pcLp);

        container.addView(partB);
    }

    private void stopPlankIfRunning(boolean save) {
        if (!plankRunning) return;
        plankRunning = false;
        hand.removeCallbacks(plankTick);
        if (save) {
            int sec = (int) Math.max(0L, Math.round((System.currentTimeMillis() - plankStartMs) / 1000.0));
            if (sec > 0) {
                prefs.edit().putInt("str_plank", sec).apply();
                Toast.makeText(act, "Plank saved: " + fmtClockSec(sec), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ================= 3. BMI (2-WAY SYNCED WITH THE FILE) =================

    private void renderBmiDetail() {
        float h = getF("body_h", 0f);
        float w = getF("body_w", 0f);
        double bmi = (h >= 100 && w > 20) ? w / Math.pow(h / 100.0, 2.0) : 0.0;

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("BODY MASS INDEX · SYNCED WITH THE FILE", 0xFF8A94A6));
        if (bmi > 0) {
            int col = bmiColor(bmi);
            hero.addView(bigHero(String.format(Locale.US, "%.1f", bmi), col));
            hero.addView(subText(bmiCategory(bmi), col, 2));
            hero.addView(makeBmiScaleBar(bmi));
            double lo = 18.5 * Math.pow(h / 100.0, 2.0);
            double hi = 24.9 * Math.pow(h / 100.0, 2.0);
            hero.addView(subText(String.format(Locale.US, "HEALTHY FOR %.0f CM: %.0f \u2013 %.0f KG", h, lo, hi), 0xFFF2F5FA, 10));
        } else {
            hero.addView(bigHero("\u2014", 0xFF66708A));
            hero.addView(subText("Enter Height & Weight below (updates THE FILE too)", 0xFF8A94A6, 4));
        }
        container.addView(hero);

        LinearLayout inputs = makeCard(dp(12));
        inputs.addView(label("PROFILE MEASUREMENTS", 0xFF8A94A6));
        addFloatStepperRow(inputs, "HEIGHT (CM)", h > 0 ? String.format(Locale.US, "%.0f CM", h) : "SET",
                "body_h", h > 0 ? h : 172f, 1f, 100f, 230f, true);
        addFloatStepperRow(inputs, "WEIGHT (KG)", w > 0 ? String.format(Locale.US, "%.0f KG", w) : "SET",
                "body_w", w > 0 ? w : 65f, 1f, 30f, 200f, true);
        container.addView(inputs);
    }

    // ================= 4. BODY FAT % (US NAVY) =================

    private void renderBodyFatDetail() {
        boolean female = prefs.getBoolean("bf_female", false);
        float h = getF("body_h", 0f);
        float w = getF("body_w", 0f);
        float waist = getF("bf_waist", 80f);
        float neck = getF("bf_neck", 36f);
        float hip = getF("bf_hip", 92f);

        double bf = computeBodyFat();

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("US NAVY BODY FAT %", 0xFF8A94A6));
        if (bf > 0) {
            int col = bfColor(bf, female);
            hero.addView(bigHero(String.format(Locale.US, "%.1f%%", bf), col));
            hero.addView(subText(bfCategory(bf, female), col, 2));
            if (w > 20) {
                double fatKg = w * (bf / 100.0);
                double leanKg = w - fatKg;
                hero.addView(subText(String.format(Locale.US, "LEAN MASS %.1f KG  ·  FAT MASS %.1f KG", leanKg, fatKg), 0xFFF2F5FA, 10));
            }
        } else {
            hero.addView(bigHero("\u2014", 0xFF66708A));
            hero.addView(subText("Check height & ensure waist is wider than neck", 0xFFFFB454, 4));
        }
        container.addView(hero);

        LinearLayout inputs = makeCard(dp(12));
        inputs.addView(label("TAPE MEASUREMENTS", 0xFF8A94A6));

        // Sex toggle
        LinearLayout sexRow = new LinearLayout(act);
        sexRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(8);
        TextView mBtn = pillToggle("MALE", !female, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("bf_female", false).apply();
                render();
            }
        });
        TextView fBtn = pillToggle("FEMALE", female, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("bf_female", true).apply();
                render();
            }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p1.rightMargin = dp(4);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p2.leftMargin = dp(4);
        sexRow.addView(mBtn, p1);
        sexRow.addView(fBtn, p2);
        inputs.addView(sexRow, srLp);

        addFloatStepperRow(inputs, "HEIGHT (FROM FILE)", h > 0 ? String.format(Locale.US, "%.0f CM", h) : "SET",
                "body_h", h > 0 ? h : 172f, 1f, 100f, 230f, true);
        addFloatStepperRow(inputs, "WAIST (AT NAVEL)", String.format(Locale.US, "%.1f CM", waist),
                "bf_waist", waist, 0.5f, 45f, 180f, false);
        addFloatStepperRow(inputs, "NECK", String.format(Locale.US, "%.1f CM", neck),
                "bf_neck", neck, 0.5f, 25f, 65f, false);
        if (female) {
            addFloatStepperRow(inputs, "HIP (WIDEST)", String.format(Locale.US, "%.1f CM", hip),
                    "bf_hip", hip, 0.5f, 60f, 180f, false);
        }
        container.addView(inputs);
    }

    // ================= 5. PACE & SPLIT CALCULATOR =================

    private void renderPaceDetail() {
        float km = getF("pace_km", 5.0f);
        if (km <= 0.1f) km = 5.0f;
        float sec = getF("pace_sec", 1500f);
        if (sec < 30f) sec = 1500f;

        double paceMin = (sec / 60.0) / km;
        double kmh = km / (sec / 3600.0);

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("TARGET PACE & SPLITS", 0xFF8A94A6));
        hero.addView(bigHero(fmtPaceMin(paceMin) + " /KM", 0xFF00E5A0));
        hero.addView(subText(String.format(Locale.US, "%.1f KM/H   ·   FINISH %s", kmh, fmtClockSec((int) sec)), 0xFFF2F5FA, 4));
        container.addView(hero);

        LinearLayout inputs = makeCard(dp(12));
        inputs.addView(label("PRESET DISTANCE", 0xFF8A94A6));
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        float[] presets = {1f, 3f, 5f, 10f, 21.1f};
        String[] labels = {"1K", "3K", "5K", "10K", "21K"};
        for (int i = 0; i < presets.length; i++) {
            final float pv = presets[i];
            boolean on = Math.abs(km - pv) < 0.05f;
            TextView ch = pillToggle(labels[i], on, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putFloat("pace_km", pv).apply();
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i > 0) lp.leftMargin = dp(4);
            chips.addView(ch, lp);
        }
        inputs.addView(chips, cLp);

        addFloatStepperRow(inputs, "DISTANCE (KM)", String.format(Locale.US, "%.1f KM", km),
                "pace_km", km, 0.5f, 0.5f, 100f, false);
        addClockStepperRow(inputs, "TARGET TIME", (int) sec, "pace_sec", 30, 30, 36000);
        container.addView(inputs);

        // Splits card
        LinearLayout splits = makeCard(dp(12));
        splits.addView(label("CHECKPOINT SPLITS", 0xFF00B4D8));
        double[] marks = km > 2.5 ? new double[]{1.0, km / 2.0, km} : new double[]{km / 2.0, km};
        String[] mNames = km > 2.5
                ? new String[]{"1 KM", String.format(Locale.US, "HALF (%.1fK)", km / 2.0), String.format(Locale.US, "FINISH (%.1fK)", km)}
                : new String[]{String.format(Locale.US, "HALF (%.2fK)", km / 2.0), String.format(Locale.US, "FINISH (%.1fK)", km)};
        for (int i = 0; i < marks.length; i++) {
            int splitS = (int) Math.round(marks[i] * paceMin * 60.0);
            addInfoRow(splits, mNames[i], fmtClockSec(splitS));
        }
        container.addView(splits);
    }

    // ================= 6. HYDRATION TARGET =================

    private void renderHydrationDetail() {
        double[] res = computeWaterLiters();
        double totalL = res[0], baseL = res[1], runL = res[2], hotL = res[3];
        float w = getF("body_w", 0f);
        boolean hot = prefs.getBoolean("water_hot", false);
        RunStore.Run todayRun = store.get(RunStore.today());
        double todayMin = todayRun != null ? todayRun.min : 0;

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("DAILY HYDRATION TARGET", 0xFF8A94A6));
        hero.addView(bigHero(String.format(Locale.US, "%.2f L", totalL), 0xFF00B4D8));
        int glasses = (int) Math.round((totalL * 1000.0) / 250.0);
        hero.addView(subText("~" + glasses + " GLASSES (250 ML EACH)", 0xFFF2F5FA, 4));
        container.addView(hero);

        LinearLayout brk = makeCard(dp(12));
        brk.addView(label("DAILY BREAKDOWN", 0xFF8A94A6));
        addInfoRow(brk, "BASE (" + (w > 0 ? Math.round(w) + " KG" : "65 KG DEFAULT") + ")",
                String.format(Locale.US, "%.2f L", baseL));
        addInfoRow(brk, "TODAY'S RUN (" + (todayMin > 0 ? Math.round(todayMin) + " MIN" : "0 MIN") + ")",
                String.format(Locale.US, "+%.2f L", runL));
        addInfoRow(brk, "HOT / HUMID BONUS", hot ? "+0.50 L" : "OFF");

        addFloatStepperRow(brk, "WEIGHT (FILE SYNCED)", w > 0 ? String.format(Locale.US, "%.0f KG", w) : "SET",
                "body_w", w > 0 ? w : 65f, 1f, 30f, 200f, true);

        TextView hotBtn = pillToggle(hot ? "HOT / SWEATY DAY: ON (+0.5 L)" : "HOT / SWEATY DAY: OFF", hot,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.edit().putBoolean("water_hot", !prefs.getBoolean("water_hot", false)).apply();
                        render();
                    }
                });
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        hLp.topMargin = dp(12);
        brk.addView(hotBtn, hLp);
        container.addView(brk);
    }

    // ================= 7. VO2 MAX, PACE ZONES & RACE PREDICTOR =================

    private void renderVo2Detail() {
        double vo2 = computeVo2Max();
        int restBpm = PulseEngine.restingBpm(prefs);
        int hrr = store.latestHrr60();
        int age = userAge();
        RunStore.Run bestRun = store.bestRunForVo2();

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("CARDIO VO2 MAX & FITNESS AGE", 0xFF00E5A0));
        if (vo2 > 0) {
            int fitAge = fitnessAge(vo2, age);
            String tier = vo2Tier(vo2);
            hero.addView(bigHero(String.format(Locale.US, "%.1f", vo2), 0xFF00E5A0));
            hero.addView(subText("ML/KG/MIN   ·   " + tier + "   ·   FITNESS AGE " + fitAge + " (AGE " + age + ")", 0xFFF2F5FA, 4));
            String basis = bestRun != null
                    ? String.format(Locale.US, "Based on %.2f km in %s", bestRun.km, fmtClockSec((int) Math.round(bestRun.min * 60.0)))
                    : "Estimated from Resting HR (" + restBpm + " BPM)";
            if (hrr > 0) basis += "  ·  Latest HRR-60: -" + hrr + " BPM";
            hero.addView(subText(basis, 0xFF8A94A6, 6));
        } else {
            hero.addView(bigHero("\u2014", 0xFF8A94A6));
            hero.addView(subText("Log a run of 6+ min (or a Resting BPM in Camera Pulse) to unlock VO2 Max & Training Zones.", 0xFF8A94A6, 6));
        }
        container.addView(hero);

        // 5 Personal Training Zones (Z1..Z5 min/km based on VDOT / VO2 velocity)
        LinearLayout zones = makeCard(dp(12));
        zones.addView(label("5 PERSONAL PACE ZONES (MIN/KM)", 0xFF00B4D8));
        if (vo2 > 0) {
            // Threshold / vVO2 velocity from VO2max: solve 0.000104 v^2 + 0.182258 v - (4.6 + vo2) = 0
            double disc = 0.182258 * 0.182258 + 4.0 * 0.000104 * (4.6 + vo2);
            double vVo2 = (-0.182258 + Math.sqrt(disc)) / (2.0 * 0.000104); // m/min at 100% VO2max
            double[][] zPct = {
                    {0.58, 0.67}, // Z1 Recovery
                    {0.67, 0.76}, // Z2 Easy Aerobic Base
                    {0.76, 0.85}, // Z3 Tempo / Cruise
                    {0.85, 0.93}, // Z4 Lactate Threshold
                    {0.93, 1.03}  // Z5 VO2 Max Intervals
            };
            String[] zName = {"Z1 RECOVERY", "Z2 AEROBIC BASE", "Z3 TEMPO", "Z4 THRESHOLD", "Z5 VO2 INTERVALS"};
            int[] zCol = {0xFF8A94A6, 0xFF00B4D8, 0xFF00E5A0, 0xFFFFB454, 0xFFFF5C5C};
            for (int i = 0; i < 5; i++) {
                double slowP = 1000.0 / (vVo2 * zPct[i][0]);
                double fastP = 1000.0 / (vVo2 * zPct[i][1]);
                addColoredInfoRow(zones, zName[i], fmtPaceMin(fastP) + " \u2013 " + fmtPaceMin(slowP) + " /km", zCol[i]);
            }
        } else {
            zones.addView(subText("Zones unlock once a qualifying run is logged.", 0xFF4A5268, 8));
        }
        container.addView(zones);

        // Race Ceiling Predictor (Riegel)
        LinearLayout race = makeCard(dp(12));
        race.addView(label("RACE CEILING PREDICTOR (RIEGEL)", 0xFFFFB454));
        if (bestRun != null) {
            double[] dists = {1.0, 5.0, 10.0, 21.1};
            String[] names = {"1 KM", "5 KM", "10 KM", "HALF MARATHON (21.1K)"};
            for (int i = 0; i < dists.length; i++) {
                double predMin = bestRun.min * Math.pow(dists[i] / bestRun.km, 1.06);
                int sec = (int) Math.round(predMin * 60.0);
                addInfoRow(race, names[i], fmtClockSec(sec) + "  (" + fmtPaceMin(predMin / dists[i]) + ")");
            }
        } else {
            race.addView(subText("Log a run \u22651 km (6+ min) to project 1K / 5K / 10K / 21K finish ceilings.", 0xFF4A5268, 8));
        }
        container.addView(race);
    }

    // ================= 8. BOLT BREATH & COOLDOWN PACER LAB =================

    private void renderBoltDetail() {
        List<long[]> history = loadBoltHistory();
        int latest = history.isEmpty() ? 0 : (int) history.get(0)[1];
        int avg30 = avgBolt30(history);

        LinearLayout hero = makeCard(dp(14));
        hero.addView(label("BOLT SCORE · CO2 TOLERANCE", 0xFF00B4D8));
        boltTimeTv = bigHero(boltRunning ? "0.0 s" : (latest > 0 ? latest + " s" : "\u2014"),
                latest >= 25 ? 0xFF00E5A0 : 0xFF00B4D8);
        hero.addView(boltTimeTv);

        String tierMsg = latest == 0
                ? "Breathe normally, exhale softly, pinch nose & tap START until first natural urge to breathe."
                : boltTier(latest) + (avg30 > 0 ? "   ·   30D AVG " + avg30 + "s" : "");
        hero.addView(subText(tierMsg, 0xFFF2F5FA, 4));

        boltBtn = new Button(act);
        boltBtn.setText(boltRunning ? "STOP & SAVE BOLT" : "START BOLT TEST");
        boltBtn.setTextSize(13);
        boltBtn.setTypeface(Typeface.DEFAULT_BOLD);
        boltBtn.setLetterSpacing(0.12f);
        boltBtn.setStateListAnimator(null);
        boltBtn.setBackgroundResource(R.drawable.btn_primary);
        boltBtn.setTextColor(0xFF07121C);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        bLp.topMargin = dp(12);
        boltBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!boltRunning) {
                    boltRunning = true;
                    boltStartMs = System.currentTimeMillis();
                    boltBtn.setText("STOP & SAVE BOLT");
                    hand.removeCallbacks(boltTick);
                    hand.post(boltTick);
                } else {
                    int sec = (int) Math.round(Math.max(0.0, (System.currentTimeMillis() - boltStartMs) / 1000.0));
                    boltRunning = false;
                    if (!paceBreathRunning) hand.removeCallbacks(boltTick);
                    if (sec >= 3 && sec <= 120) {
                        saveBoltScore(sec);
                        Toast.makeText(act, "Saved BOLT " + sec + "s \u2713", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(act, "Hold naturally (3s–120s) without forcing", Toast.LENGTH_SHORT).show();
                    }
                    render();
                }
            }
        });
        hero.addView(boltBtn, bLp);
        hero.addView(subText("<10s High Stress · 10–20s Fair · 20–30s Good · 30–40s Strong · 40s+ Elite", 0xFF66708A, 8));
        container.addView(hero);

        // Cooldown Pacer (4s Inhale / 6s Exhale)
        LinearLayout pacer = makeCard(dp(12));
        pacer.addView(label("NASAL COOLDOWN PACER · 4S IN / 6S OUT", 0xFF00E5A0));
        paceBreathPhaseTv = subText(paceBreathRunning ? "INHALE SLOWLY · 4s" : "Post-run autonomic reset (6 breaths/min)",
                0xFFF2F5FA, 6);
        pacer.addView(paceBreathPhaseTv);

        FrameLayout pbBg = new FrameLayout(act);
        GradientDrawable pbgD = new GradientDrawable();
        pbgD.setCornerRadius(dp(6));
        pbgD.setColor(0xFF1B2230);
        pbBg.setBackground(pbgD);
        paceBreathBar = new View(act);
        GradientDrawable pbfD = new GradientDrawable();
        pbfD.setCornerRadius(dp(6));
        pbfD.setColor(0xFF00E5A0);
        paceBreathBar.setBackground(pbfD);
        paceBreathBarMaxW = 0;
        pbBg.addView(paceBreathBar, new FrameLayout.LayoutParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(12));
        pLp.topMargin = dp(10);
        pacer.addView(pbBg, pLp);

        paceBreathBtn = new Button(act);
        paceBreathBtn.setText(paceBreathRunning ? "STOP PACER" : "START 4-6 PACER");
        paceBreathBtn.setTextSize(12);
        paceBreathBtn.setTypeface(Typeface.DEFAULT_BOLD);
        paceBreathBtn.setLetterSpacing(0.12f);
        paceBreathBtn.setStateListAnimator(null);
        paceBreathBtn.setBackgroundResource(R.drawable.badge_off);
        paceBreathBtn.setTextColor(0xFF00E5A0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        pbLp.topMargin = dp(10);
        paceBreathBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                paceBreathRunning = !paceBreathRunning;
                if (paceBreathRunning) {
                    paceBreathStartMs = System.currentTimeMillis();
                    paceBreathBtn.setText("STOP PACER");
                    hand.removeCallbacks(boltTick);
                    hand.post(boltTick);
                } else {
                    if (!boltRunning) hand.removeCallbacks(boltTick);
                    render();
                }
            }
        });
        pacer.addView(paceBreathBtn, pbLp);
        container.addView(pacer);

        // BOLT History Card
        LinearLayout logCard = makeCard(dp(12));
        logCard.addView(label("BOLT HISTORY", 0xFF8A94A6));
        if (history.isEmpty()) {
            logCard.addView(subText("No BOLT scores saved yet.", 0xFF4A5268, 8));
        } else {
            SimpleDateFormat tf = new SimpleDateFormat("d MMM · HH:mm", Locale.US);
            int max = Math.min(12, history.size());
            for (int i = 0; i < max; i++) {
                final long ts = history.get(i)[0];
                int sec = (int) history.get(i)[1];
                LinearLayout r = new LinearLayout(act);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(8);
                TextView sc = new TextView(act);
                sc.setText(sec + " s");
                sc.setTextSize(15);
                sc.setTypeface(Typeface.DEFAULT_BOLD);
                sc.setTextColor(sec >= 25 ? 0xFF00E5A0 : 0xFF00B4D8);
                sc.setMinWidth(dp(56));
                r.addView(sc);
                TextView tm = new TextView(act);
                tm.setText(boltTier(sec) + " · " + tf.format(new Date(ts)));
                tm.setTextSize(11);
                tm.setTextColor(0xFF8A94A6);
                r.addView(tm, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView del = new TextView(act);
                del.setText("\u00D7");
                del.setTextSize(17);
                del.setTextColor(0xFF66708A);
                del.setPadding(dp(8), dp(2), dp(4), dp(2));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteBoltEntry(ts);
                        render();
                    }
                });
                r.addView(del);
                logCard.addView(r, rlp);
            }
        }
        container.addView(logCard);
    }

    // ================= MATH HELPERS (PURE & HONEST) =================

    private int userAge() {
        String dob = prefs.getString("dob", "");
        if (dob != null && dob.length() >= 10) {
            try {
                int y = Integer.parseInt(dob.substring(6, 10));
                int cy = Calendar.getInstance().get(Calendar.YEAR);
                int a = cy - y;
                if (a >= 10 && a <= 90) return a;
            } catch (Exception ignored) {
            }
        }
        return 24;
    }

    private double computeVo2Max() {
        RunStore.Run r = store.bestRunForVo2();
        if (r != null && r.min > 0 && r.km > 0) {
            double v = (r.km * 1000.0) / r.min; // m/min
            double t = r.min;
            double pct = 0.8 + 0.1894393 * Math.exp(-0.012778 * t)
                    + 0.2989558 * Math.exp(-0.1932605 * t);
            double vo2 = -4.60 + 0.182258 * v + 0.000104 * v * v;
            double est = vo2 / Math.max(0.55, pct);
            return clamp(est, 25.0, 82.0);
        }
        int restBpm = PulseEngine.restingBpm(prefs);
        if (restBpm >= 38 && restBpm <= 110) {
            double hrMax = 208.0 - 0.7 * userAge();
            return clamp(15.3 * (hrMax / restBpm), 25.0, 75.0);
        }
        return 0.0;
    }

    private static String vo2Tier(double v) {
        if (v < 36) return "BUILDING";
        if (v < 43) return "FAIR";
        if (v < 50) return "GOOD";
        if (v < 57) return "EXCELLENT";
        if (v < 65) return "ATHLETE";
        return "ELITE";
    }

    private static int fitnessAge(double vo2, int realAge) {
        // Normative 45 ml/kg/min ~ age 25, dropping ~0.45 per year
        int fa = (int) Math.round(25.0 + (45.0 - vo2) / 0.45);
        return Math.max(18, Math.min(80, Math.min(realAge + 15, fa)));
    }

    private List<long[]> loadBoltHistory() {
        java.util.ArrayList<long[]> out = new java.util.ArrayList<long[]>();
        try {
            JSONArray a = new JSONArray(prefs.getString("bolt_log", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                int sec = o.optInt("sec", 0);
                if (sec > 0) out.add(new long[]{o.optLong("ts", 0L), sec});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private int latestBoltSec() {
        List<long[]> h = loadBoltHistory();
        return h.isEmpty() ? 0 : (int) h.get(0)[1];
    }

    private void saveBoltScore(int sec) {
        try {
            JSONArray a = new JSONArray(prefs.getString("bolt_log", "[]"));
            JSONObject o = new JSONObject();
            long now = System.currentTimeMillis();
            o.put("ts", now);
            o.put("d", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now)));
            o.put("sec", sec);
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < a.length() && next.length() < 90; i++) next.put(a.get(i));
            prefs.edit().putString("bolt_log", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void deleteBoltEntry(long ts) {
        try {
            JSONArray a = new JSONArray(prefs.getString("bolt_log", "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("ts", -1) != ts) next.put(o);
            }
            prefs.edit().putString("bolt_log", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static int avgBolt30(List<long[]> hist) {
        long cut = System.currentTimeMillis() - 30L * 86400000L;
        int sum = 0, n = 0;
        for (long[] r : hist) {
            if (r[0] >= cut) { sum += (int) r[1]; n++; }
        }
        return n > 0 ? Math.round((float) sum / n) : 0;
    }

    private static String boltTier(int sec) {
        if (sec < 10) return "HIGH STRESS";
        if (sec < 20) return "FAIR NASAL";
        if (sec < 30) return "GOOD CONTROL";
        if (sec < 40) return "STRONG CO2";
        return "ELITE";
    }

    private int[] computeStrength() {
        // Part A: Cardio (0..100) from runs
        double avgP = avgPace30Days();
        double paceScore = avgP <= 0 ? 35.0 : clamp((8.5 - avgP) * (70.0 / 4.5) + 30.0, 20.0, 100.0);
        double consScore = Math.min(100.0, runDaysLast30() * 5.0);
        double longScore = Math.min(100.0, store.longest() * 8.0);
        int restBpm = PulseEngine.restingBpm(prefs);
        double bpmBonus = 0.0;
        if (restBpm > 0) {
            if (restBpm <= 52) bpmBonus = 8.0;
            else if (restBpm <= 62) bpmBonus = 5.0;
            else if (restBpm <= 74) bpmBonus = 2.0;
            else if (restBpm > 88) bpmBonus = -2.0;
        }
        int cardio = (int) Math.round(clamp(0.55 * paceScore + 0.25 * consScore + 0.20 * longScore + bpmBonus, 15.0, 100.0));

        // Part B: Bodyweight (0..100)
        int push = prefs.getInt("str_push", 0);
        int squat = prefs.getInt("str_squat", 0);
        int pull = prefs.getInt("str_pull", 0);
        int plank = prefs.getInt("str_plank", 0);
        double p1 = Math.min(100.0, push * (100.0 / 60.0));
        double p2 = Math.min(100.0, squat * (100.0 / 100.0));
        double p3 = Math.min(100.0, pull * (100.0 / 20.0));
        double p4 = Math.min(100.0, plank * (100.0 / 180.0));
        int body = (int) Math.round((p1 + p2 + p3 + p4) / 4.0);

        int total = (int) Math.round(0.5 * cardio + 0.5 * body);
        return new int[]{total, cardio, body};
    }

    private static String strengthTier(int s) {
        if (s < 30) return "ROOKIE";
        if (s < 50) return "BRONZE";
        if (s < 65) return "IRON";
        if (s < 80) return "STEEL";
        if (s < 90) return "TITAN";
        return "APEX";
    }

    private int runDaysLast30() {
        String today = RunStore.today();
        String cut = RunStore.dateShift(today, -29);
        int c = 0;
        for (RunStore.Run r : store.recent(60)) {
            if (r.d.compareTo(cut) >= 0 && r.d.compareTo(today) <= 0) c++;
        }
        return c;
    }

    private double avgPace30Days() {
        String today = RunStore.today();
        String cut = RunStore.dateShift(today, -29);
        double km = 0, min = 0;
        for (RunStore.Run r : store.recent(60)) {
            if (r.d.compareTo(cut) >= 0 && r.d.compareTo(today) <= 0 && r.km > 0) {
                km += r.km;
                min += r.min;
            }
        }
        return km > 0 ? min / km : store.avgPace();
    }

    private double estimateVo2Max() {
        return computeVo2Max();
    }

    private String raceProjectionText() {
        RunStore.Run bestRun = store.bestRunForVo2();
        if (bestRun == null) return "Log a run \u22651 km (6+ min) to unlock 1K / 5K / 10K projections";
        int s1 = (int) Math.round(bestRun.min * Math.pow(1.0 / bestRun.km, 1.06) * 60.0);
        int s5 = (int) Math.round(bestRun.min * Math.pow(5.0 / bestRun.km, 1.06) * 60.0);
        int s10 = (int) Math.round(bestRun.min * Math.pow(10.0 / bestRun.km, 1.06) * 60.0);
        return "ESTIMATES   1K " + fmtClockSec(s1) + "   ·   5K " + fmtClockSec(s5) + "   ·   10K " + fmtClockSec(s10);
    }

    private double computeBmi() {
        float h = getF("body_h", 0f);
        float w = getF("body_w", 0f);
        return (h >= 100 && w > 20) ? w / Math.pow(h / 100.0, 2.0) : 0.0;
    }

    private static int bmiColor(double bmi) {
        if (bmi < 18.5) return 0xFF00B4D8;
        if (bmi < 25.0) return 0xFF00E5A0;
        if (bmi < 30.0) return 0xFFFFB454;
        return 0xFFFF5C5C;
    }

    private static String bmiCategory(double bmi) {
        if (bmi < 18.5) return "UNDERWEIGHT";
        if (bmi < 25.0) return "HEALTHY NORMAL";
        if (bmi < 30.0) return "OVERWEIGHT";
        return "OBESE";
    }

    private double computeBodyFat() {
        boolean female = prefs.getBoolean("bf_female", false);
        float h = getF("body_h", 0f);
        if (h < 100) h = 172f;
        float waist = getF("bf_waist", 80f);
        float neck = getF("bf_neck", 36f);
        float hip = getF("bf_hip", 92f);
        double diff = female ? (waist + hip - neck) : (waist - neck);
        if (diff <= 1.0 || h <= 0) return 0.0;
        double bf = female
                ? 495.0 / (1.29579 - 0.35004 * Math.log10(diff) + 0.22100 * Math.log10(h)) - 450.0
                : 495.0 / (1.0324 - 0.19077 * Math.log10(diff) + 0.15456 * Math.log10(h)) - 450.0;
        if (Double.isNaN(bf) || Double.isInfinite(bf) || bf < 2.0 || bf > 65.0) return 0.0;
        return bf;
    }

    private static int bfColor(double bf, boolean female) {
        double fitMax = female ? 24.0 : 17.0;
        double avgMax = female ? 31.0 : 24.0;
        if (bf <= fitMax) return 0xFF00E5A0;
        if (bf <= avgMax) return 0xFF00B4D8;
        return 0xFFFFB454;
    }

    private static String bfCategory(double bf, boolean female) {
        if (!female) {
            if (bf < 6) return "ESSENTIAL FAT";
            if (bf < 14) return "ATHLETE";
            if (bf < 18) return "FITNESS";
            if (bf < 25) return "AVERAGE";
            return "ABOVE AVERAGE";
        } else {
            if (bf < 14) return "ESSENTIAL FAT";
            if (bf < 21) return "ATHLETE";
            if (bf < 25) return "FITNESS";
            if (bf < 32) return "AVERAGE";
            return "ABOVE AVERAGE";
        }
    }

    private double[] computeWaterLiters() {
        float w = getF("body_w", 0f);
        double wt = w > 20 ? w : 65.0;
        double baseL = (wt * 35.0) / 1000.0;
        RunStore.Run r = store.get(RunStore.today());
        double runL = r != null ? (r.min * 12.0) / 1000.0 : 0.0;
        double hotL = prefs.getBoolean("water_hot", false) ? 0.50 : 0.0;
        return new double[]{baseL + runL + hotL, baseL, runL, hotL};
    }

    // ================= UI COMPONENT BUILDERS =================

    private LinearLayout makeCard(int topMarginPx) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(attrRes(R.attr.cardBg));
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s, int col) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(10);
        t.setLetterSpacing(0.2f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col);
        return t;
    }

    private TextView bigHero(String s, int col) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(38);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView subText(String s, int col, int topDp) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(col);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout miniStat(String val, String lbl) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(act);
        v.setText(val);
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFF2F5FA);
        TextView l = new TextView(act);
        l.setText(lbl);
        l.setTextSize(8);
        l.setLetterSpacing(0.15f);
        l.setTextColor(0xFF66708A);
        box.addView(v);
        box.addView(l);
        return box;
    }

    private void addInfoRow(LinearLayout parent, String k, String v) {
        addColoredInfoRow(parent, k, v, 0xFFF2F5FA);
    }

    private void addColoredInfoRow(LinearLayout parent, String k, String v, int keyCol) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        TextView kt = new TextView(act);
        kt.setText(k);
        kt.setTextSize(11);
        kt.setLetterSpacing(0.08f);
        kt.setTextColor(keyCol == 0xFFF2F5FA ? 0xFF8A94A6 : keyCol);
        kt.setTypeface(keyCol == 0xFFF2F5FA ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        TextView vt = new TextView(act);
        vt.setText(v);
        vt.setTextSize(13);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        vt.setTextColor(0xFFF2F5FA);
        r.addView(kt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(vt);
        parent.addView(r, lp);
    }

    private void addClockStepperRow(LinearLayout parent, final String labelStr, final int curSec,
                                    final String key, final int stepSec, final int minSec, final int maxSec) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);

        TextView k = new TextView(act);
        k.setText(labelStr);
        k.setTextSize(11);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLetterSpacing(0.08f);
        k.setTextColor(0xFF8A94A6);
        r.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.addView(stepBtn("\u2212", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putFloat(key, Math.max(minSec, curSec - stepSec)).apply();
                render();
            }
        }));

        TextView val = new TextView(act);
        val.setText(fmtClockSec(curSec));
        val.setTextSize(13);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFFF2F5FA);
        val.setGravity(Gravity.CENTER);
        val.setPadding(dp(10), dp(6), dp(10), dp(6));
        val.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptClockEdit(labelStr, key, curSec, minSec, maxSec);
            }
        });
        r.addView(val);

        r.addView(stepBtn("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putFloat(key, Math.min(maxSec, curSec + stepSec)).apply();
                render();
            }
        }));

        parent.addView(r, lp);
    }

    private void promptClockEdit(String title, final String key, int curSec, final int minSec, final int maxSec) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(20), dp(12), dp(20), 0);

        int m = curSec / 60, s = curSec % 60;
        final EditText minEt = new EditText(act);
        minEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        minEt.setHint("MIN");
        minEt.setGravity(Gravity.CENTER);
        minEt.setText(m > 0 ? String.valueOf(m) : "");
        minEt.setSelection(minEt.getText().length());

        TextView colon = new TextView(act);
        colon.setText(" : ");
        colon.setTextSize(18);
        colon.setTypeface(Typeface.DEFAULT_BOLD);

        final EditText secEt = new EditText(act);
        secEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        secEt.setHint("SEC");
        secEt.setGravity(Gravity.CENTER);
        secEt.setText(String.format(Locale.US, "%02d", s));

        row.addView(minEt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(colon);
        row.addView(secEt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        new AlertDialog.Builder(act)
                .setTitle(title + " (MIN : SEC)")
                .setView(row)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String ms = minEt.getText().toString().trim();
                            String ss = secEt.getText().toString().trim();
                            int mv = ms.isEmpty() ? 0 : Integer.parseInt(ms);
                            int sv = ss.isEmpty() ? 0 : Integer.parseInt(ss);
                            int total = Math.max(minSec, Math.min(maxSec, mv * 60 + sv));
                            prefs.edit().putFloat(key, total).apply();
                            render();
                        } catch (Exception e) {
                            Toast.makeText(act, "Enter minutes and seconds", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void addIntStepperRow(LinearLayout parent, final String labelStr, String valStr,
                                  final String key, final int cur, final int step, final int min, final int max) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);

        TextView k = new TextView(act);
        k.setText(labelStr);
        k.setTextSize(11);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLetterSpacing(0.1f);
        k.setTextColor(0xFF8A94A6);
        r.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.addView(stepBtn("\u2212", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int nv = Math.max(min, cur - step);
                prefs.edit().putInt(key, nv).apply();
                render();
            }
        }));

        TextView val = new TextView(act);
        val.setText(valStr);
        val.setTextSize(13);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFFF2F5FA);
        val.setGravity(Gravity.CENTER);
        val.setPadding(dp(12), dp(6), dp(12), dp(6));
        val.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptIntEdit(labelStr, key, cur, min, max);
            }
        });
        r.addView(val);

        r.addView(stepBtn("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int nv = Math.min(max, cur + step);
                prefs.edit().putInt(key, nv).apply();
                render();
            }
        }));

        parent.addView(r, lp);
    }

    private void addFloatStepperRow(LinearLayout parent, final String labelStr, String valStr,
                                    final String key, final float cur, final float step,
                                    final float min, final float max, final boolean syncProfile) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);

        TextView k = new TextView(act);
        k.setText(labelStr);
        k.setTextSize(11);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLetterSpacing(0.08f);
        k.setTextColor(0xFF8A94A6);
        r.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.addView(stepBtn("\u2212", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float nv = Math.max(min, cur - step);
                prefs.edit().putFloat(key, nv).apply();
                if (syncProfile && host != null) host.onProfileChanged();
                render();
            }
        }));

        TextView val = new TextView(act);
        val.setText(valStr);
        val.setTextSize(13);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFFF2F5FA);
        val.setGravity(Gravity.CENTER);
        val.setPadding(dp(10), dp(6), dp(10), dp(6));
        val.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptFloatEdit(labelStr, key, cur, min, max, syncProfile);
            }
        });
        r.addView(val);

        r.addView(stepBtn("+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float nv = Math.min(max, cur + step);
                prefs.edit().putFloat(key, nv).apply();
                if (syncProfile && host != null) host.onProfileChanged();
                render();
            }
        }));

        parent.addView(r, lp);
    }

    private TextView stepBtn(String text, View.OnClickListener click) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFF00E5A0);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(attrRes(R.attr.inputBg));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        return b;
    }

    private TextView pillToggle(String text, boolean on, View.OnClickListener click) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.1f);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(on ? R.drawable.badge_on : R.drawable.badge_off);
        b.setTextColor(on ? 0xFF00E5A0 : 0xFF66708A);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        return b;
    }

    private void promptIntEdit(String title, final String key, int cur, final int min, final int max) {
        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(cur > 0 ? String.valueOf(cur) : "");
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            v = Math.max(min, Math.min(max, v));
                            prefs.edit().putInt(key, v).apply();
                            render();
                        } catch (Exception e) {
                            Toast.makeText(act, "Enter a whole number", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void promptFloatEdit(String title, final String key, float cur,
                                 final float min, final float max, final boolean syncProfile) {
        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(cur > 0 ? String.valueOf(cur == Math.floor(cur) ? (int) cur : cur) : "");
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            float v = Float.parseFloat(et.getText().toString().trim());
                            v = Math.max(min, Math.min(max, v));
                            prefs.edit().putFloat(key, v).apply();
                            if (syncProfile && host != null) host.onProfileChanged();
                            render();
                        } catch (Exception e) {
                            Toast.makeText(act, "Enter a valid number", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private View makeBarView(final float frac, final int color) {
        View v = new View(act) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), r = h / 2f;
                p.setColor(0xFF1B2230);
                c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
                p.setColor(color);
                float fw = Math.max(h, w * Math.max(0f, Math.min(1f, frac)));
                c.drawRoundRect(new RectF(0, 0, fw, h), r, r, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private View makeBmiScaleBar(final double bmi) {
        View v = new View(act) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), cy = getHeight() / 2f, bh = dp(8);
                float gap = dp(3);
                // 14..36 span
                float s1 = (float) ((18.5 - 14.0) / 22.0) * w;
                float s2 = (float) ((25.0 - 14.0) / 22.0) * w;
                float s3 = (float) ((30.0 - 14.0) / 22.0) * w;
                p.setColor(0xFF00B4D8);
                c.drawRoundRect(new RectF(0, cy - bh / 2f, s1 - gap, cy + bh / 2f), dp(4), dp(4), p);
                p.setColor(0xFF00E5A0);
                c.drawRoundRect(new RectF(s1, cy - bh / 2f, s2 - gap, cy + bh / 2f), dp(4), dp(4), p);
                p.setColor(0xFFFFB454);
                c.drawRoundRect(new RectF(s2, cy - bh / 2f, s3 - gap, cy + bh / 2f), dp(4), dp(4), p);
                p.setColor(0xFFFF5C5C);
                c.drawRoundRect(new RectF(s3, cy - bh / 2f, w, cy + bh / 2f), dp(4), dp(4), p);

                float pos = (float) clamp((bmi - 14.0) / 22.0, 0.02, 0.98) * w;
                p.setColor(0xFFF2F5FA);
                c.drawCircle(pos, cy, dp(7), p);
                p.setColor(0xFF07121C);
                c.drawCircle(pos, cy, dp(3), p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20));
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private float getF(String k, float def) {
        try { return prefs.getFloat(k, def); } catch (Exception e) { return def; }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmtClockSec(int totalSec) {
        int m = totalSec / 60, s = totalSec % 60;
        if (m >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String fmtPaceMin(double paceMin) {
        long s = Math.round(paceMin * 60.0);
        return String.format(Locale.US, "%d:%02d", s / 60, (int) (s % 60));
    }

    private int attrRes(int attr) {
        TypedArray ta = act.obtainStyledAttributes(new int[]{attr});
        int r = ta.getResourceId(0, 0);
        ta.recycle();
        return r;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
