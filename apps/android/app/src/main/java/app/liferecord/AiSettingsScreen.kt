package app.liferecord

import app.liferecord.core.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * AI 配置界面 (AiSettingsScreen)：
 * - 纯状态驱动配合回调，不直接访问数据库或网络。
 * - 端点 base URL：必须 HTTPS 协议。
 * - 模型名称配置。
 * - API Key 输入：密码隐藏/可见切换，采用 remember 而非 rememberSaveable（保护敏感凭据不进入 saved Bundle）；留空保存保留现有密钥，不直接明文回显真实 Key。
 * - 外部处理/费用明确同意复选框 (consent)。
 * - 次日 00:10–01:00 自动生成 opt-in 开关 (automatic)，如同意为 false 时禁用自动选项。
 * - 保存允许 consent=false，以支持撤回同意并停用自动生成。
 * - 异步加载配置后通过 LaunchedEffect / key 更新表单（但不覆盖用户主动输入的 key）。
 * - 仅使用 material core 图标（ArrowBack 等），不引入 material-icons-extended。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    configuration: AiConfiguration,
    testing: Boolean,
    message: String,
    onSave: (AiConfiguration) -> Unit,
    onTest: (AiConfiguration) -> Unit,
    onBack: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current

    var endpoint by rememberSaveable { mutableStateOf(configuration.endpoint) }
    var model by rememberSaveable { mutableStateOf(configuration.model) }
    // New key input MUST remember not rememberSaveable (sensitive secret must not enter saved Bundle)
    var newKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(configuration.consent) }
    var automatic by rememberSaveable { mutableStateOf(configuration.automatic) }

    // Sync state when external configuration updates (e.g. after initial async load from store)
    LaunchedEffect(configuration.endpoint, configuration.model, configuration.consent, configuration.automatic) {
        endpoint = configuration.endpoint
        model = configuration.model
        consent = configuration.consent
        automatic = configuration.automatic
    }

    // If consent is revoked/unchecked, automatic generation must also be turned off
    LaunchedEffect(consent) {
        if (!consent) {
            automatic = false
        }
    }

    val isHttps = endpoint.isBlank() || endpoint.trim().startsWith("https://", ignoreCase = true)
    // Allow saving when endpoint/model are provided (or cleared) and https is valid.
    // Saving with consent = false is explicitly permitted so the user can revoke consent & disable auto-generation.
    val canSave = isHttps

    fun currentConfig(): AiConfiguration {
        return AiConfiguration(
            endpoint = endpoint.trim(),
            apiKey = newKeyInput.trim(), // Blank preserves existing key in repository/settings
            model = model.trim(),
            consent = consent,
            automatic = automatic && consent
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 与日报配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status/Result message panel
            if (message.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("连接测试与配置结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (testing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Endpoint Base URL
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("API 基础地址 (Base URL)") },
                placeholder = { Text("https://api.openai.com/v1") },
                supportingText = {
                    if (!isHttps) {
                        Text("安全要求：必须使用 https:// 协议", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("OpenAI 兼容 Chat Completions 端点，必须使用 HTTPS")
                    }
                },
                isError = !isHttps,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // Model name
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名称 (Model)") },
                placeholder = { Text("gpt-4o-mini / deepseek-chat 等") },
                supportingText = { Text("指定与端点配套使用的模型标识") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // API Key (Masked, blank retains existing, uses TextButton for toggle to avoid extended icon dependencies)
            OutlinedTextField(
                value = newKeyInput,
                onValueChange = { newKeyInput = it },
                label = { Text("API 密钥 (API Key)") },
                placeholder = { Text("留空保持现有密钥不变") },
                supportingText = {
                    Text("安全机制：应用不会在界面回显已存密钥。留空保存即可继续沿用现有密钥；输入新密钥将替换存储。")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(
                        onClick = { keyVisible = !keyVisible },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(if (keyVisible) "隐藏" else "显示", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )

            // Consent Checkbox
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("明确同意外部数据处理与潜在费用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "日报生成会将选定日期的活动记录与结局答复发送到上方配置的服务端点。这可能产生第三方费用并受所选 AI 服务协议约束。取消勾选可撤回授权并停用自动生成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Automatic generation Switch (Window: 00:10–01:00)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("次日自动合成日报 (00:10–01:00)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "在本地时间次日凌晨 00:10–01:00 窗口自动尝试生成前一日日报。若未勾选同意外部处理，此功能将禁用。自动生成不会覆盖已有日报。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = automatic && consent,
                        onCheckedChange = { automatic = it },
                        enabled = consent
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons: Test Connection & Save Configuration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        keyboard?.hide()
                        onTest(currentConfig())
                    },
                    enabled = !testing && endpoint.isNotBlank() && isHttps,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(if (testing) "测试中…" else "测试连接")
                }

                Button(
                    onClick = {
                        keyboard?.hide()
                        onSave(currentConfig())
                    },
                    enabled = !testing && canSave,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("保存配置")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
