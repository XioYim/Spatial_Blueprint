# -*- coding: utf-8 -*-
"""
第 14 测试项：hoverEvent 嵌套是否真被检测接住（验收未证实，必测）。

载体：告示牌。messages 每行是文本组件 JSON（与物品 display.Name 同一套，
可以合法地嵌 hoverEvent: show_item）。hasDangerousBlockNbt 对告示牌只查
clickEvent(run/suggest)，hoverEvent 不会判危险；容器净化也不清告示牌文字。
所以这两条 JSON 字符串只会被「体积 / 深度」两道检测碰到。

  A「胖」—— 一行消息里 show_item 的 contents 是装满 320 件物品的箱子，
            消息字符串 ~14 KB → 估算 > 8 KB，应落「超大 NBT」。
  B「瘦但深」—— 一行消息里 tag 是 16 层嵌套的 JSON，整条 ~几百字节，
            估算 < 8 KB，而 16 层在『字符串内部』，exceedsMaxDepth 走标签树
            看不见 → 预期哪都不进（本项就是来证伪这条的）。

输出: 43_hoverA_胖_超长hoverItem.litematic / 43_hoverB_瘦深_嵌套16层.litematic
"""
import ast, io, os

GEN2 = r"C:\Users\Yim\AppData\Local\Temp\opencode\gen2.py"
OUT  = r"D:\XioYimData\下载\新建文件夹"

src  = io.open(GEN2, encoding='utf-8').read()
tree = ast.parse(src)
keep = [n for n in tree.body
        if isinstance(n, (ast.FunctionDef, ast.Import, ast.ImportFrom))]
ns = {}
exec(compile(ast.Module(body=keep, type_ignores=[]), 'gen2-funcs', 'exec'), ns)
gen_litematic = ns['gen_litematic']

AIR  = "minecraft:air"
SIGN = "minecraft:oak_sign"

def idx_of(x, y, z, W, L):
    return y * (L * W) + z * W + x

def flat(W, H, L, filled):
    out = [0] * (W * H * L)
    for (x, y, z), pi in filled.items():
        out[idx_of(x, y, z, W, L)] = pi
    return out

def sign_te_with_line(x, y, z, big_line):
    """一条告示牌，第一行是 big_line（其余空），只带 front_text。"""
    def line(s): return s  # message 直接是 JSON 文本组件字符串
    empty = '""'
    return {
        "id": (8, "minecraft:sign"),
        "x": (3, x), "y": (3, y), "z": (3, z),
        "is_waxed": (1, 0),
        "front_text": (10, {
            "has_glowing_text": (1, 0),
            "color": (8, "black"),
            "messages": (9, (8, [line(big_line), empty, empty, empty])),
        }),
    }

# ── A：胖。320 件物品的箱子作为 hoverEvent:show_item 载荷 ──
def fat_hover_json():
    items = [{"Slot": i, "id": "minecraft:stone", "Count": 1} for i in range(320)]
    import json
    payload = {
        "text": "A",
        "hoverEvent": {
            "action": "show_item",
            "contents": {
                "id": "minecraft:chest",
                "Count": 1,
                "tag": {
                    "BlockEntityTag": {"id": "minecraft:chest", "Items": items}
                }
            }
        }
    }
    return json.dumps(payload, separators=(",", ":"))

# ── B：瘦但深。16 层纯 JSON 嵌套，塞进 show_item 的 tag 里 ──
def deep_hover_json(levels=16):
    s = '"leaf"'
    for i in range(levels):
        s = '{"k%d":%s}' % (i, s)
    import json
    payload = {
        "text": "B",
        "hoverEvent": {
            "action": "show_item",
            "contents": {
                "id": "minecraft:stone",
                "Count": 1,
                "tag": json.loads('{"nested":' + s + '}') if False else {"nested": s}
            }
        }
    }
    import json as _j
    return _j.dumps(payload, separators=(",", ":"))

def report(path, note):
    print("  %-46s %8d bytes  %s" % (os.path.basename(path), os.path.getsize(path), note))

print("\n输出目录: %s\n" % OUT)

W, H, L = 3, 1, 3
a = os.path.join(OUT, "43_hoverA_胖_超长hoverItem.litematic")
gen_litematic(a, W, H, L, [AIR, SIGN],
              flat(W, H, L, {(1, 0, 1): 1}),
              tile_entities=[sign_te_with_line(1, 0, 1, fat_hover_json())])
report(a, "单行 ~14KB，期望落「超大 NBT」")

b = os.path.join(OUT, "43_hoverB_瘦深_嵌套16层.litematic")
gen_litematic(b, W, H, L, [AIR, SIGN],
              flat(W, H, L, {(1, 0, 1): 1}),
              tile_entities=[sign_te_with_line(1, 0, 1, deep_hover_json())])
report(b, "几百字节 + 字符串内 16 层，期望哪都不进(证伪点)")

print("\n完成。")
