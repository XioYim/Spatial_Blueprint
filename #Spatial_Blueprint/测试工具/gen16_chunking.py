# -*- coding: utf-8 -*-
"""
测试项 4（TASK-006 Fix A：服务端主动推送必须分片）的测试文件。

要测的 bug：S2C_BlueprintDataMessage 整包发送，decode 拒收超过 1 MiB 的载荷。
六处服务端主动推送以前直接发它，大蓝图推过去被拒 —— 玩家看到空预览，
必须重开蓝图库走拉取路径（那条一直是分片的）才有内容。
现在六处 + 拉取路径统一走 BlueprintNetwork.sendBlueprintTo，一个 chunk 也分片。

所以这里的关键指标是【压缩后】的字节数要超过 1 MiB ——
sendBlueprintTo 里是 NbtIo.writeCompressed(data.serialize()) 之后再切片。

【为什么填充必须是随机的】

  既有的 16/17/18 号用的是 "A" * n 这种重复串，gzip 压缩率极高：
  17 号声称「总量 1.2 MB」，落到磁盘只有 2035 字节。
  那种文件测总量预算没问题（预算算的是解压后），但测不了分片 ——
  压缩后连一个 chunk 都填不满。
  这里改用真随机字节的 base64：gzip 最多压到约 75%，压缩后体积才压得住。

【为什么载体是玩家头颅】

  BlueprintSanitizer.hasDangerousBlockNbt 只判「非空容器 / 告示牌 clickEvent /
  刷怪笼数据」，SkullOwner 的皮肤串不在其中 —— 又大又不危险，正合适。
  命令方块会先被非法方块检查拦掉，走不到这一步。

【单体必须低于 8192】

  单体超预算的方块在净化时会被整条清 NBT，清完文件就小了，分片也就测不到了。
  注意估算规则在 TASK-006 Fix B 之后改成了 modified UTF-8：
  ASCII 是 1 字节/字符（旧代码一律按 2 字节算）。
  base64 全是 ASCII，所以 6000 字符 ≈ 6000 字节，加固定开销约 6.2 KB < 8192。
  （gen3.py 里那句「2 字节/字符」的注释是旧算法，别照着算。）
"""
import ast, io, os, base64, random

GEN2 = r"C:\Users\Yim\AppData\Local\Temp\opencode\gen2.py"
OUT  = r"D:\XioYimData\下载\新建文件夹"

# 只取 gen2.py 的函数定义，丢掉顶层生成调用（否则会重写原有那批文件）
src  = io.open(GEN2, encoding='utf-8').read()
tree = ast.parse(src)
keep = [n for n in tree.body
        if isinstance(n, (ast.FunctionDef, ast.Import, ast.ImportFrom))]
ns = {}
exec(compile(ast.Module(body=keep, type_ignores=[]), 'gen2-funcs', 'exec'), ns)
gen_litematic = ns['gen_litematic']

AIR  = "minecraft:air"
HEAD = "minecraft:player_head"

SKIN_CHARS = 6000          # 单体估算 ≈ 6218 字节 < 8192，不会被判超大
RNG = random.Random(20260903)   # 固定种子，重跑得到同样的文件

ONE_MIB = 1024 * 1024


def idx_of(x, y, z, W, L):
    """litematic BlockStates 是 YZX 顺序。"""
    return y * (L * W) + z * W + x


def flat(W, H, L, filled):
    out = [0] * (W * H * L)
    for (x, y, z), pi in filled.items():
        out[idx_of(x, y, z, W, L)] = pi
    return out


def random_skin(n_chars):
    """真随机字节的 base64。gzip 对它基本无能为力（只能吃掉 base64 自身的冗余）。"""
    raw = RNG.randbytes(n_chars * 3 // 4 + 8)
    return base64.b64encode(raw).decode('ascii')[:n_chars]


def skull_te(x, y, z, i):
    """一个玩家头颅方块实体，NBT 体积由随机 base64 皮肤串主导。"""
    return {
        "id": (8, "minecraft:skull"),
        "x": (3, x), "y": (3, y), "z": (3, z),
        "SkullOwner": (10, {
            "Id":   (11, [1, 2, 3, i + 1]),
            "Name": (8, "H%04d" % i),
            "Properties": (10, {
                "textures": (9, (10, [{"Value": (8, random_skin(SKIN_CHARS))}]))
            })
        })
    }


def build(path, W, L, note, expect_over_1mib):
    """W x 1 x L 的头颅阵，每格一个头颅。"""
    filled, tes = {}, []
    i = 0
    for z in range(L):
        for x in range(W):
            filled[(x, 0, z)] = 1
            tes.append(skull_te(x, 0, z, i))
            i += 1
    gen_litematic(path, W, 1, L, [AIR, HEAD],
                  flat(W, 1, L, filled), tile_entities=tes)

    size = os.path.getsize(path)
    nbt_total = i * (SKIN_CHARS + 218)
    ok = (size > ONE_MIB) == expect_over_1mib
    print("  %-46s %9d bytes (%.2f MiB)  头颅 %d 个  NBT总量约 %.2f MB  %s"
          % (os.path.basename(path), size, size / ONE_MIB, i,
             nbt_total / 1e6, "OK" if ok else "!! 不符合预期"))
    return ok


print("\n输出目录: %s" % OUT)
print("单体皮肤串 %d 字符 → 单体估算约 %d 字节（阈值 8192）\n"
      % (SKIN_CHARS, SKIN_CHARS + 218))

all_ok = True

# ── 46 大蓝图：压缩后必须 > 1 MiB，用来打旧路径的 decode 上限 ──
all_ok &= build(os.path.join(OUT, "46_分片推送_大蓝图压缩超1MB.litematic"),
                20, 20, "", expect_over_1mib=True)

# ── 47 小蓝图对照：压缩后远小于一个 chunk，验证「没有 size 分支」──
#     小蓝图同样必须走分片路径，不能因为塞得下就退回整包发送。
all_ok &= build(os.path.join(OUT, "47_分片推送_小蓝图单chunk对照.litematic"),
                10, 6, "", expect_over_1mib=False)

print("\n参考阈值：")
print("  S2C_BlueprintDataMessage 整包上限   1 MiB = %d" % ONE_MIB)
print("  S2C_BlueprintChunkMessage.CHUNK_SIZE 512000")
print("  MAX_TOTAL_CHUNKS 256（约 125 MB，这两个文件都远远够不着）")
print("\n全部符合预期" if all_ok else "\n!! 有文件不符合预期，调整 W/L 后重跑")
