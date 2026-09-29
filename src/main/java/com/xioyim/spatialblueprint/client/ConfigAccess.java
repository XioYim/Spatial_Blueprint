package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.config.ConfigRegistry;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ApplyConfigMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestConfigMessage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 配置界面读写配置的<b>唯一入口</b>。
 *
 * <p>界面不再直接 {@code BlueprintConfig.XXX.get()/set()} —— 那读写的是<b>客户端自己那份
 * toml</b>，服主在服务器上改了半天，服务器毫不知情。现在一律走
 * {@link ClientConfigMirror}：读服务端快照，写只进待提交集合，
 * 点保存时一次性发回服务端由它落盘。</p>
 *
 * <p>还没收到快照时（刚打开界面的头几帧、或服务端不带本 mod）退回本地配置值 ——
 * 界面不会因为一次网络延迟就显示成一堆 0。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ConfigAccess {

    private ConfigAccess() {}

    /** 打开配置界面时调一次，向服务端索取当前值。 */
    public static void request() {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestConfigMessage());
    }

    public static int get(ForgeConfigSpec.IntValue value) {
        return ClientConfigMirror.getInt(ConfigRegistry.idOf(value), value.get());
    }

    /** 该项的出厂默认值（spec 里 defineInRange 的首参），供「重置为默认」按钮使用。 */
    public static int defaultValue(ForgeConfigSpec.IntValue value) {
        return ConfigRegistry.defaultInt(value);
    }

    public static boolean get(ForgeConfigSpec.BooleanValue value) {
        return ClientConfigMirror.getBool(ConfigRegistry.idOf(value), value.get());
    }

    public static void set(ForgeConfigSpec.IntValue value, int v) {
        ClientConfigMirror.setInt(ConfigRegistry.idOf(value), v);
    }

    public static void set(ForgeConfigSpec.BooleanValue value, boolean v) {
        ClientConfigMirror.setBool(ConfigRegistry.idOf(value), v);
    }

    public static List<String> getList(ForgeConfigSpec.ConfigValue<List<String>> value) {
        return ClientConfigMirror.getList(ConfigRegistry.idOf(value), value.get());
    }

    public static void setList(ForgeConfigSpec.ConfigValue<List<String>> value, List<String> v) {
        ClientConfigMirror.setList(ConfigRegistry.idOf(value), v);
    }

    /**
     * 把待提交的改动发给服务端。
     *
     * <p>只发改过的项 —— 两个 OP 同时开着配置界面时，各自只覆盖自己动过的那几行。
     * 服务端写完会回推一份新快照，界面上看到的就是真正落盘的值。</p>
     */
    public static void save() {
        if (!ClientConfigMirror.hasPending()) return;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ApplyConfigMessage(
                ClientConfigMirror.pendingInts(),
                ClientConfigMirror.pendingBools(),
                ClientConfigMirror.pendingLists()));
        ClientConfigMirror.clearPending();
    }

    /** 是否已经拿到服务端快照 —— 没拿到时界面上的数字只是本地兜底值。 */
    public static boolean hasSnapshot() {
        return ClientConfigMirror.hasSnapshot();
    }
}
