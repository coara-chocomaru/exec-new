package com.qti.dpm;

interface IDpmService {
    void executeShellCommand(String command);
    void setSystemProperty(String key, String value);
    void writeFile(String path, byte[] content);
    void setFilePermissions(String path, int mode);
}
