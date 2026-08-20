package com.qti.dpm;

interface IDpmService {
    // 戦略A: ダイレクトシェル実行（最も攻撃しやすい）
    void executeShellCommand(in String command);
    
    // 戦略B: プロパティ操作（ctl.start によるサービス起動誘導）
    void setSystemProperty(in String key, in String value);
    
    // 戦略C: 任意バイナリ書き込み
    void writeFile(in String path, in byte[] content);
    
    // 戦略D: パーミッション変更（実行ビット付与）
    void setFilePermissions(in String path, in int mode);
}
