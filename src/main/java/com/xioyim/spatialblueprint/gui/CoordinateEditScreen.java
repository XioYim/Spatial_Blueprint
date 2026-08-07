package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.SetPointMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

@OnlyIn(Dist.CLIENT)
public class CoordinateEditScreen extends Screen implements ErrorToastSupport {

    private final Player player;
    private final Screen parent;

    private EditBox p1X, p1Y, p1Z;
    private EditBox p2X, p2Y, p2Z;

    @Nullable private Component errorToast;

    private static final int FIELD_W  = 52;
    private static final int FIELD_H  = 20;
    private static final int FIELD_GAP = 4;
    private static final int LABEL_W  = 30;

    public CoordinateEditScreen(Player player, Screen parent) {
        super(Component.translatable("spatial_blueprint.gui.coord_edit.title"));
        this.player = player;
        this.parent = parent;
    }

    private ItemStack getStack() {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BlankBlueprintItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BlankBlueprintItem) return off;
        return ItemStack.EMPTY;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width  / 2;
        int cy = this.height / 2;

        ItemStack stack = getStack();
        BlockPos pos1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos pos2 = BlankBlueprintItem.getPoint2(stack);

        int fieldsX = cx - (FIELD_W * 3 + FIELD_GAP * 2) / 2;

        int row1Y = cy - 16 + 50;
        int row2Y = cy + 10 + 50;

        p1X = addRenderableWidget(makeField(fieldsX,                           row1Y, pos1 != null ? pos1.getX() : null, "spatial_blueprint.gui.coord_edit.tooltip.p1x"));
        p1Y = addRenderableWidget(makeField(fieldsX + FIELD_W + FIELD_GAP,     row1Y, pos1 != null ? pos1.getY() : null, "spatial_blueprint.gui.coord_edit.tooltip.p1y"));
        p1Z = addRenderableWidget(makeField(fieldsX + (FIELD_W + FIELD_GAP)*2, row1Y, pos1 != null ? pos1.getZ() : null, "spatial_blueprint.gui.coord_edit.tooltip.p1z"));

        p2X = addRenderableWidget(makeField(fieldsX,                           row2Y, pos2 != null ? pos2.getX() : null, "spatial_blueprint.gui.coord_edit.tooltip.p2x"));
        p2Y = addRenderableWidget(makeField(fieldsX + FIELD_W + FIELD_GAP,     row2Y, pos2 != null ? pos2.getY() : null, "spatial_blueprint.gui.coord_edit.tooltip.p2y"));
        p2Z = addRenderableWidget(makeField(fieldsX + (FIELD_W + FIELD_GAP)*2, row2Y, pos2 != null ? pos2.getZ() : null, "spatial_blueprint.gui.coord_edit.tooltip.p2z"));

        int totalFieldsW = FIELD_W * 3 + FIELD_GAP * 2;
        int btnGap = 3;
        int btnW1 = (totalFieldsW - btnGap) / 2;
        int btnW2 = totalFieldsW - btnW1 - btnGap;
        int btnY = cy + 10 + 50 + FIELD_H + 6;  // row2Y bottom + 6px gap
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.coord_edit.btn.save"),
                b -> trySave())
                .bounds(fieldsX, btnY, btnW1, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.tooltip.capture_limit",
                                player.hasPermissions(2) ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME : com.xioyim.spatialblueprint.BlueprintConfig.MAX_CAPTURE_VOLUME_SURVIVAL.get(),
                                player.hasPermissions(2) ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME : com.xioyim.spatialblueprint.BlueprintConfig.MAX_CAPTURE_VOLUME_CREATIVE.get())))
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.coord_edit.btn.cancel"),
                b -> Minecraft.getInstance().setScreen(parent))
                .bounds(fieldsX + btnW1 + btnGap, btnY, btnW2, 20)
                .build());
    }

    private EditBox makeField(int x, int y, Integer value, String tooltipKey) {
        EditBox box = new EditBox(this.font, x, y, FIELD_W, FIELD_H, Component.empty());
        box.setMaxLength(9);
        box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{0,8}"));
        if (value != null) box.setValue(String.valueOf(value));
        box.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return box;
    }

    private void trySave() {
        BlockPos newP1 = parsePos(p1X, p1Y, p1Z);
        BlockPos newP2 = parsePos(p2X, p2Y, p2Z);

        boolean p1Entered = !p1X.getValue().isEmpty() || !p1Y.getValue().isEmpty() || !p1Z.getValue().isEmpty();
        boolean p2Entered = !p2X.getValue().isEmpty() || !p2Y.getValue().isEmpty() || !p2Z.getValue().isEmpty();

        if (p1Entered && newP1 == null) { errorToast = Component.translatable("spatial_blueprint.gui.coord_edit.error.format"); NotifySound.clientError(); return; }
        if (p2Entered && newP2 == null) { errorToast = Component.translatable("spatial_blueprint.gui.coord_edit.error.format"); NotifySound.clientError(); return; }

        if (newP1 != null && newP2 != null) {
            long dx  = Math.abs((long) newP2.getX() - newP1.getX()) + 1;
            long dy  = Math.abs((long) newP2.getY() - newP1.getY()) + 1;
            long dz  = Math.abs((long) newP2.getZ() - newP1.getZ()) + 1;
            long vol = dx * dy * dz;
            long limit = player.hasPermissions(2)
                    ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                    : (player.isCreative()
                            ? S2C_ConfigSyncMessage.clientMaxVolCreative
                            : S2C_ConfigSyncMessage.clientMaxVolSurvival);
            if (vol > limit) {
                errorToast = Component.translatable("spatial_blueprint.gui.coord_edit.error.over_limit");
                NotifySound.clientError();
                return;
            }
        }

        ItemStack stack = getStack();
        if (newP1 != null) {
            BlankBlueprintItem.setPoint1(stack, newP1);
            BlueprintNetwork.CHANNEL.sendToServer(new SetPointMessage(1, newP1));
        }
        if (newP2 != null) {
            BlankBlueprintItem.setPoint2(stack, newP2);
            BlueprintNetwork.CHANNEL.sendToServer(new SetPointMessage(2, newP2));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private BlockPos parsePos(EditBox xBox, EditBox yBox, EditBox zBox) {
        String xs = xBox.getValue().trim();
        String ys = yBox.getValue().trim();
        String zs = zBox.getValue().trim();
        if (xs.isEmpty() && ys.isEmpty() && zs.isEmpty()) return null;
        try {
            return new BlockPos(Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width  / 2;
        int cy = this.height / 2;

        int fieldsX = cx - (FIELD_W * 3 + FIELD_GAP * 2) / 2;

        // Column headers
        int headerY = cy - 28 + 50;
        graphics.drawCenteredString(this.font, Component.literal("X"), fieldsX + FIELD_W / 2,                       headerY, 0xAAAAAA);
        graphics.drawCenteredString(this.font, Component.literal("Y"), fieldsX + FIELD_W + FIELD_GAP + FIELD_W / 2, headerY, 0xAAAAAA);
        graphics.drawCenteredString(this.font, Component.literal("Z"), fieldsX + (FIELD_W+FIELD_GAP)*2 + FIELD_W/2, headerY, 0xAAAAAA);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) {
            errorToast = null;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
