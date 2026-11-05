# Windows 双向局域网客户端

实现技术为 Compose Desktop/Kotlin/JVM 与共享核心 SQLite JDBC；1.3.0/schema4提供离线记录、共享草稿、事实日报问答与独立目标/周期分析。协议见 `packages/contracts/protocol-v1.md`，不自动安装服务或开机启动。

运行分发版 `build/compose/binaries/main/app/LifeRecord/LifeRecord.exe`；正常数据目录 `%LOCALAPPDATA%\LifeRecord\client\`。`--data-dir=<path> --smoke` 只允许新的空隔离目录，禁止指向真实用户数据目录。

Windows需用户显式选定可信私有接口后才启动HTTPS/mDNS，绑定具体地址；证书pin/hostname按Windows面板与Android核对。DPAPI保护本机凭据与独立AI配置，DPAPI失败绝无明文降级。配对前两端AI配置/密钥永不交换，外发/接收同意各自明确确认。用户报告桌面已启动并检查同步正常；Agent无法确认其操作步骤，详见Android验收说明，不据此声称所有物理网络门禁通过。

“目标”入口管理期望成果、起止日期、时区/周期与完成标准；用户确认标准才改变等权进度，AI提议不会自动完成。Windows只手动分析，先独立同意目标外发/费用，密钥不从手机复制。历史分析保留引用而不另存来源原文，修改/删除后警告。同步升级扩大类别，已配对也须两端重新同意，无需清库或更换身份。

实际验收：Windows app-image真实Robot输入两个标准、确认50%、关闭重开及SQLite核对通过；独立当前用户DPAPI与所选私有接口HTTPS的目标推拉/ACK/重启/UNKNOWN smoke通过；API35 Android真客户端pin/default hostname+Network.socketFactory→Windows50→100→50与两端ACK/重开通过。模拟器网络不证明物理LAN/mDNS/Doze，受控AI不证明真实外部服务。正式app.liferecord未安装升级或传输旧内容；实时门禁见工程状态。镜像不是备份，云端/备份导出未增加。

隔离验证入口：`:desktop:goalFeatureSmoke -PgoalSmokeInterface=<本次枚举私有IP>`，`:desktop:goalDesktopSmoke`。UIA桥缺失时自动定位安全失败；人工逐图Robot模式用`-PgoalDesktopInteractive=true`，通过`:desktop:goalSmokeLaunchFiles`生成忽略的Java argfile直接启动以保留stdin，命令使用终端回车，不把换行echo当动作。所有窗口操作限定自有PID/HWND，`--smoke`必须新UUID目录。`:desktop:goalAndroidHost`提供人工批准/同意/确认的真实HTTPS验收host，运行参数及配对码只写忽略临时目录，不提交或打印凭据。

