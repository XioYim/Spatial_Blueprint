package com.xioyim.spatialblueprint.util;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Bounded gzip-NBT reads for files and network payloads. */
public final class SafeNbtIo {

    /** Maximum decompressed NBT size for local-file and server-side reads. */
    public static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    /** Maximum decompressed NBT size for client-to-server uploads. */
    public static final long MAX_UNCOMPRESSED_BYTES_UNTRUSTED = 64L * 1024L * 1024L;

    private SafeNbtIo() {}

    public static CompoundTag readCompressed(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readCompressed(input, MAX_UNCOMPRESSED_BYTES);
        }
    }

    public static CompoundTag readCompressed(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return readCompressed(input, MAX_UNCOMPRESSED_BYTES);
        }
    }

    public static CompoundTag readCompressed(InputStream input) throws IOException {
        return readCompressed(input, MAX_UNCOMPRESSED_BYTES);
    }

    public static CompoundTag readCompressed(InputStream input, long maxUncompressedBytes) throws IOException {
        // FastBufferedInputStream 这一层不能省：NbtIo.read 逐字节读类型标记、名字长度和
        // 每个数值，没有缓冲时每次 read() 都要穿透 GZIPInputStream 的 inflate 调用链。
        // 原版 NbtIo.createDecompressorStream 正是 DataInputStream(FastBuffered(GZIP))。
        try (DataInputStream data = new DataInputStream(
                new FastBufferedInputStream(new GZIPInputStream(input)))) {
            return NbtIo.read(data, NbtAccounter.create(maxUncompressedBytes));
        } catch (RuntimeException e) {
            throw new IOException("Compressed NBT exceeds the decompression quota or is invalid", e);
        }
    }
}
