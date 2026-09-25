package com.oai.redmibatterydiag;

import android.app.Activity;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
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

public class MainActivity extends Activity {
    private static final int REQ_EXPORT_CSV = 4401;
    private static final int HISTORY_LIMIT = 120;

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
    private TextView recordStatusText;
    private ProgressBar socBar;
    private Button recordButton;
    private Button exportButton;
    private LiveGraph powerGraph;
    private LiveGraph tempGraph;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Sample> history = new ArrayList<>();
    private final ArrayList<Sample> csvLog = new ArrayList<>();
    private boolean recording = false;

    private double minTemp = Double.NaN;
    private double maxTemp = Double.NaN;
    private double minVoltage = Double.NaN;
    private double maxVoltage = Double.NaN;
    private double minCurrent = Double.NaN;
    private double maxCurrent = Double.NaN;
    private double minPower = Double.NaN;
    private double maxPower = Double.NaN;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(root);

        TextView title = text("Redmi BatteryDiag", 26, true);
        title.setTextColor(Color.rgb(19, 24, 32));
        root.addView(title);

        TextView subtitle = text("Canlı batarya ve şarj diagnostik verileri", 13, false);
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
        socBar.setProgress(0);
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

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actionRow1, matchWrapWithBottom(8));
        addActionButton(actionRow1, "6485 menüsü", v -> openServiceMenu(), 0);
        addActionButton(actionRow1, "Yenile", v -> refresh(), 8);

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actionRow2, matchWrapWithBottom(8));
        recordButton = addActionButton(actionRow2, "Kaydı başlat", v -> toggleRecording(), 0);
        exportButton = addActionButton(actionRow2, "CSV dışa aktar", v -> exportCsv(), 8);
        exportButton.setEnabled(false);

        showCodes = new Switch(this);
        showCodes.setText("MB/MU teknik kodlarını göster");
        showCodes.setTextSize(13);
        showCodes.setPadding(dp(3), dp(6), dp(3), dp(6));
        showCodes.setOnCheckedChangeListener((buttonView, isChecked) -> refresh());
        root.addView(showCodes);

        recordStatusText = text("CSV kaydı kapalı", 12, false);
        recordStatusText.setTextColor(Color.rgb(102, 109, 119));
        recordStatusText.setPadding(dp(3), 0, dp(3), dp(10));
        root.addView(recordStatusText);

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

        TextView graphTitle = text("CANLI GRAFİKLER", 12, true);
        graphTitle.setTextColor(Color.rgb(80, 86, 96));
        graphTitle.setPadding(dp(2), dp(4), 0, dp(6));
        root.addView(graphTitle);

        powerGraph = new LiveGraph(this, LiveGraph.MODE_POWER, "Batarya gücü (W)");
        root.addView(powerGraph, graphLayout());
        tempGraph = new LiveGraph(this, LiveGraph.MODE_TEMP, "Batarya sıcaklığı (°C)");
        root.addView(tempGraph, graphLayout());

        TextView detailsTitle = text("DETAYLI DIAGNOSTİK", 12, true);
        detailsTitle.setTextColor(Color.rgb(80, 86, 96));
        detailsTitle.setPadding(dp(2), dp(8), 0, dp(6));
        root.addView(detailsTitle);

        detailBox = card();
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(detailBox, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text(
                "MU verileri Xiaomi/MediaTek kernel alanlarından okunur. Uygulama doğrudan dosyayı ve uevent yedeğini dener. HyperOS SELinux erişimi engellerse normal APK bunu zorlayamaz; o alanlarda 'Sistem erişimi kısıtlı' gösterilir.",
                11, false);
        note.setTextColor(Color.rgb(115, 120, 130));
        note.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(note);

        setContentView(scroll);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(158));
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
        sample.usbCurrentLimit = usbMaxRaw != null ? currentToA(usbMaxRaw) : Double.NaN;
        sample.thermal = thermalRaw != null ? thermalRaw : "";

        history.add(sample);
        while (history.size() > HISTORY_LIMIT) history.remove(0);
        if (recording) {
            csvLog.add(sample.copy());
            exportButton.setEnabled(true);
            recordStatusText.setText("CSV kaydı açık • " + csvLog.size() + " örnek");
        }

        updateMinMax(voltageV, currentA, powerW, tempC);
        updateMinMaxLabel();
        powerGraph.invalidate();
        tempGraph.invalidate();

        detailBox.removeAllViews();
        section("BATARYA");
        row("Batarya seviyesi", pct >= 0 ? pct + " %" : "N/A", "MB_00");
        row("Şarj durumu", statusText(status), "MB_01");
        row("Batarya gerilimi", !Double.isNaN(voltageV) ? fmt(voltageV, 3) + " V" : "N/A", "MB_03");
        row("Batarya akımı", !Double.isNaN(currentA) ? String.format(Locale.US, "%+.3f A", currentA) : "Desteklenmiyor", "MB_04");
        row("Ortalama batarya akımı", valid(avgUa) ? signedA(avgUa) : "Desteklenmiyor", "");
        row("Batarya sıcaklığı", !Double.isNaN(tempC) ? fmt(tempC, 1) + " °C" : "N/A", "MB_05");
        row("Batarya sağlığı", healthText(health), "MB_06");
        row("Bağlı güç kaynağı", pluggedText(plugged), "");
        row("Batarya teknolojisi", tech != null ? tech : "N/A", "");
        row("Kalan mAH Batarya Degeri", valid(chargeCounterUah) ? fmt(chargeCounterUah / 1000.0, 0) + " mAh" : "Desteklenmiyor", "");
        row("Anlık batarya gücü", !Double.isNaN(powerW) ? String.format(Locale.US, "%+.2f W", powerW) : "Desteklenmiyor", "");
        row("Şarj yorumu", chargingSummary(status, realTypeRaw, powerW, tempC), "");

        section("ŞARJ / USB / XIAOMI");
        row("Şarj protokolü / tipi", protocolFriendly, "MU_00 / MU_0000");
        row("USB giriş gerilimi", usbVRaw != null ? fmt(voltageToV(usbVRaw), 3) + " V" : restricted(), "MU_04");
        row("USB giriş akımı", usbIRaw != null ? fmt(currentToA(usbIRaw), 3) + " A" : restricted(), "");
        row("USB akım limiti", usbMaxRaw != null ? fmt(currentToA(usbMaxRaw), 3) + " A" : restricted(), "MU_03 / MU_05");
        row("USB-C CC yönü", ccOrientation(ccRaw), "MU_02");
        row("USB-C çalışma modu", typecModeText(typecModeRaw), "MU_01");
        row("Termal şarj kontrol seviyesi", thermalRaw != null ? thermalRaw : restricted(), "MB_08");
        row("Şarj entegresi sıcaklığı", chargerTempRaw != null ? formatTempRaw(chargerTempRaw) : restricted(), "MB_07");
        row("MU_06", "Redmi/MIUI sürümüne göre anlamı değişebilen vendor alanı; güvenilir eşleme yapılmadı", "MU_06");

        section("KAPASİTE / YAŞLANMA");
        row("Şarj çevrim sayısı", cycle != null ? String.valueOf(cycle) : restricted(), "");
        row("Tahmini tam dolu kapasite", full != null ? formatCapacity(full) : restricted(), "");
        row("Tasarım kapasitesi", design != null ? formatCapacity(design) : restricted(), "");
        if (full != null && design != null && full > 0 && design > 0) {
            row("Hesaplanan SOH", fmt(full * 100.0 / design, 1) + " %", "");
        } else {
            row("Hesaplanan SOH", "Kapasite alanları erişilebilir değil", "");
        }
    }

    private String restricted() {
        return "Sistem erişimi kısıtlı";
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

        TextView v = text(value == null || value.trim().isEmpty() ? "N/A" : value.trim(), 16, true);
        v.setTextColor(value != null && value.contains("kısıtlı") ? Color.rgb(161, 105, 0) : Color.rgb(23, 28, 36));
        r.addView(v);

        detailBox.addView(r);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(237, 239, 242));
        detailBox.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void toggleRecording() {
        recording = !recording;
        recordButton.setText(recording ? "Kaydı durdur" : "Kaydı başlat");
        recordStatusText.setText(recording ? "CSV kaydı açık • " + csvLog.size() + " örnek" : "CSV kaydı kapalı • " + csvLog.size() + " örnek saklandı");
    }

    private void exportCsv() {
        if (csvLog.isEmpty()) {
            Toast.makeText(this, "Önce kısa bir kayıt al", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "RedmiBatteryDiag_" + stamp + ".csv");
        startActivityForResult(i, REQ_EXPORT_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            ContentResolver resolver = getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) throw new Exception("Output stream açılamadı");
            StringBuilder b = new StringBuilder();
            b.append("timestamp;soc_percent;status;battery_v;battery_a;battery_w;temp_c;protocol;usb_v;usb_current_limit_a;thermal_level\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            for (Sample s : csvLog) {
                b.append(sdf.format(new Date(s.timeMs))).append(';')
                        .append(s.soc).append(';')
                        .append(csvSafe(s.status)).append(';')
                        .append(num(s.voltageV)).append(';')
                        .append(num(s.currentA)).append(';')
                        .append(num(s.powerW)).append(';')
                        .append(num(s.tempC)).append(';')
                        .append(csvSafe(s.protocol)).append(';')
                        .append(num(s.usbVoltage)).append(';')
                        .append(num(s.usbCurrentLimit)).append(';')
                        .append(csvSafe(s.thermal)).append('\n');
            }
            os.write(b.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            Toast.makeText(this, "CSV kaydedildi", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "CSV kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private String read(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String s = br.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String readUeventValue(String supplyName, String property) {
        String path = "/sys/class/power_supply/" + supplyName + "/uevent";
        String key = "POWER_SUPPLY_" + property.toUpperCase(Locale.US);
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                if (line.substring(0, eq).equals(key)) return line.substring(eq + 1).trim();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String powerSupplyValue(String supplyName, String property) {
        String direct = read("/sys/class/power_supply/" + supplyName + "/" + property);
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
        try {
            File base = new File("/sys/class/power_supply");
            File[] dirs = base.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (!dir.isDirectory()) continue;
                    for (String property : properties) {
                        String v = powerSupplyValue(dir.getName(), property);
                        if (v != null && !v.isEmpty()) return v;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
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

    private String chargingSummary(int status, String rawType, double powerW, double tempC) {
        if (status != BatteryManager.BATTERY_STATUS_CHARGING) return "Araç şarjda değil";
        StringBuilder b = new StringBuilder();
        String type = chargerTypeText(rawType);
        if (!type.contains("kısıtlı")) b.append(type);
        else b.append("Şarj oluyor; protokol okunamıyor");

        if (!Double.isNaN(powerW)) {
            b.append(" • batarya tarafı ").append(fmt(Math.abs(powerW), 1)).append(" W");
        }
        if (!Double.isNaN(tempC)) {
            if (tempC >= 43.0) b.append(" • sıcaklık yüksek, termal sınırlama olası");
            else if (tempC >= 38.0) b.append(" • batarya sıcak");
        }
        return b.toString();
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
        double usbCurrentLimit;
        String thermal;

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
            s.usbCurrentLimit = usbCurrentLimit;
            s.thermal = thermal;
            return s;
        }
    }

    private class LiveGraph extends View {
        static final int MODE_POWER = 1;
        static final int MODE_TEMP = 2;
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
            for (Sample s : history) {
                double v = mode == MODE_POWER ? s.powerW : s.tempC;
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
            if (Math.abs(max - min) < 0.001f) { max += 1f; min -= 1f; }
            float pad = (max - min) * 0.10f;
            max += pad;
            min -= pad;

            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(235, 238, 242));
            canvas.drawLine(left, top, right, top, paint);
            canvas.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);

            paint.setColor(mode == MODE_POWER ? Color.rgb(20, 103, 255) : Color.rgb(230, 122, 0));
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
            String unit = mode == MODE_POWER ? " W" : " °C";
            canvas.drawText(String.format(Locale.US, "%.1f%s", max - pad, unit), left, top - dp(4), paint);
            canvas.drawText(String.format(Locale.US, "%.1f%s", min + pad, unit), left, bottom + dp(13), paint);
        }

        private float map(float value, float min, float max, float outMin, float outMax) {
            return outMin + (value - min) * (outMax - outMin) / (max - min);
        }
    }
}
