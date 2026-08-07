package com.xioyim.spatialblueprint.gui;

import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 实现此接口的 Screen 可以接收 ErrorToast 错误横幅，
 * 而不是被 S2C_OperationResult 强制关闭。
 */
@OnlyIn(Dist.CLIENT)
public interface ErrorToastSupport {
    void setErrorToast(Component message);
}
