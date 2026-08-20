public interface IDpmService extends IInterface {
    int setTCMFeature(int value);                 // TRANSACTION = 1
    int getTCMFeatureEnabled();                   // TRANSACTION = 2
    int updateFdConfigParams(int, int, int, int); // TRANSACTION = 3
}
