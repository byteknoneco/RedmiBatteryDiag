package com.oai.redmibatterydiag;

import android.app.Activity;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private LinearLayout dataBox;
    private Switch showCodes;
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(247,248,250));
        scroll.addView(root);

        TextView title = text("Redmi Batarya Diagnostik", 26, true);
        root.addView(title);
        TextView sub = text("Canlı, salt-okunur batarya ve şarj bilgileri", 14, false);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button service = new Button(this);
        service.setText("6485 menüsü");
        service.setAllCaps(false);
        service.setOnClickListener(v -> openServiceMenu());
        actions.addView(service, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Space s = new Space(this);
        actions.addView(s, new LinearLayout.LayoutParams(dp(8), 1));

        Button refresh = new Button(this);
        refresh.setText("Yenile");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> refresh());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(actions);

        showCodes = new Switch(this);
        showCodes.setText("MB/MU teknik kodlarını göster");
        showCodes.setPadding(0, dp(8), 0, dp(8));
        showCodes.setOnCheckedChangeListener((b, c) -> refresh());
        root.addView(showCodes);

        dataBox = new LinearLayout(this);
        dataBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        dataBox.setBackground(bg);
        dataBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(dataBox, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("Not: Android'in standart API verileri doğrudan okunur. Xiaomi'ye özgü bazı kernel alanları HyperOS/MIUI sürümüne göre uygulamalara kapalı olabilir; bu durumda 'Erişim yok' gösterilir.", 12, false);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void refresh() {
        if (dataBox == null) return;
        dataBox.removeAllViews();
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);

        section("BATARYA");
        if (battery == null) {
            row("Batarya bilgisi", "Okunamadı", "");
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

        row("Batarya seviyesi", pct >= 0 ? pct + " %" : "N/A", "MB_00");
        row("Şarj durumu", statusText(status), "MB_01");
        row("Batarya gerilimi", voltageMv >= 0 ? fmt(voltageMv / 1000.0, 3) + " V" : "N/A", "MB_03");
        row("Batarya akımı", valid(currentUa) ? signedA(currentUa) : "Desteklenmiyor", "MB_04");
        row("Ortalama batarya akımı", valid(avgUa) ? signedA(avgUa) : "Desteklenmiyor", "");
        row("Batarya sıcaklığı", temp10 >= 0 ? fmt(temp10 / 10.0, 1) + " °C" : "N/A", "MB_05");
        row("Batarya sağlığı", healthText(health), "MB_06");
        row("Bağlı güç kaynağı", pluggedText(plugged), "");
        row("Batarya teknolojisi", tech != null ? tech : "N/A", "");
        row("Charge counter", valid(chargeCounterUah) ? fmt(chargeCounterUah / 1000.0, 0) + " mAh" : "Desteklenmiyor", "");

        if (voltageMv > 0 && valid(currentUa)) {
            double p = (voltageMv / 1000.0) * (currentUa / 1_000_000.0);
            row("Anlık batarya gücü", fmt(p, 2) + " W", "");
        }

        section("XIAOMI / KERNEL DETAYLARI");
        String realType = firstReadable(
                "/sys/class/power_supply/usb/real_type",
                "/sys/class/power_supply/charger/real_type",
                "/sys/class/power_supply/ac/real_type",
                "/sys/class/power_supply/usb/type");
        row("Şarj protokolü / tipi", pretty(realType), "MU_00 / MU_0000");

        Long usbV = firstLong(
                "/sys/class/power_supply/usb/voltage_now",
                "/sys/class/power_supply/charger/voltage_now");
        row("USB giriş gerilimi", usbV != null ? formatVoltage(usbV) : "Erişim yok", "MU_04");

        Long usbI = firstLong(
                "/sys/class/power_supply/usb/current_now",
                "/sys/class/power_supply/charger/current_now");
        row("USB giriş akımı", usbI != null ? formatCurrent(usbI) : "Erişim yok", "");

        Long usbMax = firstLong(
                "/sys/class/power_supply/usb/current_max",
                "/sys/class/power_supply/usb/input_current_limit",
                "/sys/class/power_supply/charger/current_max");
        row("USB akım limiti", usbMax != null ? formatCurrent(usbMax) : "Erişim yok", "MU_03 / MU_05");

        String cc = firstReadable(
                "/sys/class/power_supply/usb/typec_cc_orientation",
                "/sys/class/power_supply/typec/typec_cc_orientation");
        row("USB-C CC yönü", ccOrientation(cc), "MU_02");

        String typecMode = firstReadable(
                "/sys/class/power_supply/usb/typec_mode",
                "/sys/class/power_supply/typec/typec_mode");
        row("USB-C çalışma modu", pretty(typecMode), "MU_01");

        String thermal = firstReadable("/sys/class/power_supply/battery/charge_control_limit");
        row("Termal şarj kontrol seviyesi", pretty(thermal), "MB_08");

        String chargerTemp = firstReadable(
                "/sys/class/power_supply/battery/charger_temp",
                "/sys/class/power_supply/charger/temp");
        row("Şarj entegresi sıcaklığı", formatTempRaw(chargerTemp), "MB_07");

        Long cycle = firstLong("/sys/class/power_supply/battery/cycle_count");
        row("Şarj çevrim sayısı", cycle != null ? String.valueOf(cycle) : "Erişim yok", "");

        Long full = firstLong("/sys/class/power_supply/battery/charge_full");
        Long design = firstLong("/sys/class/power_supply/battery/charge_full_design");
        row("Tahmini tam dolu kapasite", full != null ? formatCapacity(full) : "Erişim yok", "");
        row("Tasarım kapasitesi", design != null ? formatCapacity(design) : "Erişim yok", "");
        if (full != null && design != null && full > 0 && design > 0) {
            double soh = full * 100.0 / design;
            row("Hesaplanan SOH", fmt(soh, 1) + " %", "");
        }
    }

    private void section(String s) {
        TextView t = text(s, 12, true);
        t.setTextColor(Color.rgb(80,86,96));
        t.setPadding(0, dp(12), 0, dp(4));
        dataBox.addView(t);
    }

    private void row(String name, String value, String code) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(9));

        TextView n = text(name + ((showCodes != null && showCodes.isChecked() && code != null && !code.isEmpty()) ? "  ·  " + code : ""), 13, false);
        n.setTextColor(Color.DKGRAY);
        r.addView(n);

        TextView v = text(value == null || value.trim().isEmpty() ? "N/A" : value.trim(), 18, true);
        v.setTextColor(Color.rgb(22,26,32));
        r.addView(v);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(236,238,241));
        dataBox.addView(r);
        dataBox.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void openServiceMenu() {
        String code = "*#*#6485#*#*";
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
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
        try { return bm.getLongProperty(id); } catch (Exception e) { return Long.MIN_VALUE; }
    }

    private boolean valid(long v) {
        return v != Long.MIN_VALUE && v != Integer.MIN_VALUE && Math.abs(v) < 1000000000000L;
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
            case BatteryManager.BATTERY_HEALTH_GOOD: return "İyi (Good)";
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

    private String read(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String s = br.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) { return null; }
    }

    private String firstReadable(String... paths) {
        for (String p : paths) {
            String s = read(p);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private Long firstLong(String... paths) {
        String s = firstReadable(paths);
        if (s == null) return null;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private String formatVoltage(long raw) {
        double v;
        if (Math.abs(raw) >= 100000) v = raw / 1_000_000.0;
        else if (Math.abs(raw) >= 1000) v = raw / 1000.0;
        else v = raw;
        return fmt(v, 3) + " V";
    }

    private String formatCurrent(long raw) {
        double a;
        if (Math.abs(raw) >= 10000) a = raw / 1_000_000.0;
        else if (Math.abs(raw) >= 100) a = raw / 1000.0;
        else a = raw;
        return fmt(a, 3) + " A";
    }

    private String formatCapacity(long raw) {
        double mah = Math.abs(raw) >= 100000 ? raw / 1000.0 : raw;
        return fmt(mah, 0) + " mAh";
    }

    private String formatTempRaw(String s) {
        if (s == null) return "Erişim yok";
        try {
            double x = Double.parseDouble(s.trim());
            if (Math.abs(x) >= 100) x /= 10.0;
            return fmt(x, 1) + " °C";
        } catch (Exception e) { return pretty(s); }
    }

    private String ccOrientation(String s) {
        if (s == null) return "Erişim yok";
        String x = s.trim();
        if (x.equals("1")) return "CC1";
        if (x.equals("2")) return "CC2";
        if (x.equals("0")) return "Bağlı değil / N-C";
        return x;
    }

    private String pretty(String s) {
        if (s == null || s.trim().isEmpty()) return "Erişim yok";
        return s.trim();
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
        for (int i=0;i<xs.size();i++) {
            if (i>0) b.append(sep);
            b.append(xs.get(i));
        }
        return b.toString();
    }
}
