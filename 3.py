#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Windows専用 A733 Pro3 (sun60iw2) fex自動抽出ツール
test.img（mmcblk0先頭36MB）からsunxi_mbrを解析して全fexを切り出す
GPTは無視 → 先頭の「gpt管理されてない」領域だけを対象
"""

import os
import struct
import sys
from tqdm import tqdm

def main():
    img_file = "test.img"
    output_dir = "extracted_fex"

    if not os.path.exists(img_file):
        print("❌ test.img が見つかりません！")
        print("   mmcblk0の先頭36MBを dd if=/dev/mmcblk0 of=test.img bs=1M count=36 で抜いて")
        print("   このスクリプトと同じフォルダに置いてください")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # 先頭36MB全部読み込み（MBR検索用）
    with open(img_file, "rb") as f:
        data = f.read(36 * 1024 * 1024)

    # SUNXI_MBR magicを検索（複数コピーされている場合も対応）
    mbr_positions = []
    pos = 0
    while True:
        pos = data.find(b"SUNXI_MBR", pos)
        if pos == -1:
            break
        mbr_positions.append(pos)
        pos += 8

    if not mbr_positions:
        print("❌ SUNXI_MBR magicが見つかりませんでした")
        print("   test.imgが先頭36MBじゃない可能性があります")
        sys.exit(1)

    print(f"✅ SUNXI_MBRを {len(mbr_positions)}箇所で検出しました")
    # 最初のMBRを使う（通常一番先頭のもの）
    mbr_offset = mbr_positions[0]

    # MBR解析（A733実機ログ準拠）
    header = data[mbr_offset:mbr_offset + 16384]
    nr_part = struct.unpack_from("<I", header, 12)[0]   # offset 12

    print(f"   パーティション数: {nr_part}個")

    extracted = 0
    for i in range(nr_part):
        part_offset = mbr_offset + 16 + (i * 128)   # 各エントリ128byte

        # name (16byte)
        name_bytes = header[part_offset - mbr_offset:part_offset - mbr_offset + 16]
        name = name_bytes.split(b'\x00')[0].decode('utf-8', errors='ignore').strip()
        if not name:
            continue

        # addrlo (オフセット), lenlo (サイズ)
        addr_lo = struct.unpack_from("<I", header, (part_offset - mbr_offset) + 36)[0]
        addr_hi = struct.unpack_from("<I", header, (part_offset - mbr_offset) + 40)[0]
        len_lo  = struct.unpack_from("<I", header, (part_offset - mbr_offset) + 44)[0]
        len_hi  = struct.unpack_from("<I", header, (part_offset - mbr_offset) + 48)[0]

        offset = (addr_lo + (addr_hi << 32)) * 512
        size   = (len_lo + (len_hi << 32)) * 512

        if size == 0 or offset >= len(data):
            continue

        output_file = os.path.join(output_dir, f"{name}.fex")

        print(f"[{i+1:2d}/{nr_part}] 抽出: {name:16}  offset={offset:,}  size={size:,} bytes")

        with open(img_file, "rb") as src:
            src.seek(offset)
            with open(output_file, "wb") as dst:
                remaining = size
                with tqdm(total=size, unit='B', unit_scale=True, desc=name, leave=False) as pbar:
                    while remaining > 0:
                        chunk_size = min(1024*1024, remaining)
                        chunk = src.read(chunk_size)
                        if not chunk:
                            break
                        dst.write(chunk)
                        pbar.update(len(chunk))
                        remaining -= len(chunk)

        extracted += 1

    print(f"\n🎉 完了！ {extracted}個のfexを extracted_fex フォルダに作成しました")
    print(f"   これでimgRePackerにドラッグしてFirmware.imgを作れます")
    print(f"   bootloader_a.fex / super.fex なども全部入っています")

if __name__ == "__main__":
    main()
