package com.qti.dpm;

interface IDpmService {
    int setTCMFeature(int value);
    int getTCMFeatureEnabled();
    int updateFdConfigParams(int delayTime, int screenOnTime, int screenOffTime, int tetheringTime);
}
