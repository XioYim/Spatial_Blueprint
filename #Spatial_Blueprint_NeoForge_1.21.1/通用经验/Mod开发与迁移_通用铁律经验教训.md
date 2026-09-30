# Minecraft Mod 开发与迁移 · 通用铁律、经验与教训

> 来源：Healthy_Diets（Forge 1.20.1，2026-08 ～ 09）与它的 NeoForge 1.21.1 迁移（2026-09-28 起）一路踩出来的规则，去掉了项目私有的类名与数值，**任何 mod、任何会话都适用**。
> 写于 2026-09-29。项目自己的记忆（`#<项目>\记忆与注意事项\`）优先于本文件；两边冲突时以项目记忆为准，并回来改本文件。
> 同目录姊妹文档：`新系统开发指南_避免客户端服务端不同步.md`（下称 **G1**，同步问题的完整方法论）、`新增自定义配置菜单方法.MD`（**G2**）。

**怎么读**：第一节「铁律」改代码前整读；第二、三节做验收 / 派任务前读；第四节跑脚本前扫一眼；第五节只在做 Forge 1.20.1 → NeoForge 1.21.1 迁移时读。

---

## 一、铁律（违反会直接出 bug，而且多半不报错）

### 1. 同步：默认一切都是服务器级别
1. **改任何东西先问一句：客户端和服务端会不会不同步？** 这是所有 mod 最大的 bug 来源。单机两端同进程、静态字段共享、config 目录共享，所以**单机测不出来的不同步，专服上全会炸**（详见 G1）。
2. **服务端往客户端推表，一律读服务端权威副本**。单机同 JVM 里「有效值 / 投影」getter 可能返回客户端投影，推出去的是自己上次推的旧值，`/reload` 看起来无效。
3. **引用服务端数据的 C2S 包一律按内容 / key，不按下标**；**选择器的候选列表必须来自服务端**，本地列表会列出服务端不存在的东西，存下去静默失效。
4. **注册表内容由配置决定 = 配置必须同步**，否则登录握手对不上。
5. **玩法值不许放进不同步的纯客户端配置**，只有纯观感可以放。
6. **客户端预测与服务端权威算法必须同一份或同步改**；预测最多只能「被服务端否决一次」，不能让玩家永远做不了。

### 2. 网络协议
1. **动 wire format / 增删网络包 → 必须 bump 协议版本号**，客户端与服务端**同一次构建**一起换。
2. **协议版本号以源码为准，文档里不写数字**：文档停在旧值、代码已 bump，照文档以为不用 bump，结果握手被拒。
3. **编解码两侧的校验不许放宽**：放宽等于改协议语义，旧客户端解码新包会被 `DecoderException` 踢下线。
4. **一个结构在多处同步时（record 本体 / 默认值 NONE / `isDefault()` / 包的 encode+decode）要四处一起改**，漏一处就静默排除（编译能过、不报错）。给这种结构加字段前先 grep 全部出现位置。

### 3. 设计与语义
1. **同一个问题两种处理 = 遗漏，不是设计**。报告说「双端有意不对称」时，先问项目里有没有同类场景已经处理过。
2. **配置为 null ≠ 用户关掉**：「从没表达过意见」和「明确不要」的默认行为必须不同。
3. **注释说「由 X 兜底」时，追进 X 验证它的语义真的对得上**。注释常常没说谎，只是没说全。
4. **症状里的名词 ≠ 代码里的模块**，排查先跟数据流，别按名字找文件。
5. **验「配置立即生效」要在线改 + 重载命令、不重登**；重登会掩盖推送缺失。
6. **已承认的现状写进记忆并标「别当 bug 修」**，否则每个新会话都会重新「修」一遍。

### 4. Mixin
1. **mixin 包里的类不能被 mixin 之外的代码直接引用或 new**：`IllegalClassLoadError`，启动即崩，javac 看不见。载体类放非 mixin 包。
2. **mixin 改动编译通过不算数**：运行时才织入，必须 `runClient` / `runServer` 看注入日志。
3. **`@Redirect` / `@ModifyVariable` 追加的原方法参数必须按目标方法参数原序**，写错只有运行时 `InvalidInjectionException`。
4. **目标方法签名一律 `javap` 取证**，别凭记忆或旧版本写。同一个 jar 要兼容两种加载器时，两边的 jar 都要 javap 过。
5. **判断「有没有容器 / 返还物」不能只看属性**：原版有硬编码在方法里的返还，类兜底表要和 mixin 目标列表同步改。

### 5. 名字与双线维护
1. **两条版本线并行时，名字保持一致**：包名、类名、配置文件名与键、lang 键、网络包类名、命令名。另一条线靠名字逐行对照，非改不可的登记到联动手册。
2. **修了 bug / 加了功能，当场登记另一条线要不要跟**（和写开发日志同时），不需要跟的也写理由。
3. **两条线的协议版本各自独立**，数字不对应。
4. 迁移期间旧线尽量只修 bug、不加大功能。

---

## 二、验证与验收

1. **编译证据**：`compileJava --rerun`（Forge 线加 `--offline`），成败看 `BUILD SUCCESSFUL`，不看退出码。**增量 `classes` 会 UP-TO-DATE、一条警告都不打印，不算证据**。每个项目立一个警告基线（数字写进记忆），每次验收重新实测。
2. **javac 在中文系统输出 GBK**（`警告:` 不是 `warning:`），按英文 grep 恒为 0，计数用 `源文件:行号:`。
3. **框定这一轮改了哪些文件**：日常任务用 `find -newermt <任务书时间>`；**迁移 / 复制类任务 mtime 会漏**（Windows 复制保留源 mtime），一律「目标全量文件列表 vs 允许清单」`comm` 两向比对，再逐个与源 `diff -w --strip-trailing-cr`，滤掉 import / 注解 / 样板后看残差。
4. **和备份比对必须 `--strip-trailing-cr`**：`core.autocrlf=true` 下行尾差异完全隐形。
5. **默认不信任报告自述**。已知五类不可靠：虚报文件清单、虚报覆盖范围、伪造任务书内容、节选冒充逐字引用、计数虚报。每条主张都自己 grep / diff 验。
6. **任务书也会写错**：写「以源码为准，有出入在报告里指出」，验收时核对执行者的纠正是否对。
7. **GameTest 要测接线，不只测序列化**：用假玩家 + 往事件总线 post 真事件，断言副作用（数值变了 / 被取消了），证明处理器真的挂上了。
8. **往返字节测试的样本容器要与 decode 同型**（decode 用 HashMap，样本也用 HashMap），否则迭代序不同会误报。
9. **测试会往开发目录写残留**（配置、数据包），会挡住默认配置生成、污染下一次运行。测试要自己清理，或用单独的测试目录。
10. **实机 / 开发运行要优雅停服**（stop 命令），不要强杀进程树；强杀后假定配置 / 存档可能被写坏。
11. **双端不同步类 bug 必须用独立客户端验**：开发环境服务端与第一个客户端常共用 `run/` 目录，在它上面必然隐形。启动顺序：服务端 → 等 Done → 客户端，**永不多端齐发**。
12. **显示链路要一路跟到渲染**，中间任何一环 return 都会让前面的判断白做。
13. **有「取出来是副本」语义的数据（1.21 组件、record 的 copy 访问器等）**：写脚本审计两类问题：「读了、改了、没写回」，以及「中间已经自写回，又用旧副本把它覆盖」。漏写回不报错，只表现为数据不变。

---

## 三、协作（主会话 + 辅助 AI）

1. **派任务前先备份**（`备份.ps1 -Project <根> -Note "..."`），备份写进任务书第一段；第二段只给项目记忆路径，其余让执行者自己按读取顺序去读。
2. **派任务前工作区必须干净**：主会话先提交文档再派。**执行期间主会话不提交、不写开发日志**，也不碰那块代码（「顺手先做一半」最贵）。
3. **主会话在执行期间做下一阶段的取证附件**：逐文件清单、javap 结论、要打的桩、验收思路。任务书里把能取证的先取证、写成「已定方案」，执行者照做；有异议写进报告，不自己改方案。
4. **报告禁止代码块**，只给「主张 + file:line」；单列「改了写法的调用」「测试覆盖」「迁移桩清单」三张清单。
5. **迁移桩**：本阶段不迁的依赖「注释掉那一条调用 + 上一行 `// <前缀>-TODO(P<阶段>): <类名>`」；每阶段报告写「新桩 + 存量 = grep 实数」，后续阶段逐步清零。
6. **交接件并发**：同一时刻只允许一个会话写待办清单这类覆盖写文件；开发日志 / 测试编号用时取当前最大号 +1，别提前预留。
7. **记忆只记一句话要点 + 指向开发日志编号**；过程、取证、改动细节留在日志。

