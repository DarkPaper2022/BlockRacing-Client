# BlockRacing Task Board

Minecraft 26.2 的 Fabric **纯客户端**模组；为配套的 [BlockRacing Paper 插件](https://github.com/DarkPaper2022/BlockRacing) 提供实时 8×8 目标面板。不是 Fabric 服务端模组，不会自行判断任务完成。

## 使用

安装 Fabric Loader 0.19.3+、Minecraft 26.2 的 Fabric API 0.154.0+，以及本模组 JAR。需要 Java 25+。服务端需包含 `blockracing:board_request` / `blockracing:board_v1` 桥接接口；普通服务器上不接管 Tab。

- 游戏内按一次 **Tab** 打开，再按 Tab 或 **Esc** 关闭；打开期间不暂停游戏。
- **Shift+Tab** 在游戏画面中保留原版玩家列表（玩家列表仍绑定 Tab 时）。聊天、背包等界面的 Tab 不接管，也不改写原有键位配置。
- 64 格普通目标，独立 3 格 Bonus；滚轮/PgUp/PgDn 切换超出 64 项的页，B 切换额外 Bonus，方向键选择格子。
- 图标、目标名、分数与队伍进度常驻，悬停查看完整信息。已结算目标保留原位置；“已结算”不代表一定是本队完成。
- 可选配套 `BlockRacing-TaskIcons-26.2.zip` 资源包展示自定义动作/候选轮播；不安装也可用原版图标。望远镜始终保持原版模型。

## 构建

```sh
JAVA_HOME=/path/to/jdk-25-or-newer ./gradlew build
```

产物 `build/libs/blockracing-client-0.1.0.jar`（不要安装 `-sources.jar`）。首次构建需要网络；Gradle 9.5.1 分发带 SHA-256 校验。使用项目内缓存可额外设置 `GRADLE_USER_HOME=/absolute/path/to/local-cache`，无需全局安装 Gradle。

固定构建基线：Minecraft 26.2、Fabric Loader 0.19.3、Fabric API 0.154.0+26.2、Loom 1.17.20。运行时允许同一 Minecraft 版本上的更新兼容版本。模组源码 AGPL-3.0；Gradle wrapper 属于 Gradle 项目（Apache-2.0）。没有打包 Minecraft JAR、资源包或其他 Mojang 素材。

## 协议与边界

纯只读投影：C→S 只有一个布尔字节（订阅/取消），不发送分数、队伍或完成请求。S→C 是有版本号的 GZIP UTF-8 JSON，最多 30,000 字节，解压上限 512 KiB、最多 512 项。每 5 秒续租，服务端每秒更新，12 秒无续租自动停止；仅打开面板时订阅。

不依赖任务源码或 CSV 内置副本；任务名称、规则、状态和进度均来自服务端。进度条限制在 100%，文字保留服务端真实计数。超过 4 秒未更新明确显示快照过期。退出服务器清空状态。

完整布局/协议说明和设计预览生成器位于父项目 `docs/client-task-board.md` 与 `tools/export_board_preview.py`。客户端与服务端可分别演进，但协议版本必须兼容。

首次版本须完成配套 Paper/Fabric 实机联调后再投入比赛，编译和离线单测不等于游戏端验收。

2026-09-06：JAR 构建成功，11 项客户端测试通过（含父仓库提供的 Paper 编码契约样本）。独立构建时可选契约测试跳过；传 `-PserverFixture=/absolute/path/to/task-board-contract.bin` 可一并运行。
