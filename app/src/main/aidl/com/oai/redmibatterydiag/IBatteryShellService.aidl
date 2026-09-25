package com.oai.redmibatterydiag;

interface IBatteryShellService {
    void destroy() = 16777114;
    String readFile(String path) = 1;
    String listDir(String path) = 2;
    int getRemoteUid() = 3;
}
