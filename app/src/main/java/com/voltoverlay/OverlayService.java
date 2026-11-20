package com.voltoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Foreground service that maintains a click-through always-on-top battery voltage
 * and software fuel-gauge overlay, plus publishes live telemetry for MainActivity.
 */
public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.voltoverlay.ACTION_STOP";
    public static final String ACTION_MOVE = "com.voltoverlay.ACTION_MOVE";
    public static final String ACTION_TELEMETRY_UPDATE = "com.voltoverlay.ACTION_TELEMETRY_UPDATE";

    public static final String EXTRA_VOLTAGE_V = "voltage_v";
    public static final String EXTRA_RAW_VOLTAGE_V = "raw_voltage_v";
    public static final String EXTRA_CURRENT_MA = "current_ma";
    public static final String EXTRA_SMOOTH_CURRENT_MA = "smooth_current_ma";
    public static final String EXTRA_POWER_W = "power_w";
    public static final String EXTRA_TEMP_C = "temp_c";
    public static final String EXTRA_PLUGGED = "plugged";
    public static final String EXTRA_CHARGING = "charging";
    public static final String EXTRA_FULL = "full";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SHOWN_PCT = "shown_pct";
    public static final String EXTRA_REMAINING_MAH = "remaining_mah";
    public static final String EXTRA_CAPACITY_MAH = "capacity_mah";
    public static final String EXTRA_RATED_MAH = "rated_mah";
    public static final String EXTRA_TYPICAL_MAH = "typical_mah";
    public static final String EXTRA_HEALTH_PCT = "health_pct";
    public static final String EXTRA_CYCLE_COUNT = "cycle_count";
    public static final String EXTRA_BATTERY_SESSION_MS = "battery_session_ms";
    public static final String EXTRA_SCREEN_ON_SESSION_MS = "screen_on_session_ms";
    public static final String EXTRA_EST_REMAINING_MS = "est_remaining_ms";
    public static final String EXTRA_EST_IS_CHARGE = "est_is_charge";
    public static final String EXTRA_EMPTY_V = "empty_v";
    public static final String EXTRA_OCV_V = "ocv_v";
    public static final String EXTRA_SIGN_LEARNED = "sign_learned";
    public static final String EXTRA_CYCLE_STATUS = "cycle_status";
    public static final String EXTRA_HAS_CURRENT = "has_current";

    private static final String PREFS_NAME = "gauge";
    private static final String CHANNEL_ID = "volt_overlay";
    private static final int NOTIF_ID = 1001;
    private static final int REQ_STOP = 2001;
    private static final int REQ_MOVE = 2002;

    private static final long TICK_MS = 1000L;
    private static final long MOVE_DURATION_MS = 10_000L;
    private static final long MAX_STALE_MS = 10L * 60L * 1000L;
    private static final long SAVE_NORMAL_MS = 30_000L;
    private static final long SAVE_LOW_MS = 5_000L;

    // Set to true to wipe all learned fuel-gauge state on startup.
    private static final boolean RESET_LEARNING = false;

    private static final double DEFAULT_CAPACITY_MAH = 4900.0;
    private static final double RATED_CAPACITY_MAH = 5100.0;
    private static final double TYPICAL_CAPACITY_MAH = 5200.0;
    private static final double CUT_V = 3.45;
    private static final double INTERNAL_RESISTANCE_OHM = 0.10;
    private static final double CHARGE_EFFICIENCY = 0.97;
    private static final double SMOOTH_ALPHA = 0.15;

    private static final double[] VT = {
        3.40, 3.50, 3.60, 3.70, 3.80, 3.90, 4.00, 4.10, 4.20, 4.30, 4.35
    };
    private static final double[] DEFAULT_PT = {
        0.0, 5.0, 12.0, 25.0, 41.0, 55.0, 67.0, 78.0, 88.0, 96.0, 100.0
    };

    private static final String[] SYSFS_VOLTAGE_PATHS = {
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now"
    };

    private static final String[] SYSFS_TEMP_PATHS = {
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp"
    };

    private static final int COLOR_BG_DARK = 0xB3000000;
    private static final int COLOR_BG_LOW_BATTERY = 0xE6C62828;
    private static final int COLOR_TEXT_WHITE = 0xFFFFFFFF;

    // Volatile snapshot readable immediately by MainActivity when opened or resumed.
    public static volatile LatestTelemetry latestTelemetry = new LatestTelemetry();

    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private GradientDrawable overlayBackground;
    private BatteryManager batteryManager;
    private PowerManager powerManager;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean userStop = false;
    private boolean moveModeActive = false;
    private boolean isDragging = false;
    private int touchStartRawX;
    private int touchStartRawY;
    private int touchStartParamX;
    private int touchStartParamY;

    private long lastTickElapsedMs = 0L;
    private long lastSaveWallMs = 0L;

    private double smoothV = Double.NaN;
    private double smoothI = Double.NaN;
    private boolean hasCurrentReading = false;

    private double capacityMah = DEFAULT_CAPACITY_MAH;
    private double remainingMah = DEFAULT_CAPACITY_MAH;
    private double typI = 400.0;
    private int cycleCount = 0;
    private double[] pt = new double[VT.length];

    private boolean signLearned = false;
    private int signMultiplier = 1;
    private int signCandidateDir = 0;
    private int signConsecutiveCount = 0;

    private int shownPct = -1;

    // Session & runtime tracking (on-battery usage, screen-on time, estimated time remaining).
    private long batterySessionMs = 0L;
    private long screenOnSessionMs = 0L;
    private Boolean prevPluggedState = null;
    private double smoothDischargeMa = 450.0;
    private double smoothChargeMa = 1200.0;

    // Clean cycle tracking (never learned from shutdowns).
    private boolean hadFullAnchor = false;
    private boolean cycleActive = false;
    private boolean cycleInvalidated = false;
    private boolean cycleCapacityRecorded = false;
    private double cycleDrawnMah = 0.0;
    private double cycleSleepDrawnMah = 0.0;
    private double[] knotDrawnMah = new double[VT.length];
    private boolean[] knotRecorded = new boolean[VT.length];

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                performTick();
            } catch (Throwable t) {
                showErrorState();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable endMoveModeRunnable = new Runnable() {
        @Override
        public void run() {
            setMoveMode(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        if (RESET_LEARNING) {
            wipeLearnedPrefs();
        }

        loadStateFromPrefs();
        createNotificationChannel();
        createOverlayWindow();

        try {
            performTick();
        } catch (Throwable t) {
            showErrorState();
        }
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStop = true;
            saveStateToPrefs(System.currentTimeMillis(), true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_MOVE.equals(intent.getAction())) {
            if (overlayView == null) {
                createOverlayWindow();
            }
            setMoveMode(true);
            ensureForegroundStarted();
            return userStop ? START_NOT_STICKY : START_STICKY;
        }

        userStop = false;
        ensureForegroundStarted();
        return START_STICKY;
    }

    private void ensureForegroundStarted() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Throwable t) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(endMoveModeRunnable);
        try {
            saveStateToPrefs(System.currentTimeMillis(), true);
        } catch (Throwable ignored) {
        }
        removeOverlayWindow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void wipeLearnedPrefs() {
        prefs.edit()
                .remove("capacity_mah")
                .remove("cycle_count")
                .remove("curve_pt")
                .remove("sign_learned")
                .remove("sign_multiplier")
                .remove("typ_i")
                .remove("remaining_mah")
                .remove("saved_ts")
                .remove("battery_session_ms")
                .remove("screen_on_session_ms")
                .apply();
    }

    private void loadStateFromPrefs() {
        System.arraycopy(DEFAULT_PT, 0, pt, 0, DEFAULT_PT.length);

        capacityMah = clampDouble(
                prefs.getFloat("capacity_mah", (float) DEFAULT_CAPACITY_MAH),
                0.4 * DEFAULT_CAPACITY_MAH,
                1.2 * DEFAULT_CAPACITY_MAH
        );
        cycleCount = Math.max(0, prefs.getInt("cycle_count", 0));
        typI = clampDouble(prefs.getFloat("typ_i", 400.0f), 150.0, 900.0);
        signLearned = prefs.getBoolean("sign_learned", false);
        int savedSign = prefs.getInt("sign_multiplier", 1);
        signMultiplier = (savedSign == -1) ? -1 : 1;

        String curveCsv = prefs.getString("curve_pt", null);
        if (curveCsv != null && !curveCsv.trim().isEmpty()) {
            parseCurveCsv(curveCsv);
        }
        sanitizeCurve();

        BatterySnapshot initialSnap = readBatterySnapshot();
        double estVoltage = !Double.isNaN(initialSnap.voltageV) ? initialSnap.voltageV : 3.85;
        double emptyV = computeEmptyVoltage();
        double estPct = curvePercent(estVoltage, emptyV);
        double voltageEstMah = clampDouble((estPct / 100.0) * capacityMah, 0.0, capacityMah);

        long savedTs = prefs.getLong("saved_ts", 0L);
        long nowMs = System.currentTimeMillis();
        boolean recentSave = (savedTs > 0L && (nowMs - savedTs) >= 0L && (nowMs - savedTs) < MAX_STALE_MS);

        if (recentSave && prefs.contains("remaining_mah")) {
            remainingMah = clampDouble(
                    prefs.getFloat("remaining_mah", (float) voltageEstMah),
                    0.0,
                    capacityMah
            );
        } else {
            remainingMah = voltageEstMah;
        }

        if (!initialSnap.plugged) {
            batterySessionMs = Math.max(0L, prefs.getLong("battery_session_ms", 0L));
            if (recentSave && (nowMs - savedTs) > 0L) {
                batterySessionMs += Math.min(nowMs - savedTs, 12L * 3600L * 1000L);
            }
            screenOnSessionMs = Math.max(0L, prefs.getLong("screen_on_session_ms", 0L));
        } else {
            batterySessionMs = 0L;
            screenOnSessionMs = 0L;
        }
        prevPluggedState = initialSnap.plugged;

        if (!Double.isNaN(initialSnap.voltageV)) {
            smoothV = initialSnap.voltageV;
        }
        if (initialSnap.hasCurrent) {
            smoothI = initialSnap.currentMa;
            hasCurrentReading = true;
            if (!initialSnap.plugged && initialSnap.currentMa < -20.0) {
                smoothDischargeMa = clampDouble(Math.abs(initialSnap.currentMa), 80.0, 5000.0);
            } else if (initialSnap.charging && initialSnap.currentMa > 20.0) {
                smoothChargeMa = clampDouble(initialSnap.currentMa, 100.0, 6000.0);
            }
        }

        shownPct = (int) Math.round(clampDouble((remainingMah / capacityMah) * 100.0, 0.0, 100.0));
        lastTickElapsedMs = SystemClock.elapsedRealtime();
        lastSaveWallMs = nowMs;
    }

    private void parseCurveCsv(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != VT.length) {
            return;
        }
        double[] parsed = new double[VT.length];
        try {
            for (int i = 0; i < VT.length; i++) {
                parsed[i] = Double.parseDouble(parts[i].trim());
            }
            System.arraycopy(parsed, 0, pt, 0, VT.length);
        } catch (NumberFormatException ignored) {
        }
    }

    private String serializeCurveCsv() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.2f", pt[i]));
        }
        return sb.toString();
    }

    private void sanitizeCurve() {
        pt[0] = 0.0;
        pt[pt.length - 1] = 100.0;
        for (int i = 1; i < pt.length - 1; i++) {
            double minBound = DEFAULT_PT[i] - 15.0;
            double maxBound = DEFAULT_PT[i] + 15.0;
            pt[i] = clampDouble(pt[i], minBound, maxBound);
        }
        for (int i = 1; i < pt.length; i++) {
            if (pt[i] < pt[i - 1] + 0.5) {
                pt[i] = pt[i - 1] + 0.5;
            }
        }
        pt[pt.length - 1] = 100.0;
        for (int i = pt.length - 2; i >= 1; i--) {
            if (pt[i] > pt[i + 1] - 0.5) {
                pt[i] = pt[i + 1] - 0.5;
            }
        }
        for (int i = 1; i < pt.length - 1; i++) {
            double minBound = DEFAULT_PT[i] - 15.0;
            double maxBound = DEFAULT_PT[i] + 15.0;
            pt[i] = clampDouble(pt[i], minBound, maxBound);
        }
        pt[0] = 0.0;
        pt[pt.length - 1] = 100.0;
    }

    private void saveStateToPrefs(long nowWallMs, boolean forceCommit) {
        SharedPreferences.Editor editor = prefs.edit()
                .putFloat("remaining_mah", (float) clampDouble(remainingMah, 0.0, capacityMah))
                .putLong("saved_ts", nowWallMs)
                .putFloat("capacity_mah", (float) capacityMah)
                .putInt("cycle_count", cycleCount)
                .putString("curve_pt", serializeCurveCsv())
                .putBoolean("sign_learned", signLearned)
                .putInt("sign_multiplier", signMultiplier)
                .putFloat("typ_i", (float) typI)
                .putLong("battery_session_ms", batterySessionMs)
                .putLong("screen_on_session_ms", screenOnSessionMs);

        if (overlayParams != null) {
            editor.putInt("overlay_x", overlayParams.x);
            editor.putInt("overlay_y", overlayParams.y);
        }

        if (forceCommit) {
            editor.commit();
        } else {
            editor.apply();
        }
        lastSaveWallMs = nowWallMs;
    }

    private void saveOverlayPosition() {
        if (prefs != null && overlayParams != null) {
            prefs.edit()
                    .putInt("overlay_x", overlayParams.x)
                    .putInt("overlay_y", overlayParams.y)
                    .apply();
        }
    }

    private void performTick() {
        long nowElapsedMs = SystemClock.elapsedRealtime();
        long nowWallMs = System.currentTimeMillis();

        double elapsedSec = (lastTickElapsedMs > 0L && nowElapsedMs >= lastTickElapsedMs)
                ? (nowElapsedMs - lastTickElapsedMs) / 1000.0
                : 1.0;
        long elapsedMs = Math.max(0L, Math.round(elapsedSec * 1000.0));
        lastTickElapsedMs = nowElapsedMs;

        BatterySnapshot snap = readBatterySnapshot();

        // Track on-battery duration & screen-on duration cleanly.
        if (prevPluggedState != null && prevPluggedState != snap.plugged) {
            if (!snap.plugged) {
                batterySessionMs = 0L;
                screenOnSessionMs = 0L;
            }
        }
        prevPluggedState = snap.plugged;

        boolean interactive = isScreenInteractive();
        if (!snap.plugged) {
            long cappedBatteryMs = Math.min(elapsedMs, 12L * 3600L * 1000L);
            batterySessionMs += cappedBatteryMs;
            if (interactive && elapsedSec <= 5.0) {
                screenOnSessionMs += elapsedMs;
            }
        }

        // Update current sign learning & signed current (mA).
        double iMa = 0.0;
        boolean currentAvailable = false;
        if (snap.plugged && snap.status == BatteryManager.BATTERY_STATUS_FULL) {
            iMa = 0.0;
            currentAvailable = true;
        } else if (snap.hasCurrent) {
            currentAvailable = true;
            double absI = Math.abs(snap.rawCurrentMa);
            int rawDir = (snap.rawCurrentMa > 0.0) ? 1 : ((snap.rawCurrentMa < 0.0) ? -1 : 0);

            if (!signLearned && snap.charging && absI > 300.0 && rawDir != 0) {
                if (rawDir == signCandidateDir) {
                    signConsecutiveCount++;
                } else {
                    signCandidateDir = rawDir;
                    signConsecutiveCount = 1;
                }
                if (signConsecutiveCount >= 10) {
                    signLearned = true;
                    signMultiplier = signCandidateDir;
                    prefs.edit()
                            .putBoolean("sign_learned", true)
                            .putInt("sign_multiplier", signMultiplier)
                            .apply();
                }
            }

            if (signLearned) {
                iMa = snap.rawCurrentMa * signMultiplier;
            } else {
                iMa = snap.plugged ? absI : -absI;
            }
        }

        // Smooth voltage and current.
        if (!Double.isNaN(snap.voltageV)) {
            if (Double.isNaN(smoothV)) {
                smoothV = snap.voltageV;
            } else {
                smoothV = smoothV + SMOOTH_ALPHA * (snap.voltageV - smoothV);
            }
        }

        if (currentAvailable) {
            if (!hasCurrentReading || Double.isNaN(smoothI)) {
                smoothI = iMa;
                hasCurrentReading = true;
            } else {
                smoothI = smoothI + SMOOTH_ALPHA * (iMa - smoothI);
            }
        }

        // Update smoothed discharge/charge rates for remaining-time estimation.
        if (!snap.plugged && currentAvailable && iMa < -15.0) {
            double instDischarge = clampDouble(Math.abs(iMa), 80.0, 5000.0);
            smoothDischargeMa = smoothDischargeMa + 0.08 * (instDischarge - smoothDischargeMa);
        } else if (snap.charging && currentAvailable && iMa > 25.0) {
            double instCharge = clampDouble(iMa, 100.0, 6000.0);
            smoothChargeMa = smoothChargeMa + 0.08 * (instCharge - smoothChargeMa);
        }

        // Update long-run discharge current (typI) and empty point (emptyV).
        if (!snap.charging && currentAvailable && iMa < 0.0) {
            double dischargeMa = Math.abs(iMa);
            typI = clampDouble(typI + (1.0 / 900.0) * (dischargeMa - typI), 150.0, 900.0);
        }
        double emptyV = computeEmptyVoltage();

        // Manage clean learning cycle state.
        updateCycleState(snap);

        // Fuel gauge step: sleep gaps vs active tick, coulomb counting, and voltage correction.
        if (!currentAvailable && !hasCurrentReading) {
            double vRef = !Double.isNaN(smoothV) ? smoothV : 3.85;
            double pctEst = curvePercent(vRef, emptyV);
            remainingMah = clampDouble((pctEst / 100.0) * capacityMah, 0.0, capacityMah);
        } else if (elapsedSec > 5.0) {
            if (snap.charging) {
                double chargeGapSec = Math.min(elapsedSec, 1800.0);
                double addedMah = iMa * (chargeGapSec / 3600.0) * CHARGE_EFFICIENCY;
                remainingMah += addedMah;
            } else {
                double gapHours = Math.min(elapsedSec / 3600.0, 12.0);
                double sleepDrain = 15.0 * gapHours;
                double oneSecDrain = currentAvailable ? (Math.abs(iMa) * (1.0 / 3600.0)) : 0.0;
                remainingMah -= (sleepDrain + oneSecDrain);
                if (cycleActive && !cycleInvalidated) {
                    cycleDrawnMah += (sleepDrain + oneSecDrain);
                    cycleSleepDrawnMah += (sleepDrain + oneSecDrain);
                }
            }
        } else {
            double dtHours = Math.max(0.0, elapsedSec) / 3600.0;
            if (currentAvailable) {
                double deltaMah = iMa * dtHours;
                if (snap.charging && deltaMah > 0.0) {
                    deltaMah *= CHARGE_EFFICIENCY;
                }
                remainingMah += deltaMah;
                if (cycleActive && !cycleInvalidated && !snap.plugged && iMa < 0.0) {
                    cycleDrawnMah += (-iMa * dtHours);
                }
            }
        }

        // Check sleep gap invalidity threshold (> 40% of charge drawn was sleep-estimated).
        if (cycleActive && !cycleInvalidated && cycleDrawnMah > 0.0) {
            if (cycleSleepDrawnMah > 0.40 * cycleDrawnMah) {
                cycleInvalidated = true;
            }
        }

        // Voltage estimate & soft pull toward OCV curve when |smoothI| < 1000 mA.
        double vForOcv = !Double.isNaN(smoothV) ? smoothV : 3.85;
        double currentForOcv = hasCurrentReading ? smoothI : 0.0;
        double ocv = vForOcv - (currentForOcv / 1000.0) * INTERNAL_RESISTANCE_OHM;
        double curvePct = curvePercent(ocv, emptyV);

        if (!hasCurrentReading) {
            remainingMah = (curvePct / 100.0) * capacityMah;
        } else if (Math.abs(currentForOcv) < 1000.0) {
            double k = (Math.abs(ocv - emptyV) <= 0.10) ? 0.02 : 0.001;
            double targetFromCurve = (curvePct / 100.0) * capacityMah;
            remainingMah = remainingMah + k * (targetFromCurve - remainingMah);
        }

        // Full anchor check.
        boolean fullAnchor = (snap.plugged && snap.status == BatteryManager.BATTERY_STATUS_FULL)
                || (snap.charging && !Double.isNaN(smoothV) && smoothV >= 4.33
                && (!hasCurrentReading || smoothI < 300.0));
        if (fullAnchor) {
            remainingMah = capacityMah;
            hadFullAnchor = true;
            cycleActive = false;
            cycleInvalidated = false;
            cycleCapacityRecorded = false;
            cycleDrawnMah = 0.0;
            cycleSleepDrawnMah = 0.0;
            resetKnots();
        }

        remainingMah = clampDouble(remainingMah, 0.0, capacityMah);

        // Record curve knots and capacity learning during a valid discharge cycle.
        if (cycleActive && !cycleInvalidated && !snap.plugged) {
            recordCurveKnots(ocv, emptyV);
            if (!cycleCapacityRecorded && ocv <= emptyV + 0.10 && cycleDrawnMah > 50.0) {
                double pctNow = clampDouble(curvePercent(ocv, emptyV), 0.0, 99.0);
                double denom = (100.0 - pctNow) / 100.0;
                if (denom > 0.01) {
                    double measured = cycleDrawnMah / denom;
                    if (measured >= 0.4 * DEFAULT_CAPACITY_MAH
                            && measured <= 1.2 * DEFAULT_CAPACITY_MAH) {
                        applyLearnedCycle(measured);
                        cycleCapacityRecorded = true;
                    }
                }
            }
        }

        // Compute displayed percent (moves at most 1 point per second toward target).
        int targetPct = (int) Math.round(clampDouble((remainingMah / capacityMah) * 100.0, 0.0, 100.0));
        if (shownPct < 0) {
            shownPct = targetPct;
        } else if (targetPct > shownPct) {
            shownPct = Math.min(targetPct, shownPct + 1);
        } else if (targetPct < shownPct) {
            shownPct = Math.max(targetPct, shownPct - 1);
        }

        // Estimate remaining discharge or charge duration.
        long estRemainingMs = -1L;
        boolean estIsCharge = false;
        if (snap.charging) {
            estIsCharge = true;
            double neededMah = Math.max(0.0, capacityMah - remainingMah);
            double effChargeMa = (hasCurrentReading && smoothI > 25.0)
                    ? smoothI * CHARGE_EFFICIENCY
                    : smoothChargeMa * CHARGE_EFFICIENCY;
            if (neededMah <= 1.0 || snap.full) {
                estRemainingMs = 0L;
            } else if (effChargeMa > 20.0) {
                double hours = neededMah / effChargeMa;
                estRemainingMs = Math.min((long) (hours * 3600.0 * 1000.0), 48L * 3600L * 1000L);
            }
        } else if (!snap.plugged) {
            estIsCharge = false;
            double rateMa = (hasCurrentReading && smoothI < -15.0)
                    ? Math.abs(smoothI)
                    : Math.max(typI, smoothDischargeMa);
            if (rateMa > 20.0 && remainingMah > 0.0) {
                double hours = remainingMah / rateMa;
                estRemainingMs = Math.min((long) (hours * 3600.0 * 1000.0), 96L * 3600L * 1000L);
            }
        }

        // Persistence every 30s (or every 5s when <= 15%).
        long saveIntervalMs = (shownPct <= 15) ? SAVE_LOW_MS : SAVE_NORMAL_MS;
        if (nowWallMs - lastSaveWallMs >= saveIntervalMs) {
            saveStateToPrefs(nowWallMs, false);
        }

        updateOverlayTextAndColor(shownPct, snap.plugged);
        publishTelemetry(snap, ocv, emptyV, estRemainingMs, estIsCharge);
    }

    private void publishTelemetry(
            BatterySnapshot snap,
            double ocv,
            double emptyV,
            long estRemainingMs,
            boolean estIsCharge
    ) {
        double activeCurrentMa = snap.hasCurrent ? snap.rawCurrentMa : 0.0;
        if (snap.plugged && snap.status == BatteryManager.BATTERY_STATUS_FULL) {
            activeCurrentMa = 0.0;
        } else if (snap.hasCurrent) {
            activeCurrentMa = signLearned
                    ? (snap.rawCurrentMa * signMultiplier)
                    : (snap.plugged ? Math.abs(snap.rawCurrentMa) : -Math.abs(snap.rawCurrentMa));
        }

        double vRef = !Double.isNaN(smoothV) ? smoothV : (!Double.isNaN(snap.voltageV) ? snap.voltageV : 0.0);
        double powerW = (vRef > 0.0) ? (vRef * (activeCurrentMa / 1000.0)) : 0.0;
        double healthPct = clampDouble((capacityMah / RATED_CAPACITY_MAH) * 100.0, 0.0, 120.0);

        String cycleStatus;
        if (snap.plugged) {
            cycleStatus = cycleActive ? "Paused (plugged in)" : (hadFullAnchor ? "Full anchor ready" : "Charging / idle");
        } else if (!hadFullAnchor) {
            cycleStatus = "Waiting for full charge anchor";
        } else if (cycleInvalidated) {
            cycleStatus = "Invalidated (plugged or sleep gap)";
        } else if (cycleCapacityRecorded) {
            cycleStatus = "Learned this cycle";
        } else if (cycleActive) {
            cycleStatus = String.format(Locale.US, "Active discharge (%.0f mAh drawn)", cycleDrawnMah);
        } else {
            cycleStatus = "Standby";
        }

        LatestTelemetry t = new LatestTelemetry();
        t.voltageV = smoothV;
        t.rawVoltageV = snap.voltageV;
        t.currentMa = activeCurrentMa;
        t.smoothCurrentMa = hasCurrentReading ? smoothI : Double.NaN;
        t.powerW = powerW;
        t.tempC = snap.tempC;
        t.plugged = snap.plugged;
        t.charging = snap.charging;
        t.full = snap.full;
        t.status = snap.status;
        t.shownPct = Math.max(0, shownPct);
        t.remainingMah = remainingMah;
        t.capacityMah = capacityMah;
        t.ratedMah = RATED_CAPACITY_MAH;
        t.typicalMah = TYPICAL_CAPACITY_MAH;
        t.healthPct = healthPct;
        t.cycleCount = cycleCount;
        t.batterySessionMs = batterySessionMs;
        t.screenOnSessionMs = screenOnSessionMs;
        t.estRemainingMs = estRemainingMs;
        t.estIsCharge = estIsCharge;
        t.emptyV = emptyV;
        t.ocvV = ocv;
        t.signLearned = signLearned;
        t.cycleStatus = cycleStatus;
        t.hasCurrent = currentAvailableOrSmooth();
        latestTelemetry = t;

        Intent broadcast = new Intent(ACTION_TELEMETRY_UPDATE);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_VOLTAGE_V, t.voltageV);
        broadcast.putExtra(EXTRA_RAW_VOLTAGE_V, t.rawVoltageV);
        broadcast.putExtra(EXTRA_CURRENT_MA, t.currentMa);
        broadcast.putExtra(EXTRA_SMOOTH_CURRENT_MA, t.smoothCurrentMa);
        broadcast.putExtra(EXTRA_POWER_W, t.powerW);
        broadcast.putExtra(EXTRA_TEMP_C, t.tempC);
        broadcast.putExtra(EXTRA_PLUGGED, t.plugged);
        broadcast.putExtra(EXTRA_CHARGING, t.charging);
        broadcast.putExtra(EXTRA_FULL, t.full);
        broadcast.putExtra(EXTRA_STATUS, t.status);
        broadcast.putExtra(EXTRA_SHOWN_PCT, t.shownPct);
        broadcast.putExtra(EXTRA_REMAINING_MAH, t.remainingMah);
        broadcast.putExtra(EXTRA_CAPACITY_MAH, t.capacityMah);
        broadcast.putExtra(EXTRA_RATED_MAH, t.ratedMah);
        broadcast.putExtra(EXTRA_TYPICAL_MAH, t.typicalMah);
        broadcast.putExtra(EXTRA_HEALTH_PCT, t.healthPct);
        broadcast.putExtra(EXTRA_CYCLE_COUNT, t.cycleCount);
        broadcast.putExtra(EXTRA_BATTERY_SESSION_MS, t.batterySessionMs);
        broadcast.putExtra(EXTRA_SCREEN_ON_SESSION_MS, t.screenOnSessionMs);
        broadcast.putExtra(EXTRA_EST_REMAINING_MS, t.estRemainingMs);
        broadcast.putExtra(EXTRA_EST_IS_CHARGE, t.estIsCharge);
        broadcast.putExtra(EXTRA_EMPTY_V, t.emptyV);
        broadcast.putExtra(EXTRA_OCV_V, t.ocvV);
        broadcast.putExtra(EXTRA_SIGN_LEARNED, t.signLearned);
        broadcast.putExtra(EXTRA_CYCLE_STATUS, t.cycleStatus);
        broadcast.putExtra(EXTRA_HAS_CURRENT, t.hasCurrent);
        sendBroadcast(broadcast);
    }

    private boolean currentAvailableOrSmooth() {
        return hasCurrentReading && !Double.isNaN(smoothI);
    }

    private boolean isScreenInteractive() {
        if (powerManager == null) {
            return true;
        }
        return powerManager.isInteractive();
    }

    private void updateCycleState(BatterySnapshot snap) {
        if (snap.plugged) {
            if (cycleActive) {
                cycleInvalidated = true;
            }
            return;
        }
        if (!cycleActive && hadFullAnchor && !cycleInvalidated) {
            cycleActive = true;
            cycleCapacityRecorded = false;
            cycleDrawnMah = 0.0;
            cycleSleepDrawnMah = 0.0;
            resetKnots();
        }
    }

    private void resetKnots() {
        for (int i = 0; i < VT.length; i++) {
            knotDrawnMah[i] = 0.0;
            knotRecorded[i] = false;
        }
    }

    private void recordCurveKnots(double ocv, double emptyV) {
        double vEval = (ocv < 3.70) ? remapBelow370(ocv, emptyV) : ocv;
        for (int k = 1; k < VT.length - 1; k++) {
            if (!knotRecorded[k] && vEval < VT[k]) {
                knotDrawnMah[k] = cycleDrawnMah;
                knotRecorded[k] = true;
            }
        }
    }

    private void applyLearnedCycle(double measuredCapacity) {
        for (int k = 1; k < VT.length - 1; k++) {
            if (knotRecorded[k]) {
                double truthPct = 100.0 * (1.0 - (knotDrawnMah[k] / measuredCapacity));
                pt[k] = pt[k] + 0.25 * (truthPct - pt[k]);
            }
        }
        sanitizeCurve();

        if (cycleCount == 0) {
            capacityMah = 0.5 * capacityMah + 0.5 * measuredCapacity;
        } else {
            capacityMah = 0.7 * capacityMah + 0.3 * measuredCapacity;
        }
        capacityMah = clampDouble(capacityMah, 0.4 * DEFAULT_CAPACITY_MAH, 1.2 * DEFAULT_CAPACITY_MAH);
        cycleCount++;

        saveStateToPrefs(System.currentTimeMillis(), false);
        refreshNotification();
    }

    private double computeEmptyVoltage() {
        return clampDouble(CUT_V + 0.10 * (typI / 1000.0), 3.20, 3.62);
    }

    private double remapBelow370(double v, double emptyV) {
        double denom = 3.70 - emptyV;
        if (denom <= 0.01) {
            return v;
        }
        return 3.40 + (v - emptyV) * (0.30 / denom);
    }

    private double curvePercent(double voltage, double emptyV) {
        if (voltage <= 3.40) {
            return 0.0;
        }
        if (voltage >= 4.35) {
            return 100.0;
        }
        double vEval = (voltage < 3.70) ? remapBelow370(voltage, emptyV) : voltage;
        if (vEval <= VT[0]) {
            return pt[0];
        }
        if (vEval >= VT[VT.length - 1]) {
            return pt[pt.length - 1];
        }
        for (int i = 0; i < VT.length - 1; i++) {
            if (vEval >= VT[i] && vEval <= VT[i + 1]) {
                double spanV = VT[i + 1] - VT[i];
                if (spanV <= 1e-9) {
                    return pt[i];
                }
                double frac = (vEval - VT[i]) / spanV;
                return pt[i] + frac * (pt[i + 1] - pt[i]);
            }
        }
        return 0.0;
    }

    private BatterySnapshot readBatterySnapshot() {
        BatterySnapshot snap = new BatterySnapshot();

        Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
        int pluggedExtra = 0;
        int extraVoltageMv = -1;
        int extraTempTenthsC = Integer.MIN_VALUE;

        if (sticky != null) {
            status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            pluggedExtra = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            extraVoltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            extraTempTenthsC = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        }

        snap.status = status;
        snap.plugged = (pluggedExtra != 0);
        snap.full = snap.plugged && (status == BatteryManager.BATTERY_STATUS_FULL);
        snap.charging = snap.plugged && !snap.full;

        double v = readSysfsVoltage();
        if (Double.isNaN(v) && extraVoltageMv > 0) {
            v = extraVoltageMv / 1000.0;
        }
        if (!Double.isNaN(v) && v >= 2.5 && v <= 5.0) {
            snap.voltageV = v;
        }

        double tempC = readSysfsTempC();
        if (Double.isNaN(tempC) && extraTempTenthsC != Integer.MIN_VALUE && extraTempTenthsC > -400 && extraTempTenthsC < 1200) {
            tempC = extraTempTenthsC / 10.0;
        }
        snap.tempC = tempC;

        if (batteryManager != null) {
            long curUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (curUa != Long.MIN_VALUE) {
                double curMa = curUa / 1000.0;
                if (Math.abs(curMa) <= 8000.0) {
                    snap.rawCurrentMa = curMa;
                    snap.currentMa = curMa;
                    snap.hasCurrent = true;
                }
            }
        }

        return snap;
    }

    private double readSysfsVoltage() {
        for (String path : SYSFS_VOLTAGE_PATHS) {
            String raw = readFirstLine(path);
            if (raw != null) {
                try {
                    long val = Long.parseLong(raw.trim());
                    double volts = (val > 100000L) ? (val / 1_000_000.0) : (val / 1000.0);
                    if (volts >= 2.5 && volts <= 5.0) {
                        return volts;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Double.NaN;
    }

    private double readSysfsTempC() {
        for (String path : SYSFS_TEMP_PATHS) {
            String raw = readFirstLine(path);
            if (raw != null) {
                try {
                    long tenths = Long.parseLong(raw.trim());
                    double c = tenths / 10.0;
                    if (c >= -20.0 && c <= 95.0) {
                        return c;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Double.NaN;
    }

    private String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void createOverlayWindow() {
        if (overlayView != null || windowManager == null) {
            return;
        }

        int padH = dp(8);
        int padV = dp(4);
        int defaultX = dp(12);
        int defaultY = dp(80);

        int savedX = prefs.getInt("overlay_x", defaultX);
        int savedY = prefs.getInt("overlay_y", defaultY);

        overlayBackground = new GradientDrawable();
        overlayBackground.setColor(COLOR_BG_DARK);
        overlayBackground.setCornerRadius(dp(6));

        overlayView = new TextView(this);
        overlayView.setTextColor(COLOR_TEXT_WHITE);
        overlayView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        overlayView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        overlayView.setPadding(padH, padV, padH, padV);
        overlayView.setBackground(overlayBackground);
        overlayView.setText("-.-V");

        overlayView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!moveModeActive || overlayParams == null || windowManager == null) {
                    return false;
                }
                int action = event.getActionMasked();
                int rawX = Math.round(event.getRawX());
                int rawY = Math.round(event.getRawY());

                if (action == MotionEvent.ACTION_DOWN) {
                    isDragging = true;
                    touchStartRawX = rawX;
                    touchStartRawY = rawY;
                    touchStartParamX = overlayParams.x;
                    touchStartParamY = overlayParams.y;
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE && isDragging) {
                    overlayParams.x = Math.max(0, touchStartParamX + (rawX - touchStartRawX));
                    overlayParams.y = Math.max(0, touchStartParamY + (rawY - touchStartRawY));
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams);
                    } catch (Throwable ignored) {
                    }
                    saveOverlayPosition();
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (isDragging) {
                        isDragging = false;
                        saveOverlayPosition();
                        return true;
                    }
                }
                return false;
            }
        });

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = savedX;
        overlayParams.y = savedY;

        if (Settings.canDrawOverlays(this)) {
            try {
                windowManager.addView(overlayView, overlayParams);
            } catch (Throwable ignored) {
            }
        }
    }

    private void setMoveMode(boolean enabled) {
        moveModeActive = enabled;
        isDragging = false;
        handler.removeCallbacks(endMoveModeRunnable);

        if (overlayParams != null && overlayView != null && windowManager != null) {
            if (enabled) {
                overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                handler.postDelayed(endMoveModeRunnable, MOVE_DURATION_MS);
            } else {
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                saveOverlayPosition();
            }
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Throwable ignored) {
            }
        }
    }

    private void removeOverlayWindow() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayView = null;
        }
    }

    private void updateOverlayTextAndColor(int pct, boolean plugged) {
        if (overlayView == null) {
            return;
        }
        String text;
        if (Double.isNaN(smoothV)) {
            text = "-.-V";
        } else {
            text = String.format(Locale.US, "%.1fV ~%d%%", smoothV, pct);
        }
        overlayView.setText(text);

        if (overlayBackground != null) {
            boolean lowOnBattery = !plugged && (pct <= 15);
            overlayBackground.setColor(lowOnBattery ? COLOR_BG_LOW_BATTERY : COLOR_BG_DARK);
        }
    }

    private void showErrorState() {
        if (overlayView != null) {
            overlayView.setText("err");
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Volt Overlay",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ongoing battery voltage and software fuel gauge status");
            nm.createNotificationChannel(channel);
        }
    }

    private String buildNotificationText() {
        double emptyV = computeEmptyVoltage();
        String emptyStr = String.format(Locale.US, "%.2f", emptyV);
        long capRound = Math.round(capacityMah);
        if (cycleCount <= 0) {
            return "Capacity " + capRound + " mAh (learning) · 0% at ~" + emptyStr + "V (plug in)";
        }
        String cycleWord = (cycleCount == 1) ? "cycle" : "cycles";
        return "Capacity " + capRound + " mAh (" + cycleCount + " " + cycleWord + ") · 0% at ~"
                + emptyStr + "V (plug in)";
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this,
                REQ_STOP,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent moveIntent = new Intent(this, OverlayService.class);
        moveIntent.setAction(ACTION_MOVE);
        PendingIntent movePi = PendingIntent.getService(
                this,
                REQ_MOVE,
                moveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Voltage overlay running")
                .setContentText(buildNotificationText())
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Stop", stopPi)
                .addAction(0, "Move", movePi)
                .build();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static double clampDouble(double val, double min, double max) {
        if (val < min) {
            return min;
        }
        if (val > max) {
            return max;
        }
        return val;
    }

    public static final class LatestTelemetry {
        public double voltageV = Double.NaN;
        public double rawVoltageV = Double.NaN;
        public double currentMa = 0.0;
        public double smoothCurrentMa = Double.NaN;
        public double powerW = 0.0;
        public double tempC = Double.NaN;
        public boolean plugged = false;
        public boolean charging = false;
        public boolean full = false;
        public int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
        public int shownPct = 0;
        public double remainingMah = 4900.0;
        public double capacityMah = 4900.0;
        public double ratedMah = 5100.0;
        public double typicalMah = 5200.0;
        public double healthPct = 96.1;
        public int cycleCount = 0;
        public long batterySessionMs = 0L;
        public long screenOnSessionMs = 0L;
        public long estRemainingMs = -1L;
        public boolean estIsCharge = false;
        public double emptyV = 3.49;
        public double ocvV = 3.85;
        public boolean signLearned = false;
        public String cycleStatus = "Waiting for full charge anchor";
        public boolean hasCurrent = false;
    }

    private static final class BatterySnapshot {
        double voltageV = Double.NaN;
        double tempC = Double.NaN;
        double rawCurrentMa = 0.0;
        double currentMa = 0.0;
        boolean hasCurrent = false;
        int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
        boolean plugged = false;
        boolean full = false;
        boolean charging = false;
    }
}
