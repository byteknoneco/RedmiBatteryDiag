package com.oai.redmibatterydiag;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Runs through Shizuku UserService with shell/root identity.
 * It is intentionally restricted to read-only battery / USB sysfs paths.
 */
public class BatteryShellService extends IBatteryShellService.Stub {

    public BatteryShellService() {
    }

    private boolean allowed(String path) {
        if (path == null) return false;
        return path.startsWith("/sys/class/power_supply/") ||
                path.startsWith("/sys/class/typec/") ||
                path.equals("/sys/class/power_supply") ||
                path.equals("/sys/class/typec");
    }

    @Override
    public String readFile(String path) {
        if (!allowed(path)) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
                if (out.length() > 32768) break;
            }
            String s = out.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String listDir(String path) {
        if (!allowed(path)) return null;
        try {
            File[] files = new File(path).listFiles();
            if (files == null) return null;
            StringBuilder out = new StringBuilder();
            for (File f : files) {
                if (out.length() > 0) out.append('\n');
                out.append(f.getName());
            }
            String s = out.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getRemoteUid() {
        return Process.myUid();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
