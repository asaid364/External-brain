# External Brain · 生活记录与成长分析

面向 Android 与 Windows 的本地优先生活记录工具：保存原始事实，用 AI 整理事实日报，再围绕用户设定的目标与期限提供有来源依据的周期分析。

当前原生版本 **1.3.0**，Android versionCode **4**，数据库 schema **4**、同步协议 **1**。项目仍处于开发与验收阶段，不承诺所有设备、网络和 AI 服务兼容。

## 功能

- **原始记录**：新增、补记、修改历史、发生时间与时区；草稿持久保存；最近删除、恢复与不可恢复的彻底删除。
- **事实日报**：使用用户配置的 OpenAI 兼容 HTTPS 服务整理事实，支持补充问答后更新。日报正文只读，不用记录时长判断成长。
- **目标与进度**：自定成果、起止日期、回顾时区、周期与完成标准。进度按用户确认的里程碑等权计算；AI 只能提出达成建议，不能自动确认。
- **周期分析**：分析方向、成果、重复问题和具体正反馈，引用已保存证据。缺少记录保持未知；来源修改或删除后保留历史衍生结论并标记来源变化，不另存来源原文快照。
- **双向局域网同步**：Android 与 Windows 都可离线编辑；同步记录、共享草稿、日报、目标、确认与成功回顾。并发冲突由用户处理，不用设备时间决定胜者。

Android 可分别授权自动日报和目标周期分析；Windows 仅手动生成。Android 自动目标回顾仅补最近一个遗漏的完整周期，较早周期由用户手动处理。后台任务受 Android 系统限制，不保证准点。

## 技术与目录

| 路径 | 内容 |
|---|---|
| `apps/android/` | Kotlin、Jetpack Compose / Material3、SQLite、WorkManager；Android 8.0 / API26+ |
| `apps/windows-sync/` | Kotlin/JVM、Compose Desktop、SQLite JDBC；Windows 客户端 |
| `packages/shared-core/` | 共享记录、日报、目标、AI 请求及因果同步核心 |
| `packages/contracts/` | 同步协议与数据契约 |
| `apps/ui-prototype/` | React + TypeScript + Vite 浏览器原型，非正式数据存储方案 |
| `docs/product/` | 产品需求与用户已确认决定 |
| `docs/project/` | 行为规则、任务、验收与边界 |
| `docs/engineering/` | 实际开发状态及分阶段验证记录 |

Android 目录中的 Gradle 工程同时包含 `:app`、`:core` 和 `:desktop` 三个模块。

## 从源码构建

### 环境

- JDK **17**，完整 JDK 需包含 Windows 打包使用的 `jpackage` / `jlink`。
- Android SDK Platform **35** 与对应构建工具；Android 应用最低 API26。
- Gradle **8.10.2**（Wrapper）、AGP **8.7.3**、Kotlin **2.0.21**。
- 首次构建需要访问 Google Maven、Maven Central 与 Gradle 分发服务。

在 `apps/android/local.properties` 设置本机 SDK 路径，例如 `sdk.dir=C:/Android/Sdk`，或配置 Android SDK 环境变量。该文件不提交；不要复制他人的机器路径。

### Android

从仓库根目录运行（PowerShell）：

```powershell
# 先将 JAVA_HOME 设置为本机 JDK17 路径
.\apps\android\gradlew.bat -p apps/android :app:assembleDebug
```

APK 输出：`apps/android/app/build/outputs/apk/debug/app-debug.apk`。

这是本地 debug 签名包，不是上架发行包。获得设备授权后可通过 `adb install -r <APK路径>` 保留数据覆盖升级；同包升级要求签名一致。不要通过卸载、清空数据或降级解决升级问题。

### Windows

```powershell
# 开发运行
.\apps\android\gradlew.bat -p apps/android :desktop:run

# 生成包含运行时的 Windows 应用目录
.\apps\android\gradlew.bat -p apps/android :desktop:createDistributable
```

