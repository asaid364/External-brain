package app.liferecord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.liferecord.core.AiConfiguration

data class DesktopAiSettingsUiState(
    val configuration: AiConfiguration = AiConfiguration(),
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testMessage: String = "",
    val isTestError: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Windows 桌面端独立 AI 服务配置面板
 */
@Composable
fun AiSettingsScreen(
    state: DesktopAiSettingsUiState,
    onSaveConfig: (AiConfiguration) -> Unit,
    onTestConfig: (AiConfiguration) -> Unit,
    onClose: () -> Unit
) {
    var endpoint by remember(state.configuration.endpoint) { mutableStateOf(state.configuration.endpoint) }
    var model by remember(state.configuration.model) { mutableStateOf(state.configuration.model) }
    var newApiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var consent by remember(state.configuration.consent) { mutableStateOf(state.configuration.consent) }

    val isHttps = endpoint.isBlank() || endpoint.trim().startsWith("https://", ignoreCase = true)
    val canSave = isHttps && !state.isSaving && !state.isTesting

    fun buildCurrentConfig() = AiConfiguration(
        endpoint = endpoint.trim(),
        apiKey = newApiKey.trim(), // 留空则后端持久层保留既有 DPAPI 密文
        model = model.trim(),
        consent = consent,
        automatic = false // Windows 端固定不启用定时自动生成
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI 服务与日报配置 (Windows 独立配置)", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onClose) {
                Text("返回")
            }
        }

        DesktopInfoPanel(
            title = "设备独立密钥与安全存储说明",
            body = "Windows 端拥有专属的 AI 接口与 API Key，使用 Windows DPAPI (CryptProtectData) 安全存储于当前用户目录。密钥绝不通过局域网同步至 Android 手机或对端。"
        )

        state.errorMessage?.let { err ->
            DesktopInfoPanel("配置错误", err, isError = true)
        }

        if (state.testMessage.isNotBlank()) {
            DesktopInfoPanel(
                title = if (state.isTestError) "连接测试失败" else "连接测试结果",
                body = state.testMessage,
                isError = state.isTestError,
                isWarning = !state.isTestError
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Endpoint
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("API 接口基地址 (Endpoint)") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    supportingText = {
                        Text(if (!isHttps) "端点必须为 https:// 加密协议" else "兼容 OpenAI Chat Completions 规范的 HTTPS 地址")
                    },
                    isError = !isHttps,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Model
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称 (Model)") },
                    placeholder = { Text("如 gpt-4o-mini, deepseek-chat") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // API Key
                OutlinedTextField(
                    value = newApiKey,
                    onValueChange = { newApiKey = it },
                    label = { Text("API Key (密钥)") },
                    placeholder = { Text("留空保存将保留现有已加密存储的密钥") },
                    supportingText = {
                        Text("明文不会在界面回显；修改时直接输入新 Key 即可")
                    },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Consent
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "我明确知晓并同意向上述第三方 AI 服务发送选定日期的生活记录正文，并已知悉可能产生的 API 调用费用。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                DesktopInfoPanel(
                    title = "自动生成规则说明",
                    body = "Windows 桌面端仅提供手动生成与手动更新日报。每日次日 00:10-01:00 的定时自动生成由 Android 端独占执行，两端生成后通过局域网双向同步结果。"
                )

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    }
                    OutlinedButton(
                        onClick = { onTestConfig(buildCurrentConfig()) },
                        enabled = canSave && endpoint.isNotBlank()
                    ) {
                        Text("测试连接")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onSaveConfig(buildCurrentConfig()) },
                        enabled = canSave
                    ) {
                        Text(if (state.isSaving) "保存中..." else "保存配置")
                    }
                }
            }
        }
    }
}