---

## 四、本机环境坑（Windows 10 + Git Bash + PowerShell 5.1）

共同点：**全都不报错、退出码 0，只是结果悄悄错**。写完任何脚本都要核验输出内容与编码。

- **Bash 工具会把 `\\` 折成 `\`**（heredoc / 单引号都挡不住）：写含反斜杠的内容走 Edit 工具或 PowerShell。
- **Git Bash 的 `sed -i` 会把 CRLF 文件整份改成 LF**；含中文或反斜杠的 sed 替换容易失败，失败后 `&&` 后面的命令不会跑、但换行后的命令照跑，所以改交接件 / 源码优先用 Edit。
- **PS 5.1**：native 命令 `2>&1` / `2>$null` 会把 stderr 包成错误；没有三参数 `Replace`；含中文的 `.ps1` 要带 BOM，`.md/.json/.html` 不要 BOM；输出中文前设 `OutputEncoding`；内联 `-Command` 里的中文会被 GBK 解码。
- **hook 从 stdin 读 payload 必须按 UTF-8 字节读**。
- **禁止按命令行子串批量 `Stop-Process`**；换 jar 前先 `Get-Process java,javaw`，运行中换 jar 会出现伪装成别的故障的 `NoClassDefFoundError`。
- **文件名带 `[` `]` 的 jar，PowerShell 复制 / 删除一律 `-LiteralPath`**，否则方括号被当通配符、静默只处理一部分。
- **jar 不是可复现构建**，SHA 只在同一次构建的拷贝之间可比。
- Bash 里的 python 可能被沙箱挡或是 WindowsApps 空壳（静默不执行），批处理用 gawk / PowerShell。
- `rm -rf` 在安全门禁里默认拒绝；临时目录换新名字即可。
- **安全门禁会拦「把 `>` 重定向写进 `src/`」的 Bash 命令**（整条命令都不执行，前面的 `cp` / `sed` 也一起没跑）：往 `src/` 下写新文件用 Write 工具，或先写到 /tmp 再 `cp`；`sed -i` 原地改不受限。
- **PowerShell 里带逗号的参数会被拆成数组**：`gradlew runServer -PwithMods=a,b` 会解析失败，必须写成 `'-PwithMods=a,b'`。
- **PowerShell 5.1 的 `Get-Content | Set-Content` 会把 UTF-8 文件按 ANSI 读写，中文和注释全部变乱码**（`gradle.properties` 就中招过，只能 `git checkout` 恢复）：改配置文件用 `sed -i` 或 Edit 工具，或显式 `-Encoding UTF8`。
- **`Remove-Item` 在含空格的系统路径上会被门禁拦**（`C:\Program Files\...`）：删单个文件用 Bash `rm -f 具体路径`。
- **给子进程 stdin 写 `stop`：`StreamWriter` 会先写 UTF-8 BOM，第一条命令被当成未知命令**（`?stop`）。用 `p.StandardInput.BaseStream.Write(字节)` 写不带 BOM 的字节，或开 RCON；否则只能 `Stop-Process` 硬杀（无数据保存）。起服后先发一条无关命令垫一下更稳。
- **Bash 工具单条命令超过 2 分钟会转后台**：大文件下载 / SHA-256 用 `run_in_background` 或 Monitor 等结果，别硬等。
- **Windows 上 Bash 的 `sed 's/…/…\\…/'` 里写 `\\` 会被折成 `\`**：要往文本里插反斜杠（路径），用 Edit 工具。

---

## 五、Forge 1.20.1 → NeoForge 1.21.1 迁移专章

### 5.1 总体做法
1. **新建姊妹项目，不原地改**；旧线继续可发版，两边共用一份「功能联动手册」（子系统 → 两边文件 → 旧线记忆编号 → 新线状态；同步登记表）。
2. **按层分阶段，一个阶段一个任务**：P0 骨架 → P1 纯逻辑 → P2 注册与数据（组件 / Attachment）→ P3 网络 → P4 服务端事件与命令 → P5 mixin → P6 客户端（界面按引用簇拆）→ P7 联动 → P8 测试环境、部署与回归。阶段太大就拆 a / b / c（按依赖簇切，簇外引用打桩）。
3. **决策点在它影响的阶段之前拍板**：版本号起点、旧存档是否升级、物品数据怎么建组件、稀有度等枚举扩展方式、协议版本编号、联动名单。
4. **环境**：Java 21（Zulu 21），与 1.20.1 线的 Java 17 各设各的 `JAVA_HOME`；不在一个项目目录跑另一个项目的 gradle（mod_id 相同时 `run/` 配置会混）。
5. **取证**：javap 看 `build\moddev\artifacts\neoforge-<版本>-merged.jar`（vanilla + NeoForge、官方映射）；FML 注解类在 gradle 缓存的 `fancymodloader\loader` jar。全量扫描可以把 merged.jar 解压后 grep 类文件里的字段 / 方法名，再逐个 javap。
6. **`neoforge.mods.toml` 模板经 `${}` 展开，注释里不能写反斜杠和 `$`**（反斜杠吞换行导致 mod 发现阶段失败）。改模板后解包 jar 看一眼。
7. **GameTest**：21.1.x 没有 `@EmptyTemplate`，找不到结构整个 gameTestServer 崩；放一个 1×1×1 空结构 `data/<modid>/structure/empty.nbt`，写 `@GameTest(template = "empty")` + `@PrefixGameTestTemplate(false)`。结构目录是单数 `structure/`。mock 玩家接口已删，要 ServerPlayer 用 `FakePlayerFactory.get(ServerLevel, GameProfile)`；它的连接会丢弃发出的包。`runGameTestServer` 不用同意 EULA，适合冒烟。

### 5.2 已取证的 API 对照（精选；标 javap 的都核过）
| 1.20.1（Forge） | 1.21.1（NeoForge） |
|---|---|
| 物品 NBT（getTag / getOrCreateTag） | 自定义 `DataComponentType`（`persistent(Codec)` + `networkSynchronized(StreamCodec)`），值用 record；**取出来是副本，改完必须 set 写回**；不许往 `custom_data` 里塞键当 NBT 用 |
| `isSameItemSameTags` | `isSameItemSameComponents`（static）；NeoForge 所有 IItemHandler（InvWrapper / SidedInvWrapper / ItemStackHandler / ItemHandlerHelper）判堆叠都调它（javap） |
| `ItemHandlerHelper.canItemStacksStack(Relaxed)` | 已删（javap） |
| `areCapsCompatible` | 物品没有 capability 了，删 |
| Capability（Attach / LazyOptional / Provider / Clone 复制） | `AttachmentType` + `player.getData(TYPE)`；`serializable(...)` + `copyOnDeath()` 后 Clone 处理器不需要 |
| `SimpleChannel` / `NetworkEvent.Context` | `CustomPacketPayload` + `StreamCodec` + `event.registrar(modid).versioned(v)`；**handler 默认主线程**，去掉 enqueueWork / setPacketHandled；两个方向的大包由 NeoForge 自动拆分 |
| `buf.writeComponent / readComponent` | 已删；`ComponentSerialization.TRUSTED_STREAM_CODEC`，缓冲要 `RegistryFriendlyByteBuf`（字节与旧版不同） |
| `Component.Serializer.toJson / fromJson(s)` | 必须带 `HolderLookup.Provider`（javap）；含物品悬停等内容时不能用 `RegistryAccess.EMPTY` |
| `ForgeRegistries.X.getValue(id) == null` | `BuiltInRegistries.X.get(id)` 缺键返回默认值（空气）不是 null → 判存在用 `containsKey` |
| `new ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath` / `parse`（构造器私有） |
| `ItemStack.of(tag)` | 已删；`ItemStack.parse(provider, tag)` 返回 Optional |
| `Item#getMaxStackSize` | 上限在组件上，从栈取 |
| `PotionUtils` | `PotionContents` 组件 |
| `BowlFoodItem` | 已删；碗 / 瓶返还由 `FoodProperties.usingConvertsTo` 在 `Player.eat(Level, ItemStack, FoodProperties)` 处理（javap） |
| `FoodProperties.Builder().meat()` / `alwaysEat()`；`isEdible()` | 6 参 record（nutrition / saturation / canAlwaysEat / eatSeconds / usingConvertsTo / effects），`alwaysEdible()`；判可食用用 `has(DataComponents.FOOD)` |
| `MobEffect` | `Holder<MobEffect>`；`removeEffect(Holder)` |
| `new AttributeModifier(UUID, name, amount, Operation.ADDITION…)` | `record AttributeModifier(ResourceLocation id, double, Operation)`，操作改名 `ADD_VALUE` 等 |
| `Item#getRarity` | `ItemStack.getRarity()`（读 RARITY 组件）；枚举扩展要在 `enumextensions.json` 预声明 |
| `BlockStateBase.use` | 拆成 `useItemOn(...) → ItemInteractionResult` 与 `useWithoutItem(...) → InteractionResult`（javap） |
| 方块实体 `load / save` | `loadWithComponents(tag, Provider)` / `saveWithFullMetadata(Provider)`（javap）；1.20.5 起容器物品存储格式整体改了，旧数据要走 DataFixer |
| `SavedData.save(tag)` / `computeIfAbsent(load, new, name)` | `save(tag, Provider)`；`computeIfAbsent(new SavedData.Factory<>(ctor, load(tag, provider), null), name)`（javap） |
| `@Mod.EventBusSubscriber` | `net.neoforged.fml.common.EventBusSubscriber`，默认 bus = GAME；mod 总线那种写法已废弃（会多出警告），mod 总线监听改手动 addListener |
| `TickEvent.X(phase)` | `PlayerTickEvent / ServerTickEvent / LevelTickEvent / ClientTickEvent` 的 `.Pre / .Post`，side 自己判 |
| `EntityItemPickupEvent`（setCanceled） | `ItemEntityPickupEvent.Pre`，**不可取消**，用 `setCanPickup(TriState.FALSE)`；`getItem()` → `getItemEntity()`（javap） |
| `PlayerEvent.ItemPickupEvent`（getStack） | `ItemEntityPickupEvent.Post`（`getOriginalStack()`），只在 `Inventory.add` 成功后触发（javap） |
| `MobEffectEvent.Applicable` + `Event.Result.DENY` | `setResult(Applicable.Result.DO_NOT_APPLY)`（javap） |
| `RightClickBlock.getUseBlock() == DENY` | 返回 `TriState`，用 `isFalse()` |
| `DistExecutor` / `@OnlyIn` 剥离 | 不再剥离；`FMLEnvironment.dist` 判断 + 客户端逻辑放嵌套类，或 `@Mod(dist = Dist.CLIENT)` 客户端入口 |
| `ConfigScreenHandler.ConfigScreenFactory` | `IConfigScreenFactory.createScreen(ModContainer, Screen)`（javap） |
| `ForgeConfigSpec` | `ModConfigSpec` |
| 顶点 `vertex().color().endVertex()` | `addVertex(...)` / `setColor(...)`（javap） |
| `mouseScrolled(x, y, delta)` | `mouseScrolled(x, y, scrollX, scrollY)`（javap） |
| `getTooltipLines(player, flag)` | `getTooltipLines(Item.TooltipContext, Player, TooltipFlag)`；`ItemTooltipEvent` 多了 `getContext()`（javap） |
| `RenderGuiOverlayEvent` / `RegisterGuiOverlaysEvent` | `RegisterGuiLayersEvent`；`RenderGuiEvent.Post` 仍在 |
| 数据包 `tags/items`、`loot_tables`、`recipes`、`structures` | 单数：`tags/item`、`loot_table`、`recipe`、`structure` |
| GLM `Codec`，JSON 在 `data/forge/loot_modifiers/` | `MapCodec`，`data/neoforge/loot_modifiers/` |