启动 `apps/windows-sync/build/compose/binaries/main/app/LifeRecord/LifeRecord.exe`。应复制完整 `LifeRecord` 目录，而不只是 EXE。正常运行数据位于 `%LOCALAPPDATA%\LifeRecord\client\`。

## 使用流程

1. 在“记录”保存内容，必要时补充发生日期和时区。
2. 如需 AI，在本机填写自己的 HTTPS 服务地址、模型及 API Key，并明确同意外发内容和可能产生的费用。两端分别配置，密钥不同步。
3. 在“日报”选择日期生成事实整理；补充答案后统一更新。
4. 在“目标”设置成果、期限、回顾周期和标准，手动确认真正完成的里程碑，再生成周期分析。目标 AI 外发许可与日报许可独立。
5. 同步时在 Windows 显式选择可信私有网络接口，按两端显示核对身份和证书信息，完成人工配对及双方收发同意。schema4 扩大同步范围后，旧配对也需重新同意。

请求超时或取消不代表外部服务未计费。目标请求发出后结果未知时不自动重复付费；本地已暂存的成功结果可仅重试保存。

## 隐私与数据安全

- 本地保存不等于绝不外发：用户明确启用 AI 后，选定范围的内容会发送至其配置的服务，费用与数据处理政策取决于服务提供方。
- Android 使用 Keystore 保护本机配置；Windows 使用当前用户 DPAPI，失败不回退为明文保存。
- 局域网同步使用 HTTPS、证书 pin 与主机名校验；不会自动修改系统证书信任、防火墙或安装开机服务。
- **同步镜像不是备份**。删除会传播，离线设备需等待后续连接与持久确认。彻底删除无法保证撤回外部 AI 已收到的内容；历史日报和分析可能仍含衍生内容。
- 不提交真实记录、API Key、配对凭据或数据库。个人归档、机器配置、构建产物与本地工具状态已由 `.gitignore` 排除；忽略规则本身不是访问控制。

## 测试与当前验证范围

```powershell
.\apps\android\gradlew.bat -p apps/android :core:test :desktop:test :app:testDebugUnitTest :app:lintDebug
```

上述综合回归与 Lint 已通过。三项目标主机 UI 测试全部通过，覆盖保存与重开、AI 建议不能代替用户确认、标准变更、未保存输入保护及来源修改/删除后的历史警告。无外部连接参数的 HTTPS 集成测试不会通过这条命令执行真实网络验收。

还已实际验证：

- API35 Android 隔离包的同三项目标界面流程。
- Windows 打包应用中新建目标、确认至 50%、关闭重开及 SQLite 状态核对。
- Android 模拟器至 Windows 真实 HTTPS、证书校验、人工配对与双方同意、双向传输、持久 ACK、进度 50% → 100% → 50% 及两端数据库重开。
- 用户授权的模拟器原应用保留数据覆盖升级后启动，并显示“目标”入口；未逐条核对既有业务内容。

**尚未验证**：两台实体设备的物理 LAN/mDNS、变更网络和防火墙场景、真实外部 AI 服务，以及 Doze / 强停等条件下的后台实际执行时间。API37 的旧 Espresso 反射兼容问题单独记录，设备 UI 验证使用 API35。云端、独立备份/导出、语音与聊天功能尚未实现。

详细证据与历史限制以 [工程状态](docs/engineering/status.md) 为准。测试使用合成数据与隔离数据库，不借用用户现有 API Key。

## 项目文档

- [Android 使用与验证](apps/android/README.md)
- [Windows 使用与验证](apps/windows-sync/README.md)
- [产品需求](docs/product/requirements.md)
- [同步协议](packages/contracts/protocol-v1.md)
- [项目验收标准](docs/project/acceptance.md)
- [开发协作规则](AGENTS.md)

修改前先阅读协作规则与对应行为文档。UI 与交互调整遵循项目的 Designer 协作约定；不把设计建议、构建成功或模拟环境结果当成尚未验证的产品保证。
