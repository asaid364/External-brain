# 正式安卓记录应用

原生 Kotlin + Jetpack Compose / Material3，使用 Android SQLiteOpenHelper 事务、java.time ZoneRules、ViewModel及协程。Android 8.0 / API26及以上。浏览器原型仍在 `../ui-prototype/`，不是本应用。

## 目标与周期回顾（1.3.0 / code4 / schema4）

第四个功能入口“目标”（导航顺序记录/日报/目标/数据与同步）支持用户自定成果、起止日期、IANA回顾时区、正整数周期和至少一项完成标准。进度为用户确认的等权K/N；AI只建议达成，不自动勾选。修改标准分配新ID，旧确认保留原标准但不误计当前进度。目标可归档/重激活。

周期分析独立于客观日报，只取该周期已保存正式记录、适用已存答案和有效日报；日期按记录原当地日期纳入。缺记录未知，不按时长推断能力。方向、成果、重复问题和具体正反馈带来源；来源后来改变/删除时保留历史衍生结论并警告，本应用不另存原文快照。建议不是自动承诺/待办。

本机目标外发与自动许可默认关闭，绑定目标范围、端点和模型，与日报同意及配对同意分开。Android可单独授权周期结束次日01:10后台尝试，WorkManager/Doze不保证准点；长期错过只补最近一期，旧期手动。Windows只手动。请求已发但未知不后台重复付费，持久暂存成功后保存重试不再次调用AI。目标/声明/成功回顾双向同步，密钥、许可和任务不传；schema3对端需升级并重新确认新增同步范围，不需清库或重配对。

本轮实际使用`.syncsmoke`与UUID库：宿主真实HTTPS/DPAPI、Windows真实app-image表单→50%→关闭重开/SQLite核对已完成；API35 Android真实已pin HTTPS1/1通过，人工批准/双方同意后进度50→100→50及ACK/两端重开通过。它不是物理手机LAN/mDNS、真实外部AI或后台延迟证明。API37旧Espresso反射不兼容，主机Robolectric可编辑弹窗idling失败仍独立记录，不等于生产功能已确认有相同故障。最终设备UI状态见工程状态，不能把缺配置的首屏视为完整AI验收。

## 已实现

- 新增、补记、发生时间及独立首次录入时间；按原当地日期分组、同组实际时间倒序、三行摘要、详情与返回位置保留。
- 一份新增草稿、每记录一份编辑草稿；自动持久化、返回保留、继续、确认放弃，失败明确提示。
- 修改正文/时间/时区、首次录入不变；前后修改历史，失败事务回滚，重试不产生重复历史。
- 最近删除、恢复、独立确认彻底删除；历史与草稿随记录保留/恢复/清除，无自动或批量清理。
- 原当地时间、IANA时区与时差保留；禁止未来正式保存；夏令时不存在则拒绝，重复则选择时差。
- 日期/时间系统选择器、中文时区名称搜索；正文保留换行、可选取；输入法弹起隐藏底导航，保存位于顶栏。
- 初始数据为空。原生日报已接入HTTPS AI客户端、配置、成果问答与后台调度；双向同步实现证据见下。

## 日报使用（1.1.0 / code2）

进入“日报”→齿轮“AI设置”，填写OpenAI兼容HTTPS地址、模型和自己的API Key，勾选外部处理与潜在费用同意后保存。已有密钥不回显，留空仅同服务沿用，换服务主机必须重新填写。先使用合成记录验证，不要把Key发到聊天或日志。测试连接仅最小合成请求，也可能计费。

选择日期后点生成；问题在正文下方，答案先保存，再统一更新。正文不能直接编辑，错误请修改来源。普通更新无变化不请求；“更多→重新生成”确认后允许重复付费。来源删除后旧正文可能仍含内容，需手动更新；所有来源已空时在更多中确认移除旧正文，不调用AI。没有单独删除日报入口。

自动默认关闭；启用后按手机当前时区次日00:10—01:00前尝试前一天，不覆盖已有日报，安卓后台不保证准时。错过打开提示手动。120秒单次超时、最多一次自动重试，默认等10秒，遵守Retry-After且自动等待不超过60秒；超时不代表未计费。取消不保证外部停止。

