# allwinner_head_parser.py
# Windows用 Allwinner 先頭領域（36MB）分離・解析ツール
# Python 3.8以上で動作（標準ライブラリのみ使用）

import os
import sys
import struct

def find_magic(data, magic_bytes, max_offset=0x02400000):
    """magic_bytesを探してオフセット一覧を返す"""
    positions = []
    offset = 0
    while offset < len(data):
        pos = data.find(magic_bytes, offset)
        if pos == -1 or pos > max_offset:
            break
        positions.append(pos)
        offset = pos + 1
    return positions

def main():
    if len(sys.argv) < 2:
        print("使い方: python allwinner_head_parser.py <head_36M.img>")
        print("例: python allwinner_head_parser.py head_36M.img")
        sys.exit(1)

    img_path = sys.argv[1]
    if not os.path.exists(img_path):
        print(f"エラー: ファイルが見つかりません → {img_path}")
        sys.exit(1)

    print(f"解析開始: {img_path} (先頭36MBまで処理)")
    
    with open(img_path, "rb") as f:
        data = f.read(36 * 1024 * 1024)  # 36MBまで読み込み

    # 主要なmagic文字列検索（16進数オフセット表示）
    print("\n=== Magic文字列検索 (16進数オフセット) ===")
    magics = {
        "TOC0": b"TOC0",
        "U-Boot": b"U-Boot",
        "sunxi_mbr": b"sunxi_mbr",
        "EGON": b"EGON.BT0",
        "boot0": b"boot0",
        "dlinfo": b"dlinfo",
        "sys_config": b"sys_config",
    }

    for name, magic in magics.items():
        positions = find_magic(data, magic)
        if positions:
            print(f"{name:12} 発見位置:")
            for pos in positions[:10]:  # 最大10件まで
                print(f"  0x{pos:08X}  ({pos:,} bytes)")
        else:
            print(f"{name:12} 見つかりませんでした")

    # TOC0検出と抽出（典型的なTOC0ヘッダー: 最初の4バイトがTOC0）
    toc_positions = find_magic(data, b"TOC0")
    if toc_positions:
        for i, pos in enumerate(toc_positions[:3]):
            # TOC0ヘッダー解析（簡易）
            if pos + 0x20 < len(data):
                length = struct.unpack_from("<I", data, pos + 0x08)[0]  # よく使われる長さフィールド
                print(f"\nTOC0 #{i+1} を抽出中... (オフセット 0x{pos:08X}, 推定サイズ {length} bytes)")
                out_name = f"toc0_{i+1}_0x{pos:08X}.img"
                with open(out_name, "wb") as f:
                    f.write(data[pos:pos + max(0x10000, length + 0x1000)])  # 安全に少し多めに
                print(f"  → 保存: {out_name}")
    
    # sunxi_mbr検出と抽出
    mbr_positions = find_magic(data, b"sunxi_mbr")
    if mbr_positions:
        for i, pos in enumerate(mbr_positions[:2]):
            print(f"\nsunxi_mbr #{i+1} を抽出中... (オフセット 0x{pos:08X})")
            out_name = f"sunxi_mbr_0x{pos:08X}.fex"
            # MBRは通常数KiB〜64KiB程度
            with open(out_name, "wb") as f:
                f.write(data[pos:pos + 0x20000])
            print(f"  → 保存: {out_name}")
            
            # 簡易MBR情報ダンプ（part数など）
            if pos + 0x100 < len(data):
                part_count = struct.unpack_from("<I", data, pos + 0x04)[0] if pos + 0x08 < len(data) else 0
                print(f"    検出されたpartition数: {part_count} (29個が期待値)")

    # U-Bootっぽい大きなブロックを抽出（128KiB以降の領域）
    print("\nU-Boot / boot_package 候補領域を抽出...")
    # 128KiB (0x20000) から数MiB単位で試す
    candidates = [0x00020000, 0x00400000, 0x00800000, 0x00A00000]
    for start in candidates:
        if start + 0x100000 < len(data):  # 最低1MiB確保
            out_name = f"uboot_package_0x{start:08X}.img"
            with open(out_name, "wb") as f:
                f.write(data[start:start + 0x800000])  # 8MiB分（調整可）
            print(f"  0x{start:08X} から抽出 → {out_name} (8MiB)")

    print("\n解析完了！")
    print("生成されたファイル:")
    for file in os.listdir("."):
        if file.startswith(("toc0_", "sunxi_mbr_", "uboot_package_")) and file.endswith((".img", ".fex")):
            size = os.path.getsize(file) / (1024*1024)
            print(f"  {file}  ({size:.2f} MiB)")

    print("\n復旧用firmware作成のヒント:")
    print("1. toc0_xxx.img と sunxi_mbr_xxx.fex を先頭に配置")
    print("2. /dev/block/by-name/ の各partitionを sunxi_mbr内のaddrloに従って配置")
    print("3. PhoenixSuit / LiveSuit で焼く")

if __name__ == "__main__":
    main()
