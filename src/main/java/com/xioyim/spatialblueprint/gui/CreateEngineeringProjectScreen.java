package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_CreateEngineeringBlueprintMessage;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * 创建工程蓝图的确认/配置界面。
 *
 * <p>由 {@link PlacementScreen} 的 ⌘ 按钮打开。</p>
 * <ul>
 *   <li>名称输入框（预填入源蓝图名称，可修改）</li>
 *   <li>距离输入框（默认 64，仅数字）</li>
 *   <li>只读显示：维度、起点坐标、终点坐标</li>
 *   <li>[取消] [创建工程] 按钮</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class CreateEngineeringProjectScreen extends Screen implements ErrorToastSupport {

    private final Screen  parent;
    private final String  sourceId;
    private final String  dim;
    private final BlockPos startPos;
    private final BlockPos endPos;

    private EditBox nameInput;
    private EditBox distInput;
    private Component errorToast;

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int PANEL_W  = 300;
    private static final int DIST_W   = 52;   // width of the distance input box
    private static final int NAME_GAP = 6;    // gap between name and dist inputs
    private static final int BTN_W    = 96;
    private static final int BTN_H    = 20;
    private static final int BTN_GAP  = 8;

    /**
     * @param parent        上层界面（关闭时返回）
     * @param sourceId      源蓝图 UUID 字符串
     * @param blueprintName 源蓝图名称（预填入名称框）
     * @param dim           当前维度 ResourceLocation 字符串
     * @param startPos      预览建筑的起点（min 角）
     * @param endPos        预览建筑的终点（max 角）
     */
    public CreateEngineeringProjectScreen(Screen parent, String sourceId,
                                          String blueprintName, String dim,
                                          BlockPos startPos, BlockPos endPos) {
        super(Component.translatable("spatial_blueprint.gui.create_engineering.title"));
        this.parent    = parent;
        this.sourceId  = sourceId;
        this.dim       = dim;
        this.startPos  = startPos;
        this.endPos    = endPos;
        // blueprintName stored via field initializer inside init()
        // (we capture it in a local for init use)
        this._initName = blueprintName;
    }

    /** Temporary holder so blueprintName is accessible inside init(). */
    private final String _initName;

    @Override
    protected void init() {
        super.init();

        int cx    = this.width  / 2;
        int cy    = this.height / 2;
        int leftX = cx - PANEL_W / 2;    // left edge of the 300-px panel

        // ── Name input ────────────────────────────────────────────────────
        int nameW  = PANEL_W - DIST_W - NAME_GAP;   // ≈ 242 px
        int inputY = cy - 30;

        nameInput = new EditBox(this.font, leftX, inputY, nameW, 20,
                Component.translatable("spatial_blueprint.gui.create_engineering.name"));
        nameInput.setMaxLength(64);
        nameInput.setValue(_initName);
        nameInput.setFocused(true);
        this.addWidget(nameInput);

        // ── Distance input ────────────────────────────────────────────────
        int distX = leftX + nameW + NAME_GAP;
        distInput = new EditBox(this.font, distX, inputY, DIST_W, 20,
                Component.translatable("spatial_blueprint.gui.create_engineering.distance"));
        distInput.setMaxLength(5);
        distInput.setValue("64");
        distInput.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        distInput.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.create_engineering.distance_tip")));
        this.addWidget(distInput);

        // ── Action buttons ─────────────────────────────────────────────────
        int btnY      = cy + 60;
        int totalW    = BTN_W * 2 + BTN_GAP;
        int cancelX   = cx - totalW / 2;
        int createX   = cancelX + BTN_W + BTN_GAP;

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> onClose())
                .bounds(cancelX, btnY, BTN_W, BTN_H)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_engineering.btn_create"),
                        b -> createEngineering())
                .bounds(createX, btnY, BTN_W, BTN_H)
                .build());
    }

    // ── Logic ─────────────────────────────────────────────────────────────

    /** Parse the distance box; clamps to [1, 99999], default 64 on parse error. */
    private int getMaxDistance() {
        try {
            int v = Integer.parseInt(distInput.getValue().trim());
            return Math.max(1, Math.min(99999, v));
        } catch (NumberFormatException e) {
            return 64;
        }
    }

    private boolean isDebug() {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.isCreative() && p.getTags().contains("DeBug");
    }

    private void createEngineering() {
        String name = nameInput.getValue().trim();

        if (isDebug()) SpatialBlueprintMod.LOGGER.info(
                "[EngBP][DBG][Client] createEngineering 触发 name='{}' sourceId='{}' dim='{}' start={} end={} dist={}",
                name, sourceId, dim, startPos, endPos, getMaxDistance());

        if (name.isEmpty()) {
            if (isDebug()) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG][Client] 拒绝：名称为空");
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.message.name_required");
            return;
        }

        BlueprintNetwork.CHANNEL.sendToServer(
                new C2S_CreateEngineeringBlueprintMessage(
                        sourceId, name, dim, startPos, endPos, getMaxDistance()));

        if (isDebug()) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG][Client] 消息已发送到服务端");

        if (Minecraft.getInstance().player != null) {
            NotifySound.client();
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("spatial_blueprint.gui.placement.engineering_created"), false);
        }

        // 关闭所有 UI（不返回父界面）
        Minecraft.getInstance().setScreen(null);
    }

    // ── Screen overrides ──────────────────────────────────────────────────

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx    = this.width  / 2;
        int cy    = this.height / 2;
        int leftX = cx - PANEL_W / 2;

        // ── Title ──────────────────────────────────────────────────────────
        graphics.drawCenteredString(this.font, this.title, cx, cy - 80, 0xFFFFFF);

        // ── Column labels above the input row ─────────────────────────────
        int nameW  = PANEL_W - DIST_W - NAME_GAP;
        int labelY = cy - 43;
        graphics.drawString(this.font,
                Component.translatable("spatial_blueprint.gui.create_engineering.name"),
                leftX, labelY, 0xAAAAAA, false);
        graphics.drawString(this.font,
                Component.translatable("spatial_blueprint.gui.create_engineering.distance"),
                leftX + nameW + NAME_GAP, labelY, 0xAAAAAA, false);

        // ── Input widgets ─────────────────────────────────────────────────
        nameInput.render(graphics, mouseX, mouseY, partialTick);
        distInput.render(graphics, mouseX, mouseY, partialTick);

        // ── Read-only info block ───────────────────────────────────────────
        int infoY  = cy + 5;
        int lineH  = 13;

        // Dimension
        Component dimLine = Component.translatable("spatial_blueprint.gui.create_engineering.dimension")
                .append(formatDim(dim).withStyle(ChatFormatting.YELLOW));
        graphics.drawString(this.font, dimLine, leftX, infoY, 0xCCCCCC, false);

        // Start pos
        String startStr = startPos.getX() + ", " + startPos.getY() + ", " + startPos.getZ();
        Component startLine = Component.translatable("spatial_blueprint.gui.create_engineering.start")
                .append(Component.literal(startStr).withStyle(ChatFormatting.AQUA));
        graphics.drawString(this.font, startLine, leftX, infoY + lineH, 0xCCCCCC, false);

        // End pos
        String endStr = endPos.getX() + ", " + endPos.getY() + ", " + endPos.getZ();
        Component endLine = Component.translatable("spatial_blueprint.gui.create_engineering.end")
                .append(Component.literal(endStr).withStyle(ChatFormatting.AQUA));
        graphics.drawString(this.font, endLine, leftX, infoY + lineH * 2, 0xCCCCCC, false);
    }

    /** Formats a dimension ResourceLocation string as a translatable Component for display (L3). */
    private static MutableComponent formatDim(String dim) {
        return switch (dim) {
            case "minecraft:overworld"  -> Component.translatable("spatial_blueprint.dimension.overworld");
            case "minecraft:the_nether" -> Component.translatable("spatial_blueprint.dimension.nether");
            case "minecraft:the_end"    -> Component.translatable("spatial_blueprint.dimension.end");
            default -> Component.literal(dim);
        };
    }

    // ── Input delegation ──────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        if (nameInput.mouseClicked(mouseX, mouseY, button)) {
            nameInput.setFocused(true);
            distInput.setFocused(false);
            return true;
        }
        if (distInput.mouseClicked(mouseX, mouseY, button)) {
            distInput.setFocused(true);
            nameInput.setFocused(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) { errorToast = null; return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            createEngineering();
            return true;
        }
        if (nameInput.isFocused() && nameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (distInput.isFocused() && distInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameInput.isFocused() && nameInput.charTyped(codePoint, modifiers)) return true;
        if (distInput.isFocused() && distInput.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }
}
