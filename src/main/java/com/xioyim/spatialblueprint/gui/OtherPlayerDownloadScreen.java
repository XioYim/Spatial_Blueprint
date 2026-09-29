package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.ClientOtherPreviewCache;
import com.xioyim.spatialblueprint.client.OtherPlayerDownloadListener;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * Simple selection screen shown when /sbp download finds 2+ players with active previews.
 * Lists each player with a "下载" button.
 */
@OnlyIn(Dist.CLIENT)
public class OtherPlayerDownloadScreen extends Screen {

    private final List<ClientOtherPreviewCache.Entry> entries;

    public OtherPlayerDownloadScreen(List<ClientOtherPreviewCache.Entry> entries) {
        super(Component.translatable("spatial_blueprint.gui.download.title"));
        this.entries = entries;
    }

    @Override
    protected void init() {
        super.init();
        int startY = this.height / 2 - (entries.size() * 26) / 2;
        for (int i = 0; i < entries.size(); i++) {
            ClientOtherPreviewCache.Entry e = entries.get(i);
            int y = startY + i * 26;
            String label = e.playerName + "  §7[" + e.blueprintId.substring(0, Math.min(8, e.blueprintId.length())) + "...]";
            addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.gui.download.btn.download", label), b -> {
                downloadEntry(e);
                this.onClose();
            }).bounds(this.width / 2 - 110, y, 220, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.gui.download.btn.cancel"), b -> this.onClose())
                .bounds(this.width / 2 - 50, startY + entries.size() * 26 + 8, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(font, Component.translatable("spatial_blueprint.gui.download.title"), this.width / 2, this.height / 2 - (entries.size() * 26) / 2 - 18, 0xFFFFAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Initiates download of full blueprint data for an other-player entry. */
    public static void downloadEntry(ClientOtherPreviewCache.Entry entry) {
        if (entry.blueprintId.isEmpty()) return;
        UUID playerUUID = entry.playerUUID;
        // Use the normal download pipeline; hook result via callback
        ClientBlueprintCache.startDownload(entry.blueprintId);
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(entry.blueprintId));
        // Register a listener so when data arrives we attach it to the preview entry
        OtherPlayerDownloadListener.register(entry.blueprintId, playerUUID);
    }
}
