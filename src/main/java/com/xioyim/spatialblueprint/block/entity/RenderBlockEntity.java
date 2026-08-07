package com.xioyim.spatialblueprint.block.entity;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RenderBlockEntity extends BlockEntity {
    public byte drawSize = 0;
    public BlockState renderBlock = Blocks.AIR.defaultBlockState();
    public BlockState targetBlock = Blocks.AIR.defaultBlockState();
    public CompoundTag blockEntityData = null;
    public boolean shrinking = false;
    public boolean exchanging = false;
    private int serverTickCount = 0;

    public RenderBlockEntity(BlockPos pos, BlockState state) {
        super(SpatialBlueprintRegistry.RENDER_BLOCK_ENTITY.get(), pos, state);
    }

    public void tickClient() {
        increaseDrawSize();
    }

    public void tickServer() {
        // 计算动画持续 tick 数，与客户端 drawSize 从 0 增长到 maxSize 的时间对齐
        int animTicks = Math.max(1, getMaxSize() / getAnimationSpeed());
        serverTickCount++;
        if (serverTickCount >= animTicks) {
            // 动画已完成，立即替换为真实方块，无需等待 forcePlaceAllBlocks
            setRealBlock(targetBlock);
        }
    }

    public byte getMaxSize() {
        return 20;
    }

    public int getAnimationSpeed() {
        return BlueprintConfig.ANIMATION_SPEED.get();
    }

    public void setRealBlock(BlockState realBlock) {
        if (realBlock == null) {
            level.setBlockAndUpdate(this.getBlockPos(), Blocks.AIR.defaultBlockState());
            return;
        }
        BlockState adjustedState = Block.updateFromNeighbourShapes(realBlock, level, getBlockPos());
        level.setBlockAndUpdate(this.getBlockPos(), adjustedState);
        if (blockEntityData != null && !blockEntityData.isEmpty()) {
            BlockEntity newBE = level.getBlockEntity(this.getBlockPos());
            if (newBE != null) {
                try {
                    newBE.load(blockEntityData);
                    newBE.setChanged();
                } catch (Exception e) {
                    SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to restore tile data at {}: {}",
                            this.getBlockPos(), e.getMessage());
                }
            }
        }
    }

    public void increaseDrawSize() {
        int speed = getAnimationSpeed();
        if (shrinking) {
            drawSize -= speed;
            if (drawSize <= 0) drawSize = 0;
        } else {
            drawSize += speed;
            if (drawSize >= getMaxSize()) drawSize = getMaxSize();
        }
    }

    public byte nextDrawSize() {
        if (shrinking) return (byte) (drawSize - 1);
        return (byte) (drawSize + 1);
    }

    public void setRenderData(BlockState sourceBlock, BlockState targetBlock) {
        this.targetBlock = targetBlock;
        if (sourceBlock.equals(Blocks.AIR.defaultBlockState())) {
            exchanging = false;
            shrinking = false;
            this.renderBlock = targetBlock;
            drawSize = 0;
        } else if (targetBlock.equals(Blocks.AIR.defaultBlockState())) {
            exchanging = false;
            shrinking = true;
            this.renderBlock = sourceBlock;
            drawSize = getMaxSize();
        } else {
            exchanging = true;
            shrinking = true;
            this.renderBlock = sourceBlock;
            drawSize = getMaxSize();
        }
        markDirtyClient();
    }

    public void setBlockEntityData(CompoundTag tag) {
        blockEntityData = tag;
        markDirtyClient();
    }

    public void markDirtyClient() {
        this.setChanged();
        if (this.getLevel() != null) {
            BlockState state = this.getLevel().getBlockState(this.getBlockPos());
            this.getLevel().sendBlockUpdated(this.getBlockPos(), state, state, 3);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.renderBlock = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("renderBlock"));
        this.targetBlock = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("targetBlock"));
        this.shrinking = tag.getBoolean("shrinking");
        this.exchanging = tag.getBoolean("exchanging");
        this.drawSize = tag.getByte("drawSize");
        this.serverTickCount = tag.contains("serverTickCount") ? tag.getInt("serverTickCount") : 0;
        if (tag.contains("blockEntityData")) {
            this.blockEntityData = tag.getCompound("blockEntityData");
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.renderBlock != null) {
            tag.put("renderBlock", NbtUtils.writeBlockState(this.renderBlock));
        }
        if (this.targetBlock != null) {
            tag.put("targetBlock", NbtUtils.writeBlockState(this.targetBlock));
        }
        tag.putBoolean("shrinking", shrinking);
        tag.putBoolean("exchanging", exchanging);
        tag.putByte("drawSize", this.drawSize);
        tag.putInt("serverTickCount", this.serverTickCount);
        if (blockEntityData != null) {
            tag.put("blockEntityData", this.blockEntityData);
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() == null) return;
        this.load(pkt.getTag());
    }
}