### 5.3 迁移大坑（都是「编译能过、运行才错」）
1. **组件副本不写回**：数据静默不变（计时恒 0、合并结果不落盘）。验收必须脚本审计，见第二节第 13 条。
2. **物品堆创建时就把物品默认组件固定进自己的原型**（`ItemStack` 构造时 `new PatchedDataComponentMap(item.components())`，javap）。所以运行时改 `Item` 的默认组件，已存在的物品堆不会变。要「改完立即全服生效」的覆盖（如改原版食物数值）必须做**读取时覆盖**：玩家吃东西的全部数值路径（`Item.use` / `getUseDuration` / `finishUsingItem` / `LivingEntity.eat`，以及猫狼喂食）都经过 `ItemStack.getFoodProperties(LivingEntity)` → `IItemStackExtension` → `IItemExtension.getFoodProperties`，一个 mixin 就能覆盖；直接 `get(FOOD)` 的几处只判断「是不是食物」。
3. **堆叠判定 = 物品 + 组件完全相等**：组件里别放会变的时间戳；要自定义「等价可合并」，在 `isSameItemSameComponents` 上接管，并在插入前把两栈对齐。
4. **界面背景**：1.21 的 `Screen.render()` 第一步就是 `renderBackground`（菜单模糊 + 暗化，javap）。1.20.1 的写法「先手动画背景 → 画自己的面板 → `super.render`」搬过来，面板会被模糊盖住、背景画两遍。规则：删掉手动的 `renderBackground`，先 `super.render` 再画自己的内容；要自控背景就覆盖 `renderBackground`。1.21.1 没有「一帧只能模糊一次」的检查（更新版本才有），不会崩只会糊，只能开客户端看。
5. **拾取事件取消语义变了**（见 5.2）；写成「Pre 里 setCanceled」编译不过，写成别的等价物之前先 javap 看原版 `playerTouch` 怎么用 TriState。
6. **NeoForge 大包自动拆分**：两个方向都由 `GenericPacketSplitter` 处理（对端也是 NeoForge 即可），旧版自己写的分片可以保留（最稳），也可以评估去掉。
7. **旧存档 / 旧数据文件**：1.20.5 起物品、方块实体的存储格式改了，想兼容旧数据就要按 DataVersion 走原版 DataFixer；不兼容的话在决策点写清楚并告诉用户。
8. **任务书里的 API 结论要先 javap 再写**；凭记忆写的对照表会错，而执行者会照着错的写。
9. **新阶段开工先把 `src/main/resources` 与源项目全量比对**（文件清单 + 大小）：语言文件曾整个漏迁（只剩一个占位键），进游戏才发现所有界面 key 丢失。
10. **Mixin 的 `LocalCapture.CAPTURE_FAILHARD` 不受 `require = 0` / `expect = 0` 约束**：目标方法局部变量布局一变，被注入的**那个 mod 的类转换直接失败、游戏起不来**。兼容类 mixin 一律 `CAPTURE_FAILSOFT`，参数表按目标 jar 的 `javap -p -c -l` 的 LocalVariableTable 写。

