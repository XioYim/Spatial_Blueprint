# Spatial_Blueprint_NeoForge_1.21.1

Spatial Blueprint 的 **NeoForge 1.21.1** 移植线：NeoForge 21.1.252、Java 21、modid `spatial_blueprint`、包根 `com.xioyim.spatialblueprint`（与 1.20.1 线同 ID）。git 仓库 `XioYim/Spatial_Blueprint` 的 **`neoforge-1.21.1` 分支**；`forge-1.20.1` 分支是 Forge 1.20.1 线（本机 `D:\Mods\Spatial_Blueprint`，本项目里**只读**）。**默认所有功能都是服务器级别。**

**协作方式（2026-09-29 起）：云端会话 = 主会话（规划、写 TASK、验收），本地会话 = 辅助 AI（执行、测试、同步、推送），全部经 git 任务分支 `task/SBN-00N` 往返。开工先读 `#Spatial_Blueprint_NeoForge_1.21.1\云端协作规程.md`。**云端会话不在、由本地会话一人接手全部工作时，改读 `#Spatial_Blueprint_NeoForge_1.21.1\本地接管手册.md`（现状、验收配方、路线图、未决事项都在里面）。**** 本地会话里用户说「执行 SBN-00N」，你就是辅助 AI，照规程第三节走；在云端被要求「验收 SBN-00N」或规划下一阶段，你就是主会话。

本文件只是路标。**全部项目文档在 `#Spatial_Blueprint_NeoForge_1.21.1\`，入口 `#Spatial_Blueprint_NeoForge_1.21.1\记忆与注意事项\项目记忆.md`。** 迁移总纲 `迁移计划书.md`，两条线对照与同步登记 `功能联动手册.md`。
记忆 = `记忆与注意事项\注意事项_目录.md`（索引：编号 / 行号 / 读的时机）+ `注意事项_分类.md`（正文：T 铁律 / K 坑 / C 参考）。先查目录拿编号，再按行号只读那一段；不整读分类文件。**待办事项.html 照旧走 `D:\Mods\项目工具\html2text.ps1`**（`-List` / `-Card` 只读一段），开发日志.md 与测试清单.txt 直接 Read。

| 时机 | 读 |
|---|---|
| 会话开头 | `#Spatial_Blueprint_NeoForge_1.21.1\待办事项.html`（用户维护的待办清单，hook 剥标签注入；没注入就自己读）+ `记忆与注意事项\项目记忆.md` 末尾「环境现状」 |
| 改代码前 | `注意事项_分类.md` 的**铁律 T 段**；搬 1.20.1 代码时再读 `#Spatial_Blueprint_NeoForge_1.21.1\通用经验\Mod开发与迁移_通用铁律经验教训.md` 第五节（API 对照；仓库副本，本机原件的同步见规程第五节） |
| 跑脚本 / 杀进程 / 派任务 / 命令失败 | `注意事项_分类.md` 的 **K1 报警行**，再按需其余 K 段 |
| 任务涉及某子系统 | `注意事项_目录.md` 参考表 + `功能联动手册.md` |
| 修完 bug / 加完功能 | 在 `功能联动手册.md` 同步登记表加一行：另一条线要不要跟 |

三份交接件的分工：**待办事项.html** 用户维护、AI 只提议；**开发日志.md** AI 做完即写（唯一免授权，文末追加 `## e<N>`）；**测试清单.txt** AI 写前必问。

最硬的几条（其余见铁律）：

- 改任何东西先问一句：**客户端和服务端会不会不同步？**
- 网络协议版本在 P3 定（决策点 D4）；定之前不存在网络包。动 wire format / 增删包必须 bump，双端一起换。
- 蓝图物品上的数据一律自定义数据组件、**只存引用**（D1），不用 `custom_data` 当 NBT；组件取出来是副本，改完必须写回。
- 验编译：PowerShell 先 `$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"`，再 `.\gradlew.bat compileJava --rerun`，基线 **0** 条警告。
- 派 TASK / 破坏性改动前先备份：`D:\Mods\项目工具\备份.ps1 -Project D:\Mods\Spatial_Blueprint_NeoForge_1.21.1 -Note "覆盖到哪一步"`。
- 通用门禁 / 命令在 `D:\Mods\项目工具\claude通用\`（改之前先读那里的 README）。
