package com.carvo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 22;
    private static final int REQ_IMPORT_CMAP = 23;
    private static final int REQ_LOC = 33;
    private static final int REQ_CAM = 44;

    private static final String[] DAY_NAMES = {
            "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"
    };
    private static final String[] DAY_SHORT = {
            "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
    };
    private static final String[] MOOD_PRE = {
            "\uD83D\uDE34", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE24"
    };
    private static final String[] MOOD_POST = {
            "\uD83D\uDE0A", "\uD83D\uDD25", "\uD83D\uDE0C", "\uD83D\uDC80"
    };

    private static final String[] MOOD_PRE_WORDS = {"SLEEPY", "FLAT", "HAPPY", "FIRED UP"};
    private static final String[] MOOD_POST_WORDS = {"GOOD", "FIRED", "CALM", "DEAD"};

    private RunStore store;
    private SharedPreferences prefs;

    private TextView todayDate, restBanner;
    private TextView recState, recMsg;
    private View recDot;
    private View inputPane, statPane, homeScroll, histScroll, recScroll;
    private TextView bigSpeed, speedTag, miniKm, miniMin, miniPace;
    private TextView[] mPre = new TextView[4], mPost = new TextView[4];
    private BarView bars;
    private LineView paceLine;
    private TextView trendChip;
    private TextView lastScoreVal, scoreStar;
    private View lastScoreCard, pinnedCard;
    private LinearLayout pinnedList;
    private Button saveBtn, editBtn, exportBtn, importBtn, shareBtn;
    private View tabHome, tabHistory, tabRecords, tabTools;
    private ImageView icoHome, icoHistory, icoRun, icoRecords, icoTools;
    private View toolsScroll;
    private LinearLayout toolsList;
    private ToolsUI toolsUI;
    private int curTab = 0;
    private boolean uiResumed = false;
    private LinearLayout histList;
    private TextView histEmpty;
    private TextView recFlame, recStreak, recBest, recTotal, recLong, recPace, recSpeed,
            recAvgPace, recAvgSpeed, recNext, wkThisKm, wkThisRuns, wkLastKm, wkLastRuns,
            wkDkm, wkDr, wkInsight, scoreLatest, scoreBest, scoreAvg, moodText;
    private View moodCard;
    private ScoreStrip scoreStrip;
    private TextView loadChip, backupAge;
    private ImageView catCrow;
    private TextView catRank;
    private String viewDate = null;
    private RunStore viewStore = null;
    private TimeBar tmBar;
    private TextView tmBanner, tmDate;
    private Button tmBack;
    private RadarView radar;
    private TextView radAllBtn, rad14Btn, radSubTxt;
    private boolean radar14d = false;

    // ================= RUN TIMER + CROSITE =================
    private static final int T_RUN = 0, T_TARGET = 1;
    private static final int TS_IDLE = 0, TS_RUN = 1, TS_PAUSE = 2, TS_DONE = 3,
            TS_REST = 4, TS_WARMUP = 5, TS_COOLDOWN = 6;
    private View runScroll, tabRun, runGearWrap, runGearDot;
    private TextView modeRunBtn, modeTimedBtn, tMinus, tPlus, timedMinTxt,
            targetTimeBtn, targetDistBtn, targetUnitTxt;
    private View timedPane, saveResumeRow, wuActionRow, playProgBox, playProgBg, playProgFill;
    private TextView timerText, timerHint, lapInfo, playNextTxt;
    private float toolSwX, toolSwY;
    private boolean toolSwTracking = false, toolSwFired = false;
    private Button btnStart, btnPause, btnSaveRun, btnResume, btnDiscard,
            btnLap, btnLapUndo, btnSkipWu, btnCancelWu;
    private View lapRow;
    private View croRow, croStatusRow, croDot, croLiveRow, batWarnCard;
    private CroMapView croMapView;
    private volatile CroMapPack croMapPack;
    private double[] cachedTrail;
    private long lastTrailReadMs = 0L;
    private TextView croOnBtn, croOffBtn, croStatusTxt, croDistTxt, croPaceTxt,
            croSpmTxt, croTurnTxt, batWarnTxt, batWarnDismiss;
    private LocationManager warmLm;
    private boolean warmingUp = false;
    private boolean pendingCroStart = false;
    private final LocationListener warmListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (loc == null) return;
            float acc = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
            CroSiteEngine.setWarmupFix(MainActivity.this, loc.getLatitude(), loc.getLongitude(),
                    acc, acc > 0 && acc <= CroSiteEngine.MAX_ACC_M);
            renderCroSiteUi();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) { renderCroSiteUi(); }
        @Override public void onProviderDisabled(String p) { renderCroSiteUi(); }
    };
    private long restUntil;
    private final ArrayList<Long> lapMs = new ArrayList<Long>();
    private double[] pendingLaps;
    private SharedPreferences tprefs;
    private int tMode, targetSub, tState, tTimedMin, wuPhase, wuIdx;
    private double targetDistKm = 3.0;
    private long tAccMs, tRunSince, wuEndMs, coolUntil, lastAutoSavedSeen;
    private Handler tHand;
    private final Runnable tTick = new Runnable() {
        @Override
        public void run() {
            syncTimerPrefs();
            if (checkAutoSavedSync()) return;
            if (tState == TS_WARMUP) {
                renderTimer();
                tHand.postDelayed(tTick, 250L);
            } else if (tState == TS_COOLDOWN) {
                if (System.currentTimeMillis() >= coolUntil + 1200L) {
                    syncTimerPrefs();
                    renderTimer();
                    if (tState == TS_IDLE) return;
                }
                renderTimer();
                tHand.postDelayed(tTick, 250L);
            } else if (tState == TS_RUN) {
                if (tMode == T_TARGET && targetSub == 0 && remainMs() <= 0) {
                    tAccMs = tTimedMin * 60000L;
                    tRunSince = 0L;
                    tState = TS_DONE;
                    saveT();
                    if (CroSiteEngine.isEnabled(MainActivity.this)) {
                        CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_PAUSE);
                    }
                    renderTimer();
                    pulse(timerText);
                    return;
                }
                renderTimer();
                tHand.postDelayed(tTick, tMode == T_TARGET && targetSub == 1 ? 350L : 500L);
            } else if (tState == TS_REST) {
                if (System.currentTimeMillis() >= restUntil) {
                    tRunSince = System.currentTimeMillis();
                    tState = TS_RUN;
                    saveT();
                    if (CroSiteEngine.isEnabled(MainActivity.this)) {
                        CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_RESUME);
                    }
                }
                renderTimer();
                tHand.postDelayed(tTick, 250L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("carvo", MODE_PRIVATE);
        setTheme(isAmoled() ? R.style.AppThemeAmoled : R.style.AppTheme);
        store = new RunStore(this);
        setContentView(R.layout.activity_main);

        todayDate = (TextView) findViewById(R.id.todayDate);
        restBanner = (TextView) findViewById(R.id.restBanner);
        recDot = findViewById(R.id.recDot);
        recState = (TextView) findViewById(R.id.recState);
        recMsg = (TextView) findViewById(R.id.recMsg);
        findViewById(R.id.hdrCorvo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                corvoCardDialog();
            }
        });
        findViewById(R.id.recCard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recoveryDialog();
            }
        });
        pressable(findViewById(R.id.hdrCorvo));
        pressable(findViewById(R.id.catCard));
        findViewById(R.id.catCard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFile();
            }
        });
        pressable(findViewById(R.id.recCard));
        inputPane = findViewById(R.id.inputPane);
        statPane = findViewById(R.id.statPane);
        bigSpeed = (TextView) findViewById(R.id.bigSpeed);
        speedTag = (TextView) findViewById(R.id.speedTag);
        miniKm = (TextView) findViewById(R.id.miniKm);
        miniMin = (TextView) findViewById(R.id.miniMin);
        miniPace = (TextView) findViewById(R.id.miniPace);
        int[] preIds = {R.id.moodPre0, R.id.moodPre1, R.id.moodPre2, R.id.moodPre3};
        int[] postIds = {R.id.moodPost0, R.id.moodPost1, R.id.moodPost2, R.id.moodPost3};
        for (int i = 0; i < 4; i++) {
            mPre[i] = (TextView) findViewById(preIds[i]);
            mPost[i] = (TextView) findViewById(postIds[i]);
        }
        bars = (BarView) findViewById(R.id.bars);
        paceLine = (LineView) findViewById(R.id.paceLine);
        trendChip = (TextView) findViewById(R.id.trendChip);
        lastScoreCard = findViewById(R.id.lastScoreCard);
        lastScoreVal = (TextView) findViewById(R.id.lastScoreVal);
        scoreStar = (TextView) findViewById(R.id.scoreStar);
        pinnedCard = findViewById(R.id.pinnedCard);
        pinnedList = (LinearLayout) findViewById(R.id.pinnedList);
        saveBtn = (Button) findViewById(R.id.saveBtn);
        editBtn = (Button) findViewById(R.id.editBtn);
        homeScroll = findViewById(R.id.homeScroll);
        histScroll = findViewById(R.id.histScroll);
        recScroll = findViewById(R.id.recScroll);
        toolsScroll = findViewById(R.id.toolsScroll);
        toolsList = (LinearLayout) findViewById(R.id.toolsList);
        tabHome = findViewById(R.id.tabHome);
        tabHistory = findViewById(R.id.tabHistory);
        tabRecords = findViewById(R.id.tabRecords);
        tabTools = findViewById(R.id.tabTools);
        icoHome = (ImageView) findViewById(R.id.icoHome);
        icoHistory = (ImageView) findViewById(R.id.icoHistory);
        icoRun = (ImageView) findViewById(R.id.icoRun);
        icoRecords = (ImageView) findViewById(R.id.icoRecords);
        icoTools = (ImageView) findViewById(R.id.icoTools);
        toolsUI = new ToolsUI(this, store, prefs, toolsList, new ToolsUI.Host() {
            @Override
            public void onProfileChanged() {
                render();
            }

            @Override
            public boolean hasCameraPerm() {
                if (Build.VERSION.SDK_INT < 23) return true;
                return checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public void requestCameraPerm() {
                if (Build.VERSION.SDK_INT >= 23) {
                    toast("Allow camera access to scan finger pulse");
                    requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAM);
                }
            }
        });
        histList = (LinearLayout) findViewById(R.id.histList);
        histEmpty = (TextView) findViewById(R.id.histEmpty);
        exportBtn = (Button) findViewById(R.id.exportBtn);
        importBtn = (Button) findViewById(R.id.importBtn);
        recFlame = (TextView) findViewById(R.id.recFlame);
        recStreak = (TextView) findViewById(R.id.recStreak);
        recBest = (TextView) findViewById(R.id.recBest);
        recTotal = (TextView) findViewById(R.id.recTotal);
        recLong = (TextView) findViewById(R.id.recLong);
        recPace = (TextView) findViewById(R.id.recPace);
        recSpeed = (TextView) findViewById(R.id.recSpeed);
        recAvgPace = (TextView) findViewById(R.id.recAvgPace);
        recAvgSpeed = (TextView) findViewById(R.id.recAvgSpeed);
        recNext = (TextView) findViewById(R.id.recNext);
        wkThisKm = (TextView) findViewById(R.id.wkThisKm);
        wkThisRuns = (TextView) findViewById(R.id.wkThisRuns);
        wkLastKm = (TextView) findViewById(R.id.wkLastKm);
        wkLastRuns = (TextView) findViewById(R.id.wkLastRuns);
        wkDkm = (TextView) findViewById(R.id.wkDkm);
        wkDr = (TextView) findViewById(R.id.wkDr);
        wkInsight = (TextView) findViewById(R.id.wkInsight);
        scoreLatest = (TextView) findViewById(R.id.scoreLatest);
        scoreBest = (TextView) findViewById(R.id.scoreBest);
        scoreAvg = (TextView) findViewById(R.id.scoreAvg);
        scoreStrip = (ScoreStrip) findViewById(R.id.scoreStrip);
        moodCard = findViewById(R.id.moodCard);
        moodText = (TextView) findViewById(R.id.moodText);
        shareBtn = (Button) findViewById(R.id.shareBtn);
        runScroll = findViewById(R.id.runScroll);
        tabRun = findViewById(R.id.tabRun);
        croRow = findViewById(R.id.croRow);
        croOnBtn = (TextView) findViewById(R.id.croOnBtn);
        croOffBtn = (TextView) findViewById(R.id.croOffBtn);
        croStatusRow = findViewById(R.id.croStatusRow);
        croDot = findViewById(R.id.croDot);
        croStatusTxt = (TextView) findViewById(R.id.croStatusTxt);
        croLiveRow = findViewById(R.id.croLiveRow);
        croDistTxt = (TextView) findViewById(R.id.croDistTxt);
        croPaceTxt = (TextView) findViewById(R.id.croPaceTxt);
        croSpmTxt = (TextView) findViewById(R.id.croSpmTxt);
        croTurnTxt = (TextView) findViewById(R.id.croTurnTxt);
        runGearWrap = findViewById(R.id.runGearWrap);
        runGearDot = findViewById(R.id.runGearDot);
        batWarnCard = findViewById(R.id.batWarnCard);
        batWarnTxt = (TextView) findViewById(R.id.batWarnTxt);
        batWarnDismiss = (TextView) findViewById(R.id.batWarnDismiss);
        croMapView = (CroMapView) findViewById(R.id.croMapView);
        if (croMapView != null) {
            croMapView.setOnExpandListener(new CroMapView.OnExpandListener() {
                @Override
                public void onToggleExpand(boolean exp) {
                    android.view.ViewGroup.LayoutParams lp = croMapView.getLayoutParams();
                    if (lp != null) {
                        lp.height = dp(exp ? 345 : 225);
                        croMapView.setLayoutParams(lp);
                    }
                }
            });
        }
        loadSavedMapAsync();
        warmLm = (LocationManager) getSystemService(LOCATION_SERVICE);
        modeRunBtn = (TextView) findViewById(R.id.modeRunBtn);
        modeTimedBtn = (TextView) findViewById(R.id.modeTimedBtn);
        timedPane = findViewById(R.id.timedPane);
        targetTimeBtn = (TextView) findViewById(R.id.targetTimeBtn);
        targetDistBtn = (TextView) findViewById(R.id.targetDistBtn);
        targetUnitTxt = (TextView) findViewById(R.id.targetUnitTxt);
        tMinus = (TextView) findViewById(R.id.tMinus);
        tPlus = (TextView) findViewById(R.id.tPlus);
        timedMinTxt = (TextView) findViewById(R.id.timedMinTxt);
        timerText = (TextView) findViewById(R.id.timerText);
        playProgBox = findViewById(R.id.playProgBox);
        playProgBg = findViewById(R.id.playProgBg);
        playProgFill = findViewById(R.id.playProgFill);
        playNextTxt = (TextView) findViewById(R.id.playNextTxt);
        timerHint = (TextView) findViewById(R.id.timerHint);
        btnStart = (Button) findViewById(R.id.btnStart);
        wuActionRow = findViewById(R.id.wuActionRow);
        btnSkipWu = (Button) findViewById(R.id.btnSkipWu);
        btnCancelWu = (Button) findViewById(R.id.btnCancelWu);
        btnPause = (Button) findViewById(R.id.btnPause);
        btnSaveRun = (Button) findViewById(R.id.btnSaveRun);
        btnResume = (Button) findViewById(R.id.btnResume);
        btnDiscard = (Button) findViewById(R.id.btnDiscard);
        saveResumeRow = findViewById(R.id.saveResumeRow);
        loadChip = (TextView) findViewById(R.id.loadChip);
        backupAge = (TextView) findViewById(R.id.backupAge);
        catCrow = (ImageView) findViewById(R.id.catCrow);
        catRank = (TextView) findViewById(R.id.catRank);
        tmBanner = (TextView) findViewById(R.id.tmBanner);
        tmDate = (TextView) findViewById(R.id.tmDate);
        tmBack = (Button) findViewById(R.id.tmBack);
        tmBar = (TimeBar) findViewById(R.id.tmBar);
        radar = (RadarView) findViewById(R.id.radar);
        radAllBtn = (TextView) findViewById(R.id.radAllBtn);
        rad14Btn = (TextView) findViewById(R.id.rad14Btn);
        radSubTxt = (TextView) findViewById(R.id.radSubTxt);
        radAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radar14d = false;
                renderRadar();
            }
        });
        rad14Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radar14d = true;
                renderRadar();
            }
        });
        lapRow = findViewById(R.id.lapRow);
        btnLap = (Button) findViewById(R.id.btnLap);
        btnLapUndo = (Button) findViewById(R.id.btnLapUndo);
        lapInfo = (TextView) findViewById(R.id.lapInfo);

        recFlame.setText("\uD83D\uDD25"); // fire
        scoreStar.setText("\u2B50");       // star

        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Double croKm = null;
                if (tState != TS_IDLE && CroSiteEngine.isEnabled(MainActivity.this)) {
                    double ck = CroSiteEngine.readDistKm(MainActivity.this);
                    if (ck >= 0.005) croKm = Math.round(ck * 100.0) / 100.0;
                }
                showDistance(false, croKm);
            }
        });
        editBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDistance(true, null);
            }
        });
        findViewById(R.id.gearBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gearMenu();
            }
        });
        tabHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(0);
            }
        });
        tabHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(1);
            }
        });
        tabRecords.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(2);
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doExport();
            }
        });
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doImport();
            }
        });
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareFlow();
            }
        });
        tabRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(3);
            }
        });
        tabTools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(4);
            }
        });
        croOnBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tState != TS_IDLE) return;
                CroSiteEngine.setEnabled(MainActivity.this, true);
                renderTimer();
                updateWarmup();
                checkBatteryOpt();
            }
        });
        croOffBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tState != TS_IDLE) return;
                CroSiteEngine.setEnabled(MainActivity.this, false);
                if (tMode == T_TARGET && targetSub == 1) {
                    targetSub = 0;
                    saveT();
                    toast("Switched Target to Time mode");
                }
                stopWarmup();
                renderTimer();
                checkBatteryOpt();
            }
        });
        if (runGearWrap != null) {
            runGearWrap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openRunSetupDialog();
                }
            });
        }
        batWarnTxt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    s.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(s);
                } catch (Exception e) {
                    toast("Open App Info > Battery > Unrestricted");
                }
            }
        });
        batWarnDismiss.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("bat_dismissed", true).apply();
                batWarnCard.setVisibility(View.GONE);
            }
        });

        tprefs = getSharedPreferences("carvo_timer", MODE_PRIVATE);
        lastAutoSavedSeen = prefs.getLong("lastAutoSavedSeen", 0L);
        syncTimerPrefs();
        try {
            org.json.JSONArray la = new org.json.JSONArray(tprefs.getString("laps", "[]"));
            for (int i = 0; i < la.length(); i++) lapMs.add(la.getLong(i));
        } catch (Exception ignored) {
        }
        tHand = new Handler();
        modeRunBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(T_RUN);
            }
        });
        modeTimedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(T_TARGET);
            }
        });
        targetTimeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTargetSub(0);
            }
        });
        targetDistBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTargetSub(1);
            }
        });
        tMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepTarget(-1);
            }
        });
        tPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepTarget(1);
            }
        });
        timedMinTxt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTargetPrompt();
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startT();
            }
        });
        btnSkipWu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipWarmup();
            }
        });
        btnCancelWu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelWarmup();
            }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pauseT();
            }
        });
        btnResume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resumeT();
            }
        });
        btnSaveRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePromptT();
            }
        });
        btnDiscard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discardT();
            }
        });
        btnLap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tState == TS_REST) skipRest();
                else lapT();
            }
        });
        btnLapUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoLap();
            }
        });
        tmBar.setListener(new TimeBar.Listener() {
            @Override
            public void onDay(String d) {
                if (d.equals(RunStore.today())) {
                    viewDate = null;
                    viewStore = null;
                } else {
                    viewDate = d;
                    viewStore = d.compareTo(RunStore.today()) < 0 ? store.viewAsOf(d) : null;
                }
                renderRecords();
            }
        });
        tmBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetTimeMachine();
            }
        });
        lastScoreVal.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showScoreBreakdown();
                return true;
            }
        });
        scoreLatest.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showScoreBreakdown();
                return true;
            }
        });

        showTab(0);
        render();
        if (getIntent() != null) {
            int ot = getIntent().getIntExtra("open_tab", -1);
            if (ot >= 0 && ot <= 4) showTab(ot);
            if (getIntent().getBooleanExtra("tile_start", false)) {
                showTab(3);
                if (tState == TS_IDLE) startT();
            }
        }
        SharedPreferences pp = getSharedPreferences("carvo", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !pp.getBoolean("tilePermAsked", false)) {
            pp.edit().putBoolean("tilePermAsked", true).apply();
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Quick Tile needs notification access \u2014 Corvo never sends notifications");
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
            }
        }
        maybeAutoBackup();
        maybeOnboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiResumed = true;
        recoverT();
        updateWarmup();
        checkBatteryOpt();
        CorvoWidget.refresh(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiResumed = false;
        stopWarmup();
        if (toolsUI != null) toolsUI.onTabHidden();
        if (tHand != null) tHand.removeCallbacks(tTick);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i != null) {
            int ot = i.getIntExtra("open_tab", -1);
            if (ot >= 0 && ot <= 4) showTab(ot);
            if (i.getBooleanExtra("tile_start", false)) {
                showTab(3);
                if (tState == TS_IDLE) startT();
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (curTab == 4 && toolsUI != null && toolsUI.isDetailOpen()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    toolSwX = ev.getX();
                    toolSwY = ev.getY();
                    toolSwTracking = true;
                    toolSwFired = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (toolSwTracking && !toolSwFired) {
                        float dx = ev.getX() - toolSwX;
                        float dy = ev.getY() - toolSwY;
                        if (Math.abs(dx) > dp(50) && Math.abs(dx) > Math.abs(dy) * 1.45f) {
                            toolSwFired = true;
                            toolSwTracking = false;
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                            toolsUI.stepTool(dx < 0 ? 1 : -1);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    toolSwTracking = false;
                    if (toolSwFired) {
                        toolSwFired = false;
                        return true;
                    }
                    break;
            }
        } else {
            toolSwTracking = false;
            toolSwFired = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (curTab == 4 && toolsUI != null && toolsUI.handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            boolean locOk = hasLocPerm();
            if (pendingCroStart) {
                pendingCroStart = false;
                if (locOk) {
                    CroSiteEngine.setEnabled(this, true);
                    startT();
                } else {
                    CroSiteEngine.setEnabled(this, false);
                    if (tMode == T_TARGET && targetSub == 1) {
                        targetSub = 0;
                        saveT();
                        toast("No location access \u2014 switched Target to Time");
                    } else {
                        toast("No location access \u2014 running Timer Only");
                    }
                    startTNow(false);
                }
            } else {
                updateWarmup();
                renderTimer();
            }
        } else if (requestCode == REQ_CAM) {
            boolean ok = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (toolsUI != null) toolsUI.onCameraPermResult(ok);
        }
    }

    private boolean isAmoled() {
        return !"classic".equals(prefs.getString("theme", "amoled"));
    }

    // ================= SETTINGS (GEAR) =================

    private void gearMenu() {
        StringBuilder restB = new StringBuilder();
        for (String ds : store.weekRestDates(RunStore.weekMonday(RunStore.today()))) {
            if (restB.length() > 0) restB.append("+");
            restB.append(DAY_SHORT[RunStore.dowOf(ds) - 1]);
        }
        String rest = restB.length() > 0 ? restB.toString() : "NONE";
        String th = isAmoled() ? "AMOLED" : "CLASSIC";
        final String[] items = {
                "REST DAY   \u00B7   " + rest,
                "THEME   \u00B7   " + th,
                "HOW TO USE"
        };
        new AlertDialog.Builder(this)
                .setTitle("SETTINGS")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) restDayDialog();
                        else if (w == 1) themeDialog();
                        else howToUseDialog();
                    }
                }).show();
    }

    private void howToUseDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(6), dp(20), dp(14));

        TextView intro = new TextView(this);
        intro.setText("Every block, hidden tap & engine in Corvo — 100% offline.");
        intro.setTextSize(11);
        intro.setTextColor(0xFF8A94A6);
        list.addView(intro);

        guideSection(list, "HOME TAB");
        guideItem(list, "CORVO HEADER",
                "Tap the top CORVO logo anytime to open Corvo's Identity Card & evolution class.");
        guideItem(list, "THE CROW CARD (THE FILE)",
                "Tap the large Crow art to open THE FILE — your runner dossier (Name, Height, Weight, BMI, Age, City, Codename, Record, Mood Profile & Strongest Day). Tap any row inside to edit all fields in one place, or share a Dossier PNG.");
        guideItem(list, "RECOVERY BRAIN",
                "Tap the recovery strip below the Crow to inspect the 4-bar breakdown (Load, Intensity, Fatigue, Mood). When overloaded, tap REST TODAY to move a rest day to today.");
        guideItem(list, "SAVE RUN & EDIT",
                "Tap SAVE RUN to enter KM then MIN + SEC. Tap EDIT anytime to update or DISCARD today's logged run.");
        guideItem(list, "MOOD CHECK-IN & INSIGHT",
                "Tap an emoji before and after saving a run. After enough runs, MOOD INSIGHT shows how starting mood shifts your /km pace.");
        guideItem(list, "RUN SCORE BREAKDOWN",
                "Long-press the big score number (on Home or BEST) to see the exact weighting: Distance 30% \u00B7 Pace 40% \u00B7 Rhythm 20% \u00B7 Improvement 10%.");
        guideItem(list, "PINNED RUNS",
                "Appears automatically on Home whenever you star milestone runs from LOG.");

        guideSection(list, "LOG TAB (HISTORY & BACKUP)");
        guideItem(list, "TAP ANY RUN FOR DETAILS",
                "Tap any run row to inspect full metrics (Calories, BPM, HRR-60, Cadence, OUT/BACK splits & Laps), Share its Day Card, Pin, or Delete it.");
        guideItem(list, "SWIPE RIGHT TO PIN",
                "Swipe any run row to the right to pin or unpin a star onto your Home screen.");
        guideItem(list, "EXPORT / IMPORT",
                "Saves or restores one offline corvo-backup.json file (runs, laps, BPM, HRR-60, cadence, BOLT, body profile & Pulse Log). Import merges safely without overwriting existing runs.");
        guideItem(list, "SHARE POSTERS",
                "Tap SHARE to generate clean dark PNG cards: RADAR PROFILE, STAT CARD (Week/Month/Year), or DAY CARD (single run + splits + cadence).");

        guideSection(list, "PLAY TAB (TIMER & CROSITE PRO)");
        guideItem(list, "RUN vs TARGET MODE",
                "RUN = open stopwatch with laps (manual pause/save). TARGET = choose TIME countdown or DISTANCE auto-finish (auto-saves exact target KM & run time, then gives a 3..2..1..Done! slow-down voice cue).");
        guideItem(list, "CROSITE GPS PRO",
                "Choose CROSITE GPS for outdoor runs. Tracks live Cadence (SPM), auto-detects highway Turnarounds (OUT vs BACK pace), calibrates your Stride Bridge, and tracks distance with screen locked.");
        guideItem(list, "VOICE COACH, WARM-UP & METRONOME",
                "Tap the top-right gear icon on PLAY to enable VOICE COACH (0.85x pace & km cues), nested WARM-UP (5..4..3..2..1..Go! + EDIT), and cadence METRONOME SPM.");
        guideItem(list, "OFFLINE AREA MAP (.CMAP)",
                "Import an offline .cmap vector map inside \u2699 RUN SETUP (built with Corvo Map Cutter — zero internet). Pinch to zoom, 2-finger rotate, tap \u2922 to expand, and \u25CE / N to re-center on GPS & North.");
        guideItem(list, "LAP & 60s REST",
                "In RUN mode, tap LAP to close a segment and start a 1-minute rest countdown. CroSite pauses during rest and auto-resumes when rest ends (or tap SKIP REST). Tap UNDO to remove the last lap.");
        guideItem(list, "POST-RUN BPM & HRR-60",
                "Right after saving a timed run, measure immediate 15s BPM or run the 60s HRR-60 recovery test to record your 1-minute heart rate drop.");

        guideSection(list, "BEST TAB (RECORDS & TIME MACHINE)");
        guideItem(list, "STREAK BADGES (7 TO 365)",
                "Tap any circle badge (7, 15, 30, 50, 100, 365) to preview its Crow form and days left to unlock.");
        guideItem(list, "5-AXIS RADAR (ALL-TIME / 14D FORM)",
                "Switch between ALL-TIME career shape and 14D FORM recent momentum.");
        guideItem(list, "TIME MACHINE SLIDER",
                "Drag the bottom dot bar left or right to rewind stats & trends to any past day. Tap BACK TO TODAY to return.");

        guideSection(list, "TOOLS TAB (8 OFFLINE LABS)");
        guideItem(list, "SWIPE LEFT / RIGHT BETWEEN LABS",
                "Open any lab and swipe left or right (or tap ‹ / › in the top bar) to jump straight to the next or previous tool.");
        guideItem(list, "CAMERA PULSE PRO (BPM · HRV · HRR-60)",
                "Gently place one fingertip over rear camera lens + flash. Supports 15s Pulse, 25s morning HRV readiness (RMSSD ms feeding Recovery Brain), and guided 60s HRR-60 drop.");
        guideItem(list, "VO2 MAX & 5 PACE ZONES",
                "Computes VO2 Max, Fitness Age, personalized Z1-Z5 training paces, and 1K / 5K / 10K / Half-Marathon race predictions from your best Corvo runs.");
        guideItem(list, "BOLT BREATH LAB & COOLDOWN",
                "Measure morning CO2 tolerance score and follow a guided 4s Inhale \u00B7 6s Exhale post-run recovery pacer.");
        guideItem(list, "STRENGTH, BODY, PACE & HYDRATION",
                "All other labs stay synced two-way with THE FILE. Tap any slider's value (or Target Pace MM:SS) to enter exact numbers.");

        guideSection(list, "SETTINGS & HOME WIDGET");
        guideItem(list, "REST DAYS",
                "Set up to 2 default weekly rest days or move rest days inside THIS WEEK. Rest days protect your streak; runs logged on rest days still save to History.");
        guideItem(list, "THEME & WIDGET",
                "Switch AMOLED / CLASSIC theme here. You can also add Corvo's 1x1 Streak Widget from your phone's launcher widgets screen.");

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("HOW TO USE CORVO")
                .setView(sc)
                .setPositiveButton("GOT IT", null)
                .show();
    }

    private void guideSection(LinearLayout list, String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(10);
        h.setLetterSpacing(0.22f);
        h.setTextColor(0xFF00E5A0);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        h.setLayoutParams(lp);
        list.addView(h);
    }

    private void guideItem(LinearLayout list, String name, String desc) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12);
        t.setTextColor(0xFFF2F5FA);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        t.setLayoutParams(tlp);
        list.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        d.setLayoutParams(dlp);
        list.addView(d);
    }

    private void restDayDialog() {
        final String today = RunStore.today();
        final java.util.ArrayList<String> sel =
                new java.util.ArrayList<String>(store.weekRestDates(RunStore.weekMonday(today)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(8), dp(18), dp(2));

        list.addView(tinyLabel("THIS WEEK", 0));
        String wd = RunStore.weekMonday(today);
        for (int i = 0; i < 7; i++) {
            final String ds = wd;
            boolean past = ds.compareTo(today) < 0;
            TextView row = new TextView(this);
            String txt = DAY_SHORT[RunStore.dowOf(ds) - 1] + "  \u00B7  "
                    + RunStore.shortDate(ds).toUpperCase(Locale.US);
            if (ds.equals(today)) txt += "  \u00B7  TODAY";
            row.setText(txt);
            row.setTextSize(12);
            row.setLetterSpacing(0.1f);
            row.setGravity(Gravity.CENTER);
            row.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            styleRestPill(row, sel.contains(ds));
            row.setPadding(0, dp(10), 0, dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(rp);
            if (past) {
                row.setAlpha(0.45f); // PAST LOCKED — badal nahi sakte
            } else {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean on = sel.contains(ds);
                        if (!on && sel.size() >= 2) {
                            toast("MAX 2 REST DAYS PER WEEK");
                            return;
                        }
                        if (on) sel.remove(ds);
                        else sel.add(ds);
                        styleRestPill((TextView) v, !on);
                    }
                });
            }
            list.addView(row);
            wd = RunStore.dateShift(wd, 1);
        }

        list.addView(tinyLabel("EVERY WEEK (DEFAULT)", dp(14)));
        final boolean[] defSel = new boolean[7];
        int dm = store.restDefaultsMask();
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 7; i++) {
            defSel[i] = ((1 << (i + 1)) & dm) != 0;
            final int idx = i;
            final TextView chip = new TextView(this);
            chip.setText(DAY_SHORT[i]);
            chip.setTextSize(11);
            chip.setLetterSpacing(0.08f);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setPadding(0, dp(9), 0, dp(9));
            styleRestPill(chip, defSel[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!defSel[idx]) {
                        int cnt = 0;
                        for (boolean b : defSel) if (b) cnt++;
                        if (cnt >= 2) {
                            toast("MAX 2 DEFAULT DAYS");
                            return;
                        }
                        defSel[idx] = true;
                    } else {
                        defSel[idx] = false;
                    }
                    styleRestPill(chip, defSel[idx]);
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            chips.addView(chip, cp);
        }
        list.addView(chips);

        TextView note = tinyLabel("MAX 2 PER WEEK \u00B7 PAST DAYS LOCKED \u00B7 MOVES = THIS WEEK ONLY", dp(14));
        note.setTextSize(9);
        list.addView(note);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("REST DAYS")
                .setView(scroll)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int m = 0;
                        for (int i = 0; i < 7; i++) if (defSel[i]) m |= (1 << (i + 1));
                        store.setRestDefaults(m);
                        store.setRestWeek(sel);
                        toast("Rest days saved \u2713");
                        render();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void styleRestPill(TextView v, boolean on) {
        v.setBackgroundResource(on ? R.drawable.badge_on : R.drawable.badge_off);
        v.setTextColor(on ? 0xFF00E5A0 : 0xFF4A5268);
    }

    private TextView tinyLabel(String s, int topMarginDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(10);
        t.setLetterSpacing(0.2f);
        t.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMarginDp, 0, dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    // ================= DOST ONBOARDING (sirf Dost builds — first launch identity setup) =================

    private void maybeOnboard() {
        if (!Flavor.DOST || prefs.getBoolean("onboarded", false)
                || safeStr("userName").length() > 0) return;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView t = new TextView(this);
        t.setText("CORVO IS WATCHING.");
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setLetterSpacing(0.2f);
        t.setTextColor(0xFF00E5A0);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        wrap.addView(t);

        TextView m = new TextView(this);
        m.setText("Set up THE FILE so calories, BMI & CroSite Stride Bridge fit you. Optional fields can be left blank.");
        m.setGravity(Gravity.CENTER);
        m.setTextSize(10);
        m.setLetterSpacing(0.05f);
        m.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(6);
        mlp.bottomMargin = dp(12);
        m.setLayoutParams(mlp);
        wrap.addView(m);

        final EditText eName = fileField("NAME (DEFAULT: RUNNER)");
        eName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        wrap.addView(eName);

        final EditText eH = fileField("HEIGHT \u00B7 CM (OPTIONAL)");
        eH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        wrap.addView(eH);

        final EditText eW = fileField("WEIGHT \u00B7 KG (OPTIONAL)");
        eW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        wrap.addView(eW);

        final EditText eDob = fileField("DOB \u00B7 DD-MM-YYYY (OPTIONAL)");
        eDob.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        attachDobDash(eDob);
        wrap.addView(eDob);

        final EditText eCity = fileField("CITY (OPTIONAL)");
        eCity.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        wrap.addView(eCity);

        EditText[] order = {eName, eH, eW, eDob, eCity};
        for (int i = 0; i < order.length; i++) {
            order[i].setImeOptions(i == order.length - 1
                    ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        }

        TextView hint = new TextView(this);
        hint.setText("Tip: Tap the Crow on HOME anytime to open THE FILE, or open Settings \u2192 HOW TO USE.");
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(10);
        hint.setTextColor(0xFF66708A);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        hint.setLayoutParams(hlp);
        wrap.addView(hint);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(wrap);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("WELCOME RUNNER")
                .setView(sc)
                .setCancelable(false)
                .setPositiveButton("BEGIN", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float h = 0f, wt = 0f;
                String hs = eH.getText().toString().trim();
                String ws = eW.getText().toString().trim();
                try {
                    if (hs.length() > 0) h = Float.parseFloat(hs);
                    if (ws.length() > 0) wt = Float.parseFloat(ws);
                } catch (Exception e) {
                    toast("Height/Weight: numbers only");
                    return;
                }
                String db = eDob.getText().toString().trim();
                if (db.length() > 0) {
                    java.text.SimpleDateFormat f =
                            new java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US);
                    f.setLenient(false);
                    try {
                        f.parse(db);
                    } catch (Exception e) {
                        toast("Check DOB \u00B7 DD-MM-YYYY (or clear it)");
                        return;
                    }
                }
                String n = eName.getText().toString().trim().toUpperCase(Locale.US);
                if (n.isEmpty()) n = "RUNNER";
                String ct = eCity.getText().toString().trim().toUpperCase(Locale.US);

                SharedPreferences.Editor ed = prefs.edit()
                        .putString("userName", n)
                        .putBoolean("onboarded", true);
                if (h > 0f) ed.putFloat("body_h", h);
                if (wt > 0f) ed.putFloat("body_w", wt);
                if (db.length() > 0) ed.putString("dob", db);
                if (ct.length() > 0) ed.putString("city", ct);
                ed.apply();

                dlg.dismiss();
                toast("Corvo remembers \u2713");
                render();
            }
        });
        eName.requestFocus();
    }

    // ================= RECOVERY BRAIN =================

    private void renderRecovery() {
        RunStore.Run todayRun = store.get(RunStore.today());
        int color;
        String state, msg;
        if (todayRun != null) {
            color = 0xFF00B4D8;
            state = "RECOVERY \u00B7 DONE FOR TODAY";
            msg = "Run logged \u2014 hydrate, rest & recharge till tomorrow";
        } else {
            RunStore.Recovery r = store.recovery();
            if (r.verdict == 4) {
                color = 0xFF8A94A6;
                state = "RECOVERY \u00B7 LEARNING";
                msg = "He is still learning who you are";
            } else if (r.verdict == 0) {
                color = 0xFF00E5A0;
                state = "RECOVERY \u00B7 PRIMED";
                msg = "Load balanced, mood up \u2014 push day";
            } else if (r.verdict == 2) {
                color = 0xFFFFB454;
                state = "RECOVERY \u00B7 CAUTION";
                msg = "Body is loaded \u2014 keep today easy and short";
            } else if (r.verdict == 3) {
                color = 0xFFFF5C5C;
                state = "RECOVERY \u00B7 REST CALL";
                msg = r.spike
                        ? "Yesterday was big \u2014 rest today, streak stays safe"
                        : "Body is tired \u2014 rest today, streak stays safe";
            } else {
                color = 0xFF8A94A6;
                state = "RECOVERY \u00B7 STEADY";
                msg = r.hasData ? "All normal \u2014 regular run" : "Need a few more runs to read you";
            }
        }
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        recDot.setBackground(gd);
        recState.setText(state);
        recState.setTextColor(color);
        recMsg.setText(msg);
    }

    private void recoveryDialog() {
        RunStore.Run todayRun = store.get(RunStore.today());
        RunStore.Recovery r = store.recovery();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(8), dp(18), dp(2));

        String[] VN = {"PRIMED", "STEADY", "CAUTION", "REST CALL", "LEARNING"};
        int[] VC = {0xFF00E5A0, 0xFF8A94A6, 0xFFFFB454, 0xFFFF5C5C, 0xFF8A94A6};
        TextView vd = new TextView(this);
        if (todayRun != null) {
            vd.setText("DONE FOR TODAY");
            vd.setTextColor(0xFF00B4D8);
        } else {
            vd.setText(VN[r.verdict]);
            vd.setTextColor(VC[r.verdict]);
        }
        vd.setTextSize(22);
        vd.setLetterSpacing(0.18f);
        vd.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        list.addView(vd);
        if (todayRun != null) {
            TextView sub = new TextView(this);
            sub.setText("Run logged today \u2014 fresh recovery call unlocks tomorrow");
            sub.setTextSize(11);
            sub.setTextColor(0xFF8A94A6);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(4);
            list.addView(sub, slp);
        }

        int loadLvl = 0, loadCol = 0xFF8A94A6;
        String loadNote = "NOT ENOUGH DATA YET";
        if (r.hasData && r.load > 0) {
            if (r.load < 0.8) {
                loadLvl = 3;
                loadNote = String.format(Locale.US, "LIGHT WEEK \u00B7 %.2fx", r.load);
            } else if (r.load <= 1.3) {
                loadLvl = 5;
                loadCol = 0xFF00E5A0;
                loadNote = String.format(Locale.US, "BALANCED \u00B7 %.2fx", r.load);
            } else if (r.load <= 1.5) {
                loadLvl = 8;
                loadCol = 0xFFFFB454;
                loadNote = String.format(Locale.US, "HIGH \u00B7 %.2fx", r.load);
            } else {
                loadLvl = 10;
                loadCol = 0xFFFF5C5C;
                loadNote = String.format(Locale.US, "OVERLOAD \u00B7 %.2fx", r.load);
            }
        }
        addBar(list, "LOAD", loadLvl, loadCol, loadNote + " \u2014 THIS WEEK VS LAST WEEK");

        int intLvl = 3, intCol = 0xFF00E5A0;
        String intNote = "STEADY PACE";
        if (r.hardStreak == 1) {
            intLvl = 6;
            intCol = 0xFFFFB454;
            intNote = "HARD SESSION \u00D71";
        } else if (r.hardStreak >= 2) {
            intLvl = 9;
            intCol = 0xFFFF5C5C;
            intNote = "HARD SESSIONS \u00D7" + r.hardStreak;
        }
        addBar(list, "INTENSITY", intLvl, intCol, intNote + " \u2014 LAST RUN VS 14-DAY PACE");

        int ftgLvl, ftgCol;
        String ftgNote;
        if (r.runDays >= 6) {
            ftgLvl = 10;
            ftgCol = 0xFFFF5C5C;
            ftgNote = r.runDays + " RUN DAYS IN A ROW";
        } else if (r.runDays >= 4) {
            ftgLvl = 7;
            ftgCol = 0xFFFFB454;
            ftgNote = r.runDays + " RUN DAYS IN A ROW";
        } else if (r.runDays >= 1) {
            ftgLvl = 4;
            ftgCol = 0xFF00E5A0;
            ftgNote = r.runDays + " RUN DAYS IN A ROW";
        } else {
            ftgLvl = 0;
            ftgCol = 0xFF8A94A6;
            ftgNote = "NO CONSECUTIVE RUNS";
        }
        addBar(list, "FATIGUE", ftgLvl, ftgCol, ftgNote);

        int mdLvl = 5, mdCol = 0xFF8A94A6;
        String mdNote = "STEADY";
        if (r.moodTrend > 0) {
            mdLvl = 3;
            mdCol = 0xFF00E5A0;
            mdNote = "RISING \u2014 RUNS FEEL GOOD";
        } else if (r.moodTrend < 0) {
            mdLvl = 9;
            mdCol = 0xFFFF5C5C;
            mdNote = "FALLING \u2014 BODY STRESS SIGNAL";
        }
        addBar(list, "MOOD", mdLvl, mdCol, mdNote + " \u2014 BEFORE VS AFTER RUN");

        double hrvToday = PulseEngine.latestHrvForDate(prefs, RunStore.today());
        double hrvBase = PulseEngine.baselineHrv(prefs);
        if (hrvToday >= 5.0 && hrvBase >= 15.0) {
            double pct = (hrvToday - hrvBase) / hrvBase;
            int hLvl = pct <= -0.25 ? 9 : (pct <= -0.10 ? 6 : 2);
            int hCol = pct <= -0.25 ? 0xFFFF5C5C : (pct <= -0.10 ? 0xFFFFB454 : 0xFF00E5A0);
            String hNote = String.format(Locale.US, "%.0f MS TODAY VS %.0f MS AVG%s",
                    hrvToday, hrvBase, pct <= -0.25 ? " \u2014 RECOVERY NEEDED" : "");
            addBar(list, "HRV", hLvl, hCol, hNote);
        }

        if (todayRun == null && r.verdict == 3) {
            String today = RunStore.today();
            if (store.isRestDay(today)) {
                list.addView(tinyLabel("TODAY IS ALREADY A REST DAY", dp(12)));
            } else {
                java.util.ArrayList<String> sel = store.weekRestDates(RunStore.weekMonday(today));
                boolean fut = false;
                for (String d : sel) {
                    if (d.compareTo(today) > 0) fut = true;
                }
                if (sel.size() >= 2 && !fut) {
                    list.addView(tinyLabel("THIS WEEK'S 2 RESTS ARE ALREADY USED", dp(12)));
                } else {
                    Button b = new Button(this);
                    b.setText("REST TODAY");
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    blp.topMargin = dp(14);
                    b.setLayoutParams(blp);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            restNow();
                        }
                    });
                    list.addView(b);
                }
            }
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("RECOVERY BRAIN")
                .setView(sc)
                .setPositiveButton("CLOSE", null)
                .show();
    }

    private void addBar(LinearLayout list, String name, int level, int color, String note) {
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(10);
        n.setLetterSpacing(0.2f);
        n.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(12);
        n.setLayoutParams(nlp);
        list.addView(n);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(10f);
        track.setBackgroundResource(R.drawable.badge_off);
        int lvl = Math.max(0, Math.min(10, level));
        View fill = new View(this);
        fill.setBackgroundColor(color);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, lvl));
        View spc = new View(this);
        track.addView(spc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 10 - lvl));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        tlp.topMargin = dp(5);
        track.setLayoutParams(tlp);
        list.addView(track);
        TextView t = new TextView(this);
        t.setText(note);
        t.setTextSize(9);
        t.setLetterSpacing(0.12f);
        t.setTextColor(0xFF66708A);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp2.topMargin = dp(4);
        t.setLayoutParams(tlp2);
        list.addView(t);
    }

    private void restNow() {
        String today = RunStore.today();
        if (store.isRestDay(today)) {
            toast("Today is already a rest day");
            return;
        }
        java.util.ArrayList<String> sel = store.weekRestDates(RunStore.weekMonday(today));
        java.util.ArrayList<String> ns = new java.util.ArrayList<String>(sel);
        while (ns.size() >= 2) {
            String fut = null;
            for (String d : ns) {
                if (d.compareTo(today) > 0) { fut = d; break; }
            }
            if (fut == null) break;
            ns.remove(fut);
        }
        if (ns.size() >= 2) {
            toast("This week's 2 rests are already used");
            return;
        }
        if (!ns.contains(today)) ns.add(today);
        store.setRestWeek(ns);
        toast("Rest moved to today \u2014 streak safe \u2713");
        render();
    }

    // ================= CORVO IDENTITY CARD =================

    private void corvoCardDialog() {
        int idx = rankIndex(store.currentStreak());
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(4), dp(20), dp(8));

        ImageView crow = new ImageView(this);
        crow.setImageResource(rankArt(idx));
        crow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        list.addView(crow);

        idRow(list, "NAME", "CORVO");
        idRow(list, "SPECIES", "CORVUS \u2014 THE RUNNER'S CROW");
        idRow(list, "CLASS", RANK_NAMES[idx]);
        idRow(list, "BONDED TO", displayName());
        idRow(list, "ORIGIN", "BORN IN THE DARK. RAISED BY THE ROAD.");
        idRow(list, "MISSION", "RUN. REMEMBER. EVOLVE.");

        list.addView(tinyLabel("SKILLS", dp(16)));
        skillRow(list, "STREAK MEMORY", "Every run is logged. Every kilometre remembered.");
        skillRow(list, "REST WISDOM", "Two rest moves a week. The streak never breaks.");
        skillRow(list, "RECOVERY BRAIN", "Reads your load, pace, fatigue and mood \u2014 then speaks.");
        skillRow(list, "EVOLUTION", "Six forms in 365 days. Couch to Legend.");
        skillRow(list, "OFFLINE SOUL", "No network. No account. Just you and him.");

        TextView q = new TextView(this);
        q.setText("RUN HAPPENS IN THE DARK.\nPOWER COMES IN THE DARK.");
        q.setGravity(Gravity.CENTER);
        q.setTextSize(11);
        q.setLetterSpacing(0.15f);
        q.setTextColor(0xFF00E5A0);
        q.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(18);
        q.setLayoutParams(qlp);
        list.addView(q);

        TextView w = new TextView(this);
        w.setText("HE IS WATCHING.");
        w.setGravity(Gravity.CENTER);
        w.setTextSize(9);
        w.setLetterSpacing(0.3f);
        w.setTextColor(0xFF4A5268);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        w.setLayoutParams(wlp);
        list.addView(w);

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("CORVO \u00B7 IDENTITY")
                .setView(sc)
                .setPositiveButton("CLOSE", null)
                .show();
    }

    private void idRow(LinearLayout list, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextSize(9);
        a.setLetterSpacing(0.2f);
        a.setTextColor(0xFF66708A);
        a.setWidth(dp(86));
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextSize(12);
        b.setTextColor(0xFFF2F5FA);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(a);
        row.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        row.setLayoutParams(lp);
        list.addView(row);
    }

    private void skillRow(LinearLayout list, String k, String v) {
        TextView a = new TextView(this);
        a.setText("\u25CF  " + k);
        a.setTextSize(11);
        a.setLetterSpacing(0.1f);
        a.setTextColor(0xFF00E5A0);
        a.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        a.setLayoutParams(alp);
        list.addView(a);
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextSize(10);
        b.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(2);
        blp.leftMargin = dp(14);
        b.setLayoutParams(blp);
        list.addView(b);
    }

    // ================= RUNNER FILE — THE FILE (v3.7) =================

    private String displayName() {
        String n = prefs.getString("userName", "");
        return n.length() > 0 ? n : "[REDACTED]";
    }

    private String fileStatus() {
        if (store.get(RunStore.today()) != null) return "RECHARGING";
        RunStore.Recovery r = store.recovery();
        if (r.verdict == 3) return "RECOVERING";
        if (r.verdict == 2) return "WATCHED";
        if (r.verdict == 4) return "LEARNING";
        return "ACTIVE";
    }

    /** Crow-given codename — fixed by his order: COLD BLOOD. */
    private String codename() {
        return "COLD BLOOD";
    }

    private LinearLayout fileList; // THE FILE ka live content (null = band)

    private void showFile() {
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(dp(20), dp(4), dp(20), dp(8));
        buildFileRows();
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(fileList);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("THE FILE")
                .setView(sc)
                .setPositiveButton("CLOSE", null)
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                fileList = null;
            }
        });
        dlg.show();
    }

    /** THE FILE ka poora content — har save ke baad andar hi rebuild (real-time). */
    private void buildFileRows() {
        if (fileList == null) return;
        fileList.removeAllViews();
        int idx = rankIndex(store.currentStreak());

        ImageView crow = new ImageView(this);
        crow.setImageResource(rankArt(idx));
        crow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        crow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        fileList.addView(crow);

        TextView cd = new TextView(this);
        cd.setText("\u201CCOLD BLOOD\u201D");
        cd.setGravity(Gravity.CENTER);
        cd.setTextSize(20);
        cd.setLetterSpacing(0.12f);
        cd.setTextColor(0xFF00E5A0);
        cd.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fileList.addView(cd);
        fileList.addView(tinyLabel("CODENAME \u00B7 GIVEN BY THE CROW", dp(2)));

        float bh = safeFloat("body_h"), bw = safeFloat("body_w");
        String dob = safeStr("dob");
        String city = safeStr("city");
        String dobShow = dob.length() > 0
                ? normalizeDob(dob) + " \u00B7 AGE " + ageFrom(dob) : "REDACTED";
        idRow(fileList, "NAME", displayName(), "NAME");
        idRow(fileList, "BODY", bh > 0 && bw > 0
                ? String.format(Locale.US, "%.0f CM \u00B7 %.0f KG", bh, bw)
                : "REDACTED", "BODY");
        if (bh > 0 && bw > 0) {
            double bmi = bw / Math.pow(bh / 100.0, 2.0);
            String cat = bmi < 18.5 ? "UNDERWEIGHT" : bmi < 25 ? "NORMAL"
                    : bmi < 30 ? "OVERWEIGHT" : "OBESE";
            double lo = 18.5 * Math.pow(bh / 100.0, 2.0), hi = 24.9 * Math.pow(bh / 100.0, 2.0);
            fileList.addView(tinyLabel(String.format(Locale.US,
                    "BMI %.1f \u00B7 %s \u00B7 HEALTHY %.0f\u2013%.0f KG", bmi, cat, lo, hi), dp(3)));
        }
        idRow(fileList, "DOB", dobShow, "DOB");
        idRow(fileList, "CITY", city.length() > 0 ? city : "REDACTED", "CITY");

        String first = store.firstRunDate();
        idRow(fileList, "RANK", RANK_NAMES[idx], null);
        idRow(fileList, "BONDED SINCE", first != null ? RunStore.pretty(first) : "NOT YET", null);
        idRow(fileList, "STATUS", fileStatus(), null);

        fileList.addView(tinyLabel("THE RECORD", dp(16)));
        idRow(fileList, "DISTANCE", String.format(Locale.US, "%.1f KM", store.totalKm()), null);
        idRow(fileList, "RUNS", String.valueOf(store.count()), null);
        idRow(fileList, "TIME ON ROAD", String.format(Locale.US, "%.1f HRS", store.totalMin() / 60.0), null);
        idRow(fileList, "STREAK", store.currentStreak() + " \u00B7 BEST " + store.bestStreak(), null);
        idRow(fileList, "AVG PACE", store.avgPace() > 0 ? paceText(store.avgPace()) : "\u2014", null);
        idRow(fileList, "LONGEST RUN", store.longest() > 0
                ? String.format(Locale.US, "%.2f KM", store.longest()) : "\u2014", null);
        double fl = store.fastestLapEver();
        if (fl > 0) idRow(fileList, "FASTEST LAP", clockText(fl), null);
        int lift = store.moodLiftPct();
        idRow(fileList, "MOOD PROFILE", lift >= 0
                ? "MOOD RISES AFTER " + lift + "% OF RUNS" : "NOT ENOUGH DATA", null);
        String sd = store.strongestDay();
        idRow(fileList, "STRONGEST DAY", sd != null ? sd : "NOT ENOUGH DATA", null);

        fileList.addView(tinyLabel("TAP A FIELD TO EDIT IT", dp(14)));

        Button share = new Button(this);
        share.setText("SHARE DOSSIER");
        share.setBackgroundResource(R.drawable.btn_primary);
        share.setStateListAnimator(null);
        share.setTextColor(0xFF07121C);
        share.setTextSize(13);
        share.setLetterSpacing(0.12f);
        share.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        slp.topMargin = dp(12);
        share.setLayoutParams(slp);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareDossier();
            }
        });
        fileList.addView(share);

        TextView w = new TextView(this);
        w.setText("HE IS WATCHING.");
        w.setGravity(Gravity.CENTER);
        w.setTextSize(9);
        w.setLetterSpacing(0.3f);
        w.setTextColor(0xFF4A5268);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(14);
        w.setLayoutParams(wlp);
        fileList.addView(w);
    }

    private void idRow(LinearLayout list, String k, String v, final String focus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextSize(9);
        a.setLetterSpacing(0.2f);
        a.setTextColor(0xFF66708A);
        a.setWidth(dp(120));
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextSize(12);
        b.setTextColor(focus != null ? 0xFFF2F5FA : 0xFF8A94A6);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(a);
        row.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        row.setLayoutParams(lp);
        if (focus != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v2) {
                    editFile(focus);
                }
            });
        }
        list.addView(row);
    }

    /** Ek hi editor — saare fields, NEXT se travel, ek SAVE. focus = field key. */
    private void editFile(final String focus) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(4), dp(20), 0);

        final EditText eName = fileField("NAME");
        eName.setText(safeStr("userName"));
        eName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        wrap.addView(eName);

        final EditText eH = fileField("HEIGHT \u00B7 CM");
        eH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        eH.setText(safeFloat("body_h") > 0
                ? String.valueOf((int) safeFloat("body_h")) : "");
        wrap.addView(eH);

        final EditText eW = fileField("WEIGHT \u00B7 KG");
        eW.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        eW.setText(safeFloat("body_w") > 0
                ? String.valueOf((int) safeFloat("body_w")) : "");
        wrap.addView(eW);

        final EditText eDob = fileField("DOB \u00B7 DD-MM-YYYY");
        eDob.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        String dob = safeStr("dob");
        eDob.setText(dob.length() > 0 ? normalizeDob(dob) : "");
        attachDobDash(eDob);
        wrap.addView(eDob);

        final EditText eCity = fileField("CITY");
        eCity.setText(prefs.getString("city", ""));
        eCity.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        wrap.addView(eCity);

        EditText[] order = {eName, eH, eW, eDob, eCity};
        for (int i = 0; i < order.length; i++) {
            order[i].setImeOptions(i == order.length - 1
                    ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        }
        if ("NAME".equals(focus)) eName.requestFocus();
        else if ("BODY".equals(focus)) eH.requestFocus();
        else if ("DOB".equals(focus)) eDob.requestFocus();
        else if ("CITY".equals(focus)) eCity.requestFocus();

        new AlertDialog.Builder(this)
                .setTitle("EDIT FILE")
                .setView(wrap)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w2) {
                        float h = 0f, wt = 0f;
                        String hs = eH.getText().toString().trim();
                        String ws = eW.getText().toString().trim();
                        try {
                            if (hs.length() > 0) h = Float.parseFloat(hs);
                            if (ws.length() > 0) wt = Float.parseFloat(ws);
                        } catch (Exception e) {
                            toast("Numbers only");
                            return;
                        }
                        String db = eDob.getText().toString().trim();
                        if (db.length() > 0) {
                            java.text.SimpleDateFormat f =
                                    new java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US);
                            f.setLenient(false);
                            try {
                                f.parse(db);
                            } catch (Exception e) {
                                toast("Check the date \u00B7 DD-MM-YYYY");
                                return;
                            }
                        }
                        prefs.edit()
                                .putString("userName", eName.getText().toString().trim().toUpperCase(Locale.US))
                                .putFloat("body_h", h)
                                .putFloat("body_w", wt)
                                .putString("dob", db)
                                .putString("city", eCity.getText().toString().trim().toUpperCase(Locale.US))
                                .apply();
                        toast("The file is updated \u2713");
                        if (fileList != null) buildFileRows(); // aankho ke saamne update
                        render();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private float safeFloat(String k) {
        try { return prefs.getFloat(k, 0f); } catch (Exception e) { return 0f; }
    }

    private String safeStr(String k) {
        try { return prefs.getString(k, ""); } catch (Exception e) { return ""; }
    }

    private EditText fileField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        return e;
    }

    private void attachDobDash(final EditText eDob) {
        eDob.addTextChangedListener(new android.text.TextWatcher() {
            boolean busy = false;
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                if (busy) return;
                busy = true;
                try {
                    String raw = s.toString();
                    int rawSel = eDob.getSelectionEnd();
                    int curPos = rawSel < 0 ? raw.length() : Math.min(rawSel, raw.length());
                    int digitsBefore = 0;
                    for (int i = 0; i < curPos; i++) {
                        char ch = raw.charAt(i);
                        if (ch >= '0' && ch <= '9') digitsBefore++;
                    }
                    String d = raw.replaceAll("[^0-9]", "");
                    if (d.length() > 8) d = d.substring(0, 8);
                    StringBuilder o = new StringBuilder();
                    for (int i = 0; i < d.length(); i++) {
                        if (i == 2 || i == 4) o.append('-');
                        o.append(d.charAt(i));
                    }
                    String formatted = o.toString();
                    if (!formatted.equals(raw)) {
                        int sel = 0;
                        if (digitsBefore > 0) {
                            int seen = 0;
                            sel = formatted.length();
                            for (int i = 0; i < formatted.length(); i++) {
                                char ch = formatted.charAt(i);
                                if (ch >= '0' && ch <= '9') {
                                    seen++;
                                    if (seen == digitsBefore) {
                                        sel = i + 1;
                                        break;
                                    }
                                }
                            }
                        }
                        eDob.setText(formatted);
                        eDob.setSelection(Math.max(0, Math.min(sel, formatted.length())));
                    }
                } catch (Exception e) { }
                busy = false;
            }
        });
    }

    /** Purana yyyy-MM-dd naya DD-MM-YYYY me; naya waise hi. */
    private String normalizeDob(String dob) {
        if (dob.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return dob.substring(8, 10) + "-" + dob.substring(5, 7) + "-" + dob.substring(0, 4);
        }
        return dob;
    }

    private int ageFrom(String dob) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US);
            f.setLenient(false);
            Calendar b = Calendar.getInstance();
            b.setTime(f.parse(normalizeDob(dob)));
            Calendar n = Calendar.getInstance();
            int a = n.get(Calendar.YEAR) - b.get(Calendar.YEAR);
            if (n.get(Calendar.DAY_OF_YEAR) < b.get(Calendar.DAY_OF_YEAR)) a--;
            return Math.max(0, a);
        } catch (Exception e) {
            return 0;
        }
    }

    // ================= LIVE TOUCH =================

    private void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v2, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    v2.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                } else if (e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    v2.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                return false;
            }
        });
    }

    private void countUp(final TextView v, final double val, final int dec, final String suffix) {
        Double last = (Double) v.getTag();
        if (last != null && last.doubleValue() == val) return;
        v.setTag(Double.valueOf(val));
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(450);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator an) {
                float f = (Float) an.getAnimatedValue();
                if (dec == 0) {
                    v.setText(String.format(Locale.US, "%d%s", (long) (val * f), suffix));
                } else {
                    v.setText(String.format(Locale.US, "%." + dec + "f%s", val * f, suffix));
                }
            }
        });
        a.start();
    }

    private void themeDialog() {
        final String[] items = {"AMOLED   \u00B7   pure black", "CLASSIC   \u00B7   dark blue"};
        int cur = isAmoled() ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("THEME")
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("theme", w == 0 ? "amoled" : "classic").apply();
                        d.dismiss();
                        recreate();
                    }
                }).show();
    }

    // ================= TABS (5-ICON DOCK) =================

    private void showTab(int i) {
        curTab = i;
        homeScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        histScroll.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        recScroll.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        runScroll.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        toolsScroll.setVisibility(i == 4 ? View.VISIBLE : View.GONE);

        View[] tabs = {tabHome, tabHistory, tabRecords, tabTools};
        ImageView[] icons = {icoHome, icoHistory, icoRecords, icoTools};
        int[] mappedIdx = {0, 1, 2, 4};
        for (int t = 0; t < tabs.length; t++) {
            boolean on = mappedIdx[t] == i;
            tabs[t].setBackgroundResource(on ? R.drawable.tab_active : 0);
            icons[t].setColorFilter(on ? 0xFF00E5A0 : 0xFF66708A);
        }
        boolean runOn = (i == 3);
        tabRun.setBackgroundResource(runOn ? R.drawable.nav_play_on : R.drawable.nav_play_off);
        icoRun.setColorFilter(runOn ? 0xFF07121C : 0xFF00E5A0);

        View content = i == 0 ? homeScroll
                : (i == 1 ? histScroll : (i == 2 ? recScroll : (i == 3 ? runScroll : toolsScroll)));
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(120L);
        content.startAnimation(a);

        if (i == 4 && toolsUI != null) toolsUI.render();
        else if (toolsUI != null) toolsUI.onTabHidden();

        if (i == 3) {
            renderTimer();
            updateWarmup();
            checkBatteryOpt();
        } else {
            stopWarmup();
        }
    }

    // ================= SAVE RUN FLOW =================

    private void showDistance(final boolean edit, final Double kmValue) {
        showDistance(edit, kmValue, null);
    }

    private void showDistance(final boolean edit, final Double kmValue, final Double timeOverride) {
        View dv = getLayoutInflater().inflate(R.layout.dialog_distance, null);
        TextView dh = (TextView) dv.findViewById(R.id.dlgHelper);
        if (timeOverride != null) {
            dh.setText("TIME " + clockText(timeOverride) + (kmValue != null
                    ? " \u00B7 CROSITE PREFILLED" : " \u00B7 FROM TIMER"));
        } else if (kmValue != null && !edit) {
            dh.setText("CROSITE PREFILLED \u00B7 EDIT IF NEEDED");
        }
        final EditText et = (EditText) dv.findViewById(R.id.dlgKm);
        LinearLayout chipRow = (LinearLayout) dv.findViewById(R.id.chipRow);
        double[] chips = store.recentKm(3);
        int[] ids = {R.id.chip0, R.id.chip1, R.id.chip2};
        for (int i = 0; i < 3; i++) {
            TextView ch = (TextView) dv.findViewById(ids[i]);
            if (i < chips.length) {
                final double v = chips[i];
                ch.setText(fmtKm(v) + " km");
                ch.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        et.setText(fmtKm(v));
                        et.setSelection(et.getText().length());
                    }
                });
            } else {
                ch.setVisibility(View.GONE);
            }
        }
        if (chips.length == 0) {
            chipRow.setVisibility(View.GONE);
            View cl = dv.findViewById(R.id.chipLabel);
            if (cl != null) cl.setVisibility(View.GONE);
        }
        if (kmValue != null) {
            et.setText(fmtKm(kmValue));
            et.setSelection(et.getText().length());
        } else if (edit) {
            RunStore.Run t = store.get(RunStore.today());
            if (t != null) {
                et.setText(fmtKm(t.km));
                et.setSelection(et.getText().length());
            }
        }
        AlertDialog.Builder db = new AlertDialog.Builder(this)
                .setTitle(timeOverride != null ? "SAVE RUN"
                        : (edit ? "EDIT TODAY'S RUN" : "DISTANCE"))
                .setView(dv)
                .setPositiveButton("NEXT", null)
                .setNegativeButton("CANCEL", null);
        if (edit && timeOverride == null && store.get(RunStore.today()) != null) {
            db.setNeutralButton("DISCARD", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dlg, int w) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("DISCARD TODAY'S RUN?")
                            .setMessage("This removes today's logged run permanently.")
                            .setPositiveButton("DISCARD", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dlg2, int w2) {
                                    store.remove(RunStore.today());
                                    render();
                                    toast("Today's run discarded");
                                }
                            })
                            .setNegativeButton("KEEP", null)
                            .show();
                }
            });
        }
        final AlertDialog d = db.create();
        d.show();
        final Runnable next = new Runnable() {
            @Override
            public void run() {
                double km = parseKm(et);
                if (km <= 0) {
                    toast("Enter a distance");
                    return;
                }
                if (km > 200) {
                    toast("That looks off");
                    return;
                }
                d.dismiss();
                if (timeOverride != null) guardThenCommit(km, timeOverride, true);
                else showTime(km, edit);
            }
        };
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                next.run();
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    next.run();
                    return true;
                }
                return false;
            }
        });
        et.requestFocus();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private void showTime(final double km, final boolean edit) {
        View tv = getLayoutInflater().inflate(R.layout.dialog_time, null);
        final EditText mEt = (EditText) tv.findViewById(R.id.dlgMin);
        final EditText sEt = (EditText) tv.findViewById(R.id.dlgSec);
        if (edit) {
            RunStore.Run t = store.get(RunStore.today());
            if (t != null) {
                long totalSec = Math.round(t.min * 60.0);
                int mi = (int) (totalSec / 60), se = (int) (totalSec % 60);
                mEt.setText(mi > 0 ? String.valueOf(mi) : "");
                sEt.setText(se > 0 ? String.valueOf(se) : "");
            }
        }
        sEt.setImeActionLabel("SAVE", EditorInfo.IME_ACTION_DONE);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("TIME")
                .setView(tv)
                .setPositiveButton("SAVE", null)
                .setNeutralButton("BACK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        dd.dismiss();
                        showDistance(true, km);
                    }
                })
                .setNegativeButton("CANCEL", null)
                .create();
        d.show();
        final Runnable save = new Runnable() {
            @Override
            public void run() {
                int mi = parse(mEt), se = parse(sEt);
                if (mi < 0 || se < 0) {
                    toast("Numbers only");
                    return;
                }
                if (se >= 60) {
                    mi += se / 60;
                    se = se % 60;
                }
                double min = mi + se / 60.0;
                if (min <= 0) {
                    toast("Enter the time");
                    return;
                }
                if (min > 600) {
                    toast("That looks off");
                    return;
                }
                d.dismiss();
                guardThenCommit(km, min, false);
            }
        };
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save.run();
            }
        });
        mEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    sEt.requestFocus();
                    return true;
                }
                return false;
            }
        });
        sEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    save.run();
                    return true;
                }
                return false;
            }
        });
        if (edit) sEt.requestFocus();
        else mEt.requestFocus();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private void commitRun(double km, double min) {
        boolean firstToday = store.get(RunStore.today()) == null;
        commitRun(km, min, firstToday);
    }

    private void commitRun(double km, double min, boolean offerBpm) {
        store.upsert(RunStore.today(), km, min);
        resetTimeMachine();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        ScaleAnimation pu = new ScaleAnimation(1f, 1.06f, 1f, 1.06f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        pu.setDuration(110);
        pu.setRepeatCount(1);
        pu.setRepeatMode(Animation.REVERSE);
        saveBtn.startAnimation(pu);
        toast(store.isRestDay(RunStore.today())
                ? "Saved \u2713 REST DAY RUN \u2014 STREAK +1"
                : "Saved \u2713 streak safe");
        render();
        statPane.post(new Runnable() {
            @Override
            public void run() {
                pulse(statPane);
            }
        });
        if (offerBpm) {
            promptPostRunBpm();
        }
    }

    private void promptPostRunBpm() {
        new AlertDialog.Builder(this)
                .setTitle("MEASURE POST-RUN PULSE?")
                .setMessage("Check immediate 15s BPM or run the 60s HRR-60 cardiac recovery test.")
                .setPositiveButton("15S BPM", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showTab(4);
                        if (toolsUI != null) toolsUI.openPulsePostRun();
                    }
                })
                .setNeutralButton("HRR-60", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showTab(4);
                        if (toolsUI != null) toolsUI.openPulseHrr60();
                    }
                })
                .setNegativeButton("LATER", null)
                .show();
    }

    // ================= TYPO GUARD =================

    /** Returns a warning string if km/min looks like a typo, else null. */
    private String typoWarning(double km, double min) {
        double lo = store.longest();
        if (lo >= 3 && km > lo * 3) {
            return String.format(Locale.US,
                    "%.1f km is over 3\u00D7 your longest run (%.1f km)", km, lo);
        }
        if (km >= 1 && min > 0 && min / km < 2.5) {
            return "That pace is faster than 2:30/km";
        }
        return null;
    }

    private void guardThenCommit(final double km, final double min, final boolean fromTimer) {
        String warn = typoWarning(km, min);
        if (warn == null) {
            if (fromTimer) commitTimerRun(km, min);
            else commitRun(km, min);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("DOUBLE-CHECK")
                .setMessage(warn + ". Save anyway?")
                .setPositiveButton("SAVE ANYWAY", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (fromTimer) commitTimerRun(km, min);
                        else commitRun(km, min);
                    }
                })
                .setNegativeButton("FIX", null)
                .show();
    }

    private static double parseKm(EditText e) {
        try {
            String s = e.getText().toString().trim();
            if (s.length() == 0) return 0;
            return Double.parseDouble(s);
        } catch (Exception x) {
            return -1;
        }
    }

    private static int parse(EditText e) {
        try {
            String s = e.getText().toString().trim();
            if (s.length() == 0) return 0;
            return Integer.parseInt(s);
        } catch (Exception x) {
            return -1;
        }
    }

    private static String fmtKm(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        double r2 = Math.round(v * 100.0) / 100.0;
        if (Math.round(r2 * 10.0) * 10 == Math.round(r2 * 100.0)) {
            return String.format(Locale.US, "%.1f", r2);
        }
        return String.format(Locale.US, "%.2f", r2);
    }

    private void pulse(View v) {
        ScaleAnimation s = new ScaleAnimation(1f, 1.03f, 1f, 1.03f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
        s.setDuration(180L);
        s.setRepeatCount(1);
        s.setRepeatMode(Animation.REVERSE);
        v.startAnimation(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================= RUN TIMER =================

    private long elapsedMs() {
        return tAccMs + (tState == TS_RUN ? System.currentTimeMillis() - tRunSince : 0L);
    }

    private long remainMs() {
        return tTimedMin * 60000L - elapsedMs();
    }

    private void saveT() {
        StringBuilder lb = new StringBuilder();
        for (Long v : lapMs) {
            if (lb.length() > 0) lb.append(',');
            lb.append(v);
        }
        tprefs.edit().putInt("mode", tMode)
                .putInt("targetSub", targetSub)
                .putLong("targetDistBits", Double.doubleToRawLongBits(targetDistKm))
                .putInt("state", tState)
                .putInt("timedMin", tTimedMin)
                .putLong("accMs", tAccMs)
                .putLong("runSince", tRunSince)
                .putLong("restUntil", restUntil)
                .putInt("wuPhase", wuPhase)
                .putInt("wuIdx", wuIdx)
                .putLong("wuEndMs", wuEndMs)
                .putLong("coolUntil", coolUntil)
                .putString("laps", "[" + lb + "]")
                .apply();
    }

    private void syncTimerPrefs() {
        tMode = tprefs.getInt("mode", T_RUN);
        targetSub = tprefs.getInt("targetSub", 0);
        targetDistKm = CroSiteService.readTargetDistKm(tprefs);
        tState = tprefs.getInt("state", TS_IDLE);
        tTimedMin = tprefs.getInt("timedMin", 30);
        tAccMs = tprefs.getLong("accMs", 0L);
        tRunSince = tprefs.getLong("runSince", 0L);
        restUntil = tprefs.getLong("restUntil", 0L);
        wuPhase = tprefs.getInt("wuPhase", 0);
        wuIdx = tprefs.getInt("wuIdx", 0);
        wuEndMs = tprefs.getLong("wuEndMs", 0L);
        coolUntil = tprefs.getLong("coolUntil", 0L);
    }

    private boolean checkAutoSavedSync() {
        long at = tprefs.getLong("autoSavedAt", 0L);
        if (at > lastAutoSavedSeen) {
            lastAutoSavedSeen = at;
            prefs.edit().putLong("lastAutoSavedSeen", at).apply();
            store.reload();
            resetTimeMachine();
            render();
            String msg = tprefs.getString("autoSavedMsg", "Target saved \u2713");
            toast(msg);
            if (tState == TS_IDLE) {
                tHand.removeCallbacks(tTick);
                renderTimer();
                if (uiResumed) promptPostRunBpm();
                return true;
            }
        }
        return false;
    }

    private void recoverT() {
        syncTimerPrefs();
        boolean synced = checkAutoSavedSync();
        if (tState == TS_RUN && tMode == T_TARGET && targetSub == 0 && remainMs() <= 0) {
            tAccMs = tTimedMin * 60000L;
            tRunSince = 0L;
            tState = TS_DONE;
            saveT();
            if (CroSiteEngine.isEnabled(this)) {
                CroSiteService.dispatch(this, CroSiteService.ACT_PAUSE);
            }
        }
        if (tState == TS_REST && System.currentTimeMillis() >= restUntil) {
            tRunSince = restUntil > 0 ? restUntil : System.currentTimeMillis();
            tState = TS_RUN;
            saveT();
            if (CroSiteEngine.isEnabled(this)) {
                CroSiteService.dispatch(this, CroSiteService.ACT_RESUME);
            }
        }
        if (tState == TS_COOLDOWN && System.currentTimeMillis() >= coolUntil + 1200L) {
            tState = TS_IDLE;
            tAccMs = 0L;
            tRunSince = 0L;
            saveT();
        }
        if (tState == TS_RUN && CroSiteEngine.isEnabled(this) && hasLocPerm()) {
            CroSiteService.dispatch(this, CroSiteService.ACT_RESUME);
        } else if (tState == TS_WARMUP) {
            CroSiteService.dispatch(this, CroSiteService.ACT_SYNC_COACH);
        }
        if (!synced) renderTimer();
        startTick();
    }

    private void startTick() {
        tHand.removeCallbacks(tTick);
        if (tState == TS_RUN || tState == TS_REST || tState == TS_WARMUP || tState == TS_COOLDOWN) {
            tHand.postDelayed(tTick, 250L);
        }
    }

    private void setMode(int m) {
        if (tState != TS_IDLE) {
            toast("Finish or discard this timer first");
            return;
        }
        tMode = m;
        if (tMode == T_TARGET && targetSub == 1 && !CroSiteEngine.isEnabled(this)) {
            CroSiteEngine.setEnabled(this, true);
            updateWarmup();
            toast("CroSite GPS enabled for Target Distance");
        }
        saveT();
        renderTimer();
    }

    private void setTargetSub(int sub) {
        if (tState != TS_IDLE) return;
        targetSub = sub;
        if (sub == 1 && !CroSiteEngine.isEnabled(this)) {
            CroSiteEngine.setEnabled(this, true);
            updateWarmup();
            toast("CroSite GPS enabled for Target Distance");
        }
        saveT();
        renderTimer();
    }

    private void stepTarget(int dir) {
        if (tState != TS_IDLE) return;
        if (targetSub == 1) {
            double v = Math.round(Math.max(0.5, Math.min(50.0, targetDistKm + dir * 0.5)) * 100.0) / 100.0;
            if (Math.abs(v - targetDistKm) > 0.001) {
                targetDistKm = v;
                saveT();
                renderTimer();
            }
        } else {
            int v = Math.max(5, Math.min(180, tTimedMin + dir * 5));
            if (v != tTimedMin) {
                tTimedMin = v;
                saveT();
                renderTimer();
            }
        }
    }

    private void editTargetPrompt() {
        if (tState != TS_IDLE || tMode != T_TARGET) return;
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER
                | (targetSub == 1 ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        et.setText(targetSub == 1 ? fmtKm(targetDistKm) : String.valueOf(tTimedMin));
        et.setSelection(et.getText().length());
        et.setTextColor(0xFFF2F5FA);
        et.setPadding(dp(20), dp(14), dp(20), dp(10));
        new AlertDialog.Builder(this)
                .setTitle(targetSub == 1 ? "TARGET DISTANCE (KM)" : "TARGET TIME (MINUTES)")
                .setView(et)
                .setPositiveButton("SET", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String s = et.getText().toString().trim();
                            if (targetSub == 1) {
                                double v = Double.parseDouble(s);
                                if (v >= 0.2 && v <= 100.0) {
                                    targetDistKm = Math.round(v * 100.0) / 100.0;
                                    saveT();
                                    renderTimer();
                                } else {
                                    toast("Enter 0.2 to 100 km");
                                }
                            } else {
                                int v = Integer.parseInt(s);
                                if (v >= 1 && v <= 180) {
                                    tTimedMin = v;
                                    saveT();
                                    renderTimer();
                                } else {
                                    toast("Enter 1 to 180 min");
                                }
                            }
                        } catch (Exception e) {
                            toast("Invalid number");
                        }
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private boolean hasLocPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateWarmup() {
        if (!uiResumed || curTab != 3 || tState != TS_IDLE
                || !CroSiteEngine.isEnabled(this) || !hasLocPerm()) {
            stopWarmup();
            return;
        }
        if (warmingUp || warmLm == null) return;
        try {
            if (warmLm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                warmLm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 0f,
                        warmListener, Looper.getMainLooper());
                warmingUp = true;
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        renderCroSiteUi();
    }

    private void stopWarmup() {
        if (!warmingUp || warmLm == null) return;
        try {
            warmLm.removeUpdates(warmListener);
        } catch (Exception ignored) {
        }
        warmingUp = false;
    }

    private void checkBatteryOpt() {
        if (batWarnCard == null) return;
        if (Build.VERSION.SDK_INT < 23 || !CroSiteEngine.isEnabled(this)
                || prefs.getBoolean("bat_dismissed", false)) {
            batWarnCard.setVisibility(View.GONE);
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batWarnCard.setVisibility(unrestricted ? View.GONE : View.VISIBLE);
    }

    private void startT() {
        if (tMode == T_TARGET && targetSub == 1 && !CroSiteEngine.isEnabled(this)) {
            CroSiteEngine.setEnabled(this, true);
        }
        if (CroSiteEngine.isEnabled(this) && !hasLocPerm()) {
            pendingCroStart = true;
            ArrayList<String> perms = new ArrayList<String>();
            perms.add("android.permission.ACCESS_FINE_LOCATION");
            perms.add("android.permission.ACCESS_COARSE_LOCATION");
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.POST_NOTIFICATIONS");
            }
            toast("Allow location so CroSite tracks distance automatically");
            requestPermissions(perms.toArray(new String[0]), REQ_LOC);
            return;
        }
        final boolean croOn = CroSiteEngine.isEnabled(this);
        if (tMode == T_TARGET && targetSub == 1) {
            RunStore.Run existing = store.get(RunStore.today());
            if (existing != null) {
                new AlertDialog.Builder(this)
                        .setTitle("TODAY ALREADY HAS A RUN")
                        .setMessage(String.format(Locale.US,
                                "Target Distance auto-saves when %.2f km finishes and will update today's %.2f km run. Start anyway?",
                                targetDistKm, existing.km))
                        .setPositiveButton("START", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                startTNow(croOn);
                            }
                        })
                        .setNegativeButton("CANCEL", null)
                        .show();
                return;
            }
        }
        startTNow(croOn);
    }

    private void startTNow(boolean withCroSite) {
        stopWarmup();
        cachedTrail = null;
        boolean coachTts = prefs.getBoolean("coach_tts", false);
        boolean wuOn = coachTts && prefs.getBoolean("coach_warmup_on", false);
        List<CroSiteService.WarmMove> moves = wuOn ? CroSiteService.loadWarmupMoves(this) : null;
        if (wuOn && moves != null && !moves.isEmpty()) {
            long now = System.currentTimeMillis();
            tAccMs = 0L;
            tRunSince = 0L;
            wuPhase = 0;
            wuIdx = 0;
            wuEndMs = now + 5000L;
            tState = TS_WARMUP;
            saveT();
            CroSiteService.dispatch(this, CroSiteService.ACT_WARMUP);
            renderTimer();
            startTick();
            return;
        }
        tAccMs = 0L;
        tRunSince = System.currentTimeMillis();
        tState = TS_RUN;
        saveT();
        if (withCroSite || coachTts || prefs.getBoolean("coach_metro", false)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_START);
        } else {
            CroSiteEngine.resetAll(this);
        }
        renderTimer();
        startTick();
    }

    private void skipWarmup() {
        if (tState != TS_WARMUP) return;
        tAccMs = 0L;
        tRunSince = System.currentTimeMillis();
        tState = TS_RUN;
        saveT();
        if (CroSiteEngine.isEnabled(this) || prefs.getBoolean("coach_tts", false)
                || prefs.getBoolean("coach_metro", false)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_START);
        } else {
            CroSiteService.dispatch(this, CroSiteService.ACT_STOP);
            CroSiteEngine.resetAll(this);
        }
        renderTimer();
        startTick();
    }

    private void cancelWarmup() {
        if (tState != TS_WARMUP) return;
        resetT();
    }

    private Runnable onRunSetupRefresh;

    private void openWarmupEditor() {
        final List<CroSiteService.WarmMove> moves = new ArrayList<CroSiteService.WarmMove>(
                CroSiteService.loadWarmupMoves(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(12));

        TextView tip = new TextView(this);
        tip.setText("Counts 5..4..3..2..1 before warm-up starts, says '<Move> done' after each exercise, then counts 5..4..3..2..1..Go! into your run.");
        tip.setTextSize(11);
        tip.setTextColor(0xFF8A94A6);
        root.addView(tip);

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(10);
        root.addView(listBox, lbLp);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                listBox.removeAllViews();
                for (int i = 0; i < moves.size(); i++) {
                    final int idx = i;
                    final CroSiteService.WarmMove mv = moves.get(i);
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackgroundResource(R.drawable.badge_off);
                    row.setPadding(dp(12), dp(8), dp(8), dp(8));
                    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rLp.topMargin = dp(6);

                    TextView txt = new TextView(MainActivity.this);
                    txt.setText((i + 1) + ". " + mv.name + "  \u00B7  " + mv.sec + "s");
                    txt.setTextSize(12);
                    txt.setTypeface(Typeface.DEFAULT_BOLD);
                    txt.setTextColor(0xFFF2F5FA);
                    txt.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            promptMoveDialog(mv, new Runnable() {
                                @Override
                                public void run() {
                                    CroSiteService.saveWarmupMoves(MainActivity.this, moves);
                                    rebuild[0].run();
                                    renderCroSiteUi();
                                    if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                                }
                            });
                        }
                    });
                    row.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    if (i > 0) {
                        TextView up = smallMoveBtn("\u25B2", 0xFF8A94A6);
                        up.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                java.util.Collections.swap(moves, idx, idx - 1);
                                CroSiteService.saveWarmupMoves(MainActivity.this, moves);
                                rebuild[0].run();
                                renderCroSiteUi();
                                if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                            }
                        });
                        row.addView(up);
                    }
                    if (i < moves.size() - 1) {
                        TextView dn = smallMoveBtn("\u25BC", 0xFF8A94A6);
                        dn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                java.util.Collections.swap(moves, idx, idx + 1);
                                CroSiteService.saveWarmupMoves(MainActivity.this, moves);
                                rebuild[0].run();
                                renderCroSiteUi();
                                if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                            }
                        });
                        row.addView(dn);
                    }
                    if (moves.size() > 1) {
                        TextView del = smallMoveBtn("\u00D7", 0xFFFF5C5C);
                        del.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                moves.remove(idx);
                                CroSiteService.saveWarmupMoves(MainActivity.this, moves);
                                rebuild[0].run();
                                renderCroSiteUi();
                                if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                            }
                        });
                        row.addView(del);
                    }
                    listBox.addView(row, rLp);
                }
            }
        };
        rebuild[0].run();

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("WARM-UP EXERCISES")
                .setView(sc)
                .setPositiveButton("+ ADD MOVE", null)
                .setNeutralButton("RESET", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove("coach_warmup_list").apply();
                        renderCroSiteUi();
                        if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                        toast("Reset to defaults");
                    }
                })
                .setNegativeButton("DONE", null)
                .show()
                .getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final CroSiteService.WarmMove nm = new CroSiteService.WarmMove("", 25);
                        promptMoveDialog(nm, new Runnable() {
                            @Override
                            public void run() {
                                if (nm.name.length() > 0) {
                                    moves.add(nm);
                                    CroSiteService.saveWarmupMoves(MainActivity.this, moves);
                                    rebuild[0].run();
                                    renderCroSiteUi();
                                    if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                                }
                            }
                        });
                    }
                });
    }

    private TextView smallMoveBtn(String txt, int col) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(col);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        return tv;
    }

    private void promptMoveDialog(final CroSiteService.WarmMove mv, final Runnable onSaved) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(8));

        final EditText nEt = new EditText(this);
        nEt.setHint("Exercise name (e.g. High Knees)");
        nEt.setText(mv.name);
        nEt.setTextColor(0xFFF2F5FA);
        nEt.setHintTextColor(0xFF66708A);
        box.addView(nEt);

        final EditText sEt = new EditText(this);
        sEt.setHint("Duration in seconds (e.g. 30)");
        sEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        sEt.setText(String.valueOf(mv.sec));
        sEt.setTextColor(0xFFF2F5FA);
        sEt.setHintTextColor(0xFF66708A);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(8);
        box.addView(sEt, sLp);

        new AlertDialog.Builder(this)
                .setTitle(mv.name.length() > 0 ? "EDIT EXERCISE" : "NEW EXERCISE")
                .setView(box)
                .setPositiveButton("SAVE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = nEt.getText().toString().trim();
                        int s = 20;
                        try { s = Integer.parseInt(sEt.getText().toString().trim()); } catch (Exception ignored) {}
                        if (n.length() == 0) {
                            toast("Enter exercise name");
                            return;
                        }
                        mv.name = n;
                        mv.sec = Math.max(5, Math.min(300, s));
                        onSaved.run();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void pauseT() {
        if (tState != TS_RUN) return;
        tAccMs = elapsedMs();
        tRunSince = 0L;
        tState = TS_PAUSE;
        saveT();
        if (CroSiteEngine.isEnabled(this) || prefs.getBoolean("coach_tts", false)
                || prefs.getBoolean("coach_metro", false)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_PAUSE);
        }
        renderTimer();
        tHand.removeCallbacks(tTick);
    }

    private void resumeT() {
        if (tState != TS_PAUSE) return;
        tRunSince = System.currentTimeMillis();
        tState = TS_RUN;
        saveT();
        if (CroSiteEngine.isEnabled(this) || prefs.getBoolean("coach_tts", false)
                || prefs.getBoolean("coach_metro", false)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_RESUME);
        }
        renderTimer();
        startTick();
    }

    // ================= LAP (v3.7) — lap is just a lap =================

    private void lapT() {
        if (tState != TS_RUN || tMode != T_RUN) return;
        lapMs.add(elapsedMs());
        tAccMs = elapsedMs();
        tRunSince = 0L;
        tState = TS_REST;
        restUntil = System.currentTimeMillis() + 60000L;
        saveT();
        if (CroSiteEngine.isEnabled(this)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_PAUSE);
        }
        renderTimer();
        startTick();
        pulse(timerText);
    }

    private void skipRest() {
        if (tState != TS_REST) return;
        tRunSince = System.currentTimeMillis();
        tState = TS_RUN;
        saveT();
        if (CroSiteEngine.isEnabled(this)) {
            CroSiteService.dispatch(this, CroSiteService.ACT_RESUME);
        }
        renderTimer();
        startTick();
    }

    private void undoLap() {
        if (lapMs.isEmpty()) return;
        lapMs.remove(lapMs.size() - 1);
        if (tState == TS_REST) {
            tRunSince = System.currentTimeMillis();
            tState = TS_RUN;
            if (CroSiteEngine.isEnabled(this)) {
                CroSiteService.dispatch(this, CroSiteService.ACT_RESUME);
            }
        }
        saveT();
        renderTimer();
    }

    private double[] lapsMinutes() {
        double[] out = new double[lapMs.size()];
        long prev = 0L;
        for (int i = 0; i < lapMs.size(); i++) {
            out[i] = (lapMs.get(i) - prev) / 60000.0;
            prev = lapMs.get(i);
        }
        return out;
    }

    private void savePromptT() {
        if (tState != TS_PAUSE && tState != TS_DONE && tState != TS_REST) return;
        double min = elapsedMs() / 60000.0;
        if (min < 0.02) {
            toast("No time on the timer");
            return;
        }
        pendingLaps = lapsMinutes();
        Double croKm = null;
        if (CroSiteEngine.isEnabled(this)) {
            double ck = CroSiteEngine.readDistKm(this);
            if (ck >= 0.005) croKm = Math.round(ck * 100.0) / 100.0;
        }
        showDistance(false, croKm, min);
    }

    private void commitTimerRun(double km, double min) {
        if (min > 600) {
            toast("Timer looks off \u2014 discard it");
            return;
        }
        double[] laps = pendingLaps;
        pendingLaps = null;
        int avgCad = CroSiteEngine.isEnabled(this) ? CroSiteEngine.readAvgSpm(this) : 0;
        double[] ob = CroSiteEngine.isEnabled(this)
                ? CroSiteEngine.readOutBack(this, min) : new double[]{0, 0};
        resetT();
        showTab(0);
        commitRun(km, min, true);
        if (laps != null && laps.length > 0) store.setLaps(RunStore.today(), laps);
        if (avgCad > 0 || ob[0] > 0) {
            store.setCroPro(RunStore.today(), avgCad, ob[0], ob[1]);
            render();
        }
    }

    private void discardT() {
        if (tState != TS_PAUSE && tState != TS_DONE && tState != TS_REST) return;
        new AlertDialog.Builder(this)
                .setTitle("DISCARD TIMER?")
                .setMessage("This time will not be saved.")
                .setPositiveButton("DISCARD", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        resetT();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void resetT() {
        tState = TS_IDLE;
        tAccMs = 0L;
        tRunSince = 0L;
        restUntil = 0L;
        lapMs.clear();
        pendingLaps = null;
        cachedTrail = null;
        saveT();
        CroSiteService.dispatch(this, CroSiteService.ACT_STOP);
        CroSiteEngine.resetAll(this);
        updateWarmup();
        renderTimer();
        tHand.removeCallbacks(tTick);
    }

    private void renderTimer() {
        boolean idle = tState == TS_IDLE;
        boolean wu = tState == TS_WARMUP;
        boolean cd = tState == TS_COOLDOWN;
        modeRunBtn.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        modeRunBtn.setTextColor(tMode == T_RUN ? 0xFF00E5A0 : 0xFF66708A);
        modeTimedBtn.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        modeTimedBtn.setTextColor(tMode == T_TARGET ? 0xFF00E5A0 : 0xFF66708A);
        timedPane.setVisibility(idle && tMode == T_TARGET ? View.VISIBLE : View.GONE);
        if (targetTimeBtn != null && targetDistBtn != null) {
            targetTimeBtn.setBackgroundResource(targetSub == 0 ? R.drawable.badge_on : R.drawable.badge_off);
            targetTimeBtn.setTextColor(targetSub == 0 ? 0xFF00E5A0 : 0xFF66708A);
            targetDistBtn.setBackgroundResource(targetSub == 1 ? R.drawable.badge_on : R.drawable.badge_off);
            targetDistBtn.setTextColor(targetSub == 1 ? 0xFF00E5A0 : 0xFF66708A);
        }
        timedMinTxt.setText(targetSub == 1 ? fmtKm(targetDistKm) : String.valueOf(tTimedMin));
        if (targetUnitTxt != null) targetUnitTxt.setText(targetSub == 1 ? "KM" : "MINUTES");

        btnStart.setVisibility(idle ? View.VISIBLE : View.GONE);
        if (wuActionRow != null) wuActionRow.setVisibility(wu ? View.VISIBLE : View.GONE);
        btnPause.setVisibility(tState == TS_RUN ? View.VISIBLE : View.GONE);
        boolean savable = tState == TS_PAUSE || tState == TS_DONE || tState == TS_REST;
        saveResumeRow.setVisibility(savable ? View.VISIBLE : View.GONE);
        btnResume.setVisibility(tState == TS_PAUSE ? View.VISIBLE : View.GONE);
        btnDiscard.setVisibility(savable ? View.VISIBLE : View.GONE);

        String hint;
        float progFrac = -1f;
        int progCol = 0xFF00E5A0;
        String progLabel = null;
        if (wu) {
            long leftMs = Math.max(0L, wuEndMs - System.currentTimeMillis());
            int leftS = (int) Math.ceil(leftMs / 1000.0);
            timerText.setText(String.format(Locale.US, "0:%02d", Math.max(0, leftS)));
            timerText.setTextColor(0xFF00B4D8);
            progCol = 0xFF00B4D8;
            if (wuPhase == 0) {
                List<CroSiteService.WarmMove> mv = CroSiteService.loadWarmupMoves(this);
                CroSiteService.WarmMove first = !mv.isEmpty() ? mv.get(0) : null;
                hint = "PRE-WARMUP \u00B7 STARTING IN " + Math.max(1, Math.min(5, leftS)) + "...";
                progFrac = Math.max(0.05f, Math.min(1f, 1f - leftMs / 5000f));
                progLabel = first != null
                        ? "FIRST: " + first.name.toUpperCase(Locale.US) + " \u00B7 " + first.sec + "S"
                        : "GET READY";
            } else if (wuPhase == 2) {
                hint = "GET READY \u00B7 STARTING IN " + Math.max(1, Math.min(5, leftS)) + "...";
                progFrac = Math.max(0.05f, Math.min(1f, 1f - leftMs / 6300f));
                progLabel = "LAUNCHING RUN AUTOMATICALLY";
            } else {
                List<CroSiteService.WarmMove> mv = CroSiteService.loadWarmupMoves(this);
                CroSiteService.WarmMove curM = wuIdx < mv.size() ? mv.get(wuIdx) : null;
                String name = curM != null ? curM.name.toUpperCase(Locale.US) : "WARM-UP";
                hint = "WARM-UP " + (wuIdx + 1) + "/" + mv.size() + " \u00B7 " + name;
                double totMs = curM != null ? curM.sec * 1000.0 : 20000.0;
                progFrac = (float) Math.max(0.03, Math.min(1.0, 1.0 - leftMs / totMs));
                if (wuIdx + 1 < mv.size()) {
                    CroSiteService.WarmMove nxt = mv.get(wuIdx + 1);
                    progLabel = "NEXT: " + nxt.name.toUpperCase(Locale.US) + " \u00B7 " + nxt.sec + "S";
                } else {
                    progLabel = "NEXT: 5..4..3..2..1..GO!";
                }
            }
        } else if (cd) {
            long leftMs = Math.max(0L, coolUntil - System.currentTimeMillis());
            if (leftMs > 3000L) {
                timerText.setText("SAVED");
            } else {
                int s = Math.max(1, Math.min(3, (int) Math.ceil(leftMs / 1000.0)));
                timerText.setText(String.valueOf(s));
            }
            timerText.setTextColor(0xFF00E5A0);
            hint = "TARGET SAVED \u00B7 KEEP IT SLOW";
        } else {
            if (tMode == T_TARGET && targetSub == 0) {
                long ms = idle ? tTimedMin * 60000L : remainMs();
                timerText.setText(clockText(ms / 60000.0));
            } else {
                timerText.setText(clockText(elapsedMs() / 60000.0));
            }
            timerText.setTextColor(tState == TS_DONE || tState == TS_REST ? 0xFFFFB454
                    : (tState == TS_RUN ? 0xFF00E5A0 : 0xFFF2F5FA));
            if (idle) {
                if (tMode == T_TARGET) {
                    hint = targetSub == 1
                            ? "AUTO-SAVES AT " + fmtKm(targetDistKm) + " KM"
                            : "COUNTS DOWN TO ZERO";
                } else {
                    hint = "STOPWATCH \u00B7 COUNTS UP";
                }
            } else if (tState == TS_RUN) {
                if (tMode == T_TARGET && targetSub == 1) {
                    double doneKm = CroSiteEngine.readDistKm(this);
                    double rem = Math.max(0.0, targetDistKm - doneKm);
                    hint = String.format(Locale.US, "TARGET %s KM \u00B7 %.2f KM LEFT",
                            fmtKm(targetDistKm), rem);
                    progFrac = (float) Math.max(0.02, Math.min(1.0, doneKm / Math.max(0.1, targetDistKm)));
                    progCol = 0xFF00E5A0;
                    progLabel = String.format(Locale.US, "%.2f / %s KM \u00B7 %d%%",
                            doneKm, fmtKm(targetDistKm), Math.min(100, Math.round(progFrac * 100f)));
                } else {
                    hint = "RUNNING \u2014 KEEPS COUNTING IF CLOSED";
                }
            } else if (tState == TS_PAUSE) {
                if (tMode == T_TARGET && targetSub == 1) {
                    double doneKm = CroSiteEngine.readDistKm(this);
                    progFrac = (float) Math.max(0.02, Math.min(1.0, doneKm / Math.max(0.1, targetDistKm)));
                    progCol = 0xFFFFB454;
                    progLabel = String.format(Locale.US, "%.2f / %s KM \u00B7 PAUSED",
                            doneKm, fmtKm(targetDistKm));
                }
                hint = "PAUSED \u2014 SAVE, RESUME OR DISCARD";
            } else if (tState == TS_REST) {
                hint = "REST \u00B7 ONE MINUTE WALK BREAK";
            } else {
                hint = "TIME'S UP \u2014 SAVE YOUR RUN";
            }
        }
        timerHint.setText(hint);

        if (playProgBox != null && playProgBg != null && playProgFill != null) {
            if (progFrac >= 0f) {
                playProgBox.setVisibility(View.VISIBLE);
                playProgFill.setBackgroundColor(progCol);
                final float pf = progFrac;
                int pw = playProgBg.getWidth();
                if (pw > 0) {
                    playProgFill.getLayoutParams().width = Math.max(dp(4), Math.round(pw * pf));
                    playProgFill.requestLayout();
                } else {
                    playProgBg.post(new Runnable() {
                        @Override
                        public void run() {
                            int w = playProgBg.getWidth();
                            if (w > 0) {
                                playProgFill.getLayoutParams().width = Math.max(dp(4), Math.round(w * pf));
                                playProgFill.requestLayout();
                            }
                        }
                    });
                }
                if (playNextTxt != null) {
                    if (progLabel != null && progLabel.length() > 0) {
                        playNextTxt.setText(progLabel);
                        playNextTxt.setVisibility(View.VISIBLE);
                    } else {
                        playNextTxt.setVisibility(View.GONE);
                    }
                }
            } else {
                playProgBox.setVisibility(View.GONE);
            }
        }

        // LAP row — sirf RUN mode (uska order), TARGET me kabhi nahi
        lapRow.setVisibility(tMode == T_RUN && !idle && !wu && !cd ? View.VISIBLE : View.GONE);
        btnLap.setVisibility(tState == TS_RUN || tState == TS_REST ? View.VISIBLE : View.GONE);
        btnLap.setText(tState == TS_REST ? "SKIP REST" : "LAP");
        btnLapUndo.setVisibility(!lapMs.isEmpty() && tState != TS_IDLE ? View.VISIBLE : View.GONE);
        if (tState == TS_REST) {
            long left = Math.max(0L, restUntil - System.currentTimeMillis());
            lapInfo.setText("REST " + clockText(left / 60000.0) + " \u00B7 WALK IT OFF");
            lapInfo.setVisibility(View.VISIBLE);
        } else if (!lapMs.isEmpty() && !wu && !cd) {
            long last = lapMs.get(lapMs.size() - 1);
            long prev = lapMs.size() > 1 ? lapMs.get(lapMs.size() - 2) : 0L;
            lapInfo.setText("LAP " + lapMs.size() + " \u00B7 LAST "
                    + clockText((last - prev) / 60000.0));
            lapInfo.setVisibility(View.VISIBLE);
        } else {
            lapInfo.setVisibility(View.GONE); // khaali hint line nahi dikhti
        }
        renderCroSiteUi();
    }

    private void renderCroSiteUi() {
        if (croRow == null) return;
        boolean en = CroSiteEngine.isEnabled(this);
        boolean idle = tState == TS_IDLE;
        croRow.setVisibility(idle ? View.VISIBLE : View.GONE);
        croOnBtn.setBackgroundResource(en ? R.drawable.badge_on : R.drawable.badge_off);
        croOnBtn.setTextColor(en ? 0xFF00E5A0 : 0xFF66708A);
        croOffBtn.setBackgroundResource(!en ? R.drawable.badge_on : R.drawable.badge_off);
        croOffBtn.setTextColor(!en ? 0xFF00E5A0 : 0xFF66708A);

        int dotColor;
        String status;
        if (!en) {
            dotColor = 0xFF66708A;
            status = "TIMER ONLY \u00B7 ENTER KM ON SAVE";
        } else if (idle) {
            boolean gpsOn = warmLm != null && warmLm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (!gpsOn) {
                dotColor = 0xFFFFB454;
                status = "TURN ON LOCATION OF YOUR PHONE";
            } else if (!hasLocPerm()) {
                dotColor = 0xFFFFB454;
                status = "CROSITE \u00B7 GRANTS GPS ON START";
            } else if (CroSiteEngine.isSatReady(this)) {
                dotColor = 0xFF00E5A0;
                int acc = Math.round(CroSiteEngine.lastAccuracy(this));
                status = "CROSITE READY (\u00B1" + acc + "M)";
            } else {
                dotColor = 0xFF00B4D8;
                status = "LOCKING SATELLITES...";
            }
        } else if (tState == TS_WARMUP) {
            dotColor = 0xFF00B4D8;
            status = "VOICE COACH WARM-UP";
        } else if (tState == TS_COOLDOWN) {
            dotColor = 0xFF00E5A0;
            status = "TARGET COMPLETE \u00B7 SAVED";
        } else if (tState == TS_REST) {
            dotColor = 0xFFFFB454;
            status = "CROSITE PAUSED DURING REST";
        } else if (tState == TS_PAUSE || tState == TS_DONE) {
            dotColor = 0xFFFFB454;
            status = "CROSITE PAUSED";
        } else {
            dotColor = CroSiteEngine.isSatReady(this) ? 0xFF00E5A0 : 0xFF00B4D8;
            status = CroSiteEngine.isSatReady(this)
                    ? "CROSITE ACTIVE \u00B7 GPS + STRIDE"
                    : "CROSITE ACTIVE \u00B7 LOCKING GPS...";
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(dotColor);
        croDot.setBackground(gd);
        croStatusTxt.setText(status);
        croStatusTxt.setTextColor(dotColor);

        if (en && !idle && tState != TS_WARMUP && tState != TS_COOLDOWN) {
            croLiveRow.setVisibility(View.VISIBLE);
            double km = CroSiteEngine.readDistKm(this);
            croDistTxt.setText(String.format(Locale.US, "%.2f", km));
            double elMin = elapsedMs() / 60000.0;
            if (km >= 0.05 && elMin > 0.1) {
                long sp = Math.round((elMin / km) * 60.0);
                croPaceTxt.setText(String.format(Locale.US, "%d:%02d", sp / 60, (int) (sp % 60)));
            } else {
                croPaceTxt.setText("\u2014");
            }
            int liveSpm = CroSiteEngine.readLiveSpm(this);
            croSpmTxt.setText(liveSpm > 0 ? String.valueOf(liveSpm) : "\u2014");
            if (CroSiteEngine.isTurnFound(this)) {
                croTurnTxt.setVisibility(View.VISIBLE);
                croTurnTxt.setText("\u21BA TURNAROUND LOCKED \u00B7 RECORDING BACK SPLIT");
            } else {
                croTurnTxt.setVisibility(View.GONE);
            }
        } else {
            croLiveRow.setVisibility(View.GONE);
        }

        if (croMapView != null) {
            boolean showMap = en && croMapPack != null
                    && prefs.getBoolean("map_show", true)
                    && tState != TS_WARMUP && tState != TS_COOLDOWN;
            if (showMap) {
                croMapView.setVisibility(View.VISIBLE);
                double[] fix = CroSiteEngine.readLiveFix(this);
                boolean hasFix = fix != null;
                long nowMs = System.currentTimeMillis();
                if (tState == TS_IDLE) {
                    cachedTrail = null;
                } else if (cachedTrail == null || (nowMs - lastTrailReadMs) >= 1800L) {
                    cachedTrail = CroSiteEngine.readTrail(this);
                    lastTrailReadMs = nowMs;
                }
                double[] turn = tState != TS_IDLE ? CroSiteEngine.readTurnLatLon(this) : null;
                croMapView.updateState(
                        hasFix,
                        hasFix ? fix[0] : 0.0,
                        hasFix ? fix[1] : 0.0,
                        hasFix ? (float) fix[2] : 0f,
                        cachedTrail,
                        turn != null,
                        turn != null ? turn[0] : 0.0,
                        turn != null ? turn[1] : 0.0
                );
            } else {
                croMapView.setVisibility(View.GONE);
            }
        }

        if (runGearDot != null) {
            boolean armed = prefs.getBoolean("coach_tts", false)
                    || prefs.getBoolean("coach_metro", false)
                    || (croMapPack != null && prefs.getBoolean("map_show", true));
            runGearDot.setVisibility(armed ? View.VISIBLE : View.GONE);
        }
    }

    private void openRunSetupDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(12));

        // 1) VOICE COACH + NESTED WARM-UP CARD
        final LinearLayout voiceCard = new LinearLayout(this);
        voiceCard.setOrientation(LinearLayout.VERTICAL);
        voiceCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout vTop = new LinearLayout(this);
        vTop.setOrientation(LinearLayout.HORIZONTAL);
        vTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout vTxtCol = new LinearLayout(this);
        vTxtCol.setOrientation(LinearLayout.VERTICAL);
        TextView vTitle = new TextView(this);
        vTitle.setText("VOICE COACH");
        vTitle.setTextSize(14);
        vTitle.setTypeface(Typeface.DEFAULT_BOLD);
        vTitle.setLetterSpacing(0.12f);
        vTitle.setTextColor(0xFFF2F5FA);
        TextView vSub = new TextView(this);
        vSub.setText("0.85x offline pace, km & split cues");
        vSub.setTextSize(11);
        vSub.setTextColor(0xFF8A94A6);
        vTxtCol.addView(vTitle);
        vTxtCol.addView(vSub);
        vTop.addView(vTxtCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView ttsPill = new TextView(this);
        ttsPill.setGravity(Gravity.CENTER);
        ttsPill.setPadding(dp(14), dp(6), dp(14), dp(6));
        ttsPill.setMinWidth(dp(54));
        ttsPill.setTextSize(11);
        ttsPill.setTypeface(Typeface.DEFAULT_BOLD);
        ttsPill.setLetterSpacing(0.12f);
        vTop.addView(ttsPill);
        voiceCard.addView(vTop);

        View div = new View(this);
        div.setBackgroundColor(isAmoled() ? 0xFF1B2230 : 0xFF232C42);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dLp.topMargin = dp(12);
        dLp.bottomMargin = dp(12);
        voiceCard.addView(div, dLp);

        // Nested Warm-Up + Cooldown sub-option inside Voice Coach
        final LinearLayout wuSubBox = new LinearLayout(this);
        wuSubBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout wTop = new LinearLayout(this);
        wTop.setOrientation(LinearLayout.HORIZONTAL);
        wTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout wTxtCol = new LinearLayout(this);
        wTxtCol.setOrientation(LinearLayout.VERTICAL);
        TextView wTitle = new TextView(this);
        wTitle.setText("WARM-UP + COOLDOWN");
        wTitle.setTextSize(12);
        wTitle.setTypeface(Typeface.DEFAULT_BOLD);
        wTitle.setLetterSpacing(0.1f);
        wTitle.setTextColor(0xFF00B4D8);
        final TextView wSub = new TextView(this);
        wSub.setTextSize(10);
        wSub.setTextColor(0xFF8A94A6);
        wTxtCol.addView(wTitle);
        wTxtCol.addView(wSub);
        wTop.addView(wTxtCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView wuPill = new TextView(this);
        wuPill.setGravity(Gravity.CENTER);
        wuPill.setPadding(dp(14), dp(6), dp(14), dp(6));
        wuPill.setMinWidth(dp(54));
        wuPill.setTextSize(11);
        wuPill.setTypeface(Typeface.DEFAULT_BOLD);
        wuPill.setLetterSpacing(0.12f);
        wTop.addView(wuPill);
        wuSubBox.addView(wTop);

        LinearLayout wEditBar = new LinearLayout(this);
        wEditBar.setOrientation(LinearLayout.HORIZONTAL);
        wEditBar.setGravity(Gravity.CENTER_VERTICAL);
        wEditBar.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        wEditBar.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams weLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        weLp.topMargin = dp(10);
        final TextView wuListTxt = new TextView(this);
        wuListTxt.setTextSize(10);
        wuListTxt.setTypeface(Typeface.DEFAULT_BOLD);
        wuListTxt.setLetterSpacing(0.1f);
        wuListTxt.setTextColor(0xFF8A94A6);
        TextView wuEditLink = new TextView(this);
        wuEditLink.setText("EDIT  \u203A");
        wuEditLink.setTextSize(11);
        wuEditLink.setTypeface(Typeface.DEFAULT_BOLD);
        wuEditLink.setLetterSpacing(0.1f);
        wuEditLink.setTextColor(0xFF00B4D8);
        wEditBar.addView(wuListTxt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        wEditBar.addView(wuEditLink);
        wuSubBox.addView(wEditBar, weLp);
        voiceCard.addView(wuSubBox);
        root.addView(voiceCard);

        // 2) METRONOME + SPM STEPPER CARD
        final LinearLayout metroCard = new LinearLayout(this);
        metroCard.setOrientation(LinearLayout.VERTICAL);
        metroCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mcLp.topMargin = dp(12);

        LinearLayout mTop = new LinearLayout(this);
        mTop.setOrientation(LinearLayout.HORIZONTAL);
        mTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout mTxtCol = new LinearLayout(this);
        mTxtCol.setOrientation(LinearLayout.VERTICAL);
        TextView mTitle = new TextView(this);
        mTitle.setText("METRONOME");
        mTitle.setTextSize(14);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setLetterSpacing(0.12f);
        mTitle.setTextColor(0xFFF2F5FA);
        TextView mSub = new TextView(this);
        mSub.setText("Steady cadence click during run");
        mSub.setTextSize(11);
        mSub.setTextColor(0xFF8A94A6);
        mTxtCol.addView(mTitle);
        mTxtCol.addView(mSub);
        mTop.addView(mTxtCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView metroPill = new TextView(this);
        metroPill.setGravity(Gravity.CENTER);
        metroPill.setPadding(dp(14), dp(6), dp(14), dp(6));
        metroPill.setMinWidth(dp(54));
        metroPill.setTextSize(11);
        metroPill.setTypeface(Typeface.DEFAULT_BOLD);
        metroPill.setLetterSpacing(0.12f);
        mTop.addView(metroPill);
        metroCard.addView(mTop);

        LinearLayout stepRow = new LinearLayout(this);
        stepRow.setOrientation(LinearLayout.HORIZONTAL);
        stepRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(12);
        TextView spmMinus = new TextView(this);
        spmMinus.setText("\u2212");
        spmMinus.setTextSize(20);
        spmMinus.setTypeface(Typeface.DEFAULT_BOLD);
        spmMinus.setTextColor(0xFFF2F5FA);
        spmMinus.setGravity(Gravity.CENTER);
        spmMinus.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        final TextView spmVal = new TextView(this);
        spmVal.setGravity(Gravity.CENTER);
        spmVal.setTextSize(16);
        spmVal.setTypeface(Typeface.DEFAULT_BOLD);
        spmVal.setLetterSpacing(0.08f);
        TextView spmPlus = new TextView(this);
        spmPlus.setText("+");
        spmPlus.setTextSize(19);
        spmPlus.setTypeface(Typeface.DEFAULT_BOLD);
        spmPlus.setTextColor(0xFFF2F5FA);
        spmPlus.setGravity(Gravity.CENTER);
        spmPlus.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        stepRow.addView(spmMinus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        stepRow.addView(spmVal, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        stepRow.addView(spmPlus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        metroCard.addView(stepRow, srLp);
        root.addView(metroCard, mcLp);

        // 3) OFFLINE AREA MAP (.CMAP) CARD
        final LinearLayout mapCard = new LinearLayout(this);
        mapCard.setOrientation(LinearLayout.VERTICAL);
        mapCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mapLp.topMargin = dp(12);

        LinearLayout mpTop = new LinearLayout(this);
        mpTop.setOrientation(LinearLayout.HORIZONTAL);
        mpTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout mpTxtCol = new LinearLayout(this);
        mpTxtCol.setOrientation(LinearLayout.VERTICAL);
        TextView mpTitle = new TextView(this);
        mpTitle.setText("OFFLINE AREA MAP");
        mpTitle.setTextSize(14);
        mpTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mpTitle.setLetterSpacing(0.12f);
        mpTitle.setTextColor(0xFFF2F5FA);
        final TextView mpSub = new TextView(this);
        mpSub.setTextSize(11);
        mpSub.setTextColor(0xFF8A94A6);
        mpTxtCol.addView(mpTitle);
        mpTxtCol.addView(mpSub);
        mpTop.addView(mpTxtCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView mapPill = new TextView(this);
        mapPill.setGravity(Gravity.CENTER);
        mapPill.setPadding(dp(14), dp(6), dp(14), dp(6));
        mapPill.setMinWidth(dp(54));
        mapPill.setTextSize(11);
        mapPill.setTypeface(Typeface.DEFAULT_BOLD);
        mapPill.setLetterSpacing(0.12f);
        mpTop.addView(mapPill);
        mapCard.addView(mpTop);

        LinearLayout mpBtnRow = new LinearLayout(this);
        mpBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mpbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mpbLp.topMargin = dp(12);
        final TextView mpImportBtn = new TextView(this);
        mpImportBtn.setGravity(Gravity.CENTER);
        mpImportBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        mpImportBtn.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        mpImportBtn.setTextSize(11);
        mpImportBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mpImportBtn.setLetterSpacing(0.1f);
        mpImportBtn.setTextColor(0xFF00E5A0);
        final TextView mpClearBtn = new TextView(this);
        mpClearBtn.setText("REMOVE");
        mpClearBtn.setGravity(Gravity.CENTER);
        mpClearBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        mpClearBtn.setBackgroundResource(isAmoled() ? R.drawable.input_bg_a : R.drawable.input_bg);
        mpClearBtn.setTextSize(11);
        mpClearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        mpClearBtn.setLetterSpacing(0.1f);
        mpClearBtn.setTextColor(0xFFFFB454);
        LinearLayout.LayoutParams mpcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mpcLp.leftMargin = dp(8);
        mpBtnRow.addView(mpImportBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mpBtnRow.addView(mpClearBtn, mpcLp);
        mapCard.addView(mpBtnRow, mpbLp);
        root.addView(mapCard, mapLp);

        final Runnable syncUI = new Runnable() {
            @Override
            public void run() {
                boolean ttsOn = prefs.getBoolean("coach_tts", false);
                boolean wuOn = prefs.getBoolean("coach_warmup_on", false);
                boolean metroOn = prefs.getBoolean("coach_metro", false);
                int spm = prefs.getInt("coach_metro_spm", 172);
                CroMapPack mp = croMapPack;
                boolean mapOn = mp != null && prefs.getBoolean("map_show", true);
                List<CroSiteService.WarmMove> mv = CroSiteService.loadWarmupMoves(MainActivity.this);
                int sumS = 0;
                for (CroSiteService.WarmMove m : mv) sumS += m.sec;

                voiceCard.setBackgroundResource(ttsOn ? R.drawable.badge_on : R.drawable.badge_off);
                ttsPill.setBackgroundResource(ttsOn ? R.drawable.badge_on : R.drawable.badge_off);
                ttsPill.setTextColor(ttsOn ? 0xFF00E5A0 : 0xFF66708A);
                ttsPill.setText(ttsOn ? "ON" : "OFF");

                wuSubBox.setAlpha(ttsOn ? 1f : 0.55f);
                boolean wuActive = ttsOn && wuOn;
                wuPill.setBackgroundResource(wuActive ? R.drawable.badge_on : R.drawable.badge_off);
                wuPill.setTextColor(wuActive ? 0xFF00E5A0 : 0xFF66708A);
                wuPill.setText(wuOn ? "ON" : "OFF");
                wSub.setText(ttsOn
                        ? (wuOn ? "Guides warm-up + 3..2..1 cooldown" : "Off \u00B7 skips straight to run")
                        : "Turns Voice Coach ON automatically");
                wuListTxt.setText(mv.size() + " MOVES  \u00B7  " + sumS + "S TOTAL");

                metroCard.setBackgroundResource(metroOn ? R.drawable.badge_on : R.drawable.badge_off);
                metroPill.setBackgroundResource(metroOn ? R.drawable.badge_on : R.drawable.badge_off);
                metroPill.setTextColor(metroOn ? 0xFF00B4D8 : 0xFF66708A);
                metroPill.setText(metroOn ? "ON" : "OFF");
                spmVal.setText(spm + "  SPM");
                spmVal.setTextColor(metroOn ? 0xFF00B4D8 : 0xFF8A94A6);

                mapCard.setBackgroundResource(mapOn ? R.drawable.badge_on : R.drawable.badge_off);
                mapPill.setBackgroundResource(mapOn ? R.drawable.badge_on : R.drawable.badge_off);
                mapPill.setTextColor(mapOn ? 0xFF00E5A0 : 0xFF66708A);
                mapPill.setText(mp == null ? "EMPTY" : (mapOn ? "ON" : "OFF"));
                if (mp == null) {
                    mpSub.setText("Load .cmap file from Corvo Map Cutter (0 KB bloat)");
                    mpImportBtn.setText("IMPORT .CMAP FILE");
                    mpClearBtn.setVisibility(View.GONE);
                } else {
                    String sz = mp.fileBytes >= 1048576L
                            ? String.format(Locale.US, "%.1f MB", mp.fileBytes / 1048576.0)
                            : Math.max(1L, Math.round(mp.fileBytes / 1024.0)) + " KB";
                    mpSub.setText(String.format(Locale.US, "%s \u00B7 %.0f\u00D7%.0fkm \u00B7 %s",
                            mp.areaName, mp.widthKm(), mp.heightKm(), sz));
                    mpImportBtn.setText("REPLACE .CMAP");
                    mpClearBtn.setVisibility(View.VISIBLE);
                }
                renderCroSiteUi();
            }
        };
        onRunSetupRefresh = syncUI;
        syncUI.run();

        View.OnClickListener flipTts = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !prefs.getBoolean("coach_tts", false);
                prefs.edit().putBoolean("coach_tts", next).apply();
                if (tState != TS_IDLE && (CroSiteEngine.isEnabled(MainActivity.this)
                        || prefs.getBoolean("coach_metro", false) || next)) {
                    CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_SYNC_COACH);
                }
                syncUI.run();
            }
        };
        vTop.setOnClickListener(flipTts);
        ttsPill.setOnClickListener(flipTts);

        wuPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tState != TS_IDLE) {
                    toast("Stop or reset timer to change warm-up");
                    return;
                }
                boolean next = !prefs.getBoolean("coach_warmup_on", false);
                SharedPreferences.Editor ed = prefs.edit().putBoolean("coach_warmup_on", next);
                if (next && !prefs.getBoolean("coach_tts", false)) {
                    ed.putBoolean("coach_tts", true);
                }
                ed.apply();
                syncUI.run();
            }
        });
        wEditBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWarmupEditor();
            }
        });

        View.OnClickListener flipMetro = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !prefs.getBoolean("coach_metro", false);
                prefs.edit().putBoolean("coach_metro", next).apply();
                if (tState != TS_IDLE && (CroSiteEngine.isEnabled(MainActivity.this)
                        || prefs.getBoolean("coach_tts", false) || next)) {
                    CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_SYNC_COACH);
                }
                syncUI.run();
            }
        };
        mTop.setOnClickListener(flipMetro);
        metroPill.setOnClickListener(flipMetro);

        spmMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int s = Math.max(140, prefs.getInt("coach_metro_spm", 172) - 2);
                prefs.edit().putInt("coach_metro_spm", s).putBoolean("coach_metro", true).apply();
                if (tState != TS_IDLE) CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_SYNC_COACH);
                syncUI.run();
            }
        });
        spmPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int s = Math.min(210, prefs.getInt("coach_metro_spm", 172) + 2);
                prefs.edit().putInt("coach_metro_spm", s).putBoolean("coach_metro", true).apply();
                if (tState != TS_IDLE) CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_SYNC_COACH);
                syncUI.run();
            }
        });
        spmVal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = {"160 SPM \u00B7 EASY", "168 SPM \u00B7 CRUISE",
                        "172 SPM \u00B7 PRO", "176 SPM \u00B7 FAST", "180 SPM \u00B7 TEMPO", "186 SPM \u00B7 SPEED"};
                final int[] vals = {160, 168, 172, 176, 180, 186};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("CADENCE PRESET")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                prefs.edit().putInt("coach_metro_spm", vals[w])
                                        .putBoolean("coach_metro", true).apply();
                                if (tState != TS_IDLE) CroSiteService.dispatch(MainActivity.this, CroSiteService.ACT_SYNC_COACH);
                                syncUI.run();
                            }
                        }).show();
            }
        });

        View.OnClickListener flipMap = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (croMapPack == null) {
                    doImportCmap();
                    return;
                }
                boolean next = !prefs.getBoolean("map_show", true);
                prefs.edit().putBoolean("map_show", next).apply();
                syncUI.run();
            }
        };
        mpTop.setOnClickListener(flipMap);
        mapPill.setOnClickListener(flipMap);
        mpImportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doImportCmap();
            }
        });
        mpClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CroMapPack.clear(MainActivity.this);
                croMapPack = null;
                if (croMapView != null) croMapView.setPack(null);
                toast("Offline map removed");
                syncUI.run();
            }
        });

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(root);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("RUN SETUP")
                .setView(sc)
                .setPositiveButton("DONE", null)
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                onRunSetupRefresh = null;
            }
        });
        dlg.show();
    }

    // ================= RENDER =================

    private void render() {
        String today = RunStore.today();
        RunStore.Run t = store.get(today);

        if (t != null) {
            inputPane.setVisibility(View.GONE);
            statPane.setVisibility(View.VISIBLE);
            double speed = t.km / (t.min / 60.0);
            bigSpeed.setText(String.format(Locale.US, "%.2f", speed));
            double cal = store.calOf(t);
            speedTag.setText(tagFor(speed) + (cal > 0
                    ? " \u00B7 " + String.format(Locale.US, "%.0f CAL", cal) : ""));
            countUp(miniKm, t.km, 2, " km");
            miniMin.setText(timeText(t.min));
            miniPace.setText(String.format(Locale.US, "%.1f min/km", t.min / t.km));
            renderMoods(t);
        } else {
            statPane.setVisibility(View.GONE);
            inputPane.setVisibility(View.VISIBLE);
        }
        boolean restToday = store.isRestDay(today);
        todayDate.setText(RunStore.pretty(today));
        restBanner.setVisibility(restToday ? View.VISIBLE : View.GONE);

        bars.setData(store.lastDays(7), RunStore.dayLabels(7), 6);

        renderTrend();
        renderScore();
        renderPinned();
        renderHistory();
        renderRecords();
        renderCategory();
        renderRecovery();
        setupTimeBar();
        CorvoWidget.refresh(this);
    }

    private void renderMoods(final RunStore.Run t) {
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            mPre[i].setText(MOOD_PRE[i]);
            mPost[i].setText(MOOD_POST[i]);
            styleMood(mPre[i], t.m1 == idx);
            styleMood(mPost[i], t.m2 == idx);
            mPre[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setMood(t.d, 0, t.m1 == idx ? -1 : idx);
                    render();
                }
            });
            mPost[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setMood(t.d, 1, t.m2 == idx ? -1 : idx);
                    render();
                }
            });
        }
    }

    private void styleMood(TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.tab_active : 0);
        tv.setAlpha(on ? 1f : 0.35f);
    }

    private void renderTrend() {
        double[] p = store.lastPaces(14);
        paceLine.setData(p);
        if (p.length >= 6) {
            double recent = (p[p.length - 1] + p[p.length - 2] + p[p.length - 3]) / 3.0;
            double prior = (p[p.length - 4] + p[p.length - 5] + p[p.length - 6]) / 3.0;
            int sec = (int) Math.round((prior - recent) * 60.0); // + = improving
            if (sec >= 3) {
                trendChip.setText("\u25B2 IMPROVING \u00B7 \u2212" + secText(sec) + "/KM");
                trendChip.setTextColor(0xFF00E5A0);
            } else if (sec <= -3) {
                trendChip.setText("\u25BC SLIPPING \u00B7 +" + secText(-sec) + "/KM");
                trendChip.setTextColor(0xFFFFB454);
            } else {
                trendChip.setText("STEADY");
                trendChip.setTextColor(0xFF66708A);
            }
        } else {
            trendChip.setText("TRACKING \u00B7 " + p.length + " RUNS");
            trendChip.setTextColor(0xFF66708A);
        }
    }

    private static String secText(int sec) {
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private void renderScore() {
        int s = store.latestScore();
        lastScoreCard.setVisibility(s > 0 ? View.VISIBLE : View.GONE);
        if (s > 0) countUp(lastScoreVal, s, 0, "");
        // mood insight — HOME pe, hamesha aaj ka real data (words, no emoji)
        int[] mi = store.moodInsight();
        moodCard.setVisibility(mi == null ? View.GONE : View.VISIBLE);
        if (mi != null) {
            moodText.setText("When you start " + MOOD_PRE_WORDS[mi[0]].toLowerCase(Locale.US)
                    + ", you run " + secText(mi[1]) + "/km "
                    + (mi[2] == 1 ? "faster" : "slower") + " than usual.");
        }
    }

    private void renderPinned() {
        List<RunStore.Run> pins = store.pinned();
        pinnedCard.setVisibility(pins.isEmpty() ? View.GONE : View.VISIBLE);
        pinnedList.removeAllViews();
        for (RunStore.Run r : pins) {
            TextView line = new TextView(this);
            line.setText("\u2B50 " + RunStore.shortDate(r.d) + " \u00B7 " + r.pin
                    + " \u00B7 " + timeText(r.min));
            line.setTextSize(12);
            line.setTextColor(0xFFF2F5FA);
            line.setPadding(0, dp(6), 0, 0);
            pinnedList.addView(line);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void renderRecords() {
        // ---- Time Machine view state (past only) ----
        RunStore st = store;
        String anchor = RunStore.today();
        if (viewDate != null && viewDate.compareTo(RunStore.today()) < 0 && viewStore != null) {
            anchor = viewDate;
            st = viewStore;
        }
        int cur = st.currentStreakAsOf(anchor);
        int best = st.bestStreak();
        double totalKm = st.totalKm();
        if (st != store) {
            tmBanner.setText("VIEWING \u00B7 " + RunStore.pretty(viewDate));
            tmBanner.setVisibility(View.VISIBLE);
            tmDate.setText(RunStore.shortDate(viewDate));
            tmBack.setVisibility(View.VISIBLE);
        } else {
            tmBanner.setVisibility(View.GONE);
            tmDate.setText("TODAY");
            tmBack.setVisibility(View.GONE);
        }
        recStreak.setText(String.valueOf(cur));
        recStreak.setTextColor(0xFFF2F5FA);
        recBest.setText("BEST " + best);
        recFlame.clearAnimation();
        if (cur > 0) {
            AlphaAnimation blink = new AlphaAnimation(0.35f, 1f);
            blink.setDuration(650L);
            blink.setRepeatMode(Animation.REVERSE);
            blink.setRepeatCount(Animation.INFINITE);
            recFlame.startAnimation(blink);
        }
        recTotal.setText(String.format(Locale.US, "%.1f km", totalKm));
        recTotal.setTextColor(0xFFF2F5FA);
        double lo = st.longest();
        double bp = st.bestPace();
        double ap = st.avgPace();
        recLong.setText(lo > 0 ? String.format(Locale.US, "%.2f km", lo) : "\u2014");
        recPace.setText(bp > 0 ? paceText(bp) : "\u2014");
        recSpeed.setText(st.bestSpeed() > 0
                ? String.format(Locale.US, "%.1f km/h", st.bestSpeed()) : "\u2014");
        recAvgPace.setText(ap > 0 ? paceText(ap) : "\u2014");
        recAvgSpeed.setText(st.avgSpeed() > 0
                ? String.format(Locale.US, "%.1f km/h", st.avgSpeed()) : "\u2014");

        // week vs last week (as-of in past mode, real in future mode)
        String mon = RunStore.weekMonday(anchor);
        String lastM = RunStore.dateShift(mon, -7);
        double tK = st.weekKm(mon), lK = st.weekKm(lastM);
        int tR = st.weekRuns(mon), lR = st.weekRuns(lastM);
        wkThisKm.setText(String.format(Locale.US, "%.1f km", tK));
        wkThisRuns.setText(tR + (tR == 1 ? " RUN" : " RUNS"));
        wkLastKm.setText(String.format(Locale.US, "%.1f km", lK));
        wkLastRuns.setText(lR + (lR == 1 ? " RUN" : " RUNS"));
        double dK = tK - lK;
        int dR = tR - lR;
        setDelta(wkDkm, dK, "KM");
        setDelta(wkDr, dR, "RUNS");
        if (tR == 0) {
            wkInsight.setText("THIS WEEK IS WAITING FOR ITS FIRST RUN");
        } else if (lR == 0) {
            wkInsight.setText("FIRST FULL WEEK \u2014 BENCHMARK SET");
        } else if (dK >= 1) {
            wkInsight.setText(String.format(Locale.US, "%.1f KM MORE THAN LAST WEEK", dK));
        } else if (dK <= -1) {
            wkInsight.setText(String.format(Locale.US, "%.1f KM BEHIND LAST WEEK", -dK));
        } else {
            wkInsight.setText("SAME DISTANCE AS LAST WEEK");
        }

        // ramp guard: this week vs last week
        if (lK >= 3) {
            double ratio = tK / lK;
            if (ratio >= 1.5) {
                loadChip.setText(String.format(Locale.US,
                        "LOAD %.1f\u00D7 LAST WEEK \u00B7 TAPER DOWN", ratio));
                loadChip.setTextColor(0xFFFFB454);
                loadChip.setVisibility(View.VISIBLE);
            } else if (ratio >= 0.8) {
                loadChip.setText(String.format(Locale.US,
                        "LOAD %.1f\u00D7 LAST WEEK \u00B7 SWEET SPOT", ratio));
                loadChip.setTextColor(0xFF00E5A0);
                loadChip.setVisibility(View.VISIBLE);
            } else {
                loadChip.setVisibility(View.GONE);
            }
        } else {
            loadChip.setVisibility(View.GONE);
        }

        // scores
        int sl = st.latestScore();
        scoreLatest.setText(sl > 0 ? String.valueOf(sl) : "\u2014");
        scoreBest.setText(String.valueOf(st.bestScore()));
        scoreAvg.setText(String.valueOf(st.avgScore()));
        scoreStrip.setData(st.lastScores(14));

        // badges
        int[] th = {7, 15, 30, 50, 100, 365};
        int[] ids = {R.id.b7, R.id.b15, R.id.b30, R.id.b50, R.id.b100, R.id.b365};
        for (int i = 0; i < th.length; i++) {
            TextView t = (TextView) findViewById(ids[i]);
            boolean on = cur >= th[i];
            t.setBackgroundResource(on ? R.drawable.badge_on : R.drawable.badge_off);
            t.setTextColor(on ? 0xFF00E5A0 : 0xFF4A5268);
            final int thv = th[i];
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRankDialog(thv);
                }
            });
        }
        int next = 0;
        for (int v : th) {
            if (cur < v) {
                next = v;
                break;
            }
        }
        recNext.setText(next > 0 ? "NEXT \u00B7 " + next + " DAYS" : "ALL DONE \uD83D\uDD25");


        // radar profile (ALL-TIME vs 14D FORM)
        renderRadar();
    }

    private void renderRadar() {
        if (radar == null) return;
        radar.setData(radarValues());
        if (radAllBtn != null && rad14Btn != null) {
            radAllBtn.setBackgroundResource(!radar14d ? R.drawable.badge_on : R.drawable.badge_off);
            radAllBtn.setTextColor(!radar14d ? 0xFF00E5A0 : 0xFF66708A);
            rad14Btn.setBackgroundResource(radar14d ? R.drawable.badge_on : R.drawable.badge_off);
            rad14Btn.setTextColor(radar14d ? 0xFF00E5A0 : 0xFF66708A);
        }
        if (radSubTxt != null) {
            radSubTxt.setText(radar14d
                    ? "LAST 14 DAYS · PACE · DISTANCE · RHYTHM · STREAK · SCORE"
                    : "ALL-TIME · PACE · DISTANCE · 30D RHYTHM · STREAK · SCORE");
        }
    }

    private void setDelta(TextView tv, double d, String unit) {
        String s;
        int col;
        if (d > 0.05) {
            s = String.format(Locale.US, "+%.1f %s \u25B2", d, unit);
            col = 0xFF00E5A0;
        } else if (d < -0.05) {
            s = String.format(Locale.US, "\u2212%.1f %s \u25BC", -d, unit);
            col = 0xFFFFB454;
        } else {
            s = String.format(Locale.US, "%.1f %s", d, unit);
            col = 0xFF66708A;
        }
        tv.setText(s);
        tv.setTextColor(col);
    }

    private void renderHistory() {
        long lastExp = Math.max(prefs.getLong("lastExport", 0L),
                prefs.getLong("lastAutoBackup", 0L));
        if (store.count() >= 3) {
            if (lastExp == 0L) {
                backupAge.setText("LAST BACKUP: NEVER");
                backupAge.setTextColor(0xFF4A5268);
                backupAge.setVisibility(View.VISIBLE);
            } else {
                long days = (System.currentTimeMillis() - lastExp) / 86400000L;
                if (days >= 8) {
                    backupAge.setText("LAST BACKUP: " + days
                            + (days == 1 ? " DAY AGO" : " DAYS AGO"));
                    backupAge.setTextColor(days >= 30 ? 0xFFFFB454 : 0xFF4A5268);
                    backupAge.setVisibility(View.VISIBLE);
                } else {
                    backupAge.setVisibility(View.GONE);
                }
            }
        } else {
            backupAge.setVisibility(View.GONE);
        }
        histList.removeAllViews();
        List<RunStore.Run> all = store.recent(Integer.MAX_VALUE);
        if (all.isEmpty()) {
            histEmpty.setVisibility(View.VISIBLE);
            return;
        }
        histEmpty.setVisibility(View.GONE);

        LinkedHashMap<String, List<RunStore.Run>> groups =
                new LinkedHashMap<String, List<RunStore.Run>>();
        for (RunStore.Run r : all) {
            String key = r.d.length() >= 7 ? r.d.substring(0, 7) : r.d;
            List<RunStore.Run> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<RunStore.Run>();
                groups.put(key, g);
            }
            g.add(r);
        }
        for (Map.Entry<String, List<RunStore.Run>> e : groups.entrySet()) {
            int cnt = 0;
            double sum = 0;
            for (RunStore.Run r : e.getValue()) {
                cnt++;
                sum += r.km;
            }
            View h = getLayoutInflater().inflate(R.layout.row_month, histList, false);
            ((TextView) h.findViewById(R.id.mTitle)).setText(RunStore.monthTitle(e.getKey()));
            ((TextView) h.findViewById(R.id.mStats)).setText(
                    String.format(Locale.US, "%d runs \u00B7 %.1f km", cnt, sum));
            histList.addView(h);
            for (final RunStore.Run r : e.getValue()) {
                View row = getLayoutInflater().inflate(R.layout.row_run, histList, false);
                ((TextView) row.findViewById(R.id.rDate)).setText(RunStore.pretty(r.d));
                row.findViewById(R.id.rRest).setVisibility(
                        store.isRestDay(r.d) ? View.VISIBLE : View.GONE);
                ((TextView) row.findViewById(R.id.rKm))
                        .setText(String.format(Locale.US, "%.2f km", r.km));
                ((TextView) row.findViewById(R.id.rTime)).setText(timeText(r.min));
                ((TextView) row.findViewById(R.id.rSpeed))
                        .setText(String.format(Locale.US, "%.1f", r.km / (r.min / 60.0)));
                histList.addView(makeSwipeable(r, row));
            }
        }
    }

    private View makeSwipeable(final RunStore.Run r, final View content) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView star = new TextView(this);
        star.setText("\u2B50");
        star.setTextSize(20);
        star.setGravity(Gravity.CENTER_VERTICAL);
        star.setPadding(dp(14), 0, 0, 0);
        star.setAlpha(0f);
        wrap.addView(star, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wrap.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        content.setOnTouchListener(new View.OnTouchListener() {
            float downX = 0, downY = 0;
            long downT = 0L;
            boolean horiz = false, decided = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        downT = e.getEventTime();
                        horiz = false;
                        decided = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!decided) {
                            if (dx > 20 && dx > Math.abs(dy) * 1.2f) {
                                horiz = true;
                                decided = true;
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            } else if (Math.abs(dy) > 20) {
                                decided = true;
                            }
                        }
                        if (horiz) {
                            float t = Math.max(0f, Math.min(1f, dx / 160f));
                            v.setTranslationX(t * 100f);
                            star.setAlpha(t);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        if (horiz) {
                            if (e.getX() - downX > 80f) {
                                String title = store.togglePin(r.d);
                                render();
                                toast(title.length() > 0
                                        ? "PINNED \u2B50 " + title : "UNPINNED");
                            }
                            v.animate().translationX(0).setDuration(150).start();
                            star.animate().alpha(0).setDuration(150).start();
                            horiz = false;
                            return true;
                        }
                        if (!decided && Math.abs(e.getX() - downX) < dp(12)
                                && Math.abs(e.getY() - downY) < dp(12)
                                && (e.getEventTime() - downT) < 450L) {
                            openRunDetail(r);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        if (horiz) {
                            v.animate().translationX(0).setDuration(150).start();
                            star.animate().alpha(0).setDuration(150).start();
                            horiz = false;
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
        return wrap;
    }

    private void openRunDetail(final RunStore.Run r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(10));

        // Top tags (rest / pin / mood)
        StringBuilder tag = new StringBuilder();
        if (store.isRestDay(r.d)) tag.append("REST DAY RUN");
        if (r.pin != null && r.pin.length() > 0) {
            if (tag.length() > 0) tag.append("  \u00B7  ");
            tag.append("\u2B50 ").append(r.pin);
        }
        if (tag.length() > 0) {
            TextView tg = new TextView(this);
            tg.setText(tag.toString());
            tg.setTextSize(10);
            tg.setTypeface(Typeface.DEFAULT_BOLD);
            tg.setLetterSpacing(0.12f);
            tg.setTextColor(0xFFFFB454);
            box.addView(tg);
        }

        // 2x2 clean stat tiles (dead-centered numbers + labels, zero column squeeze)
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = dp(8);
        addDetailTile(r1, fmtKm(r.km), "KM", 0xFF00E5A0, 0);
        addDetailTile(r1, timeText(r.min), "TIME", 0xFFF2F5FA, dp(8));
        box.addView(r1, r1Lp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = dp(8);
        addDetailTile(r2, paceText(r.min / r.km), "MIN/KM", 0xFFF2F5FA, 0);
        addDetailTile(r2, String.format(Locale.US, "%.1f", r.km / (r.min / 60.0)), "KM/H", 0xFF00B4D8, dp(8));
        box.addView(r2, r2Lp);

        int sc = store.scoreOf(r.d);
        if (sc > 0) detailRow(box, "RUN SCORE", sc + " / 100", 0xFF00E5A0);
        double cal = store.calOf(r);
        if (cal > 0) detailRow(box, "CALORIES (EST)", Math.round(cal) + " kcal", 0xFFF2F5FA);
        if (r.cadence > 0) detailRow(box, "AVG CADENCE", r.cadence + " SPM", 0xFF00B4D8);
        if (r.outPace > 0 && r.backPace > 0) {
            detailRow(box, "OUT / BACK SPLIT",
                    paceText(r.outPace) + "  /  " + paceText(r.backPace) + " /km", 0xFFF2F5FA);
        }
        if (r.bpm > 0) detailRow(box, "POST-RUN PULSE", r.bpm + " BPM", 0xFFFFB454);
        if (r.hrr60 > 0) detailRow(box, "HRR-60 RECOVERY", "\u2212" + r.hrr60 + " BPM", 0xFF00E5A0);
        if (r.m1 >= 0 || r.m2 >= 0) {
            String mTxt = (r.m1 >= 0 ? MOOD_PRE[r.m1] + " " + MOOD_PRE_WORDS[r.m1] : "\u2014")
                    + "  \u2192  "
                    + (r.m2 >= 0 ? MOOD_POST[r.m2] + " " + MOOD_POST_WORDS[r.m2] : "\u2014");
            detailRow(box, "MOOD", mTxt, 0xFFF2F5FA);
        }
        if (r.laps != null && r.laps.length > 0) {
            StringBuilder lSb = new StringBuilder();
            for (int i = 0; i < r.laps.length; i++) {
                if (i > 0) lSb.append("  \u00B7  ");
                lSb.append("#").append(i + 1).append(" ").append(timeText(r.laps[i]));
            }
            detailRow(box, "LAPS (" + r.laps.length + ")", lSb.toString(), 0xFF8A94A6);
        }

        android.widget.ScrollView scv = new android.widget.ScrollView(this);
        scv.addView(box);
        final boolean pinned = r.pin != null && r.pin.length() > 0;
        new AlertDialog.Builder(this)
                .setTitle(RunStore.pretty(r.d))
                .setView(scv)
                .setPositiveButton("SHARE CARD", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shareDayCard(r.d);
                    }
                })
                .setNeutralButton(pinned ? "UNPIN" : "PIN \u2B50", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String title = store.togglePin(r.d);
                        render();
                        toast(title.length() > 0 ? "PINNED \u2B50 " + title : "UNPINNED");
                    }
                })
                .setNegativeButton("DELETE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("DELETE THIS RUN?")
                                .setMessage("Remove " + RunStore.pretty(r.d) + " (" + fmtKm(r.km) + " km) permanently?")
                                .setPositiveButton("DELETE", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        store.remove(r.d);
                                        render();
                                        toast("Run deleted");
                                    }
                                })
                                .setNegativeButton("CANCEL", null)
                                .show();
                    }
                })
                .show();
    }

    private void addDetailTile(LinearLayout row, String val, String lbl, int valCol, int leftMarginPx) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundResource(isAmoled() ? R.drawable.tile_bg_a : R.drawable.tile_bg);
        tile.setPadding(dp(10), dp(12), dp(10), dp(12));

        TextView vTv = new TextView(this);
        vTv.setText(val);
        vTv.setTextSize(17);
        vTv.setTypeface(Typeface.DEFAULT_BOLD);
        vTv.setTextColor(valCol);
        vTv.setSingleLine(true);
        vTv.setGravity(Gravity.CENTER);

        TextView lTv = new TextView(this);
        lTv.setText(lbl);
        lTv.setTextSize(9);
        lTv.setLetterSpacing(0.15f);
        lTv.setTextColor(0xFF66708A);
        lTv.setSingleLine(true);
        lTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(3);

        tile.addView(vTv);
        tile.addView(lTv, llp);

        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = leftMarginPx;
        row.addView(tile, tLp);
    }

    private void detailRow(LinearLayout parent, String k, String v, int vCol) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);

        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextSize(10);
        kt.setLetterSpacing(0.12f);
        kt.setTextColor(0xFF66708A);

        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextSize(12);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        vt.setTextColor(vCol);
        vt.setGravity(Gravity.RIGHT);

        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kLp.rightMargin = dp(12);
        row.addView(kt, kLp);
        row.addView(vt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, lp);
    }

    // ================= CATEGORY (THE CROW) =================

    private static final int[] RANK_TH = {7, 15, 30, 50, 100, 365};
    private static final String[] RANK_NAMES = {
            "COUCH", "JOGGER", "RUNNER", "SPRINTER", "BEAST", "LEGEND"
    };

    /** His mapping: the crow you carry at streak s is the next milestone's form. */
    private static int rankIndex(int streak) {
        if (streak < 7) return 0;
        if (streak < 15) return 1;
        if (streak < 30) return 2;
        if (streak < 50) return 3;
        if (streak < 100) return 4;
        return 5;
    }

    private static int rankArt(int idx) {
        int[] art = {R.drawable.crow_7, R.drawable.crow_15, R.drawable.crow_30,
                R.drawable.crow_50, R.drawable.crow_100, R.drawable.crow_365};
        return art[idx];
    }

    private void renderCategory() {
        int idx = rankIndex(store.currentStreak());
        catCrow.setImageResource(rankArt(idx));
        catRank.setText(RANK_NAMES[idx]);
    }

    /** Tap a streak badge: see the crow form that streak unlocks. */
    private void showRankDialog(int th) {
        int idx = rankIndex(th);
        int cur = store.currentStreak();
        View v = getLayoutInflater().inflate(R.layout.dialog_rank, null);
        ((ImageView) v.findViewById(R.id.rdCrow)).setImageResource(rankArt(idx));
        ((TextView) v.findViewById(R.id.rdRank)).setText(RANK_NAMES[idx]);
        ((TextView) v.findViewById(R.id.rdAt)).setText("AT " + th + " DAY STREAK");
        TextView st = (TextView) v.findViewById(R.id.rdStatus);
        if (cur >= th) {
            st.setText("UNLOCKED \u2713");
            st.setTextColor(0xFF00E5A0);
        } else {
            st.setText((th - cur) + " DAYS TO GO");
            st.setTextColor(0xFFFFB454);
        }
        ((TextView) v.findViewById(R.id.rdNext)).setText(idx < 5
                ? "NEXT FORM \u00B7 " + RANK_NAMES[idx + 1] + " AT " + RANK_TH[idx] + " DAYS"
                : "FINAL FORM");
        new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Weekly silent snapshot — one file, replaced in place. */
    private void maybeAutoBackup() {
        if (store.count() < 3 || Build.VERSION.SDK_INT < 29) return;
        long last = Math.max(prefs.getLong("lastExport", 0L),
                prefs.getLong("lastAutoBackup", 0L));
        if (System.currentTimeMillis() - last < 7L * 24 * 60 * 60 * 1000L) return;
        try {
            android.net.Uri files = android.provider.MediaStore.Files.getContentUri("external");
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
            java.util.ArrayList<android.net.Uri> kill = new java.util.ArrayList<android.net.Uri>();
            android.database.Cursor c = getContentResolver().query(files,
                    new String[]{MediaStore.MediaColumns._ID}, sel,
                    new String[]{"Documents/Corvo%", "corvo-auto.json"}, null);
            if (c != null) {
                while (c.moveToNext()) {
                    kill.add(android.content.ContentUris.withAppendedId(files, c.getLong(0)));
                }
                c.close();
            }
            for (android.net.Uri u : kill) {
                try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "corvo-auto.json");
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Corvo");
            android.net.Uri uri = getContentResolver().insert(files, cv);
            if (uri == null) return;
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(store.toJSON().getBytes("UTF-8"));
            os.close();
            prefs.edit().putLong("lastAutoBackup", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    // ================= TIME MACHINE / RADAR / BREAKDOWN =================

    private void resetTimeMachine() {
        viewDate = null;
        viewStore = null;
        if (tmBar != null) tmBar.setSelected(RunStore.epochDay(RunStore.today()));
        renderRecords();
    }

    private void setupTimeBar() {
        List<RunStore.Run> all = store.recent(Integer.MAX_VALUE);
        if (all.isEmpty() || tmBar == null) return;
        String first = all.get(all.size() - 1).d;
        long minD = RunStore.epochDay(first);
        long todayD = RunStore.epochDay(RunStore.today());
        tmBar.setRange(minD, todayD, todayD);
        Map<Long, Float> dots = new HashMap<Long, Float>();
        double lo = store.longest();
        for (RunStore.Run r : all) {
            if (r.km > 0) {
                dots.put(RunStore.epochDay(r.d), (float) (lo > 0 ? r.km / lo : 0.5));
            }
        }
        tmBar.setDots(dots);
    }

    private double[] radarValues() {
        if (radar14d) {
            double pace14 = store.paceLast14();
            double paceV = pace14 <= 0 ? 0 : clamp01((8.5 - pace14) / 4.5);
            double distV = clamp01(Math.sqrt(store.kmLast14() / 70.0));
            double rhythmV = clamp01(store.runDaysLast14() / 14.0);
            double streakV = clamp01(store.currentStreak() / 30.0);
            double scoreV = clamp01(store.avgScoreLast14() / 100.0);
            return new double[]{paceV, distV, rhythmV, streakV, scoreV};
        }
        double pace = store.avgPace();
        double paceV = pace <= 0 ? 0 : clamp01((8.5 - pace) / 4.5);
        double distV = clamp01(Math.sqrt(store.totalKm() / 500.0));
        double rhythmV = clamp01(store.runDaysLast30() / 30.0);
        double streakV = clamp01(store.currentStreak() / 100.0);
        double scoreV = clamp01(store.avgScore() / 100.0);
        return new double[]{paceV, distV, rhythmV, streakV, scoreV};
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private void showScoreBreakdown() {
        String d = store.latestCountedDate();
        if (d == null) {
            toast("No runs yet");
            return;
        }
        double[] p = store.scoreParts(d);
        if (p == null) {
            toast("No score for this run");
            return;
        }
        String msg = "RUN \u00B7 " + RunStore.pretty(d) + "\n\n"
                + String.format(Locale.US, "DISTANCE\n%.2f km  (10-run avg %.2f)  \u2192  %d\n\n",
                        p[4], p[6], (int) p[0])
                + "PACE\n" + paceText(p[5] / p[4])
                + (p[7] > 0 ? "  (avg " + paceText(p[7]) + ")" : "")
                + "  \u2192  " + (int) p[1] + "\n\n"
                + String.format(Locale.US, "RHYTHM\n%d of 7 days  \u2192  %d\n\n",
                        (int) p[9], (int) p[2])
                + "IMPROVEMENT\n"
                + (p[8] > 0 ? "vs prev " + paceText(p[8]) : "first scored run")
                + "  \u2192  " + (int) p[3] + "\n\n"
                + "WEIGHTS \u00B7 DISTANCE 0.3 / PACE 0.4 / RHYTHM 0.2 / IMPROVEMENT 0.1";
        new AlertDialog.Builder(this)
                .setTitle("SCORE " + store.scoreOf(d) + " \u00B7 HOW")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    // ================= SHARE WEEK CARD =================

    private Bitmap buildPeriodCard(String sub, double[] paces, double wk, double wm, int wr,
                                    double bestKm) {
        int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF05070A);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setColor(0xFF1B2230);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRoundRect(new RectF(24, 24, W - 24, H - 24), 48, 48, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(0xFF00E5A0);
        p.setTextSize(76);
        p.setFakeBoldText(true);
        c.drawText("CORVO", 84, 158, p);
        p.setFakeBoldText(false);
        p.setColor(0xFF66708A);
        p.setTextSize(30);
        c.drawText(sub, 84, 214, p);

        // pace line hero
        p.setColor(0xFF232C42);
        p.setStrokeWidth(2);
        c.drawLine(84, 300, W - 84, 300, p);
        p.setTextSize(28);
        p.setColor(0xFF8A94A6);
        c.drawText("PACE THIS WEEK", 84, 346, p);
        if (paces.length == 0) {
            p.setColor(0xFF4A5268);
            p.setTextSize(40);
            c.drawText("NO RUNS THIS WEEK", 84, 520, p);
        } else {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            for (double v : paces) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
            }
            if (max - min < 0.0001) max = min + 1;
            float x0 = 110, x1 = W - 110, y0 = 400, y1 = 660;
            float iw = x1 - x0, ih = y1 - y0;
            double avgV = sum / paces.length;
            Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setColor(0xFF4A5268);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(3);
            android.graphics.DashPathEffect dp = new android.graphics.DashPathEffect(
                    new float[]{18, 18}, 0);
            dash.setPathEffect(dp);
            float ay = y0 + (float) ((avgV - min) / (max - min)) * ih;
            c.drawLine(x0, ay, x1, ay, dash);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(0xFF00E5A0);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(7);
            line.setStrokeCap(Paint.Cap.ROUND);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFFF2F5FA);
            int best = 0;
            for (int i = 1; i < paces.length; i++) {
                if (paces[i] < paces[best]) best = i;
            }
            float[] xs = new float[paces.length], ys = new float[paces.length];
            for (int i = 0; i < paces.length; i++) {
                xs[i] = paces.length == 1 ? (x0 + x1) / 2 : x0 + iw * i / (paces.length - 1);
                ys[i] = y0 + (float) ((paces[i] - min) / (max - min)) * ih;
            }
            for (int i = 1; i < paces.length; i++) {
                c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], line);
            }
            for (int i = 0; i < paces.length; i++) {
                if (i == best) {
                    dot.setColor(0xFF00E5A0);
                    c.drawCircle(xs[i], ys[i], 16, dot);
                    dot.setColor(0xFFF2F5FA);
                    c.drawCircle(xs[i], ys[i], 9, dot);
                } else {
                    c.drawCircle(xs[i], ys[i], 11, dot);
                }
            }
            long bt = Math.round(paces[best] * 60.0);
            p.setColor(0xFF00E5A0);
            p.setTextSize(32);
            p.setFakeBoldText(true);
            c.drawText(String.format(Locale.US, "BEST %d:%02d /KM",
                    bt / 60, (int) (bt % 60)), 84, 712, p);
            p.setFakeBoldText(false);
        }

        // numbers row
        String[][] nums = {
                {String.format(Locale.US, "%.1f", wk), "KM"},
                {compactTime(wm), "TIME"},
                {wk > 0 ? paceShort(wm / wk) : "\u2014", "AVG PACE"},
                {String.valueOf(wr), "RUNS"}
        };
        p.setTextSize(52);
        p.setFakeBoldText(true);
        p.setColor(0xFFF2F5FA);
        float colW = (W - 168) / 4f;
        for (int i = 0; i < 4; i++) {
            float cx = 84 + colW * i + colW / 2 - p.measureText(nums[i][0]) / 2;
            c.drawText(nums[i][0], cx, 850, p);
        }
        p.setFakeBoldText(false);
        p.setTextSize(26);
        p.setColor(0xFF66708A);
        for (int i = 0; i < 4; i++) {
            float cx = 84 + colW * i + colW / 2 - p.measureText(nums[i][1]) / 2;
            c.drawText(nums[i][1], cx, 892, p);
        }

        // v3.7: streak line OUT (uska order) — NAME block IN (identity se)
        p.setColor(0xFF00E5A0);
        p.setTextSize(46);
        p.setFakeBoldText(true);
        String nm = displayName();
        c.drawText(nm, 84, 985, p);
        p.setFakeBoldText(false);
        if (bestKm > 0) {
            p.setColor(0xFF8A94A6);
            p.setTextSize(28);
            String br = String.format(Locale.US, "BEST RUN \u00B7 %.1f KM", bestKm);
            c.drawText(br, 84, 1030, p);
        }

        // badges
        int[] th = {7, 15, 30, 50, 100, 365};
        int cs = store.currentStreak();
        float r = 38, gap = 122, startX = 84 + r;
        for (int i = 0; i < th.length; i++) {
            float cx = startX + gap * i, cy = 1120;
            boolean on = cs >= th[i];
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(on ? 0xFF00E5A0 : 0xFF232C42);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(32);
            p.setFakeBoldText(true);
            p.setColor(on ? 0xFF00E5A0 : 0xFF4A5268);
            String t = String.valueOf(th[i]);
            c.drawText(t, cx - p.measureText(t) / 2, cy + 11, p);
            p.setFakeBoldText(false);
        }

        p.setColor(0xFF4A5268);
        p.setTextSize(24);
        String foot = "MADE WITH CORVO";
        c.drawText(foot, W / 2 - p.measureText(foot) / 2, 1284, p);
        return bmp;
    }

    private void shareFlow() {
        new AlertDialog.Builder(this)
                .setTitle("SHARE")
                .setItems(new String[]{"RADAR PROFILE", "STAT CARD", "DAY CARD"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) shareRadar();
                                else if (w == 1) pickPeriod();
                                else pickDay();
                            }
                        })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void pickPeriod() {
        final String mon = RunStore.weekMonday(RunStore.today());
        final String lastM = RunStore.dateShift(mon, -7);
        final List<String> months = store.monthsWithRuns();
        final String thisY = RunStore.today().substring(0, 4);
        final String lastY = String.valueOf(Integer.parseInt(thisY) - 1);
        final boolean hasLastY = store.yearRuns(lastY) > 0;
        List<String> labels = new ArrayList<String>();
        labels.add("THIS WEEK \u00B7 " + RunStore.shortDate(mon)
                + " \u2013 " + RunStore.shortDate(RunStore.dateShift(mon, 6)));
        labels.add("LAST WEEK \u00B7 " + RunStore.shortDate(lastM)
                + " \u2013 " + RunStore.shortDate(RunStore.dateShift(lastM, 6)));
        for (String ym : months) labels.add(RunStore.monthTitle(ym) + " \u00B7 MONTH");
        labels.add("THIS YEAR \u00B7 " + thisY);
        if (hasLastY) labels.add("LAST YEAR \u00B7 " + lastY);
        new AlertDialog.Builder(this)
                .setTitle("SHARE WHAT?")
                .setItems(labels.toArray(new String[0]),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) {
                                    sharePeriod(RunStore.shortDate(mon) + " \u2013 "
                                            + RunStore.shortDate(RunStore.dateShift(mon, 6))
                                            + "  \u00B7  WEEK REPORT",
                                            store.weekPaces(mon), store.weekKm(mon),
                                            store.weekMin(mon), store.weekRuns(mon),
                                            store.bestKmWeek(mon),
                                            "corvo-week-" + mon + ".png");
                                } else if (w == 1) {
                                    sharePeriod(RunStore.shortDate(lastM) + " \u2013 "
                                            + RunStore.shortDate(RunStore.dateShift(lastM, 6))
                                            + "  \u00B7  WEEK REPORT",
                                            store.weekPaces(lastM), store.weekKm(lastM),
                                            store.weekMin(lastM), store.weekRuns(lastM),
                                            store.bestKmWeek(lastM),
                                            "corvo-week-" + lastM + ".png");
                                } else if (w < 2 + months.size()) {
                                    String ym = months.get(w - 2);
                                    sharePeriod(RunStore.monthTitle(ym) + "  \u00B7  MONTH REPORT",
                                            store.monthPaces(ym), store.monthKm(ym),
                                            store.monthMin(ym), store.monthRuns(ym),
                                            store.bestKmMonth(ym),
                                            "corvo-" + ym + ".png");
                                } else if (w == 2 + months.size()) {
                                    sharePeriod(thisY + "  \u00B7  YEAR REPORT",
                                            store.yearPaces(thisY), store.yearKm(thisY),
                                            store.yearMin(thisY), store.yearRuns(thisY),
                                            store.bestKmYear(thisY),
                                            "corvo-" + thisY + ".png");
                                } else {
                                    sharePeriod(lastY + "  \u00B7  YEAR REPORT",
                                            store.yearPaces(lastY), store.yearKm(lastY),
                                            store.yearMin(lastY), store.yearRuns(lastY),
                                            store.bestKmYear(lastY),
                                            "corvo-" + lastY + ".png");
                                }
                            }
                        })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void shareRadar() {
        try {
            Bitmap b = buildRadarCard();
            Uri uri = savePng(b, "corvo-profile-" + RunStore.today() + ".png");
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("image/png");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "SHARE PROFILE"));
            toast("Saved to Pictures/Corvo \u2713");
        } catch (Exception e) {
            toast("Share failed");
        }
    }

    private void sharePeriod(String sub, double[] paces, double wk, double wm, int wr,
                             double bestKm, String name) {
        try {
            Bitmap b = buildPeriodCard(sub, paces, wk, wm, wr, bestKm);
            Uri uri = savePng(b, name);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("image/png");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "SHARE RUN CARD"));
            toast("Saved to Pictures/Corvo \u2713");
        } catch (Exception e) {
            toast("Share failed");
        }
    }

    private Uri savePng(Bitmap b, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Corvo");
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("no uri");
            OutputStream os = getContentResolver().openOutputStream(uri);
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            return uri;
        }
        File dir = new File(Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                + "/Corvo");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        FileOutputStream fo = new FileOutputStream(f);
        b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        fo.close();
        return Uri.fromFile(f);
    }

    private Bitmap buildRadarCard() {
        int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF05070A);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setColor(0xFF1B2230);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRoundRect(new RectF(24, 24, W - 24, H - 24), 48, 48, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(0xFF00E5A0);
        p.setTextSize(76);
        p.setFakeBoldText(true);
        c.drawText("CORVO", 84, 158, p);
        p.setFakeBoldText(false);
        p.setColor(0xFF66708A);
        p.setTextSize(30);
        c.drawText((radar14d ? "14D FORM PROFILE \u00B7 " : "RUNNER PROFILE \u00B7 ")
                + RunStore.shortDate(RunStore.today()), 84, 214, p);

        RadarView.drawRadar(c, W / 2f, 640, 330, radarValues(), 34, 7);

        p.setTextSize(38);
        p.setFakeBoldText(true);
        p.setColor(0xFFF2F5FA);
        String stats = String.format(Locale.US, "%.1f KM \u00B7 %d DAY STREAK \u00B7 SCORE %d",
                store.totalKm(), store.currentStreak(), store.avgScore());
        c.drawText(stats, W / 2 - p.measureText(stats) / 2, 1075, p);

        p.setColor(0xFF00E5A0);
        p.setTextSize(84);
        p.setLetterSpacing(0.25f);
        String nm = displayName();
        c.drawText(nm, W / 2 - p.measureText(nm) / 2, 1195, p);
        p.setLetterSpacing(0f);
        p.setFakeBoldText(false);

        p.setColor(0xFF4A5268);
        p.setTextSize(24);
        String foot = "MADE WITH CORVO";
        c.drawText(foot, W / 2 - p.measureText(foot) / 2, 1284, p);
        return bmp;
    }

    // ================= DAY CARD + DOSSIER (v3.7) =================

    private void pickDay() {
        List<RunStore.Run> rec = store.recent(20);
        if (rec.isEmpty()) {
            toast("No runs yet");
            return;
        }
        final List<RunStore.Run> fr = rec;
        String[] labels = new String[fr.size()];
        for (int i = 0; i < fr.size(); i++) {
            RunStore.Run r = fr.get(i);
            labels[i] = RunStore.pretty(r.d) + " \u00B7 " + fmtKm(r.km) + " KM"
                    + (r.laps != null ? " \u00B7 " + r.laps.length + " LAPS" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("WHICH DAY?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shareDayCard(fr.get(w).d);
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void shareDayCard(String d) {
        try {
            Bitmap b = buildDayCard(d);
            Uri uri = savePng(b, "corvo-day-" + d + ".png");
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("image/png");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "SHARE DAY CARD"));
            toast("Saved to Pictures/Corvo \u2713");
        } catch (Exception e) {
            toast("Share failed");
        }
    }

    private void shareDossier() {
        try {
            Bitmap b = buildDossierCard();
            Uri uri = savePng(b, "corvo-dossier-" + RunStore.today() + ".png");
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("image/png");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "SHARE DOSSIER"));
            toast("Saved to Pictures/Corvo \u2713");
        } catch (Exception e) {
            toast("Share failed");
        }
    }

    private Bitmap buildDayCard(String d) {
        RunStore.Run r = store.get(d);
        int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF05070A);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1B2230);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRoundRect(new RectF(24, 24, W - 24, H - 24), 48, 48, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(0xFF00E5A0);
        p.setTextSize(76);
        p.setFakeBoldText(true);
        c.drawText("CORVO", 84, 158, p);
        p.setFakeBoldText(false);
        p.setColor(0xFF66708A);
        p.setTextSize(30);
        int runNo = 0;
        for (RunStore.Run x : store.recent(Integer.MAX_VALUE)) {
            if (x.d.compareTo(d) <= 0) runNo++;
        }
        c.drawText("DAY REPORT \u00B7 RUN #" + runNo, 84, 214, p);

        p.setColor(0xFF00E5A0);
        p.setTextSize(46);
        p.setFakeBoldText(true);
        c.drawText(displayName(), 84, 284, p);
        p.setFakeBoldText(false);

        p.setColor(0xFF232C42);
        p.setStrokeWidth(2);
        c.drawLine(84, 326, W - 84, 326, p);
        p.setStrokeWidth(0);

        // hero KM — overlap-safe: measure at draw size, phir label
        p.setTextSize(130);
        p.setFakeBoldText(true);
        p.setColor(0xFFF2F5FA);
        String kmS = String.format(Locale.US, "%.2f", r.km);
        c.drawText(kmS, 84, 480, p);
        float kmW = measure(p, kmS);
        p.setTextSize(30);
        p.setFakeBoldText(false);
        p.setColor(0xFF8A94A6);
        c.drawText("KM", 84 + kmW + 22, 480, p);

        // stat row: TIME / PACE / CAL / SCORE
        double cal = store.calOf(r);
        int score = store.scoreOf(d);
        String[][] nums = {
                {timeText(r.min), "TIME"},
                {paceShort(r.min / r.km), "PACE /KM"},
                {cal > 0 ? String.format(Locale.US, "%.0f", cal) : "\u2014", "CAL (EST)"},
                {score > 0 ? String.valueOf(score) : "\u2014", "RUN SCORE"}
        };
        p.setTextSize(46);
        p.setFakeBoldText(true);
        p.setColor(0xFFF2F5FA);
        float colW = (W - 168) / 4f;
        for (int i = 0; i < 4; i++) {
            float cx = 84 + colW * i + colW / 2 - measure(p, nums[i][0]) / 2;
            c.drawText(nums[i][0], cx, 580, p);
        }
        p.setFakeBoldText(false);
        p.setTextSize(24);
        p.setColor(0xFF66708A);
        for (int i = 0; i < 4; i++) {
            float cx = 84 + colW * i + colW / 2 - measure(p, nums[i][1]) / 2;
            c.drawText(nums[i][1], cx, 618, p);
        }

        // context rows — BODY (weight + BMI), avg speed, city, mood (words), rest
        int yy = 672;
        p.setTextSize(28);
        float bh = safeFloat("body_h"), bw = safeFloat("body_w");
        if (bh > 0 && bw > 0) {
            double bmi = bw / ((bh / 100.0) * (bh / 100.0));
            p.setColor(0xFF8A94A6);
            c.drawText(String.format(Locale.US, "BODY %.0f CM \u00B7 %.0f KG \u00B7 BMI %.1f",
                    bh, bw, bmi), 84, yy, p);
            yy += 46;
        }
        p.setColor(0xFF8A94A6);
        String spd = String.format(Locale.US, "AVG SPEED %.1f KM/H", r.km / (r.min / 60.0))
                + (r.cadence > 0 ? " \u00B7 " + r.cadence + " SPM" : "")
                + (r.hrr60 > 0 ? " \u00B7 HRR \u2212" + r.hrr60 : "");
        c.drawText(spd, 84, yy, p);
        yy += 46;
        if (r.outPace > 0 && r.backPace > 0) {
            p.setColor(r.backPace < r.outPace ? 0xFF00E5A0 : 0xFF00B4D8);
            c.drawText("OUT " + paceText(r.outPace) + "/KM \u00B7 BACK " + paceText(r.backPace) + "/KM"
                    + (r.backPace < r.outPace ? " \u00B7 NEGATIVE SPLIT" : ""), 84, yy, p);
            yy += 46;
        }
        p.setColor(0xFF8A94A6);
        String city = safeStr("city");
        if (city.length() > 0) {
            c.drawText("IN " + city, 84, yy, p);
            yy += 46;
        }
        if (r.m1 >= 0 || r.m2 >= 0) {
            String mood = "FELT " + (r.m1 >= 0 ? MOOD_PRE_WORDS[r.m1] : "\u2014")
                    + " \u2192 " + (r.m2 >= 0 ? MOOD_POST_WORDS[r.m2] : "\u2014");
            c.drawText(mood, 84, yy, p);
            yy += 46;
        }
        if (store.isRestDay(d)) {
            p.setColor(0xFFFFB454);
            c.drawText("REST DAY RUN \u00B7 STREAK +1", 84, yy, p);
            yy += 46;
        }

        // laps — content ke saath flow
        if (r.laps != null && r.laps.length > 0) {
            p.setTextSize(24);
            p.setColor(0xFF8A94A6);
            c.drawText("LAPS", 84, yy + 10, p);
            p.setTextSize(38);
            p.setFakeBoldText(true);
            StringBuilder sb = new StringBuilder();
            double fastest = Double.MAX_VALUE;
            for (double v : r.laps) fastest = Math.min(fastest, v);
            for (int i = 0; i < r.laps.length; i++) {
                if (i > 0) sb.append(" \u00B7 ");
                sb.append(clockText(r.laps[i]));
            }
            p.setColor(0xFFF2F5FA);
            c.drawText(sb.toString(), 84, yy + 56, p);
            p.setFakeBoldText(false);
            p.setTextSize(30);
            p.setColor(0xFF00E5A0);
            p.setFakeBoldText(true);
            String fs = "FASTEST LAP " + clockText(fastest);
            c.drawText(fs, 84, yy + 102, p);
            int half = r.laps.length / 2;
            double firstH = 0, secondH = 0;
            for (int i = 0; i < r.laps.length; i++) {
                if (i < half) firstH += r.laps[i]; else secondH += r.laps[i];
            }
            if (half > 0 && secondH < firstH) {
                c.drawText("NEGATIVE SPLIT \u00B7 2ND HALF FASTER", 84 + measure(p, fs) + 30, yy + 102, p);
            }
            p.setFakeBoldText(false);
            yy += 148;
        }

        // pace trend — week line, ye din highlighted; slot aligned (zyada content ho to hi neeche)
        String mon = RunStore.weekMonday(d);
        double[] paces = store.weekPaces(mon);
        int hi = 0;
        String dd = mon;
        while (dd.compareTo(d) < 0) {
            RunStore.Run x = store.get(dd);
            if (x != null && x.km > 0) hi++;
            dd = RunStore.dateShift(dd, 1);
        }
        if (paces.length > 0) {
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE, sum = 0;
            for (double v : paces) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; }
            if (mx - mn < 0.0001) mx = mn + 1;
            if (hi >= paces.length) hi = paces.length - 1;
            int gy = Math.min(Math.max(yy + 40, 1006), 1090);
            p.setTextSize(26);
            p.setColor(0xFF8A94A6);
            c.drawText("PACE THIS WEEK \u00B7 THIS DAY HIGHLIGHTED", 84, gy, p);
            float x0 = 110, x1 = W - 110, y0 = gy + 30, y1 = gy + 160;
            float iw = x1 - x0, ih = y1 - y0;
            double avgV = sum / paces.length;
            Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setColor(0xFF4A5268);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(3);
            dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{18, 18}, 0));
            float ay = y0 + (float) ((avgV - mn) / (mx - mn)) * ih;
            c.drawLine(x0, ay, x1, ay, dash);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(0xFF00E5A0);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(7);
            line.setStrokeCap(Paint.Cap.ROUND);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            float[] xs = new float[paces.length], ys = new float[paces.length];
            for (int i = 0; i < paces.length; i++) {
                xs[i] = paces.length == 1 ? (x0 + x1) / 2 : x0 + iw * i / (paces.length - 1);
                ys[i] = y0 + (float) ((paces[i] - mn) / (mx - mn)) * ih;
            }
            for (int i = 1; i < paces.length; i++) c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], line);
            for (int i = 0; i < paces.length; i++) {
                if (i == hi) {
                    dot.setColor(0xFF00E5A0);
                    c.drawCircle(xs[i], ys[i], 18, dot);
                    dot.setColor(0xFFF2F5FA);
                    c.drawCircle(xs[i], ys[i], 10, dot);
                } else {
                    dot.setColor(0xFFF2F5FA);
                    c.drawCircle(xs[i], ys[i], 10, dot);
                }
            }
        }

        p.setColor(0xFF4A5268);
        p.setTextSize(24);
        String foot = "MADE WITH CORVO";
        c.drawText(foot, W / 2f - measure(p, foot) / 2, 1284, p);
        return bmp;
    }

    private float measure(Paint p, String s) {
        return p.measureText(s);
    }

    private Bitmap buildDossierCard() {
        int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF05070A);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1B2230);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRoundRect(new RectF(24, 24, W - 24, H - 24), 48, 48, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(0xFF00E5A0);
        p.setTextSize(76);
        p.setFakeBoldText(true);
        c.drawText("CORVO", 84, 158, p);
        p.setFakeBoldText(false);
        p.setColor(0xFF66708A);
        p.setTextSize(30);
        c.drawText("RUNNER DOSSIER \u00B7 " + RunStore.shortDate(RunStore.today()), 84, 214, p);

        // current crow (rank art)
        int idx = rankIndex(store.currentStreak());
        android.graphics.drawable.BitmapDrawable bd =
                (android.graphics.drawable.BitmapDrawable) getResources().getDrawable(rankArt(idx));
        android.graphics.Bitmap cb = bd.getBitmap();
        int maxW = 300, maxH = 260;
        float sc = Math.min((float) maxW / cb.getWidth(), (float) maxH / cb.getHeight());
        int dw = Math.round(cb.getWidth() * sc), dh = Math.round(cb.getHeight() * sc);
        int cx = W / 2, cy = 380;
        android.graphics.Rect dst = new android.graphics.Rect(
                cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);
        c.drawBitmap(cb, null, dst, p);

        p.setColor(0xFF00E5A0);
        p.setTextSize(54);
        p.setFakeBoldText(true);
        p.setLetterSpacing(0.15f);
        String cn = codename();
        c.drawText(cn, W / 2f - measure(p, cn) / 2, 588, p);
        p.setLetterSpacing(0f);
        p.setFakeBoldText(false);
        p.setColor(0xFF66708A);
        p.setTextSize(22);
        String cl = "CODENAME \u00B7 GIVEN BY THE CROW";
        c.drawText(cl, W / 2f - measure(p, cl) / 2, 624, p);

        p.setColor(0xFF232C42);
        p.setStrokeWidth(2);
        c.drawLine(84, 662, W - 84, 662, p);
        p.setStrokeWidth(0);

        float bh = safeFloat("body_h"), bw = safeFloat("body_w");
        String city = safeStr("city");
        String first = store.firstRunDate();
        String[][] rows = {
                {"SUBJECT", displayName()},
                {"RANK", RANK_NAMES[idx]},
                {"BONDED SINCE", first != null ? RunStore.shortDate(first) : "\u2014"},
                {"CITY", city.length() > 0 ? city : "\u2014"},
                {"BODY", bh > 0 && bw > 0
                        ? String.format(Locale.US, "%.0f CM \u00B7 %.0f KG", bh, bw) : "\u2014"},
                {"STATUS", fileStatus()}
        };
        int y = 726;
        for (String[] row : rows) {
            p.setColor(0xFF66708A);
            p.setTextSize(26);
            c.drawText(row[0], 84, y, p);
            p.setColor(0xFFF2F5FA);
            p.setTextSize(40);
            p.setFakeBoldText(true);
            c.drawText(row[1], 460, y, p);
            p.setFakeBoldText(false);
            y += 64;
        }

        p.setColor(0xFF00E5A0);
        p.setTextSize(28);
        p.setFakeBoldText(true);
        c.drawText("THE RECORD", 84, y + 8, p);
        p.setFakeBoldText(false);
        p.setColor(0xFFF2F5FA);
        p.setTextSize(40);
        p.setFakeBoldText(true);
        String rec = String.format(Locale.US, "%.1f KM \u00B7 %d RUNS \u00B7 %.1f HRS",
                store.totalKm(), store.count(), store.totalMin() / 60.0);
        c.drawText(rec, W / 2f - measure(p, rec) / 2, y + 68, p);
        String st = "STREAK " + store.currentStreak() + " \u00B7 BEST " + store.bestStreak();
        c.drawText(st, W / 2f - measure(p, st) / 2, y + 124, p);
        p.setFakeBoldText(false);

        p.setColor(0xFF4A5268);
        p.setTextSize(24);
        String foot = "HE IS WATCHING \u00B7 MADE WITH CORVO";
        c.drawText(foot, W / 2f - measure(p, foot) / 2, 1284, p);
        return bmp;
    }

    // ================= EXPORT / IMPORT =================

    private void doExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "corvo-backup.json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void doImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void doImportCmap() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT_CMAP);
    }

    private void loadSavedMapAsync() {
        if (!CroMapPack.exists(this)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final CroMapPack loaded = CroMapPack.loadSaved(MainActivity.this);
                if (loaded != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            croMapPack = loaded;
                            if (croMapView != null) croMapView.setPack(loaded);
                            renderCroSiteUi();
                            if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_IMPORT_CMAP) {
            toast("Loading offline map...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final CroMapPack loaded = CroMapPack.importFromUri(MainActivity.this, u);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                croMapPack = loaded;
                                prefs.edit().putBoolean("map_show", true).apply();
                                if (croMapView != null) croMapView.setPack(loaded);
                                long kb = Math.max(1L, Math.round(loaded.fileBytes / 1024.0));
                                toast("Loaded " + loaded.areaName + " (" + kb + " KB) \u2713");
                                renderCroSiteUi();
                                if (onRunSetupRefresh != null) onRunSetupRefresh.run();
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast("Invalid .cmap file: " + (e.getMessage() != null ? e.getMessage() : "failed"));
                            }
                        });
                    }
                }
            }).start();
            return;
        }
        if (req == REQ_EXPORT) {
            OutputStream os = null;
            try {
                os = getContentResolver().openOutputStream(u);
                os.write(store.toJSON().getBytes(StandardCharsets.UTF_8));
                os.flush();
                prefs.edit().putLong("lastExport", System.currentTimeMillis()).apply();
                toast("Exported \u2713");
            } catch (Exception e) {
                toast("Export failed");
            } finally {
                try {
                    if (os != null) os.close();
                } catch (Exception ignored) {
                }
            }
        } else if (req == REQ_IMPORT) {
            InputStream is = null;
            try {
                is = getContentResolver().openInputStream(u);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                int c = store.importMerge(json);
                toast(c > 0 ? "Imported " + c + " runs \u2713" : "Nothing to import");
                resetTimeMachine();
                render();
            } catch (Exception e) {
                toast("Import failed");
            } finally {
                try {
                    if (is != null) is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ================= HELPERS =================

    private static String timeText(double min) {
        long totalSec = Math.round(min * 60.0);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            if (s > 0) return String.format(Locale.US, "%dh %02dm %02ds", h, m, s);
            return String.format(Locale.US, "%dh %02dm", h, m);
        }
        if (s > 0) return String.format(Locale.US, "%dm %02ds", m, s);
        return m + "m";
    }

    private static String paceText(double minPerKm) {
        long totalSec = Math.round(minPerKm * 60.0);
        return String.format(Locale.US, "%d:%02d /km", totalSec / 60, totalSec % 60);
    }

    private static String clockText(double min) {
        long totalSec = Math.max(0, Math.round(min * 60.0));
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String compactTime(double min) {
        long totalSec = Math.round(min * 60.0);
        long h = totalSec / 3600, m = (totalSec % 3600) / 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        return String.format(Locale.US, "%02d:%02d", m, totalSec % 60);
    }

    private static String paceShort(double minPerKm) {
        long totalSec = Math.round(minPerKm * 60.0);
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    private static String tagFor(double speed) {
        if (speed < 7) return "EASY DAY";
        if (speed < 9) return "STEADY";
        if (speed < 11) return "STRONG";
        if (speed < 13) return "FAST";
        return "BEAST MODE";
    }
}