正式配置使用Android Keystore AES-GCM和noBackup目录，日志不输出Key/正文。加法数据库迁移保留记录；网络请求只发选中日期正式有效数据及适用答案，不读取个人归档。新增WorkManager2.10.0与协程1.9.0用于成熟后台任务/取消能力，无精确闹钟、电池豁免或通知权限。

规则：[行为与问答](../../docs/project/reports/behavior.md)、[生成调度](../../docs/project/reports/generation.md)。本轮构建与36项主机回归通过；Pixel_7模拟器13项SQLite仪器测试通过，Mobile MCP验证真实页面及未配置错误，Designer复核实际截图。API37的Compose仪器测试因Espresso InputManager反射兼容失败，未冒充通过。真实服务、真实凌晨后台执行、成功生成全界面及实机仍待验收，详见 [日报验收证据](../../docs/project/reports/acceptance.md)。

## Android—Windows 双向局域网同步（1.2.0 / code3 实现）

Android 使用 `app.liferecord.syncsmoke` 隔离应用包，不替换正式安装，也不读正式业务库/AI配置。通过 Mobile MCP 在 Pixel_7/API37 实际查看未配对首屏，确认配对前不展示远端数据计数；正式版本 APK 未安装。

- 核心协议1、schema3；Android作为客户端主动发现/连接，Windows须用户明确选择私有Wi-Fi/以太网接口。NSD/mDNS地址只是路由提示，Android手动路由需要用户输入Windows显示的稳定 `life-<serverId>.local` 标识。传递配对码/token/内容之前先校验证书完整SHA-256 DER pin及默认hostname；无trust-all、证书绕过、代理或重定向转发。
- 配对窗口5分钟、26位Base32、最多5次错误；Windows人工确认设备和请求。双方各自外发/接收合并同意未齐，数据统计或正文/彻删围栏不交换。Android令牌和pin存同步专用Keystore/AES-GCM noBackup文件，AI密钥/同意绝不同步；Windows凭据和独立AI配置用当前用户DPAPI保护，失败拒绝明文降级。
- 记录/草稿/日报/独立答案以因果heads和观察基线合并；未见候选不被静默退休，冲突须人工选择/整合。共享草稿有稳定代与target，同new槽并发不自动创建多条正式记录；已结束残留可明确转为新草稿或丢弃。保存/删除/答案使用幂等操作标识。彻删有持久吸收fence，跨端ACK只在接收数据库提交后记账；待对端确认期间不承诺离线即刻删除。镜像同步不是独立备份。
- 前台轻量检查与Android唯一WorkManager任务/15分钟周期兜底共享锁，离线从30秒指数退避；网络、Doze、进程被终止或电脑关闭可延迟。单一Windows配对设备由一轮程序设计；无自动启动服务/系统开机注册、防火墙规则或系统证书信任改动。
- 两端都能本地编辑；日报正文只读，独立答案/来源证据同步。Windows AI密钥/费用许可本机独立，Windows手动生成；Android原自动日报保持既有开关。在线协调存储run lease=180秒/30秒心跳，已知RUNNING/UNKNOWN不自动重发；未知隔离下保留原Android自动行为。外部调用/收费仅在用户各端各自设置凭据与同意后可能发生，本轮未执行真实AI请求。

本轮 Windows 软件与 Android 隔离包、外部验收须分开理解：

