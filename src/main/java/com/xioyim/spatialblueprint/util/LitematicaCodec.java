package com.xioyim.spatialblueprint.util;

/**
 * Litematica {@code .litematic} 格式的调色板位压缩编解码——读写共用同一套算法，
 * 避免 {@link LitematicaImporter}（读，外部导入）和 {@link BlueprintData}
 * （读+写，mod 自身持久化存储）各自实现一遍导致行为不一致。
 *
 * <p>条目允许跨越 long 边界（不是 1.16+ 版本区块那种"从不跨边界"的打包方式），
 * 这是 Litematica 自己的 {@code LitematicaBitArray} 实现方式，必须原样复刻。</p>
 */
public final class LitematicaCodec {

    private LitematicaCodec() {}

    /** bitsPerEntry = max(2, ceil(log2(paletteSize)))。 */
    public static int bitsPerEntry(int paletteSize) {
        int size = Math.max(paletteSize, 2);
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(size - 1));
    }

    /** 存放 {@code totalEntries} 个、每个占 {@code bitsPerEntry} 位的条目所需的 long 数组长度。 */
    public static int arrayLength(int totalEntries, int bitsPerEntry) {
        long totalBits = (long) totalEntries * bitsPerEntry;
        return (int) ((totalBits + 63) / 64);
    }

    /** 取出第 {@code index} 个条目的值。 */
    public static long getPackedValue(long[] arr, int index, int bitsPerEntry) {
        final long bitIndex    = (long) index * bitsPerEntry;
        final int  startLong   = (int) (bitIndex >>> 6);
        final int  startOffset = (int) (bitIndex & 0x3F);
        final long maxEntryValue = (1L << bitsPerEntry) - 1L;

        if (startLong < 0 || startLong >= arr.length) return 0L;

        long value;
        if (startOffset + bitsPerEntry <= 64) {
            value = arr[startLong] >>> startOffset;
        } else {
            int endLong = startLong + 1;
            if (endLong >= arr.length) return 0L;
            value = (arr[startLong] >>> startOffset) | (arr[endLong] << (64 - startOffset));
        }
        return value & maxEntryValue;
    }

    /** 写入第 {@code index} 个条目的值（原地修改 {@code arr}）。 */
    public static void putPackedValue(long[] arr, int index, int bitsPerEntry, long value) {
        final long mask = (1L << bitsPerEntry) - 1L;
        value &= mask;

        final long bitIndex    = (long) index * bitsPerEntry;
        final int  startLong   = (int) (bitIndex >>> 6);
        final int  startOffset = (int) (bitIndex & 0x3F);

        arr[startLong] = (arr[startLong] & ~(mask << startOffset)) | (value << startOffset);

        if (startOffset + bitsPerEntry > 64) {
            final int bitsInFirst = 64 - startOffset;
            final int endLong = startLong + 1;
            arr[endLong] = (arr[endLong] & ~(mask >>> bitsInFirst)) | (value >>> bitsInFirst);
        }
    }

    /** 把一个相对坐标压成单个 long key，用作 HashMap 查找方块实体/坐标的键。 */
    public static long packXYZ(int x, int y, int z) {
        return ((long) (y & 0xFFFF) << 32) | ((long) (z & 0xFFFF) << 16) | (x & 0xFFFF);
    }
}
