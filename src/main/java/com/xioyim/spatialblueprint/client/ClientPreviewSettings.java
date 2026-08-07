package com.xioyim.spatialblueprint.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent client-side settings for the blueprint preview feature.
 * Saved to {@code .minecraft/config/spatial_blueprint_preview.json}.
 */
@OnlyIn(Dist.CLIENT)
public class ClientPreviewSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "spatial_blueprint_preview.json";

    private static boolean enabled      = true;
    private static final Set<String> blockedPlayers = new HashSet<>();

    // ── Load / Save ───────────────────────────────────────────────────────

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d != null) {
                enabled = d.enabled;
                blockedPlayers.clear();
                if (d.blockedPlayers != null) blockedPlayers.addAll(d.blockedPlayers);
            }
        } catch (IOException ignored) {}
    }

    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                Data d = new Data();
                d.enabled        = enabled;
                d.blockedPlayers = new HashSet<>(blockedPlayers);
                GSON.toJson(d, w);
            }
        } catch (IOException ignored) {}
    }

    // ── API ───────────────────────────────────────────────────────────────

    public static boolean isEnabled()            { return enabled; }

    public static void setEnabled(boolean v) {
        enabled = v;
        if (!v) ClientOtherPreviewCache.clear();
        save();
    }

    public static boolean isBlocked(String name) { return blockedPlayers.contains(name); }

    public static void block(String name) {
        blockedPlayers.add(name);
        // Remove from live cache immediately
        ClientOtherPreviewCache.all().stream()
                .filter(e -> e.playerName.equals(name))
                .map(e -> e.playerUUID)
                .forEach(ClientOtherPreviewCache::remove);
        save();
    }

    public static void unblock(String name) {
        blockedPlayers.remove(name);
        save();
    }

    public static Set<String> getBlockedPlayers() {
        return java.util.Collections.unmodifiableSet(blockedPlayers);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE);
    }

    private static class Data {
        @SerializedName("enabled")        boolean      enabled = true;
        @SerializedName("blockedPlayers") Set<String>  blockedPlayers = new HashSet<>();
    }
}
