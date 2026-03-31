#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
from pathlib import Path

def main():
    # コマンドライン引数で fex フォルダを指定可能（指定がなければ ./fex）
    if len(sys.argv) > 1:
        fex_dir = Path(sys.argv[1])
    else:
        fex_dir = Path("fex")

    if not fex_dir.exists() or not fex_dir.is_dir():
        print(f"エラー: '{fex_dir}' ディレクトリが見つかりません。")
        print("使い方:")
        print("  python3 generate_allwinner_image_cfg.py          # fex/ を使う場合")
        print("  python3 generate_allwinner_image_cfg.py myfex     # 任意のフォルダを使う場合")
        sys.exit(1)

    output_file = Path("image.cfg")

    print(f"fex ディレクトリ: {fex_dir.resolve()}")
    print(f"出力ファイル: {output_file}\n")

    # image.cfg の内容をリストで構築
    cfg = [
        "[image]",
        "version = 1.0",
        "chip = sunxi",           # ← 必要に応じて sun8i / sun50i / sunxi 等に変更してください
        "mode = normal",
        "",
        "[image_list]",
    ]

    # fexフォルダ内のファイルをソートして処理
    files = sorted(fex_dir.iterdir())

    for file_path in files:
        if not file_path.is_file():
            continue

        filename = file_path.name
        stem = file_path.stem  # 拡張子なし

        # 特殊ファイルはコメントを付けて扱いをわかりやすく
        if filename == "sys_config.fex":
            line = f'  item = sys_config : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        elif filename == "sys_partition.fex":
            line = f'  item = sys_partition : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        elif filename in ["boot0.fex", "boot1.fex", "u-boot.fex", "bootloader.fex"]:
            line = f'  item = {stem} : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'
        else:
            # その他の .fex や .bin はそのまま
            line = f'  item = {stem} : "{filename}" : 0x0 : 0x0 : 0x0 : 0x0'

        cfg.append(line)

    cfg.extend([
        "",
        "[end]"
    ])

    # ファイル書き出し（UTF-8、改行はLF）
    try:
        with open(output_file, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(cfg) + "\n")
        
        print(f"✅ {output_file} を正常に生成しました！")
        print("\n生成された image.cfg の内容を確認してください。")
        print("特に以下の部分は実際の環境に合わせて修正することをおすすめします：")
        print("   - chip = sunxi          ← SoCに合わせて変更（例: sun8iw7p1 など）")
        print("   - item の後ろの 0x0 : 0x0 : 0x0 : 0x0   ← オフセット・サイズ・verify など")
        print("\n次に imgRePacker または dragon ツールで以下のように使ってください：")
        print("   dragon image.cfg sys_partition.fex")
        
    except Exception as e:
        print(f"❌ ファイル書き出し中にエラーが発生しました: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