- 完整构建/回归通过：`:core:test :desktop:test :app:testDebugUnitTest :app:assembleDebug :app:assembleSyncSmoke :app:assembleDebugAndroidTest :app:lintDebug`，最终`BUILD SUCCESSFUL in 1m21s`；之前 `:desktop:createDistributable` Windows app-image成功。JDBC两副本并发编辑/解决/重启、草稿恢复、问答证据和桌面Compose测试通过。
- 隔离分片/JDBC持久ACK烟测通过。真实Windows DPAPI、显式所选私有Wi-Fi接口HTTPS、证书pin+默认hostname、人工配对/同意、双向数据流和彻删/重启/撤销通过 `SECURE_SMOKE: PASS`。范围是宿主进程对本机LAN地址，不包含手机。
- `app.liferecord.syncsmoke` 单独包已在Pixel_7 API37安装，Mobile MCP显式选择并观察真实未配对同步页面；没有安装或升级正式 `app.liferecord` 包，也未访问其正式业务库/密钥。
- **用户反馈** Windows桌面软件已启动并手动检查同步结果正常。本Agent不能确认其操作步骤/参与设备或该次状态，与上述构建/宿主 smoke是不同证据，按用户实测报告记录，不扩展声称受控手机MCP双端测试通过。
- 尚未由Agent实际完成：Android客户端到真实WindowsHTTPS的受控配对/传输；两台实体设备同一物理LAN/mDNS、变IP、防火墙和无Internet LAN；WorkManager/Doze实际延迟；真实AI外发；正式包签名/升级以及原有正式数据迁移。它们不受用户报告替代。本轮未操作正式APK/数据、不触碰系统网络设置、不发真实记录至AI服务。

镜像同步不是独立备份；用户删除/彻删按计划双向传播。云端、备份/导出与阶段分析仍未实现。

README中的安装/历史验证仍只对应先前1.1.0记录日报；本轮1.2.0源码/build APK测试不代表正式安装升级。

## 构建与验证

JDK17、Android SDK35、Gradle8.10.2（Wrapper）、AGP8.7.3、Kotlin2.0.21。依赖版本在Gradle文件固定；首次构建需下载Google/Maven依赖。`local.properties`写本机SDK位置，已忽略，不提交。

```powershell
cd D:\external-brain\apps\android
# 本机环境示例；不要求修改全局环境
$env:JAVA_HOME='E:\JavaJdk17'
# 本机 local.properties 当前指向 C:/Users/asus/AppData/Local/Android/Sdk，以 SDK Manager 实际路径为准
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。这是debug签名的本地安装包，不是已签名上架发布包。复制APK到手机，按系统提示允许所用安装来源；不要为此关闭系统安全防护。卸载/清除数据会丢失内容，升级请使用相同签名并先评估数据风险。

若已有授权调试设备：

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:connectedDebugAndroidTest
```

当前未连接实体设备，未执行实体安装。实体中文输入法、系统返回、进程结束/重启及设备特有行为待实机验收，不能因构建或本地Android测试通过而视为已通过。

## 测试与证据

- `RecordTimeTest`：未来时间、当地日期保留、夏令时缺口/重叠、无效日期。
- `RecordStoreTest`：真实SQLite语句在Robolectric Android运行环境中执行，覆盖重开读取、重试去重、历史、草稿、软删除/恢复/级联清除、事务故障回滚与损坏文件保留。
- `RecordFlowTest`：Compose实际界面操作，保存失败重试、编辑草稿返回、编辑历史、删除恢复。使用独立合成测试库，不读应用业务库；同源可在设备执行。
- 本地Robolectric不等于Android模拟器/实体手机；设备测试目录同时接入host sourceSet，避免两套行为用例漂移。
- 本轮使用一次性原生绘制烟测生成截图，保留在被忽略的 `app/build/smoke/`，复核列表/编辑/详情并与Designer协调修正主题；烟测脚本完成后移除。

## Mobile MCP 接入与使用

