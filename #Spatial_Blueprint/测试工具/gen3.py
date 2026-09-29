# -*- coding: utf-8 -*-
"""
生成 NBT 体积预算（nbt_budget）相关的测试蓝图。

复用 gen2.py 的 NBT 写入函数，但只 exec 它的函数定义 —— gen2.py 的顶层有生成调用，
直接 import 会把原有 14 个文件全部重写一遍。

载体选玩家头颅而不是命令方块：BlueprintSanitizer.hasDangerousBlockNbt 只判
「容器物品 / 告示牌 clickEvent / 刷怪笼数据」，SkullOwner 不在其中；
而命令方块会先被非法方块检查拦掉，根本走不到体积这一步。
"""
import ast, io, os, sys

GEN2 = r"C:\Users\Yim\AppData\Local\Temp\opencode\gen2.py"
OUT  = r"D:\XioYimData\下载\新建文件夹"

# ── 只取 gen2.py 的函数定义与 import，丢掉所有顶层生成调用 ──
src  = io.open(GEN2, encoding='utf-8').read()
tree = ast.parse(src)
keep = [n for n in tree.body
        if isinstance(n, (ast.FunctionDef, ast.Import, ast.ImportFrom))]
ns = {}
exec(compile(ast.Module(body=keep, type_ignores=[]), 'gen2-funcs', 'exec'), ns)
gen_litematic = ns['gen_litematic']
print('复用 gen2 函数: %s' % ', '.join(sorted(k for k in ns if not k.startswith('_'))))

AIR  = "minecraft:air"
HEAD = "minecraft:player_head"
SIGN = "minecraft:oak_sign"


def idx_of(x, y, z, W, L):
    """litematic BlockStates 是 YZX 顺序。"""
    return y * (L * W) + z * W + x


def flat(W, H, L, filled):
    """filled: {(x,y,z): palette_index}，其余为 0（空气）。"""
    out = [0] * (W * H * L)
    for (x, y, z), pi in filled.items():
        out[idx_of(x, y, z, W, L)] = pi
    return out


def skull_te(x, y, z, texture_chars):
    """
    一个玩家头颅方块实体。NBT 体积由 base64 皮肤串主导。

    对照 BlueprintSanitizer.estimateNbtBytes 的估算规则：
    字符串按 2 字节/字符、标签头 2 字节、Compound 的每个 key 再 2+2*len(key)。
    所以估算值约等于 2*texture_chars + 150 左右的固定开销。
    """
    return {
        "id": (8, "minecraft:skull"),
        "x": (3, x), "y": (3, y), "z": (3, z),
        "SkullOwner": (10, {
            "Id":   (11, [1, 2, 3, 4]),
            "Name": (8, "TestHead"),
            "Properties": (10, {
                "textures": (9, (10, [{"Value": (8, "A" * texture_chars)}]))
            })
        })
    }


def sign_te(x, y, z, chars_per_line):
    """
    告示牌，四行长文本，**不带 clickEvent** —— 所以不算危险 NBT，
    只测体积。1.20.1 的结构是 front_text / back_text 各一组 messages。
    """
    line = '{"text":"' + ("B" * chars_per_line) + '"}'
    return {
        "id": (8, "minecraft:sign"),
        "x": (3, x), "y": (3, y), "z": (3, z),
        "is_waxed": (1, 0),
        "front_text": (10, {
            "has_glowing_text": (1, 0),
            "color": (8, "black"),
            "messages": (9, (8, [line, line, line, line])),
        }),
    }


def report(path, note):
    size = os.path.getsize(path)
    print("  %-40s %8d bytes  %s" % (os.path.basename(path), size, note))


print("\n输出目录: %s\n" % OUT)

# ── 16 单体超预算：一个头颅，估算约 40 KB，远超默认 8192 ──
W, H, L = 3, 3, 3
p = os.path.join(OUT, "16_大NBT_头颅单体超8KB.litematic")
gen_litematic(p, W, H, L, [AIR, HEAD],
              flat(W, H, L, {(1, 0, 1): 1}),
              tile_entities=[skull_te(1, 0, 1, 20000)])
report(p, "估算 ~40KB，默认阈值即触发降级")

# ── 17 总量超预算：200 个头颅，每个约 6 KB（单体不超），合计约 1.2 MB ──
W, H, L = 10, 2, 10
filled, tes = {}, []
for y in range(2):
    for z in range(10):
        for x in range(10):
            filled[(x, y, z)] = 1
            tes.append(skull_te(x, y, z, 3000))
p = os.path.join(OUT, "17_大NBT_头颅墙总量1.2MB.litematic")
gen_litematic(p, W, H, L, [AIR, HEAD], flat(W, H, L, filled), tile_entities=tes)
report(p, "200 个 x ~6KB，单体都不超，靠总量触发中止")

# ── 18 临界：一个略低于 8192、一个略高于 ──
W, H, L = 3, 1, 3
p = os.path.join(OUT, "18_大NBT_头颅临界8KB.litematic")
gen_litematic(p, W, H, L, [AIR, HEAD],
              flat(W, H, L, {(0, 0, 0): 1, (2, 0, 2): 1}),
              tile_entities=[skull_te(0, 0, 0, 3000),
                             skull_te(2, 0, 2, 4200)])
report(p, "~6.2KB 不该裁 / ~8.5KB 该裁，验证边界判定")

# ── 19 另一种载体：告示牌长文本，不带 clickEvent 所以不算危险 ──
W, H, L = 3, 1, 3
p = os.path.join(OUT, "19_大NBT_告示牌无命令超8KB.litematic")
gen_litematic(p, W, H, L, [AIR, SIGN],
              flat(W, H, L, {(1, 0, 1): 1}),
              tile_entities=[sign_te(1, 0, 1, 2500)])
report(p, "四行各 2500 字符，估算 ~20KB，验证载体不只头颅")

print("\n完成。")
