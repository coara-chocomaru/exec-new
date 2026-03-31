#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
A733 Pro3 用 「GPTに明記されていない複数raw領域」自動抽出ツール
先頭raw領域に散らばる複数のfex相当ブロックを個別に切り出す
"""

import os
import sys
from tqdm import tqdm

def find_magic(data, magics):
    positions = []
    for magic in magics:
        pos = 0
        while True:
            pos = data.find(magic, pos)
            if pos == -1:
                break
            positions.append((pos, magic))
            pos += len(magic)
    return sorted(set(positions))  # 重複除去

def main():
    img_file = "test.img"
    output_dir = "extracted_raw_fex"

    if not os.path.exists(img_file):
        print("❌ test.img が見つかりません！ 先頭36MBをddで抜いてください")
        sys.exit(1)

    print("test.img を読み込み中...")
    with open(img_file, "rb") as f:
        data = f.read(36 * 1024 * 1024)

    # よくあるAllwinner rawヘッダー（複数検出用）
    magics = [
        b"eGON",      # Allwinner boot0/SPL
        b"TOC0",      # TOC0形式
        b"AWUS",      # Allwinner U-Boot signature
        b"SUNXI",     # sunxi header
        b"BOOT",      # 一般的なboot header
        b"UBOOT",     # U-Boot文字列周辺
    ]

    print("rawヘッダーを検索中...")
    found = find_magic(data, magics)

    if not found:
        print("❌ 既知のrawヘッダーが見つかりませんでした")
        print("   先頭512バイトの16進ダンプを出力します")
        print(data[:512].hex()[:512])  # 簡易ダンプ
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)
    extracted = 0

    for i, (pos, magic) in enumerate(found):
        # 各検出位置から前後数MBを安全に切り出す（サイズは調整可能）
        start = max(0, pos - 512 * 8)   # 少し前から
        end = min(len(data), pos + 1024*1024 * 8)  # 最大8MB程度
        chunk_size = end - start

        filename = f"raw_block_{i:02d}_{magic.decode('ascii', errors='ignore')}.fex"
        output_path = os.path.join(output_dir, filename)

        print(f"[{i+1}] 抽出: {filename}  offset={start:,}  size={chunk_size:,} bytes (magic: {magic})")

        with open(output_path, "wb") as dst:
            dst.write(data[start:end])

        extracted += 1

    # 念のため先頭全体も1ファイルとして保存（保険）
    with open(os.path.join(output_dir, "pre_gpt_raw_full_36MB.fex"), "wb") as dst:
        dst.write(data)

    print(f"\n🎉 完了！ {extracted}個のrawブロックを抽出しました")
    print(f"   フォルダ: {output_dir}")
    print(f"   これらをimgRePackerで適切な名前（bootloader.fexなど）に置き換えて使ってください")
    print(f"   pre_gpt_raw_full_36MB.fex は全領域の保険用です")

if __name__ == "__main__":
    main()
