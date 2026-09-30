package com.xioyim.spatialblueprint.client;

import net.minecraft.client.Minecraft;

/**
 * 客户端缓存：本玩家能否打开 /sb config 配置页面。
 *
 * <p>由 {@code S2C_ConfigPermMessage} 在登录时推送（= 服务端 hasPermissions(2)）。
 * 真正拦截在服务端（C2S 请求 / 应用都校验 OP），这里只负责别让非 OP 看到那个
 * 读了本地 toml、保存又被拒的假配置页面。</p>
 */
public final class ClientConfigPermCache {

    /**
     * 默认 {@code false}：同步包到达前一律按「不可打开」处理 ——
     * 宁可让刚登录的 OP 短暂开不了页面，也不要让非 OP 看到能编辑的样子。
     */
    private static boolean allowed = false;

    private ClientConfigPermCache() {}

    public static boolean canEdit() {
        return allowed;
    }

    public static void set(boolean value) {
        allowed = value;
    }

    /**
     * 断线 / 退出世界时复位，避免把上一个服的 OP 身份带到下一个服。
     *
     * <p>不复位的话，在 A 服当 OP 的玩家退出后进只有普通权限的 B 服，
     * Mod 列表里的 Config 按钮仍会放行 —— 进去看到的是一个读本地 toml、
     * 保存又被服务端拒绝的假页面，正是 {@link #canOpen} 想避免的情形。</p>
     */
    public static void clear() {
        allowed = false;
    }

    /** 打开配置页面失败时的提示文案 key。 */
    public static String deniedKey() {
        return "spatial_blueprint.config.open.denied";
    }

    /** 配置页面入口的统一判定：不在世界内（没有玩家）也一律拒绝。 */
    public static boolean canOpen(Minecraft mc) {
        return mc.player != null && allowed;
    }
}
