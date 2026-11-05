# 正式安卓记录验证证据

本轮：2026-11-01。实现位于 `apps/android/`。结论：代码及debug APK交付，本地Android回归通过；实体手机安装与总验收未通过/待验证，不放行全部门禁。

## 执行环境与命令

Windows x64、JDK17、Gradle8.10.2、AGP8.7.3、Kotlin2.0.21、compile/target SDK35、min26。SDK与下载工具置于 `D:/android-tools/`，未修改系统防火墙/虚拟化设置。UI设计调用OMP当前designer角色，未覆盖模型。

最终命令：

```text
:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
BUILD SUCCESSFUL
```

Lint：No issues found。APK在 `apps/android/app/build/outputs/apk/debug/app-debug.apk`；测试APK在 `app/build/outputs/apk/androidTest/debug/`。

## 已观察结果

| 验证 | 数量 | 结果与覆盖边界 |
|---|---|---|
| RecordTimeTest | 4 | 未来时间、时区、夏令时缺口/重叠、无效日期通过；JVM时间逻辑 |
| RecordStoreTest | 6 | Android本地运行环境中SQLite执行；重开、草稿、历史、重试、删除恢复、故障事务回滚及损坏文件不重建通过 |
| RecordFlowTest | 1 | Compose真实交互：新增、失败重试、编辑草稿往返、修改历史、删除恢复通过；独立合成库 |
| 临时SmokeRenderTest | 1 | 实际Compose原生绘制并操作新增/草稿/保存/详情通过；脚本按清理要求移除 |

截图在 `apps/android/app/build/smoke/record-list.png`、`record-editor.png`、`record-detail.png`，build被忽略，不含个人数据。已打开编辑/详情图观察，并由Designer复核；修正底导航紫色偏差、长时间戳换行和图标依赖。

本地Android运行环境是Robolectric（API28，390dp宽），不是Android Emulator或实体设备；不宣称系统级行为已通过。仪器测试同源也可在设备执行。

## 遇到问题与修复

- 原生图标Sync不在icons-core，改用现有Settings图标，无新增大图标依赖。
- Robolectric旧SQLite不支持表达式索引，单新增草稿改为可移植UNIQUE/CHECK约束；相同数据库不以删库重试，故障用例通过。
- Compose测试未推进主线程协程导致等待超时，改用runOnIdle推进并断言实际草稿状态，不改功能来迎合测试。
- 冒烟断言错误要求整段精确匹配，改为明确子串核对完整正文中的目标内容。
- 默认SQLite损坏处理可能删除数据库，改为显式保留文件并报错，损坏文件保留回归通过。

## 实机阻塞与未覆盖

ADB无实体设备。已尝试SDK35 x86_64软件模拟器（offline）、API28 x86新版/兼容版（启动退出）、ARM镜像（宿主不支持）；未为此安装内核驱动或修改系统安全设置。已停止无效长驻模拟器。

R01—R21对应完整功能已写入，但真实进程终止/重启、中文输入法、触摸、真实设备存储不足、系统时区变化和实际安装仍待设备验收。当前数据库重开不等于系统重启。设备不同Android版本表现也待覆盖。

用户要求功能补齐后统一构建安装；最后在功能代码补齐后完成统一构建，未执行实体安装。没有发布签名/上架，没有备份、导出、同步、AI、API Key、定位权限，不能作相关能力承诺。

## 下一次验收

设备可用后按 [验收用例](acceptance.md) 逐项记录，先使用合成数据。尤其R14/R16需真实Android安装和输入法证据；总验收通过后再勾选AR-13/14，不以当前构建替代。

## Mobile MCP 连接验证（2026-11-02）

本节为后续新增事实，不改写上文历史环境。用户报告基本功能已手动检查，未提供逐项覆盖和证据；本次按授权仅接入工具和验证连接，不运行完整功能测试，不将 R01—R21 标为通过。

### 环境与接入

