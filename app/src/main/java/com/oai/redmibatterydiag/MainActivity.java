package com.oai.redmibatterydiag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT_CSV = 4401;
    private static final int SHIZUKU_PERMISSION_REQUEST = 4402;
    private static final int LIVE_HISTORY_LIMIT = 720;
    private static final int TEST_HISTORY_LIMIT = 4320;
    private static final long TEST_SAMPLE_MS = 5000L;

    private LinearLayout detailBox;
    private Switch showCodes;
    private TextView socText;
    private TextView chargeStateText;
    private TextView voltageText;
    private TextView currentText;
    private TextView powerText;
    private TextView tempText;
    private TextView protocolText;
    private TextView minMaxText;
    private TextView testStatusText;
    private TextView testSummaryText;
    private TextView shizukuStatusText;
    private ProgressBar socBar;
    private Button testButton;
    private Button exportButton;
    private Button shizukuButton;
    private LiveGraph socGraph;
    private LiveGraph voltageGraph;
    private LiveGraph currentGraph;
    private LiveGraph powerGraph;
    private LiveGraph tempGraph;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Sample> history = new ArrayList<>();
    private final ArrayList<Sample> testLog = new ArrayList<>();

    private boolean testActive = false;
    private long testStartMs = 0L;
    private long lastTestSampleMs = 0L;

    private double minTemp = Double.NaN;
    private double maxTemp = Double.NaN;
    private double minVoltage = Double.NaN;
    private double maxVoltage = Double.NaN;
    private double minCurrent = Double.NaN;
    private double maxCurrent = Double.NaN;
    private double minPower = Double.NaN;
    private double maxPower = Double.NaN;

    private IBatteryShellService shellService;
    private boolean shizukuBinding = false;
    private int shizukuRemoteUid = -1;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        updateShizukuState();
        tryBindShizukuService();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        shellService = null;
        shizukuBinding = false;
        shizukuRemoteUid = -1;
        updateShizukuState();
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku izni verildi", Toast.LENGTH_SHORT).show();
            tryBindShizukuService();
        } else {
            Toast.makeText(this, "Shizuku izni verilmedi", Toast.LENGTH_SHORT).show();
        }
        updateShizukuState();
    };

    private final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            shellService = IBatteryShellService.Stub.asInterface(service);
            shizukuBinding = false;
            try {
                shizukuRemoteUid = shellService.getRemoteUid();
            } catch (Exception ignored) {
                shizukuRemoteUid = -1;
            }
            updateShizukuState();
            refresh();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            shellService = null;
            shizukuBinding = false;
            shizukuRemoteUid = -1;
            updateShizukuState();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        updateShizukuState();
        tryBindShizukuService();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        updateShizukuState();
    }

    @Override protected void onPause() {
        if (!testActive) handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(30));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(root);

        TextView title = text("Redmi BatteryDiag", 26, true);
        title.setTextColor(Color.rgb(19, 24, 32));
        root.addView(title);

        TextView subtitle = text("v1.2 • canlı batarya, şarj testi ve gelişmiş Xiaomi diagnostik", 13, false);
        subtitle.setTextColor(Color.rgb(93, 101, 113));
        subtitle.setPadding(0, dp(3), 0, dp(14));
        root.addView(subtitle);

        LinearLayout hero = card();
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(hero, matchWrapWithBottom(10));

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(heroTop);

        socText = text("--%", 42, true);
        socText.setTextColor(Color.rgb(20, 103, 255));
        heroTop.addView(socText, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout stateBox = new LinearLayout(this);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.RIGHT);
        heroTop.addView(stateBox, new LinearLayout.LayoutParams(0, -2, 1f));

        chargeStateText = text("Veri bekleniyor", 15, true);
        chargeStateText.setGravity(Gravity.RIGHT);
        stateBox.addView(chargeStateText);

        protocolText = text("Şarj protokolü: --", 12, false);
        protocolText.setTextColor(Color.rgb(102, 109, 119));
        protocolText.setGravity(Gravity.RIGHT);
        protocolText.setPadding(0, dp(3), 0, 0);
        stateBox.addView(protocolText);

        socBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        socBar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(7));
        barLp.setMargins(0, dp(10), 0, 0);
        hero.addView(socBar, barLp);

        LinearLayout metrics1 = new LinearLayout(this);
        metrics1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(metrics1, matchWrapWithBottom(8));
        voltageText = addMetric(metrics1, "GERİLİM", "-- V", 0);
        currentText = addMetric(metrics1, "AKIM", "-- A", 8);

        LinearLayout metrics2 = new LinearLayout(this);
        metrics2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(metrics2, matchWrapWithBottom(10));
        powerText = addMetric(metrics2, "BATARYA GÜCÜ", "-- W", 0);
        tempText = addMetric(metrics2, "SICAKLIK", "-- °C", 8);

        TextView testTitle = sectionTitle("ŞARJ TESTİ");
        root.addView(testTitle);
        LinearLayout testCard = card();
        testCard.setOrientation(LinearLayout.VERTICAL);
        testCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(testCard, matchWrapWithBottom(10));

        testStatusText = text("Hazır • test başlatılmadı", 14, true);
        testCard.addView(testStatusText);
        testSummaryText = text("%10–20 civarında başlayıp %80'e kadar kayıt alırsan şarj eğrisini daha net görebilirsin.", 12, false);
        testSummaryText.setTextColor(Color.rgb(92, 99, 109));
        testSummaryText.setPadding(0, dp(5), 0, dp(8));
        testCard.addView(testSummaryText);

        LinearLayout testButtons = new LinearLayout(this);
        testButtons.setOrientation(LinearLayout.HORIZONTAL);
        testCard.addView(testButtons);
        testButton = addActionButton(testButtons, "Şarj testini başlat", v -> toggleChargeTest(), 0);
        exportButton = addActionButton(testButtons, "CSV dışa aktar", v -> exportCsv(), 8);
        exportButton.setEnabled(false);

        TextView accessTitle = sectionTitle("GELİŞMİŞ ERİŞİM • SHIZUKU");
        root.addView(accessTitle);
        LinearLayout shizukuCard = card();
        shizukuCard.setOrientation(LinearLayout.VERTICAL);
        shizukuCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(shizukuCard, matchWrapWithBottom(10));

        shizukuStatusText = text("Shizuku kontrol ediliyor…", 13, true);
        shizukuCard.addView(shizukuStatusText);
        TextView shizukuNote = text("Normal APK'nin okuyamadığı MU / USB sysfs alanlarını ADB-shell kimliğiyle okumayı dener. Root gerekmez; HyperOS SELinux yine de bazı alanları engelleyebilir.", 11, false);
        shizukuNote.setTextColor(Color.rgb(102, 109, 119));
        shizukuNote.setPadding(0, dp(4), 0, dp(8));
        shizukuCard.addView(shizukuNote);
        shizukuButton = new Button(this);
        shizukuButton.setAllCaps(false);
        shizukuButton.setText("Shizuku'yu bağla");
        shizukuButton.setOnClickListener(v -> handleShizukuButton());
        shizukuCard.addView(shizukuButton, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actionRow, matchWrapWithBottom(8));
        addActionButton(actionRow, "6485 menüsü", v -> openServiceMenu(), 0);
        addActionButton(actionRow, "Yenile", v -> refresh(), 8);

        showCodes = new Switch(this);
        showCodes.setText("MB/MU teknik kodlarını göster");
        showCodes.setTextSize(13);
        showCodes.setPadding(dp(3), dp(6), dp(3), dp(6));
        showCodes.setOnCheckedChangeListener((buttonView, isChecked) -> refresh());
        root.addView(showCodes);

        LinearLayout minMaxCard = card();
        minMaxCard.setOrientation(LinearLayout.VERTICAL);
        minMaxCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(minMaxCard, matchWrapWithBottom(10));

        LinearLayout mmHead = new LinearLayout(this);
        mmHead.setGravity(Gravity.CENTER_VERTICAL);
        minMaxCard.addView(mmHead);
        TextView mmTitle = text("Oturum min / max", 14, true);
        mmHead.addView(mmTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reset = new Button(this);
        reset.setText("Sıfırla");
        reset.setAllCaps(false);
        reset.setTextSize(12);
        reset.setOnClickListener(v -> resetMinMax());
        mmHead.addView(reset, new LinearLayout.LayoutParams(dp(92), dp(44)));

        minMaxText = text("Henüz veri yok", 12, false);
        minMaxText.setTextColor(Color.rgb(81, 88, 98));
        minMaxText.setPadding(0, dp(5), 0, 0);
        minMaxCard.addView(minMaxText);

        TextView graphTitle = sectionTitle("CANLI / TEST GRAFİKLERİ");
        root.addView(graphTitle);
        socGraph = new LiveGraph(this, LiveGraph.MODE_SOC, "SOC (%)");
        root.addView(socGraph, graphLayout());
        voltageGraph = new LiveGraph(this, LiveGraph.MODE_VOLTAGE, "Batarya gerilimi (V)");
        root.addView(voltageGraph, graphLayout());
        currentGraph = new LiveGraph(this, LiveGraph.MODE_CURRENT, "Batarya akımı (A)");
        root.addView(currentGraph, graphLayout());
        powerGraph = new LiveGraph(this, LiveGraph.MODE_POWER, "Batarya gücü (W)");
        root.addView(powerGraph, graphLayout());
        tempGraph = new LiveGraph(this, LiveGraph.MODE_TEMP, "Batarya sıcaklığı (°C)");
        root.addView(tempGraph, graphLayout());

        TextView detailsTitle = sectionTitle("DETAYLI DIAGNOSTİK");
        root.addView(detailsTitle);
        detailBox = card();
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(detailBox, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("Not: MB alanlarının önemli bölümü Android BatteryManager ile okunabilir. MU alanları Xiaomi/MediaTek vendor sysfs verisidir. Normal erişim başarısız olursa v1.2 Shizuku üzerinden shell kimliğiyle tekrar dener.", 11, false);
        note.setTextColor(Color.rgb(115, 120, 130));
        note.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(note);

        setContentView(scroll);
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 12, true);
        t.setTextColor(Color.rgb(80, 86, 96));
        t.setPadding(dp(2), dp(5), 0, dp(6));
        return t;
    }

    private TextView addMetric(LinearLayout parent, String label, String initial, int leftMarginDp) {
        LinearLayout box = card();
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(11), dp(13), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(88), 1f);
        lp.setMargins(dp(leftMarginDp), 0, 0, 0);
        parent.addView(box, lp);

        TextView l = text(label, 10, true);
        l.setTextColor(Color.rgb(113, 120, 130));
        box.addView(l);
        TextView v = text(initial, 21, true);
        v.setTextColor(Color.rgb(24, 29, 38));
        v.setPadding(0, dp(6), 0, 0);
        box.addView(v);
        return v;
    }

    private Button addActionButton(LinearLayout parent, String label, View.OnClickListener listener, int leftMarginDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp.setMargins(dp(leftMarginDp), 0, 0, 0);
        parent.addView(b, lp);
        return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(232, 235, 239));
        l.setBackground(bg);
        l.setElevation(dp(1));
        return l;
    }

    private LinearLayout.LayoutParams matchWrapWithBottom(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(bottomDp));
        return lp;
    }

    private LinearLayout.LayoutParams graphLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(154));
        lp.setMargins(0, 0, 0, dp(9));
        return lp;
    }

    private void refresh() {
        if (detailBox == null) return;

        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (battery == null) {
            chargeStateText.setText("Batarya bilgisi okunamadı");
            return;
        }

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale > 0 ? Math.round(level * 100f / scale) : level;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int temp10 = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

        long currentUa = safeBatteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long avgUa = safeBatteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
        long chargeCounterUah = safeBatteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

        double voltageV = voltageMv > 0 ? voltageMv / 1000.0 : Double.NaN;
        double currentA = valid(currentUa) ? currentUa / 1_000_000.0 : Double.NaN;
        double tempC = temp10 >= 0 ? temp10 / 10.0 : Double.NaN;
        double powerW = (!Double.isNaN(voltageV) && !Double.isNaN(currentA)) ? voltageV * currentA : Double.NaN;

        String realTypeRaw = firstPowerSupplyText(
                new String[]{"real_type", "usb_type", "type"},
                new String[]{"usb", "charger", "ac", "mtk-master-charger"});
        String protocolFriendly = chargerTypeText(realTypeRaw);

        Long usbVRaw = firstPowerSupplyLong(
                new String[]{"voltage_now", "voltage_max"},
                new String[]{"usb", "charger", "ac", "mtk-master-charger"});
        Long usbIRaw = firstPowerSupplyLong(
                new String[]{"input_current_now", "current_now"},
                new String[]{"usb", "charger", "ac", "mtk-master-charger"});
        Long usbMaxRaw = firstPowerSupplyLong(
                new String[]{"current_max", "input_current_limit", "constant_charge_current_max"},
                new String[]{"usb", "charger", "ac", "mtk-master-charger"});
        String ccRaw = firstPowerSupplyText(
                new String[]{"typec_cc_orientation"},
                new String[]{"usb", "typec", "charger"});
        String typecModeRaw = firstPowerSupplyText(
                new String[]{"typec_mode"},
                new String[]{"usb", "typec", "charger"});
        String thermalRaw = firstPowerSupplyText(
                new String[]{"charge_control_limit"},
                new String[]{"battery"});
        String chargerTempRaw = firstPowerSupplyText(
                new String[]{"charger_temp", "temp"},
                new String[]{"charger", "mtk-master-charger"});
        Long cycle = firstPowerSupplyLong(new String[]{"cycle_count"}, new String[]{"battery"});
        Long full = firstPowerSupplyLong(new String[]{"charge_full"}, new String[]{"battery"});
        Long design = firstPowerSupplyLong(new String[]{"charge_full_design"}, new String[]{"battery"});

        socText.setText(pct >= 0 ? pct + "%" : "--%");
        socBar.setProgress(Math.max(0, Math.min(100, pct)));
        chargeStateText.setText(statusText(status));
        protocolText.setText("Şarj protokolü: " + protocolFriendly);
        voltageText.setText(!Double.isNaN(voltageV) ? fmt(voltageV, 3) + " V" : "-- V");
        currentText.setText(!Double.isNaN(currentA) ? String.format(Locale.US, "%+.3f A", currentA) : "-- A");
        powerText.setText(!Double.isNaN(powerW) ? String.format(Locale.US, "%+.2f W", powerW) : "-- W");
        tempText.setText(!Double.isNaN(tempC) ? fmt(tempC, 1) + " °C" : "-- °C");
        tempText.setTextColor(tempColor(tempC));

        Sample sample = new Sample();
        sample.timeMs = System.currentTimeMillis();
        sample.soc = pct;
        sample.status = statusText(status);
        sample.voltageV = voltageV;
        sample.currentA = currentA;
        sample.powerW = powerW;
        sample.tempC = tempC;
        sample.protocol = protocolFriendly;
        sample.usbVoltage = usbVRaw != null ? voltageToV(usbVRaw) : Double.NaN;
        sample.usbCurrent = usbIRaw != null ? currentToA(usbIRaw) : Double.NaN;
        sample.usbCurrentLimit = usbMaxRaw != null ? currentToA(usbMaxRaw) : Double.NaN;
        sample.thermal = thermalRaw != null ? thermalRaw : "";
        sample.chargeCounterMah = valid(chargeCounterUah) ? chargeCounterUah / 1000.0 : Double.NaN;

        history.add(sample);
        while (history.size() > LIVE_HISTORY_LIMIT) history.remove(0);

        if (testActive && (lastTestSampleMs == 0L || sample.timeMs - lastTestSampleMs >= TEST_SAMPLE_MS)) {
            testLog.add(sample.copy());
            while (testLog.size() > TEST_HISTORY_LIMIT) testLog.remove(0);
            lastTestSampleMs = sample.timeMs;
            exportButton.setEnabled(testLog.size() >= 2);
            updateChargeTestUi();
        }

        updateMinMax(voltageV, currentA, powerW, tempC);
        updateMinMaxLabel();
        invalidateGraphs();

        detailBox.removeAllViews();
        section("BATARYA");
        row("Batarya seviyesi", pct >= 0 ? pct + " %" : "N/A", "MB_00");
        row("Şarj durumu", statusText(status), "MB_01");
        row("Batarya gerilimi", !Double.isNaN(voltageV) ? fmt(voltageV, 3) + " V" : "N/A", "MB_03");
        row("Batarya akımı", !Double.isNaN(currentA) ? String.format(Locale.US, "%+.3f A", currentA) : "Desteklenmiyor", "MB_04");
        row("Ortalama batarya akımı", valid(avgUa) ? signedA(avgUa) : "Desteklenmiyor", "");
        row("Batarya sıcaklığı", !Double.isNaN(tempC) ? fmt(tempC, 1) + " °C • " + tempState(tempC) : "N/A", "MB_05");
        row("Batarya sağlığı", healthText(health), "MB_06");
        row("Bağlı güç kaynağı", pluggedText(plugged), "");
        row("Batarya teknolojisi", tech != null ? tech : "N/A", "");
        row("Kalan yük (charge counter)", valid(chargeCounterUah) ? fmt(chargeCounterUah / 1000.0, 0) + " mAh" : "Desteklenmiyor", "");
        row("Anlık batarya gücü", !Double.isNaN(powerW) ? String.format(Locale.US, "%+.2f W", powerW) : "Desteklenmiyor", "");
        row("Şarj yorumu", chargingSummary(status, realTypeRaw, powerW, tempC, thermalRaw), "");

        section("ŞARJ / USB / XIAOMI");
        row("Şarj protokolü / tipi", protocolFriendly, "MU_00 / MU_0000");
        row("USB giriş gerilimi", usbVRaw != null ? fmt(voltageToV(usbVRaw), 3) + " V" : restricted(), "MU_04");
        row("USB giriş akımı", usbIRaw != null ? fmt(currentToA(usbIRaw), 3) + " A" : restricted(), "");
        row("USB akım limiti", usbMaxRaw != null ? fmt(currentToA(usbMaxRaw), 3) + " A" : restricted(), "MU_03 / MU_05");
        if (usbVRaw != null && usbMaxRaw != null) {
            double inputLimitW = Math.abs(voltageToV(usbVRaw) * currentToA(usbMaxRaw));
            row("USB profil güç limiti", fmt(inputLimitW, 1) + " W", "");
        }
        row("USB-C CC yönü", ccOrientation(ccRaw), "MU_02");
        row("USB-C çalışma modu", typecModeText(typecModeRaw), "MU_01");
        row("Termal şarj kontrol seviyesi", thermalRaw != null ? thermalRaw : restricted(), "MB_08");
        row("Şarj entegresi sıcaklığı", chargerTempRaw != null ? formatTempRaw(chargerTempRaw) : restricted(), "MB_07");
        row("MU_06", "Vendor alanı; Redmi/MIUI build'ine göre anlamı değişebildiği için sabit yorum yapılmıyor", "MU_06");
        row("Veri erişim modu", shellService != null ? "Normal Android + Shizuku shell fallback (UID " + shizukuRemoteUid + ")" : "Normal Android uygulama erişimi", "");

        section("KAPASİTE / YAŞLANMA");
        row("Şarj çevrim sayısı", cycle != null ? String.valueOf(cycle) : restricted(), "");
        row("BMS tam dolu kapasitesi", full != null ? formatCapacity(full) : restricted(), "");
        row("Tasarım kapasitesi", design != null ? formatCapacity(design) : restricted(), "");
        if (full != null && design != null && full > 0 && design > 0) {
            row("Hesaplanan SOH", fmt(full * 100.0 / design, 1) + " %", "");
        } else {
            row("Hesaplanan SOH", "Kapasite alanları erişilebilir değil", "");
        }
    }

    private void toggleChargeTest() {
        if (!testActive) {
            testLog.clear();
            testStartMs = System.currentTimeMillis();
            lastTestSampleMs = 0L;
            testActive = true;
            testButton.setText("Testi bitir");
            exportButton.setEnabled(false);
            testStatusText.setText("Test aktif • 5 sn örnekleme");
            testSummaryText.setText("İlk örnek bekleniyor…");
            handler.removeCallbacks(refreshTask);
            handler.post(refreshTask);
            Toast.makeText(this, "Şarj testi başladı", Toast.LENGTH_SHORT).show();
        } else {
            testActive = false;
            testButton.setText("Yeni şarj testi");
            testStatusText.setText("Test tamamlandı • " + testLog.size() + " örnek");
            exportButton.setEnabled(testLog.size() >= 2);
            updateChargeTestUi();
            Toast.makeText(this, "Şarj testi tamamlandı", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateChargeTestUi() {
        if (testLog.isEmpty()) {
            testSummaryText.setText(testActive ? "İlk örnek bekleniyor…" : "Test verisi yok");
            return;
        }
        TestStats st = calculateTestStats();
        StringBuilder b = new StringBuilder();
        b.append("SOC: %").append(st.startSoc).append(" → %").append(st.endSoc)
                .append("  •  Süre: ").append(formatDuration(st.durationMs)).append('\n');
        if (!Double.isNaN(st.maxAbsPowerW)) b.append("Max güç: ").append(fmt(st.maxAbsPowerW, 1)).append(" W");
        if (!Double.isNaN(st.avgAbsPowerW)) b.append("  •  Ort: ").append(fmt(st.avgAbsPowerW, 1)).append(" W");
        if (!Double.isNaN(st.maxTempC)) b.append("\nMax sıcaklık: ").append(fmt(st.maxTempC, 1)).append(" °C");
        if (!Double.isNaN(st.avgTempC)) b.append("  •  Ort: ").append(fmt(st.avgTempC, 1)).append(" °C");
        if (st.above40Ms > 0) b.append("\n40 °C üstü: ").append(formatDuration(st.above40Ms));
        if (!Double.isNaN(st.chargeCounterDeltaMah)) b.append("\nCharge counter farkı: ").append(String.format(Locale.US, "%+.0f mAh", st.chargeCounterDeltaMah));
        testSummaryText.setText(b.toString());
        if (testActive) testStatusText.setText("Test aktif • " + testLog.size() + " örnek • 5 sn aralık");
    }

    private TestStats calculateTestStats() {
        TestStats st = new TestStats();
        if (testLog.isEmpty()) return st;
        Sample first = testLog.get(0);
        Sample last = testLog.get(testLog.size() - 1);
        st.startSoc = first.soc;
        st.endSoc = last.soc;
        st.durationMs = Math.max(0L, last.timeMs - first.timeMs);

        double sumP = 0, sumT = 0;
        int nP = 0, nT = 0;
        st.maxAbsPowerW = Double.NaN;
        st.maxTempC = Double.NaN;
        long above40 = 0L;
        for (int i = 0; i < testLog.size(); i++) {
            Sample s = testLog.get(i);
            if (!Double.isNaN(s.powerW)) {
                double p = Math.abs(s.powerW);
                sumP += p;
                nP++;
                st.maxAbsPowerW = Double.isNaN(st.maxAbsPowerW) ? p : Math.max(st.maxAbsPowerW, p);
            }
            if (!Double.isNaN(s.tempC)) {
                sumT += s.tempC;
                nT++;
                st.maxTempC = Double.isNaN(st.maxTempC) ? s.tempC : Math.max(st.maxTempC, s.tempC);
            }
            if (i > 0 && s.tempC >= 40.0) {
                above40 += Math.max(0L, s.timeMs - testLog.get(i - 1).timeMs);
            }
        }
        st.avgAbsPowerW = nP > 0 ? sumP / nP : Double.NaN;
        st.avgTempC = nT > 0 ? sumT / nT : Double.NaN;
        st.above40Ms = above40;
        if (!Double.isNaN(first.chargeCounterMah) && !Double.isNaN(last.chargeCounterMah)) {
            st.chargeCounterDeltaMah = last.chargeCounterMah - first.chargeCounterMah;
        } else {
            st.chargeCounterDeltaMah = Double.NaN;
        }
        return st;
    }

    private void exportCsv() {
        if (testLog.size() < 2) {
            Toast.makeText(this, "Önce şarj testi kaydı al", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "RedmiBatteryDiag_Test_" + stamp + ".csv");
        startActivityForResult(i, REQ_EXPORT_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            OutputStream os = getContentResolver().openOutputStream(data.getData());
            if (os == null) throw new Exception("Dosya açılamadı");
            StringBuilder b = new StringBuilder();
            b.append("timestamp;soc_percent;status;battery_v;battery_a;battery_w;temp_c;protocol;usb_v;usb_a;usb_current_limit_a;thermal_level;charge_counter_mah\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            for (Sample s : testLog) {
                b.append(sdf.format(new Date(s.timeMs))).append(';')
                        .append(s.soc).append(';')
                        .append(csvSafe(s.status)).append(';')
                        .append(num(s.voltageV)).append(';')
                        .append(num(s.currentA)).append(';')
                        .append(num(s.powerW)).append(';')
                        .append(num(s.tempC)).append(';')
                        .append(csvSafe(s.protocol)).append(';')
                        .append(num(s.usbVoltage)).append(';')
                        .append(num(s.usbCurrent)).append(';')
                        .append(num(s.usbCurrentLimit)).append(';')
                        .append(csvSafe(s.thermal)).append(';')
                        .append(num(s.chargeCounterMah)).append('\n');
            }
            os.write(b.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            Toast.makeText(this, "Test CSV dosyası kaydedildi", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "CSV kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleShizukuButton() {
        if (!isShizukuInstalled()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")));
            }
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                launchShizukuApp();
                Toast.makeText(this, "Shizuku'yu başlat; sonra BatteryDiag'e dön", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
                return;
            }
            tryBindShizukuService();
        } catch (Exception e) {
            Toast.makeText(this, "Shizuku bağlantısı kurulamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void tryBindShizukuService() {
        if (shellService != null || shizukuBinding) return;
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), BatteryShellService.class.getName()))
                    .daemon(false)
                    .tag("battery-sysfs-reader")
                    .version(2);
            shizukuBinding = true;
            Shizuku.bindUserService(args, userServiceConnection);
            updateShizukuState();
        } catch (Exception e) {
            shizukuBinding = false;
            updateShizukuState();
        }
    }

    private void updateShizukuState() {
        if (shizukuStatusText == null || shizukuButton == null) return;
        if (!isShizukuInstalled()) {
            shizukuStatusText.setText("Shizuku kurulu değil • Normal mod");
            shizukuButton.setText("Shizuku'yu Play Store'da aç");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatusText.setText("Shizuku kurulu ama servis çalışmıyor • Normal mod");
                shizukuButton.setText("Shizuku'yu aç");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                shizukuStatusText.setText("Shizuku çalışıyor • BatteryDiag izni gerekiyor");
                shizukuButton.setText("Shizuku izni ver");
                return;
            }
            if (shellService != null) {
                String mode = shizukuRemoteUid == 0 ? "root" : (shizukuRemoteUid == 2000 ? "ADB shell" : "UID " + shizukuRemoteUid);
                shizukuStatusText.setText("Bağlı ✓ • Gelişmiş sysfs erişimi • " + mode);
                shizukuButton.setText("Shizuku bağlı");
                shizukuButton.setEnabled(false);
            } else {
                shizukuStatusText.setText(shizukuBinding ? "Shizuku UserService bağlanıyor…" : "Shizuku izni var • Servis bağlanabilir");
                shizukuButton.setText("Gelişmiş erişimi bağla");
                shizukuButton.setEnabled(true);
            }
        } catch (Exception e) {
            shizukuStatusText.setText("Shizuku durumu okunamadı • Normal mod");
            shizukuButton.setText("Tekrar dene");
            shizukuButton.setEnabled(true);
        }
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchShizukuApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i != null) startActivity(i);
        } catch (Exception ignored) { }
    }

    private String restricted() {
        return shellService != null ? "Shell erişiminde de okunamadı" : "Sistem erişimi kısıtlı • Shizuku denenebilir";
    }

    private void section(String s) {
        TextView t = text(s, 11, true);
        t.setTextColor(Color.rgb(74, 82, 94));
        t.setPadding(dp(2), dp(12), 0, dp(4));
        detailBox.addView(t);
    }

    private void row(String name, String value, String code) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(4), dp(8), dp(4), dp(8));

        String shownName = name;
        if (showCodes != null && showCodes.isChecked() && code != null && !code.isEmpty()) {
            shownName += "  ·  " + code;
        }
        TextView n = text(shownName, 12, false);
        n.setTextColor(Color.rgb(96, 103, 113));
        r.addView(n);

        String shownValue = value == null || value.trim().isEmpty() ? "N/A" : value.trim();
        TextView v = text(shownValue, 16, true);
        v.setTextColor((shownValue.contains("kısıtlı") || shownValue.contains("okunamadı")) ? Color.rgb(161, 105, 0) : Color.rgb(23, 28, 36));
        r.addView(v);

        detailBox.addView(r);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(237, 239, 242));
        detailBox.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private String csvSafe(String s) {
        if (s == null) return "";
        return s.replace(';', ',').replace('\n', ' ');
    }

    private String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.4f", v);
    }

    private void resetMinMax() {
        minTemp = maxTemp = minVoltage = maxVoltage = minCurrent = maxCurrent = minPower = maxPower = Double.NaN;
        updateMinMaxLabel();
        Toast.makeText(this, "Min/max değerleri sıfırlandı", Toast.LENGTH_SHORT).show();
    }

    private void updateMinMax(double v, double a, double w, double t) {
        if (!Double.isNaN(v)) { minVoltage = min(minVoltage, v); maxVoltage = max(maxVoltage, v); }
        if (!Double.isNaN(a)) { minCurrent = min(minCurrent, a); maxCurrent = max(maxCurrent, a); }
        if (!Double.isNaN(w)) { minPower = min(minPower, w); maxPower = max(maxPower, w); }
        if (!Double.isNaN(t)) { minTemp = min(minTemp, t); maxTemp = max(maxTemp, t); }
    }

    private double min(double oldV, double newV) { return Double.isNaN(oldV) ? newV : Math.min(oldV, newV); }
    private double max(double oldV, double newV) { return Double.isNaN(oldV) ? newV : Math.max(oldV, newV); }

    private void updateMinMaxLabel() {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(minVoltage)) b.append("Gerilim: ").append(fmt(minVoltage, 3)).append(" … ").append(fmt(maxVoltage, 3)).append(" V\n");
        if (!Double.isNaN(minCurrent)) b.append("Akım: ").append(fmt(minCurrent, 3)).append(" … ").append(fmt(maxCurrent, 3)).append(" A\n");
        if (!Double.isNaN(minPower)) b.append("Güç: ").append(fmt(minPower, 2)).append(" … ").append(fmt(maxPower, 2)).append(" W\n");
        if (!Double.isNaN(minTemp)) b.append("Sıcaklık: ").append(fmt(minTemp, 1)).append(" … ").append(fmt(maxTemp, 1)).append(" °C");
        minMaxText.setText(b.length() == 0 ? "Henüz veri yok" : b.toString());
    }

    private void invalidateGraphs() {
        socGraph.invalidate();
        voltageGraph.invalidate();
        currentGraph.invalidate();
        powerGraph.invalidate();
        tempGraph.invalidate();
    }

    private List<Sample> graphData() {
        return testLog.size() >= 2 ? testLog : history;
    }

    private void openServiceMenu() {
        String code = "*#*#6485#*#*";
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Xiaomi batarya servis kodu", code));
        Toast.makeText(this, "6485 kodu panoya kopyalandı", Toast.LENGTH_SHORT).show();
        try {
            Intent i = new Intent(Intent.ACTION_DIAL);
            i.setData(Uri.parse("tel:" + Uri.encode(code)));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Telefon uygulaması açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    private long safeBatteryProperty(BatteryManager bm, int id) {
        try { return bm.getLongProperty(id); }
        catch (Exception e) { return Long.MIN_VALUE; }
    }

    private boolean valid(long v) {
        return v != Long.MIN_VALUE && v != Integer.MIN_VALUE && Math.abs(v) < 1000000000000L;
    }

    private String readNormal(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String s = br.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String readWithFallback(String path) {
        String s = readNormal(path);
        if (s != null && !s.isEmpty()) return s;
        if (shellService != null) {
            try {
                s = shellService.readFile(path);
                if (s != null) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0) s = s.substring(0, nl);
                    s = s.trim();
                    if (!s.isEmpty()) return s;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String readUeventValue(String supplyName, String property) {
        String path = "/sys/class/power_supply/" + supplyName + "/uevent";
        String key = "POWER_SUPPLY_" + property.toUpperCase(Locale.US);
        String content = null;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
            content = b.toString();
        } catch (Exception ignored) { }
        if ((content == null || content.isEmpty()) && shellService != null) {
            try { content = shellService.readFile(path); } catch (Exception ignored) { }
        }
        if (content == null) return null;
        String[] lines = content.split("\\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).equals(key)) return line.substring(eq + 1).trim();
        }
        return null;
    }

    private String powerSupplyValue(String supplyName, String property) {
        String direct = readWithFallback("/sys/class/power_supply/" + supplyName + "/" + property);
        if (direct != null && !direct.isEmpty()) return direct;
        String fromUevent = readUeventValue(supplyName, property);
        if (fromUevent != null && !fromUevent.isEmpty()) return fromUevent;
        return null;
    }

    private String firstPowerSupplyText(String[] properties, String[] preferredSupplies) {
        for (String supply : preferredSupplies) {
            for (String property : properties) {
                String v = powerSupplyValue(supply, property);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        for (String supply : listPowerSupplies()) {
            for (String property : properties) {
                String v = powerSupplyValue(supply, property);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    private List<String> listPowerSupplies() {
        ArrayList<String> out = new ArrayList<>();
        try {
            File[] dirs = new File("/sys/class/power_supply").listFiles();
            if (dirs != null) {
                for (File dir : dirs) if (dir.isDirectory()) out.add(dir.getName());
            }
        } catch (Exception ignored) { }
        if (out.isEmpty() && shellService != null) {
            try {
                String s = shellService.listDir("/sys/class/power_supply");
                if (s != null) {
                    for (String x : s.split("\\n")) if (!x.trim().isEmpty()) out.add(x.trim());
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    private Long firstPowerSupplyLong(String[] properties, String[] preferredSupplies) {
        String s = firstPowerSupplyText(properties, preferredSupplies);
        if (s == null) return null;
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return null; }
    }

    private String statusText(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Şarj oluyor";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Deşarj oluyor";
            case BatteryManager.BATTERY_STATUS_FULL: return "Tam dolu";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Şarj olmuyor";
            default: return "Bilinmiyor";
        }
    }

    private String healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "İyi";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Aşırı sıcak";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Arızalı / Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Aşırı gerilim";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Çok soğuk";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Belirsiz arıza";
            default: return "Bilinmiyor";
        }
    }

    private String pluggedText(int p) {
        List<String> a = new ArrayList<>();
        if ((p & BatteryManager.BATTERY_PLUGGED_AC) != 0) a.add("AC adaptör");
        if ((p & BatteryManager.BATTERY_PLUGGED_USB) != 0) a.add("USB");
        if ((p & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) a.add("Kablosuz");
        return a.isEmpty() ? "Bağlı değil" : join(a, " + ");
    }

    private String signedA(long ua) {
        return String.format(Locale.US, "%+.3f A", ua / 1_000_000.0);
    }

    private String chargerTypeText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Sistem erişimi kısıtlı";
        String x = raw.trim().toUpperCase(Locale.US);
        if (x.contains("HVDCP_3") || x.contains("HVDCP3")) return "Hızlı şarj • HVDCP 3";
        if (x.contains("HVDCP")) return "Hızlı şarj • HVDCP";
        if (x.contains("PPS")) return "USB Power Delivery • PPS";
        if (x.contains("PD")) return "USB Power Delivery";
        if (x.contains("DCP")) return "Dedicated Charger (DCP)";
        if (x.contains("CDP")) return "Charging Downstream Port (CDP)";
        if (x.contains("SDP")) return "Standard USB Port (SDP)";
        if (x.contains("ACA")) return "USB Accessory Charger Adapter";
        return raw.trim();
    }

    private String typecModeText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return restricted();
        String x = raw.trim();
        String u = x.toUpperCase(Locale.US);
        if (u.contains("SOURCE ATTACHED")) return "Güç kaynağı bağlı" + (u.contains("DEFAULT") ? " • varsayılan Type-C akımı" : "");
        if (u.contains("SINK")) return "Sink / güç tüketen cihaz";
        if (u.contains("SOURCE")) return "Source / güç sağlayan cihaz";
        return x;
    }

    private String ccOrientation(String s) {
        if (s == null) return restricted();
        String x = s.trim();
        if (x.equals("1")) return "CC1";
        if (x.equals("2")) return "CC2";
        if (x.equals("0")) return "Bağlı değil / N-C";
        return x;
    }

    private String chargingSummary(int status, String rawType, double powerW, double tempC, String thermalRaw) {
        if (status != BatteryManager.BATTERY_STATUS_CHARGING) return "Telefon şarjda değil";
        StringBuilder b = new StringBuilder();
        String type = chargerTypeText(rawType);
        if (!type.contains("kısıtlı")) b.append(type);
        else b.append("Şarj oluyor; protokol okunamıyor");
        if (!Double.isNaN(powerW)) b.append(" • batarya tarafı ").append(fmt(Math.abs(powerW), 1)).append(" W");
        if (!Double.isNaN(tempC)) {
            if (tempC >= 43.0) b.append(" • sıcaklık yüksek, termal sınırlama olası");
            else if (tempC >= 38.0) b.append(" • batarya sıcak");
        }
        if (thermalRaw != null && !thermalRaw.equals("0")) b.append(" • thermal level ").append(thermalRaw);
        return b.toString();
    }

    private String tempState(double tempC) {
        if (Double.isNaN(tempC)) return "Bilinmiyor";
        if (tempC >= 46.0) return "Yüksek";
        if (tempC >= 43.0) return "Şarj kısıtlanabilir";
        if (tempC >= 38.0) return "Sıcak";
        return "Normal";
    }

    private int tempColor(double tempC) {
        if (Double.isNaN(tempC)) return Color.rgb(24, 29, 38);
        if (tempC >= 43.0) return Color.rgb(201, 46, 46);
        if (tempC >= 38.0) return Color.rgb(190, 116, 0);
        return Color.rgb(30, 132, 73);
    }

    private double voltageToV(long raw) {
        long a = Math.abs(raw);
        if (a >= 100000) return raw / 1_000_000.0;
        if (a >= 1000) return raw / 1000.0;
        return raw;
    }

    private double currentToA(long raw) {
        long a = Math.abs(raw);
        if (a >= 10000) return raw / 1_000_000.0;
        if (a >= 100) return raw / 1000.0;
        return raw;
    }

    private String formatCapacity(long raw) {
        double mah = Math.abs(raw) >= 100000 ? raw / 1000.0 : raw;
        return fmt(mah, 0) + " mAh";
    }

    private String formatTempRaw(String s) {
        try {
            double x = Double.parseDouble(s.trim());
            if (Math.abs(x) >= 100) x /= 10.0;
            return fmt(x, 1) + " °C";
        } catch (Exception e) {
            return s;
        }
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private String fmt(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private String join(List<String> xs, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(xs.get(i));
        }
        return b.toString();
    }

    private String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return String.format(Locale.US, "%d sa %02d dk", h, m);
        if (m > 0) return String.format(Locale.US, "%d dk %02d sn", m, s);
        return s + " sn";
    }

    private static class Sample {
        long timeMs;
        int soc;
        String status;
        double voltageV;
        double currentA;
        double powerW;
        double tempC;
        String protocol;
        double usbVoltage;
        double usbCurrent;
        double usbCurrentLimit;
        String thermal;
        double chargeCounterMah;

        Sample copy() {
            Sample s = new Sample();
            s.timeMs = timeMs;
            s.soc = soc;
            s.status = status;
            s.voltageV = voltageV;
            s.currentA = currentA;
            s.powerW = powerW;
            s.tempC = tempC;
            s.protocol = protocol;
            s.usbVoltage = usbVoltage;
            s.usbCurrent = usbCurrent;
            s.usbCurrentLimit = usbCurrentLimit;
            s.thermal = thermal;
            s.chargeCounterMah = chargeCounterMah;
            return s;
        }
    }

    private static class TestStats {
        int startSoc;
        int endSoc;
        long durationMs;
        double maxAbsPowerW = Double.NaN;
        double avgAbsPowerW = Double.NaN;
        double maxTempC = Double.NaN;
        double avgTempC = Double.NaN;
        long above40Ms;
        double chargeCounterDeltaMah = Double.NaN;
    }

    private class LiveGraph extends View {
        static final int MODE_SOC = 1;
        static final int MODE_VOLTAGE = 2;
        static final int MODE_CURRENT = 3;
        static final int MODE_POWER = 4;
        static final int MODE_TEMP = 5;
        private final int mode;
        private final String title;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LiveGraph(Activity context, int mode, String title) {
            super(context);
            this.mode = mode;
            this.title = title;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(1), Color.rgb(232, 235, 239));
            setBackground(bg);
            setPadding(dp(12), dp(10), dp(12), dp(10));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int left = dp(12);
            int right = w - dp(12);
            int top = dp(32);
            int bottom = h - dp(18);

            paint.setColor(Color.rgb(70, 77, 88));
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(title, dp(12), dp(20), paint);

            ArrayList<Float> values = new ArrayList<>();
            for (Sample s : graphData()) {
                double v = graphValue(s);
                if (!Double.isNaN(v) && !Double.isInfinite(v)) values.add((float) v);
            }
            if (values.size() < 2) {
                paint.setTypeface(Typeface.DEFAULT);
                paint.setColor(Color.rgb(145, 150, 158));
                paint.setTextSize(dp(10));
                canvas.drawText("Grafik için veri toplanıyor…", dp(12), h / 2f, paint);
                return;
            }

            float min = values.get(0);
            float max = values.get(0);
            for (float v : values) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (mode == MODE_SOC) {
                min = Math.max(0f, min - 2f);
                max = Math.min(100f, max + 2f);
            } else if (Math.abs(max - min) < 0.001f) {
                max += 1f;
                min -= 1f;
            }
            float range = Math.max(0.001f, max - min);
            float pad = mode == MODE_SOC ? 0f : range * 0.10f;
            max += pad;
            min -= pad;

            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(235, 238, 242));
            canvas.drawLine(left, top, right, top, paint);
            canvas.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);

            paint.setColor(graphColor());
            paint.setStrokeWidth(dp(2));
            float prevX = left;
            float prevY = map(values.get(0), min, max, bottom, top);
            for (int i = 1; i < values.size(); i++) {
                float x = left + (right - left) * (i / (float) (values.size() - 1));
                float y = map(values.get(i), min, max, bottom, top);
                canvas.drawLine(prevX, prevY, x, y, paint);
                prevX = x;
                prevY = y;
            }

            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(9));
            paint.setColor(Color.rgb(120, 126, 135));
            canvas.drawText(String.format(Locale.US, "%.2f%s", max - pad, graphUnit()), left, top - dp(4), paint);
            canvas.drawText(String.format(Locale.US, "%.2f%s", min + pad, graphUnit()), left, bottom + dp(13), paint);
        }

        private double graphValue(Sample s) {
            switch (mode) {
                case MODE_SOC: return s.soc;
                case MODE_VOLTAGE: return s.voltageV;
                case MODE_CURRENT: return s.currentA;
                case MODE_POWER: return s.powerW;
                case MODE_TEMP: return s.tempC;
                default: return Double.NaN;
            }
        }

        private int graphColor() {
            switch (mode) {
                case MODE_SOC: return Color.rgb(20, 103, 255);
                case MODE_VOLTAGE: return Color.rgb(96, 73, 184);
                case MODE_CURRENT: return Color.rgb(0, 137, 123);
                case MODE_POWER: return Color.rgb(20, 103, 255);
                case MODE_TEMP: return Color.rgb(230, 122, 0);
                default: return Color.DKGRAY;
            }
        }

        private String graphUnit() {
            switch (mode) {
                case MODE_SOC: return " %";
                case MODE_VOLTAGE: return " V";
                case MODE_CURRENT: return " A";
                case MODE_POWER: return " W";
                case MODE_TEMP: return " °C";
                default: return "";
            }
        }

        private float map(float value, float min, float max, float outMin, float outMax) {
            return outMin + (value - min) * (outMax - outMin) / (max - min);
        }
    }
}
