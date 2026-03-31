#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Windows用 Allwinner A733 Pro3 (sun60iw2) fex自動抽出ツール
test.img (先頭36MB) から sunxi_mbr を解析して全fexを抽出
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
        print("   → mmcblk0の先頭36MBをddで抜いたファイルを test.img という名前でこのフォルダに置いてください")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    # 先頭16KBを読み込み（MBRは通常先頭にあり）
    with open(img_file, "rb") as f:
        header = f.read(16384)

    # SUNXI_MBR magicを探す（オフセット0付近）
    mbr_offset = header.find(b"SUNXI_MBR")
    if mbr_offset == -1:
        print("⚠️ SUNXI_MBR magicが見つかりませんでした")
        print("   先頭36MBにMBRがない可能性があります")
        sys.exit(1)

    print(f"✅ SUNXI_MBR magicを検出 (オフセット: {mbr_offset} byte)")

    # MBR構造体解析（A733 Pro3実測値に基づく）
    # 構造: magic(8) + version(4) + nr_part(4) + parts[32] + crc(4)
    # part: name(16) + classname(16) + reserved(4) + start_lo(4) + start_hi(4) + len_lo(4) + len_hi(4) + ...
    f.seek(mbr_offset)
    data = f.read(16384)

    magic = data[0:8]
    version = struct.unpack_from("<I", data, 8)[0]
    nr_part = struct.unpack_from("<I", data, 12)[0]

    print(f"   バージョン: {version}  パーティション数: {nr_part}")

    extracted_count = 0
    for i in range(nr_part):
        part_offset = 16 + i * 128  # 各partの開始位置（128byte/エントリ）

        name_bytes = data[part_offset:part_offset+16]
        classname_bytes = data[part_offset+16:part_offset+32]
        start_lo = struct.unpack_from("<I", data, part_offset+36)[0]
        start_hi = struct.unpack_from("<I", data, part_offset+40)[0]
        len_lo = struct.unpack_from("<I", data, part_offset+44)[0]
        len_hi = struct.unpack_from("<I", data, part_offset+48)[0]

        name = name_bytes.split(b'\x00')[0].decode('utf-8', errors='ignore').strip()
        if not name:
            continue

        offset = (start_lo + (start_hi << 32)) * 512
        size = (len_lo + (len_hi << 32)) * 512

        if size == 0 or offset >= os.path.getsize(img_file):
            continue

        output_file = os.path.join(output_dir, f"{name}.fex")
        print(f"[{i+1:2d}/{nr_part}] 抽出中: {name:16}  offset={offset:,}  size={size:,} bytes")

        with open(img_file, "rb") as src:
            src.seek(offset)
            with open(output_file, "wb") as dst:
                # 1MBずつ読み込んで進捗表示
                with tqdm(total=size, unit='B', unit_scale=True, desc=name, leave=False) as pbar:
                    remaining = size
                    while remaining > 0:
                        chunk_size = min(1024*1024, remaining)
                        chunk = src.read(chunk_size)
                        if not chunk:
                            break
                        dst.write(chunk)
                        pbar.update(len(chunk))
                        remaining -= len(chunk)

        extracted_count += 1

    print(f"\n🎉 抽出完了！ {extracted_count}個のfexファイルを作成しました")
    print(f"   フォルダ: {os.path.abspath(output_dir)}")
    print("\n次にimgRePackerでこれらのfexを上書きしてFirmware.imgを作成してください！")

if __name__ == "__main__":
    main()
