package dev.musealert.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeParseException

class MainActivity : ComponentActivity() {
    private val settings by lazy { Settings(this) }
    private var refresh by mutableStateOf(0)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    // refresh を読むことで、権限画面から戻ったときに表示を更新する。
                    refresh.let { SettingsScreen() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    @Composable
    private fun SettingsScreen() {
        val initial = remember { settings.load() }
        val labels = remember { AppLabels(this@MainActivity) }
        val targets = remember {
            mutableStateListOf(*initial.targets.map { TargetRow(it.packageName, labels.label(it.packageName), it.thresholdMinutes.toString(), it.repeatMinutes.toString()) }.toTypedArray())
        }
        var picking by remember { mutableStateOf(false) }
        var grace by remember { mutableStateOf(initial.graceSeconds.toString()) }
        var windowStart by remember { mutableStateOf(initial.windowStart.toString()) }
        var windowEnd by remember { mutableStateOf(initial.windowEnd.toString()) }
        var gmail by remember { mutableStateOf(initial.gmailAddress) }
        var password by remember { mutableStateOf("") }
        var mailCheck by remember { mutableStateOf(initial.mailCheckMinutes.toString()) }
        var message by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        fun parse(): Config? = try {
            Config(
                targets = targets.map {
                    TargetApp(it.packageName, it.threshold.toInt().coerceAtLeast(1), it.repeat.toInt().coerceAtLeast(0))
                },
                graceSeconds = grace.toInt().coerceAtLeast(0),
                windowStart = LocalTime.parse(windowStart),
                windowEnd = LocalTime.parse(windowEnd),
                gmailAddress = gmail.trim(),
                mailCheckMinutes = mailCheck.toInt().coerceAtLeast(1),
            )
        } catch (_: NumberFormatException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }

        fun save(): Boolean {
            val config = parse() ?: run { message = "入力を確認してください（時刻は 23:00 の形式）"; return false }
            settings.save(config)
            if (password.isNotEmpty()) settings.appPassword = password
            password = ""
            if (settings.monitoring) {
                // 設定を読み直させるため、サービスを起動し直す。
                UsageMonitorService.stop(this)
                UsageMonitorService.start(this)
            }
            message = "保存しました"
            return true
        }

        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("使用時間リマインダー", style = MaterialTheme.typography.headlineSmall)

            Section("権限")
            val usageGranted = UsageEventSource(this@MainActivity).hasPermission()
            Text("使用状況へのアクセス: " + if (usageGranted) "許可済み" else "未許可")
            if (!usageGranted) {
                Button(onClick = { startActivity(Intent(SystemSettings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                    Text("使用状況へのアクセスを開く")
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                Text("通知: " + if (granted) "許可済み" else "未許可")
                if (!granted) {
                    Button(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("通知を許可する")
                    }
                }
            }
            OutlinedButton(onClick = { startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("バッテリー最適化の設定を開く")
            }

            Section("監視")
            Text("通知するアプリ", style = MaterialTheme.typography.titleSmall)
            targets.forEachIndexed { index, row ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.label)
                            Text(row.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { targets.removeAt(index) }) { Text("削除") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("通知開始（分）", row.threshold, Modifier.weight(1f)) { targets[index] = row.copy(threshold = it) }
                        NumberField("再通知（分, 0で無し）", row.repeat, Modifier.weight(1f)) { targets[index] = row.copy(repeat = it) }
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }) { Text("アプリを追加") }
            if (picking) {
                AppPicker(
                    apps = remember { labels.launchable() },
                    exclude = targets.map { it.packageName }.toSet(),
                    onPick = { pkg, label ->
                        targets += TargetRow(pkg, label, "5", "5")
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
            Text("レポートには、通知するアプリ以外も含めて、その日に使ったすべてのアプリが入ります。", style = MaterialTheme.typography.bodySmall)
            NumberField("離脱の猶予（秒）", grace) { grace = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("通知する時間帯 開始", windowStart, Modifier.weight(1f)) { windowStart = it }
                Field("終了", windowEnd, Modifier.weight(1f)) { windowEnd = it }
            }
            Text("開始と終了が同じなら終日。通知は時間帯内だけ、記録は常に行います。", style = MaterialTheme.typography.bodySmall)

            Section("専用 Gmail（Muse 連携）")
            Field("Gmail アドレス", gmail, keyboard = KeyboardType.Email) { gmail = it }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (settings.appPassword.isEmpty()) "アプリパスワード" else "アプリパスワード（保存済み・変更時のみ入力）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("画面 ON 時のリクエスト確認間隔（分）", mailCheck) { mailCheck = it }
            Text("画面 OFF の間は、電池を節約するため 15 分ごと（OS がまとめて実行）に確認します。", style = MaterialTheme.typography.bodySmall)
            Text("最終結果: " + settings.lastMailStatus.ifEmpty { "なし" }, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            if (settings.monitoring) {
                OutlinedButton(onClick = { UsageMonitorService.stop(this@MainActivity); refresh++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("監視を停止")
                }
            } else {
                Button(
                    onClick = { if (save()) { UsageMonitorService.start(this@MainActivity); refresh++ } },
                    enabled = usageGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存して監視を開始") }
            }
            OutlinedButton(
                onClick = {
                    if (!save()) return@OutlinedButton
                    message = "ログインを確認中…"
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching { settings.mailClient().checkLogin() }
                                .fold({ "Gmail にログインできました" }, { "ログイン失敗: ${it.message}" })
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Gmail のログインを確認") }
            OutlinedButton(
                onClick = {
                    if (!save()) return@OutlinedButton
                    MailSync.runNow(this@MainActivity, sendToday = true)
                    message = "今日の履歴を送信しています（結果は「最終結果」に出ます）"
                },
                enabled = settings.mailConfigured(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("今日の履歴を専用 Gmail に送信") }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
        }
    }

    private data class TargetRow(val packageName: String, val label: String, val threshold: String, val repeat: String)

    @Composable
    private fun AppPicker(
        apps: List<Pair<String, String>>,
        exclude: Set<String>,
        onPick: (String, String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        val shown = apps.filter { (pkg, label) ->
            pkg !in exclude && (query.isBlank() || label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true))
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
            title = { Text("アプリを追加") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("検索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(shown, key = { it.first }) { (pkg, label) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(pkg, label) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(label)
                                Text(pkg, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
        )
    }

    @Composable
    private fun Section(title: String) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }

    @Composable
    private fun Field(
        label: String,
        value: String,
        modifier: Modifier = Modifier.fillMaxWidth(),
        keyboard: KeyboardType = KeyboardType.Text,
        onChange: (String) -> Unit,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = modifier,
        )
    }

    @Composable
    private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) =
        Field(label, value, modifier, KeyboardType.Number, onChange)
}
