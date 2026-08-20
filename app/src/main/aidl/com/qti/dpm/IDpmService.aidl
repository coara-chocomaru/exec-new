package com.qti.dpm;

interface IDpmService {
    // DpmApi が呼ぶ実際のメソッドその1
    void setTCMFeature(in int value);
    
    // DpmApi が呼ぶ実際のメソッドその2
    int getTCMFeatureEnabled();
    
    // DpmApi が呼ぶ実際のメソッドその3
    void updateFdConfigParams(in int delayTime, in int screenOnTime, in int screenOffTime, in int tetheringTime);
}