### 5.4 联动 mod 移植套路（NF 线 7a～7d 实战，可复用于任何兼容层）
1. **先查有没有目标版本**：Modrinth API（`/v2/project/<slug>/version?loaders=[...]&game_versions=[...]`，返回带 sha1）；Modrinth 没有的（如 MrCrayfish 系）去官方 GitHub Releases。下载后校验 sha1 / 记 sha256，写进暂存目录的 `manifest.txt`。**读 jar 内的 `META-INF/neoforge.mods.toml` 才是真实前置**（API 里的 `required` 依赖数可能虚高，也可能漏——Shadered 的 GeckoLib 就没在 toml 声明，专服一带就 `NoClassDefFoundError`）。
2. **编译期只 `compileOnly` 目标 jar**（放项目 `libs/`，`.gitignore` 忽略）；运行期用 gradle 属性开关（`-PwithMods=lso,es,...`，默认不带，GameTest 基线不依赖联动 mod）。PowerShell 里逗号参数要加引号。
3. **对每个反射目标 / mixin 目标逐个 `javap`**：包名、类名、方法签名、返回类型（Optional 还是裸值）、字段名。大版本的联动 mod 常常是整个重写（Tom's 1.x→2.x、Obscure Tooltips 3.x→4.x 的类路径全变），不能只改包名。
4. **能全反射就全反射**（LSOCompat 就是），mod 没装时类都不加载；必须编译期引用的 Mixin 一律 `@Pseudo` + `require = 0`。
5. **验证 Mixin 真的织到调用点**：`-Dmixin.debug.export=true`（或 `JAVA_TOOL_OPTIONS`）导出转换后的类到 `run/.mixin.out/`，`javap -c` 看调用点已经被换成 `redirect$…`；目标类是懒加载的，要在 GameTest 里 `Class.forName` 强制加载再导出。`HDMixinPlugin.wasApplied` 只说明「mixin 类应用了」，不等于注入点生效。
6. **GameTest 写成「装了才真断言，没装安静通过」**：默认基线数不变，带 `-PwithMods` 再验联动；断言用注册表 id 和反射，不直接引用联动 mod 的类型（测试类在没有该 mod 时也要能加载）。
7. **客户端专属联动（tooltip / 渲染）专服测不到**：只能编译 + 导出字节码核 + 实机；写进测试清单交给用户。
8. **数据格式换代的联动（OT 3.x 的样式 JSON → 4.x 的 definition/style/element）先判断值不值得移植**：如果关键匹配字段依赖本项目的动态值（如枚举槽位名），静态 JSON 写不出来，宁可降级并写进已知缺口。

