# Spatial_Blueprint

Spatial Blueprint —— Minecraft 1.20.1（Forge）模组，modid `spatial_blueprint`，包根 `com.xioyim.spatialblueprint`；蓝图捕获与放置系统，含空间 / 投影 / 次元 / 工程 / 项目多类蓝图。**默认所有功能都是服务器级别。**

本文件只是路标。**全部项目文档在 `#Spatial_Blueprint\`，入口 `#Spatial_Blueprint\记忆与注意事项\项目记忆.md`。**
记忆 = `记忆与注意事项\注意事项_目录.md`（索引：编号 / 行号 / 读的时机）+ `注意事项_分类.md`（正文：T 铁律 / K 坑 / C 参考）。先查目录拿编号，再按行号只读那一段；不整读分类文件。**待办事项.html 照旧走 `D:\Mods\项目工具\html2text.ps1`**（`-List` / `-Card` 只读一段），开发日志.md 与测试清单.txt 直接 Read。

| 时机 | 读 |
|---|---|
| 会话开头 | `#Spatial_Blueprint\待办事项.html`（用户维护的待办清单，hook 剥标签注入；没注入就自己读——仍走 `html2text.ps1`）+ `记忆与注意事项\项目记忆.md` 末尾「环境现状」 |
| 改代码前 | `注意事项_分类.md` 的**铁律 T 段** —— 全部硬约束在那里，本文件不重复 |
| 跑脚本 / 杀进程 / 派任务 / 命令失败 | `注意事项_分类.md` 的 **K1 报警行**，再按需其余 K 段 |
| 任务涉及某子系统 | `注意事项_目录.md` 参考表按「读的时机」找 C 段 |

三份交接件的分工：**待办事项.html** 用户维护、AI 只提议；**开发日志.md** AI 做完即写（唯一免授权，文末追加 `## e<N>`）；**测试清单.txt** AI 写前必问。

最硬的几条（其余见铁律）：

- 改任何东西先问一句：**客户端和服务端会不会不同步？**
- **发版必须 bump mod_version**（协议版本 = mod 版本）；新增网络消息只能追加在 `BlueprintNetwork.register()` 末尾，插队 / 删除 / 改方向都必须 bump mod_version，双端一起换。
- 验编译用 `.\gradlew.bat compileJava --offline --rerun`，基线 20 条警告。
- 派 TASK / 破坏性改动前先备份：`D:\Mods\项目工具\备份.ps1 -Project D:\Mods\Spatial_Blueprint -Note "覆盖到哪一步"`。
- 通用门禁 / 命令在 `D:\Mods\项目工具\claude通用\`（改之前先读那里的 README）。
