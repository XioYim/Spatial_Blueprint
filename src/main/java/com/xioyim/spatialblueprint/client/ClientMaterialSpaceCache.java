package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.gui.MaterialSpaceScreen;
import com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端缓存：玩家材料空间普通池中，<b>当前蓝图清单涉及的那些材料</b>的已存入数量。
 *
 * <p>材料空间是服务端权威数据，客户端不再从蓝图物品 NBT 读取已存入量
 * （旧实现依赖 {@code ItemSyncUtil.syncHand} 自动携带，迁移后那条链路已断开）。
 * 服务端在每次存入 / 消耗后通过 {@link S2C_MaterialSpaceSyncMessage} 推送增量。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientMaterialSpaceCache {

    private static final Map<String, Integer>     counts = new LinkedHashMap<>();
    private static final Map<String, CompoundTag> tags   = new LinkedHashMap<>();
    private static int total    = 0;
    private static int capacity = 0;
    /** 每次收到服务端同步自增。界面据此判断是否需要用权威值覆盖乐观更新。 */
    private static int version  = 0;

    private ClientMaterialSpaceCache() {}

    /** 同步版本号；变化即表示服务端推来了新数据。 */
    public static int version() {
        return version;
    }

    /** 某材料复合键的已存入数量。 */
    public static int count(String materialKey) {
        return counts.getOrDefault(materialKey, 0);
    }

    /** 该复合键的 NBT（用于重建显示用 ItemStack）。 */
    @Nullable
    public static CompoundTag tagFor(String materialKey) {
        return tags.get(materialKey);
    }

    /** 普通池当前总件数。 */
    public static int total() {
        return total;
    }

    /** 普通池上限。 */
    public static int capacity() {
        return capacity;
    }

    public static void onReceive(S2C_MaterialSpaceSyncMessage msg) {
        counts.clear();
        counts.putAll(msg.getCounts());
        tags.clear();
        tags.putAll(msg.getTags());
        total    = msg.getTotal();
        capacity = msg.getCapacity();
        version++;

        if (msg.isOpenScreen()) {
            Minecraft.getInstance().setScreen(
                    new MaterialSpaceScreen(msg.getTargetName(), msg.getTargetUuid(),
                            msg.getCounts(), msg.getTags(),
                            msg.getTotal(), msg.getCapacity()));
        }
    }

    /**
     * 断线 / 退出世界时清空。
     *
     * <p>材料空间是按玩家存在服务端世界数据里的，换服 / 换档后数字完全不同。
     * 不清的话界面会拿上一个服的已存入量去算「还差多少」，显示成负数或直接显示够了。</p>
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** 断线 / 退出世界时清空。 */
    public static void clear() {
        counts.clear();
        tags.clear();
        total = 0;
        capacity = 0;
        version++;
    }
}