后续安卓界面与操作测试优先使用 [Mobile Next Mobile MCP](https://github.com/mobile-next/mobile-mcp)。完整测试规则统一见 [项目验收标准](../../docs/project/acceptance.md#mobile-mcp-安卓端到端测试工作方式)，连接结果见 [验证证据](../../docs/project/records/verification.md#mobile-mcp-连接验证2026-11-02)。本次只验证连接，没有重新执行功能测试。

### 本机接入

- OMP `18.14.0`；按当前 OMP 原生文档 `omp://mcp-config.md`，项目入口为仓库根目录 `.omp/mcp.json`，不是其他客户端的配置文件。项目 MCP 加载已启用。
- Mobile MCP 固定为 `@mobilenext/mobile-mcp@1.0.15`；Node.js `22.13.1` 满足官方 `>=20`，npm `10.9.2`。仅通过 npx 缓存必要依赖，没有执行 `install` 子命令或自动配置其他客户端。
- stdio 启动命令为绝对路径 `C:/Program Files/nodejs/node_global/npx.cmd`，参数 `-y @mobilenext/mobile-mcp@1.0.15 --stdio`，已通过 OMP 实际握手和工具调用；未开放 HTTP 控制端口、未接入云端设备。
- 配置设置 `MOBILEMCP_DISABLE_TELEMETRY=1`，按官方说明关闭匿名使用遥测；不启用调试正文日志。`ANDROID_HOME`、`ANDROID_SDK_ROOT` 指向 `C:/Users/asus/AppData/Local/Android/Sdk`，服务进程 PATH 前置其中 `platform-tools`，不修改系统全局环境。
- 本机路径仅适用于此工作站，换机器按实际 Node/SDK 路径调整此服务，不覆盖其他服务或角色模型。顶层项目目录启动 OMP：`D:/external-brain`。
- 修改前保存的恢复资料在 `C:/Users/asus/.omp/agent/backups/mobile-mcp-external-brain-20261102/`：原用户 MCP 配置副本及恢复说明。原项目无 MCP 文件，本次新建只含 `mobile-mcp`；用户级 `drawio` 文件未修改。回退仅移除新增服务；不要用旧副本覆盖后来的其他配置变更。

### 在 OMP 中发起测试

1. 在 Android Studio 启动目标模拟器，保留当前应用和数据。本轮已识别 `Pixel_7`（ADB `emulator-5554`），后续每轮重新枚举，不能永久假定该映射不变。
2. 已运行的 OMP 会话执行 `/mcp reload` 更新工具表，再执行 `/mcp list`；可用 `/mcp test mobile-mcp` 检查连接。也可从仓库根目录新开 OMP 会话。这些命令仅处理客户端连接，不代表功能测试通过。重载可能重连其他已配置服务，但不修改其配置。
3. 主 Agent 或测试 Agent 调用 `mobile_list_available_devices`，明确选定模拟器 ID；本轮 MCP ID 为 `Pixel_7`，不是 ADB 序列号。随后用截图、元素和前台应用核对目标，再执行当次授权的步骤。
4. 按授权范围报告业务预期与实际结果；没有实际执行的功能写未验证。现有安装包本轮只读取版本，未确认与当前源码一一对应，后续验证代码修改时先确认版本；不擅自重装或清空来消除差异。

可直接发送给 OMP：

> 请按项目 Mobile MCP 规则，仅在明确选定的安卓模拟器中验证【本次功能与步骤】。先报告已安装版本、设备、范围与预期；只使用带【本轮唯一标记】的合成数据，保留已有记录。未经授权不安装/卸载、清除数据、重置、降级或修改系统设置。逐项报告通过、失败、阻塞或无法确认及证据，列出遗留测试数据，未执行项明确标为未验证。

MCP 不可用时报告实际错误；只读 ADB 诊断只能作补充，不得冒充 MCP 测试。模拟器通过不等于实体手机或所有 Android 版本通过。


## 数据边界

SQLite保存在应用私有目录（记录和日报数据库未加密，AI配置单独Keystore加密）。正式保存、历史追加和草稿清除单事务；日报正文、问题及来源同样原子提交。数据库损坏不删除重建。仅单设备本机，不实现同步、备份或导出，系统备份/迁移已排除，不是取证级物理擦除承诺。

输入后异步写草稿，只保证已提交部分，极端断电前最后输入可能未落盘。时钟依赖设备。本应用API Key仅用于用户显式配置的AI，不同步/导出；测试只用合成数据，日志不输出正文或秘密。

## Designer

实际通过OMP当前 `@designer` 角色调用，交付主题、Shell、导航、卡片、时间标签、确认框与图标；集成后复核实际Compose截图，修正图标依赖、长时间换行和底导航选中色。不固定或覆盖角色模型配置。
