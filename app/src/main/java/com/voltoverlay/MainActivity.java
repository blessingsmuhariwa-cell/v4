package com.voltoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Battery Guru-inspired telemetry dashboard supporting Light and Dark themes,
 * auto-start after reboot, and free overlay positioning.
 */
public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 101;
    private static final int MAX_HISTORY_SAMPLES = 90;

    private static final class ThemePalette {
        final boolean dark;
        final int pageBg;
        final int cardBg;
        final int cardBorder;
        final int textPrimary;
        final int textSecondary;
        final int accentBlue;
        final int accentGreen;
        final int accentAmber;
        final int accentRed;
        final int trackBg;
        final int chartBg;
        final int chartGrid;
        final int btnBg;
        final int btnBorder;

        ThemePalette(boolean isDark) {
            this.dark = isDark;
            if (isDark) {
                this.pageBg = 0xFF0B1120;
                this.cardBg = 0xFF111827;
                this.cardBorder = 0xFF1E293B;
                this.textPrimary = 0xFFF8FAFC;
                this.textSecondary = 0xFF94A3B8;
                this.accentBlue = 0xFF38BDF8;
                this.accentGreen = 0xFF4ADE80;
                this.accentAmber = 0xFFFBBF24;
                this.accentRed = 0xFFF87171;
                this.trackBg = 0xFF1E293B;
                this.chartBg = 0xFF0F172A;
                this.chartGrid = 0xFF1E293B;
                this.btnBg = 0xFF1E293B;
                this.btnBorder = 0xFF334155;
            } else {
                this.pageBg = 0xFFF1F5F9;
                this.cardBg = 0xFFFFFFFF;
                this.cardBorder = 0xFFE2E8F0;
                this.textPrimary = 0xFF0F172A;
                this.textSecondary = 0xFF475569;
                this.accentBlue = 0xFF0284C7;
                this.accentGreen = 0xFF15803D;
                this.accentAmber = 0xFFB45309;
                this.accentRed = 0xFFC62828;
                this.trackBg = 0xFFE2E8F0;
                this.chartBg = 0xFFF8FAFC;
                this.chartGrid = 0xFFE2E8F0;
                this.btnBg = 0xFFE2E8F0;
                this.btnBorder = 0xFFCBD5E1;
            }
        }
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final double[] voltageHistory = new double[MAX_HISTORY_SAMPLES];
    private final double[] currentHistory = new double[MAX_HISTORY_SAMPLES];
    private int historyCount = 0;

    private ThemePalette palette;
    private ScrollView scrollView;
    private LinearLayout root;
    private TextView titleView;
    private TextView subtitleView;

    private LinearLayout heroCard;
    private LinearLayout usageCard;
    private LinearLayout healthCard;
    private LinearLayout liveCard;
    private LinearLayout ctrlCard;

    private TextView heroPctText;
    private TextView heroStateBadge;
    private TextView heroEstimateText;
    private GaugeBarView chargeGaugeBar;

    private TextView onBatteryTimeValue;
    private TextView screenOnTimeValue;
    private TextView estRemainingValue;
    private TextView healthPctValue;
    private TextView learnedCapValue;
    private TextView cyclesValue;

    private TextView voltageValue;
    private TextView currentValue;
    private TextView powerValue;
    private TextView tempValue;
    private TextView ocvEmptyValue;
    private TextView gaugeEngineValue;
    private TextView chartCaption;

    private Button allowOverlayBtn;
    private Button startOverlayBtn;
    private Button moveOverlayBtn;
    private Button stopOverlayBtn;

    private TelemetrySparklineView sparklineView;
    private OverlayService.LatestTelemetry lastRendered = OverlayService.latestTelemetry;

    private final BroadcastReceiver telemetryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !OverlayService.ACTION_TELEMETRY_UPDATE.equals(intent.getAction())) {
                return;
            }
            OverlayService.LatestTelemetry t = telemetryFromIntent(intent);
            lastRendered = t;
            renderTelemetry(t);
        }
    };

    private final Runnable fallbackPollRunnable = new Runnable() {
        @Override
        public void run() {
            lastRendered = OverlayService.latestTelemetry;
            renderTelemetry(lastRendered);
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        for (int i = 0; i < MAX_HISTORY_SAMPLES; i++) {
            voltageHistory[i] = Double.NaN;
            currentHistory[i] = Double.NaN;
        }

        palette = new ThemePalette(isSystemDarkMode());
        buildUi();
        applyPalette();
        renderTelemetry(lastRendered);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        palette = new ThemePalette((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        applyPalette();
        renderTelemetry(lastRendered);
    }

    private boolean isSystemDarkMode() {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void buildUi() {
        final int padBase = dp(14);
        final int gapMed = dp(10);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padBase, padBase, padBase, padBase);
        scrollView.addView(root);

        scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                root.setPadding(
                        padBase + bars.left,
                        padBase + bars.top,
                        padBase + bars.right,
                        padBase + bars.bottom
                );
                return insets;
            }
        });

        titleView = new TextView(this);
        titleView.setText("Volt Overlay");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        titleView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        root.addView(titleView);

        subtitleView = new TextView(this);
        subtitleView.setText("Fuel-gauge telemetry · Auto-starts on boot · Light/Dark aware");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        subtitleView.setPadding(0, dp(2), 0, gapMed);
        root.addView(subtitleView);

        heroCard = createCard(dp(14));
        LinearLayout heroTopRow = new LinearLayout(this);
        heroTopRow.setOrientation(LinearLayout.HORIZONTAL);
        heroTopRow.setGravity(Gravity.CENTER_VERTICAL);

        heroPctText = new TextView(this);
        heroPctText.setText("~--%");
        heroPctText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        heroPctText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        heroTopRow.addView(heroPctText, pctLp);

        heroStateBadge = new TextView(this);
        heroStateBadge.setText("ON BATTERY");
        heroStateBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        heroStateBadge.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        heroStateBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(999));
        heroStateBadge.setBackground(badgeBg);
        heroTopRow.addView(heroStateBadge);
        heroCard.addView(heroTopRow);

        heroEstimateText = new TextView(this);
        heroEstimateText.setText("Stabilizing runtime estimate...");
        heroEstimateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        heroEstimateText.setPadding(0, dp(6), 0, dp(10));
        heroCard.addView(heroEstimateText);

        chargeGaugeBar = new GaugeBarView(this);
        heroCard.addView(chargeGaugeBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
        ));
        addCardWithSpacing(root, heroCard, gapMed);

        usageCard = createCard(dp(12));
        usageCard.addView(createSectionHeader("USAGE & TIME ESTIMATES"));
        onBatteryTimeValue = addMetricRow(usageCard, "On battery time", "--");
        screenOnTimeValue = addMetricRow(usageCard, "Screen-on time (battery)", "--");
        estRemainingValue = addMetricRow(usageCard, "Estimated remaining", "--");
        addCardWithSpacing(root, usageCard, gapMed);

        healthCard = createCard(dp(12));
        healthCard.addView(createSectionHeader("BATTERY HEALTH & FUEL GAUGE"));
        healthPctValue = addMetricRow(healthCard, "Estimated health (vs 5100 mAh)", "--");
        learnedCapValue = addMetricRow(healthCard, "Fuel gauge capacity / rem.", "--");
        cyclesValue = addMetricRow(healthCard, "Learned clean cycles", "0");
        ocvEmptyValue = addMetricRow(healthCard, "OCV / 0% empty point", "--");
        gaugeEngineValue = addMetricRow(healthCard, "Cycle & sign state", "--");
        addCardWithSpacing(root, healthCard, gapMed);

        liveCard = createCard(dp(12));
        liveCard.addView(createSectionHeader("LIVE ELECTRICAL & THERMAL"));
        voltageValue = addMetricRow(liveCard, "Smoothed voltage", "-.-V");
        currentValue = addMetricRow(liveCard, "Current (+in / -out)", "--");
        powerValue = addMetricRow(liveCard, "Instantaneous power", "--");
        tempValue = addMetricRow(liveCard, "Battery temperature", "--");

        chartCaption = new TextView(this);
        chartCaption.setText("Recent history: Voltage (blue) & Current (amber)");
        chartCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        chartCaption.setPadding(0, dp(10), 0, dp(6));
        liveCard.addView(chartCaption);

        sparklineView = new TelemetrySparklineView(this);
        liveCard.addView(sparklineView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ));
        addCardWithSpacing(root, liveCard, gapMed);

        ctrlCard = createCard(dp(12));
        ctrlCard.addView(createSectionHeader("OVERLAY CONTROLS"));

        allowOverlayBtn = createActionButton("1. Allow display over other apps");
        allowOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            }
        });

        startOverlayBtn = createActionButton("2. Start overlay");
        startOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Do step 1 first", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQ_POST_NOTIFICATIONS
                        );
                    }
                }
                Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
                startForegroundService(serviceIntent);
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        lastRendered = OverlayService.latestTelemetry;
                        renderTelemetry(lastRendered);
                    }
                }, 250L);
            }
        });

        moveOverlayBtn = createActionButton("Move overlay (10s drag)");
        moveOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Do step 1 first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent moveIntent = new Intent(MainActivity.this, OverlayService.class);
                moveIntent.setAction(OverlayService.ACTION_MOVE);
                startForegroundService(moveIntent);
                Toast.makeText(
                        MainActivity.this,
                        "Drag overlay anywhere for 10 seconds",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        stopOverlayBtn = createActionButton("Stop overlay");
        stopOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent stopIntent = new Intent(MainActivity.this, OverlayService.class);
                stopIntent.setAction(OverlayService.ACTION_STOP);
                startService(stopIntent);
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnLp.bottomMargin = dp(8);
        ctrlCard.addView(allowOverlayBtn, new LinearLayout.LayoutParams(btnLp));
        ctrlCard.addView(startOverlayBtn, new LinearLayout.LayoutParams(btnLp));
        ctrlCard.addView(moveOverlayBtn, new LinearLayout.LayoutParams(btnLp));
        ctrlCard.addView(stopOverlayBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        root.addView(ctrlCard);
        setContentView(scrollView);
    }

    private void applyPalette() {
        scrollView.setBackgroundColor(palette.pageBg);
        titleView.setTextColor(palette.textPrimary);
        subtitleView.setTextColor(palette.textSecondary);
        heroPctText.setTextColor(palette.textPrimary);
        heroEstimateText.setTextColor(palette.textSecondary);
        chartCaption.setTextColor(palette.textSecondary);

        styleCard(heroCard, dp(14));
        styleCard(usageCard, dp(12));
        styleCard(healthCard, dp(12));
        styleCard(liveCard, dp(12));
        styleCard(ctrlCard, dp(12));

        styleSectionHeaders(usageCard);
        styleSectionHeaders(healthCard);
        styleSectionHeaders(liveCard);
        styleSectionHeaders(ctrlCard);

        styleMetricRows(usageCard);
        styleMetricRows(healthCard);
        styleMetricRows(liveCard);

        styleButton(allowOverlayBtn);
        styleButton(startOverlayBtn);
        styleButton(moveOverlayBtn);
        styleButton(stopOverlayBtn);

        sparklineView.applyColors(palette.chartBg, palette.chartGrid, palette.accentBlue, palette.accentAmber);
    }

    private void styleCard(LinearLayout card, int radiusDp) {
        if (card == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.cardBg);
        bg.setCornerRadius(radiusDp);
        bg.setStroke(dp(1), palette.cardBorder);
        card.setBackground(bg);
    }

    private void styleSectionHeaders(LinearLayout card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof TextView && child != chartCaption) {
                TextView tv = (TextView) child;
                String s = tv.getText().toString();
                if (s.equals(s.toUpperCase(Locale.US)) && s.length() > 4) {
                    tv.setTextColor(palette.accentBlue);
                }
            }
        }
    }

    private void styleMetricRows(LinearLayout card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                if (row.getChildCount() >= 2) {
                    View left = row.getChildAt(0);
                    View right = row.getChildAt(1);
                    if (left instanceof TextView) {
                        ((TextView) left).setTextColor(palette.textSecondary);
                    }
                    if (right instanceof TextView) {
                        ((TextView) right).setTextColor(palette.textPrimary);
                    }
                }
            }
        }
    }

    private void styleButton(Button btn) {
        if (btn == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.btnBg);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), palette.btnBorder);
        btn.setBackground(bg);
        btn.setTextColor(palette.textPrimary);
    }

    private Button createActionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(OverlayService.ACTION_TELEMETRY_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(telemetryReceiver, filter);
        }
        lastRendered = OverlayService.latestTelemetry;
        renderTelemetry(lastRendered);
        uiHandler.removeCallbacks(fallbackPollRunnable);
        uiHandler.postDelayed(fallbackPollRunnable, 1000L);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(fallbackPollRunnable);
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private void renderTelemetry(OverlayService.LatestTelemetry t) {
        if (t == null) {
            return;
        }

        int pct = clampInt(t.shownPct, 0, 100);
        String vStr = Double.isNaN(t.voltageV)
                ? "-.-V"
                : String.format(Locale.US, "%.2fV", t.voltageV);
        String voltShort = Double.isNaN(t.voltageV)
                ? "-.-V"
                : String.format(Locale.US, "%.1fV", t.voltageV);

        heroPctText.setText(String.format(Locale.US, "%s · ~%d%%", voltShort, pct));

        String stateLabel;
        int badgeColor;
        int badgeTextColor;
        if (t.full) {
            stateLabel = "FULL";
            badgeColor = palette.dark ? 0xFF14532D : 0xFFDCFCE7;
            badgeTextColor = palette.accentGreen;
        } else if (t.charging) {
            stateLabel = "CHARGING ⚡";
            badgeColor = palette.dark ? 0xFF0C4A6E : 0xFFE0F2FE;
            badgeTextColor = palette.accentBlue;
        } else if (t.plugged) {
            stateLabel = "PLUGGED";
            badgeColor = palette.dark ? 0xFF451A03 : 0xFFFEF3C7;
            badgeTextColor = palette.accentAmber;
        } else if (pct <= 15) {
            stateLabel = "LOW BATTERY";
            badgeColor = palette.dark ? 0xFF450A0A : 0xFFFEE2E2;
            badgeTextColor = palette.accentRed;
        } else {
            stateLabel = "ON BATTERY";
            badgeColor = palette.trackBg;
            badgeTextColor = palette.textPrimary;
        }
        heroStateBadge.setText(stateLabel);
        heroStateBadge.setTextColor(badgeTextColor);
        if (heroStateBadge.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) heroStateBadge.getBackground()).setColor(badgeColor);
        }

        String estText;
        if (t.estRemainingMs < 0L) {
            estText = "Stabilizing runtime estimate...";
        } else if (t.estIsCharge) {
            estText = "Est. time to full: " + formatDurationShort(t.estRemainingMs);
        } else {
            estText = "Est. battery time left: " + formatDurationShort(t.estRemainingMs);
        }
        heroEstimateText.setText(estText);

        int barColor = (!t.plugged && pct <= 15)
                ? palette.accentRed
                : (t.charging ? palette.accentBlue : palette.accentGreen);
        chargeGaugeBar.setColors(palette.trackBg, barColor);
        chargeGaugeBar.setPercent(pct);

        if (t.plugged) {
            onBatteryTimeValue.setText("Plugged in (0m)");
            screenOnTimeValue.setText("Plugged in");
        } else {
            onBatteryTimeValue.setText(formatDurationShort(t.batterySessionMs));
            screenOnTimeValue.setText(formatDurationShort(t.screenOnSessionMs));
        }

        estRemainingValue.setText(
                t.estRemainingMs < 0L
                        ? "Stabilizing..."
                        : ((t.estIsCharge ? "Full in " : "Left ~") + formatDurationShort(t.estRemainingMs))
        );

        healthPctValue.setText(String.format(Locale.US, "%.1f%%", t.healthPct));
        learnedCapValue.setText(String.format(
                Locale.US, "%.0f / %.0f mAh", t.remainingMah, t.capacityMah
        ));
        cyclesValue.setText(String.valueOf(t.cycleCount));
        ocvEmptyValue.setText(String.format(
                Locale.US, "%.2fV / 0%% @ ~%.2fV", t.ocvV, t.emptyV
        ));
        gaugeEngineValue.setText(
                t.cycleStatus + (t.signLearned ? " · sign OK" : " · sign auto")
        );

        voltageValue.setText(vStr);
        if (!t.hasCurrent) {
            currentValue.setText("Unavailable");
            powerValue.setText("--");
        } else {
            String signPrefix = t.currentMa > 0.5 ? "+" : "";
            currentValue.setText(String.format(Locale.US, "%s%.0f mA", signPrefix, t.currentMa));
            powerValue.setText(String.format(Locale.US, "%s%.2f W", signPrefix, t.powerW));
        }

        if (Double.isNaN(t.tempC)) {
            tempValue.setText("--");
        } else {
            tempValue.setText(String.format(Locale.US, "%.1f °C", t.tempC));
        }

        appendHistorySample(t.voltageV, t.hasCurrent ? t.currentMa : Double.NaN);
        sparklineView.setSeries(voltageHistory, currentHistory, historyCount);
    }

    private void appendHistorySample(double v, double iMa) {
        if (historyCount < MAX_HISTORY_SAMPLES) {
            voltageHistory[historyCount] = v;
            currentHistory[historyCount] = iMa;
            historyCount++;
        } else {
            System.arraycopy(voltageHistory, 1, voltageHistory, 0, MAX_HISTORY_SAMPLES - 1);
            System.arraycopy(currentHistory, 1, currentHistory, 0, MAX_HISTORY_SAMPLES - 1);
            voltageHistory[MAX_HISTORY_SAMPLES - 1] = v;
            currentHistory[MAX_HISTORY_SAMPLES - 1] = iMa;
        }
    }

    private static OverlayService.LatestTelemetry telemetryFromIntent(Intent intent) {
        OverlayService.LatestTelemetry t = new OverlayService.LatestTelemetry();
        t.voltageV = intent.getDoubleExtra(OverlayService.EXTRA_VOLTAGE_V, Double.NaN);
        t.rawVoltageV = intent.getDoubleExtra(OverlayService.EXTRA_RAW_VOLTAGE_V, Double.NaN);
        t.currentMa = intent.getDoubleExtra(OverlayService.EXTRA_CURRENT_MA, 0.0);
        t.smoothCurrentMa = intent.getDoubleExtra(OverlayService.EXTRA_SMOOTH_CURRENT_MA, Double.NaN);
        t.powerW = intent.getDoubleExtra(OverlayService.EXTRA_POWER_W, 0.0);
        t.tempC = intent.getDoubleExtra(OverlayService.EXTRA_TEMP_C, Double.NaN);
        t.plugged = intent.getBooleanExtra(OverlayService.EXTRA_PLUGGED, false);
        t.charging = intent.getBooleanExtra(OverlayService.EXTRA_CHARGING, false);
        t.full = intent.getBooleanExtra(OverlayService.EXTRA_FULL, false);
        t.status = intent.getIntExtra(OverlayService.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        t.shownPct = intent.getIntExtra(OverlayService.EXTRA_SHOWN_PCT, 0);
        t.remainingMah = intent.getDoubleExtra(OverlayService.EXTRA_REMAINING_MAH, 4900.0);
        t.capacityMah = intent.getDoubleExtra(OverlayService.EXTRA_CAPACITY_MAH, 4900.0);
        t.ratedMah = intent.getDoubleExtra(OverlayService.EXTRA_RATED_MAH, 5100.0);
        t.typicalMah = intent.getDoubleExtra(OverlayService.EXTRA_TYPICAL_MAH, 5200.0);
        t.healthPct = intent.getDoubleExtra(OverlayService.EXTRA_HEALTH_PCT, 96.1);
        t.cycleCount = intent.getIntExtra(OverlayService.EXTRA_CYCLE_COUNT, 0);
        t.batterySessionMs = intent.getLongExtra(OverlayService.EXTRA_BATTERY_SESSION_MS, 0L);
        t.screenOnSessionMs = intent.getLongExtra(OverlayService.EXTRA_SCREEN_ON_SESSION_MS, 0L);
        t.estRemainingMs = intent.getLongExtra(OverlayService.EXTRA_EST_REMAINING_MS, -1L);
        t.estIsCharge = intent.getBooleanExtra(OverlayService.EXTRA_EST_IS_CHARGE, false);
        t.emptyV = intent.getDoubleExtra(OverlayService.EXTRA_EMPTY_V, 3.49);
        t.ocvV = intent.getDoubleExtra(OverlayService.EXTRA_OCV_V, 3.85);
        t.signLearned = intent.getBooleanExtra(OverlayService.EXTRA_SIGN_LEARNED, false);
        String cs = intent.getStringExtra(OverlayService.EXTRA_CYCLE_STATUS);
        t.cycleStatus = (cs != null) ? cs : "";
        t.hasCurrent = intent.getBooleanExtra(OverlayService.EXTRA_HAS_CURRENT, false);
        return t;
    }

    private LinearLayout createCard(int paddingPx) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        return card;
    }

    private void addCardWithSpacing(LinearLayout parent, View card, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = bottomMargin;
        parent.addView(card, lp);
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView addMetricRow(LinearLayout parent, String label, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        row.addView(labelView, labelLp);

        TextView valView = new TextView(this);
        valView.setText(initialValue);
        valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        valView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        valView.setGravity(Gravity.END);
        row.addView(valView);

        parent.addView(row);
        return valView;
    }

    private static String formatDurationShort(long durationMs) {
        if (durationMs <= 0L) {
            return "0m";
        }
        long totalSec = durationMs / 1000L;
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%dh %02dm", hours, mins);
        }
        return String.format(Locale.US, "%dm", mins);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static final class GaugeBarView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int percent = 0;

        GaugeBarView(Context context) {
            super(context);
            trackPaint.setColor(0xFFE2E8F0);
            fillPaint.setColor(0xFF15803D);
        }

        void setColors(int trackColor, int fillColor) {
            this.trackPaint.setColor(trackColor);
            this.fillPaint.setColor(fillColor);
            invalidate();
        }

        void setPercent(int pct) {
            this.percent = clampInt(pct, 0, 100);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;

            rect.set(0f, 0f, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);

            float fillW = w * (percent / 100f);
            if (fillW > 0f) {
                rect.set(0f, 0f, Math.max(r * 2f, fillW), h);
                canvas.drawRoundRect(rect, r, r, fillPaint);
            }
        }
    }

    private static final class TelemetrySparklineView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint voltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint currPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        private double[] volts = new double[0];
        private double[] currents = new double[0];
        private int count = 0;

        TelemetrySparklineView(Context context) {
            super(context);
            bgPaint.setColor(0xFFF8FAFC);
            gridPaint.setColor(0xFFE2E8F0);
            gridPaint.setStrokeWidth(1f);

            voltPaint.setColor(0xFF0284C7);
            voltPaint.setStyle(Paint.Style.STROKE);
            voltPaint.setStrokeWidth(3f);

            currPaint.setColor(0xFFB45309);
            currPaint.setStyle(Paint.Style.STROKE);
            currPaint.setStrokeWidth(2.5f);
        }

        void applyColors(int bgColor, int gridColor, int voltColor, int currColor) {
            bgPaint.setColor(bgColor);
            gridPaint.setColor(gridColor);
            voltPaint.setColor(voltColor);
            currPaint.setColor(currColor);
            invalidate();
        }

        void setSeries(double[] vArr, double[] iArr, int n) {
            this.volts = vArr;
            this.currents = iArr;
            this.count = n;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            box.set(0f, 0f, w, h);
            canvas.drawRoundRect(box, 12f, 12f, bgPaint);

            canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint);
            canvas.drawLine(0f, h * 0.50f, w, h * 0.50f, gridPaint);
            canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint);

            if (count < 2) {
                return;
            }

            float prevVx = 0f;
            float prevVy = 0f;
            boolean hasPrevV = false;

            float prevCx = 0f;
            float prevCy = 0f;
            boolean hasPrevC = false;

            for (int i = 0; i < count; i++) {
                float x = (i / (float) (MAX_HISTORY_SAMPLES - 1)) * (w - 16f) + 8f;

                double vv = volts[i];
                if (!Double.isNaN(vv)) {
                    double normV = (vv - 3.30) / (4.45 - 3.30);
                    normV = Math.max(0.0, Math.min(1.0, normV));
                    float vy = (float) ((1.0 - normV) * (h - 16f) + 8f);
                    if (hasPrevV) {
                        canvas.drawLine(prevVx, prevVy, x, vy, voltPaint);
                    }
                    prevVx = x;
                    prevVy = vy;
                    hasPrevV = true;
                } else {
                    hasPrevV = false;
                }

                double cc = currents[i];
                if (!Double.isNaN(cc)) {
                    double normC = (cc + 3000.0) / 6000.0;
                    normC = Math.max(0.0, Math.min(1.0, normC));
                    float cy = (float) ((1.0 - normC) * (h - 16f) + 8f);
                    if (hasPrevC) {
                        canvas.drawLine(prevCx, prevCy, x, cy, currPaint);
                    }
                    prevCx = x;
                    prevCy = cy;
                    hasPrevC = true;
                } else {
                    hasPrevC = false;
                }
            }
        }
    }
}
