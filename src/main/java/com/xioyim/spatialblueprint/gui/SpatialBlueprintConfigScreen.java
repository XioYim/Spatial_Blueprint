package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ReloadConfigMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * Spatial Blueprint 游戏内配置主页面（Forge ModList → Config 按钮或 {@code /sb config} 打开）。
 *
 * <p>主页面只负责分类导航：7 个分类按钮 → 各自子页；[关闭] 返回上一界面。
 * 各分类的具体配置项编辑控件在子页中实现（当前为占位页）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SpatialBlueprintConfigScreen extends Screen {

    private final Screen parent;

    /** 分类按钮：id + lang key（显示名）。 */
    private static final String[][] CATEGORIES = {
            {"general", "spatial_blueprint.config.category.general"},
            {"material", "spatial_blueprint.config.category.material"},
            {"animation", "spatial_blueprint.config.category.animation"},
            {"placement", "spatial_blueprint.config.category.placement"},
            {"upload", "spatial_blueprint.config.category.upload"},
            {"special_drops", "spatial_blueprint.config.category.special_drops"},
    };

    public SpatialBlueprintConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int cx = this.width / 2;
        int btnW = 220;
        int btnH = 20;
        int gap = 6;
        int col = 2; // 两列排列，减少纵向占用
        int rows = (CATEGORIES.length + 1) / 2;
        int startY = Math.max(40, this.height / 2 - rows * (btnH + gap) - 10);
        for (int i = 0; i < CATEGORIES.length; i++) {
            int r = i / col;
            int c = i % col;
            int x = cx + (c == 0 ? -btnW - gap / 2 : gap / 2);
            int y = startY + r * (btnH + gap);
            String id = CATEGORIES[i][0];
            String key = CATEGORIES[i][1];
            addRenderableWidget(Button.builder(Component.translatable(key), b -> openCategory(id))
                    .bounds(x, y, btnW, btnH).build());
        }
        int closeY = startY + rows * (btnH + gap) + 10;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.save_and_close"),
                        b -> saveAndClose())
                .bounds(cx - btnW / 2, closeY, btnW, btnH).build());
    }

    private void openCategory(String id) {
        switch (id) {
            case "general" -> Minecraft.getInstance().setScreen(new GeneralConfigScreen(this));
            case "material" -> Minecraft.getInstance().setScreen(new MaterialConfigScreen(this));
            case "animation" -> Minecraft.getInstance().setScreen(new AnimationConfigScreen(this));
            case "placement" -> Minecraft.getInstance().setScreen(new PlacementConfigScreen(this));
            case "upload" -> Minecraft.getInstance().setScreen(new UploadConfigScreen(this));
            case "special_drops" -> Minecraft.getInstance().setScreen(new BlockListConfigScreen(this,
                    Component.translatable("spatial_blueprint.config.special_drops.title"),
                    "spatial_blueprint.config.special_drops.help",
                    com.xioyim.spatialblueprint.BlueprintConfig.SPECIAL_DROP_ITEMS, true));
            default -> Minecraft.getInstance().setScreen(new ConfigCategoryPlaceholderScreen(this, id));
        }
    }

    private void saveAndClose() {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ReloadConfigMessage());
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        ConfigTitles.render(graphics, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.title.help", this.width / 2, 16, mouseX, mouseY);
        graphics.drawCenteredString(this.font,
                Component.translatable("spatial_blueprint.config.subtitle"),
                this.width / 2, 32, 0x777777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return true; }

    /**
     * 分类子页占位：展示分类名与说明，具体配置项编辑控件后续填充。
     */
    @OnlyIn(Dist.CLIENT)
    public static class ConfigCategoryPlaceholderScreen extends Screen {
        private final Screen parent;
        private final String category;

        public ConfigCategoryPlaceholderScreen(Screen parent, String category) {
            super(Component.translatable("spatial_blueprint.config.placeholder.title", category));
            this.parent = parent;
            this.category = category;
        }

        @Override
        protected void init() {
            super.init();
            clearWidgets();
            addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                            b -> Minecraft.getInstance().setScreen(parent))
                    .bounds(this.width / 2 - 75, this.height / 2 + 20, 150, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(graphics);
            ConfigTitles.render(graphics, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                    "spatial_blueprint.config.placeholder.help", this.width / 2, 20, mouseX, mouseY);
            graphics.drawCenteredString(this.font,
                    Component.translatable("spatial_blueprint.config.placeholder.dev", category),
                    this.width / 2, this.height / 2 - 10, 0xAAAAAA);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }

        @Override
        public boolean isPauseScreen() { return true; }
    }
}
