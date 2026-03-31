#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
from pathlib import Path

def main():
    if len(sys.argv) > 1:
        fex_dir = Path(sys.argv[1])
    else:
        fex_dir = Path("fex")  # 実行フォルダ内の fex ディレクトリ

    if not fex_dir.exists() or not fex_dir.is_dir():
        print(f"エラー: {fex_dir} ディレクトリが見つかりません。")
        print("使い方: python generate_allwinner_image_cfg.py [fex_dir]")
        sys.exit(1)

    output_file = Path("image.cfg")

    print(f"fex ディレクトリ: {fex_dir.resolve()}")
    print(f"出力: {output_file}")

    # image.cfg のヘッダー部分（基本的なテンプレート）
    cfg_lines = [
        "[image]",
        "version = 1.0",
        "chip = sunxi",          # 必要に応じて sun8i, sun50i などに変更
        "mode = normal",
        "",
        "[image_list]",
    ]

    # fex フォルダ内のファイルをスキャンしてエントリを追加
    for file_path in sorted(fex_dir.iterdir()):
        if not file_path.is_file():
            continue

        filename = file_path.name
        name_without_ext = file_path.stem

        # 典型的な Allwinner ファイル名に応じた設定（カスタマイズ可能）
        if filename == "sys_config.fex":
            entry = f'  item = sys_config.fex : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        elif filename == "sys_partition.fex":
            entry = f'  item = sys_partition.fex : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        elif filename in ["boot0.fex", "boot1.fex", "u-boot.fex"]:
            entry = f'  item = {name_without_ext} : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        elif filename.endswith(".fex"):
            # 一般的なパーティション（boot, system, recovery など）
            entry = f'  item = {name_without_ext} : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        else:
            # その他のファイル（.bin など）
            entry = f'  item = {name_without_ext} : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'

        cfg_lines.append(entry)

    # フッター
    cfg_lines.append("")
    cfg_lines.append("[end]")

    # image.cfg として書き出し
    with open(output_file, "w", encoding="utf-8") as f:
        f.write("\n".join(cfg_lines) + "\n")

    print(f"\n✅ {output_file} を生成しました！")
    print("中身を確認して必要に応じて以下を編集してください：")
    print("  - chip = sunxi の部分（SoC に合わせて変更）")
    - item の後ろの数値（オフセット、サイズ、verify など）")
    print("\n次に imgRePacker や dragon pack ツールで image.cfg を使用して firmware.img を作成してください。")

if __name__ == "__main__":
    main()