### 5.5 「主会话直接执行」的推进节奏（NF 线 P7 实践）
- 小而散的阶段（联动、桩恢复）主会话自己做，比派任务书快；大批量机械改写（数千行界面）才派。
- 每个子任务的闭环：取证 → 改 → `compileJava --rerun` 0 警告 → GameTest（带 / 不带联动）→ 写日志 → 提交。**不要攒多个子任务再提交**。
- 每次部署：改版本号 → `build` → 两端换 jar（旧的直接删）→ 专服冒烟（到 Done、`/hdreload`、`stop` 优雅退出码 0）→ 日志 / 项目记忆更新 → 完整备份 → 打标签。
- 实机测试交给用户时**一次一项**，给命令级步骤（`/give`、`/effect`、`/headiet set` 这类可复制的），用户回「通过」就写测试清单，不通过就要现象。
- 用户说「此前已测过」的项照记，并在结果里注明来源。

---

## 六、出处（查细节去这里）
- Healthy_Diets 1.20.1：`D:\Mods\Healthy_Diets\#Healthy_Diets\记忆与注意事项\`（铁律 T1–T20、坑 K1–K10）
- Healthy_Diets NeoForge 1.21.1：`D:\Mods\Healthy_Diets_NeoForge_1.21.1\#Healthy_Diets_NeoForge_1.21.1\`（记忆 T1–T6 / K1–K7 / C1；开发日志 e01 起；任务派发与核验下 NF-003 / 004b / 005 / 006 的取证附件）
- Spatial_Blueprint 迁移梗概：`D:\Mods\Spatial_Blueprint\#Spatial_Blueprint\迁移计划书_NeoForge_1.21.1.md`