- OMP `18.14.0`，Node.js `22.13.1`，npm `10.9.2`。核实 [官方 README](https://github.com/mobile-next/mobile-mcp#installation-and-configuration)、npm 发布元数据及本机 OMP 配置文档；Mobile MCP 固定 `1.0.15`，满足 Node `>=20`。
- 原 OMP 实际发现的服务只有用户级 `drawio`，没有 Mobile MCP；本次新增项目级 `.omp/mcp.json`，保留原用户配置，字节比对确认未变。恢复副本位置见 [安卓说明](../../../apps/android/README.md#mobile-mcp-接入与使用)。未修改角色、模型、业务代码或系统设置，未提交/推送 Git。
- 绝对路径 npx.cmd + `--stdio`，不开放控制端口；设置官方 `MOBILEMCP_DISABLE_TELEMETRY=1`。只缓存固定版本依赖，没有调用自动配置所有客户端的安装方式。
- 当前 SDK 为 `C:/Users/asus/AppData/Local/Android/Sdk`，ADB `1.0.41 / 37.0.1-15733141`。只读 ADB 诊断确认 `emulator-5554` → AVD `Pixel_7`，Android `17 / API 37`、x86_64；此时仅列出该模拟器，没有操作真实手机。
- 已安装应用 `app.liferecord`，`versionName=1.0.0`、`versionCode=1`、debuggable、targetSdk35。版本元数据由只读 ADB 获取，不冒充 MCP 结果；未核对已安装 APK 与当前源码哈希对应关系。

### 实际执行与结论

| 项目 | 预期 | 实际与依据 | 结论 |
|---|---|---|---|
| OMP 加载工具 | 握手及工具发现成功 | 使用本机已安装 OMP 的 `discoverAndLoadMCPTools` 与工具桥，在 Eval 中读取项目配置、握手、发现 34 个 Mobile MCP 工具；serverInfo 为 mobile-mcp 1.0.15，无连接错误 | 通过 |
| MCP 枚举模拟器 | 列出运行中的目标 | `mobile_list_available_devices` 返回 `Pixel_7`、type=emulator、state=online、Android17，与 ADB 的 AVD 名一致 | 通过 |
| MCP 截图与元素 | 返回实际屏幕和界面树 | `mobile_save_screenshot` 保存截图并实际打开观察；`mobile_list_elements_on_screen` 返回结构化元素，应用页共 42 个元素 | 通过 |
| MCP 打开并识别应用 | 前台包名和页面内容对应项目 | `mobile_launch_app(app.liferecord)` 成功；`mobile_get_foreground_app` 返回生活记录及 MainActivity；截图与元素共同识别“记录”列表、“新增记录”“最近删除”及三入口导航 | 通过 |
| 记录功能与完整验收 | 不在本轮范围 | 未点击业务按钮、未输入文字，未创建/修改/删除记录，未安装/卸载/清除/重置 | 未验证 |

本次工具调用经过 OMP 的 MCP 连接及工具桥，不是普通 ADB 操作替代。验证使用单独的 OMP 运行时连接；当前交互会话原有工具表仍需 `/mcp reload` 或在项目根目录新开会话后更新，不宣称父会话已自动热加载。

首次保存截图因目标目录不存在返回 ENOENT；创建本机证据目录后重试成功。初次 npx 的 Windows 包装引号调用也曾失败，调整 Windows 命令调用后缓存成功；最终项目配置中的 npx.cmd 绝对路径经 OMP 加载及调用验证可用。

本机证据位于忽略的 `apps/android/app/build/mobile-mcp/`：`connection-before.png`、`connection-app.png`、`connection-elements.json`、`connection-result.json`。界面已有记录，原始截图/元素不复制到版本化文档；build 清理可能移除证据。未执行业务写入操作，无本轮新增测试记录、无待清理业务数据。最终保留应用记录列表页。

结论仅覆盖工具连接及页面识别，不证明草稿、时区、删除、失败恢复或后续代码修改通过；模拟器也不替代实体手机和多系统版本验收。后续执行统一遵守 [测试工作方式](../acceptance.md#mobile-mcp-安卓端到端测试工作方式)。

