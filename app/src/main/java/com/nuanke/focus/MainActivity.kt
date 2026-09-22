package com.nuanke.focus

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button as AndroidButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuanke.focus.data.AppRule
import com.nuanke.focus.data.AppStore
import com.nuanke.focus.data.DayStats
import com.nuanke.focus.data.FocusPhase
import com.nuanke.focus.data.FocusState
import com.nuanke.focus.focus.FocusTimerService
import com.nuanke.focus.monitor.safetyPackages
import com.nuanke.focus.diagnostics.ServiceDiagnostics
import com.nuanke.focus.diagnostics.GuardHealth
import com.nuanke.focus.ui.theme.NuankeTheme
import com.nuanke.focus.ui.theme.Sage
import com.nuanke.focus.ui.theme.SunGold
import com.nuanke.focus.ui.theme.Terracotta
import com.nuanke.focus.update.DownloadResult
import com.nuanke.focus.update.ManualUpdater
import com.nuanke.focus.update.ReleaseInfo
import com.nuanke.focus.update.UpdateCheckResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        ServiceDiagnostics.activityVisible(true)
    }

    override fun onStop() {
        ServiceDiagnostics.activityVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StartupCrashGuard.previousReport(this)?.let { report ->
            showStartupRecovery(report)
            return
        }

        StartupCrashGuard.beginStartup(this)
        runCatching {
            enableEdgeToEdge()
            val store = (application as? NuankeApplication)?.store ?: AppStore(applicationContext)
            setContent {
                NuankeTheme {
                    NuankeApp(store, this@MainActivity)
                }
            }
        }.onFailure { error ->
            StartupCrashGuard.recordStartupFailure(this, error)
            showStartupRecovery(StartupCrashGuard.previousReport(this).orEmpty())
        }
    }

    private fun showStartupRecovery(report: String) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
            setBackgroundColor(AndroidColor.rgb(255, 248, 240))
        }
        content.addView(TextView(this).apply {
            text = "暖刻遇到了一点启动问题"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(AndroidColor.rgb(69, 55, 46))
        })
        content.addView(TextView(this).apply {
            text = "诊断只保存在这台手机里，不会自动上传。你可以复制后发给开发者，或清除记录再试一次。"
            textSize = 16f
            setTextColor(AndroidColor.rgb(102, 87, 75))
            setPadding(0, dp(12), 0, dp(18))
        })
        content.addView(AndroidButton(this).apply {
            text = "清除记录并重试"
            isAllCaps = false
            setOnClickListener {
                StartupCrashGuard.clear(this@MainActivity)
                recreate()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(AndroidButton(this).apply {
            text = "复制诊断信息"
            isAllCaps = false
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("暖刻启动诊断", report))
                Toast.makeText(this@MainActivity, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        content.addView(TextView(this).apply {
            text = report.ifBlank { "没有读取到诊断详情，请清除记录后重试。" }
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(AndroidColor.rgb(69, 55, 46))
            setPadding(0, dp(20), 0, 0)
            gravity = Gravity.START
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }
}

private enum class MainTab { TODAY, RULES, STATS, SETTINGS }

private enum class FocusValueKind(
    val title: String,
    val suffix: String,
    val range: IntRange,
) {
    FOCUS("专注时长", "分钟", 1..180),
    BREAK("休息时长", "分钟", 1..60),
    ROUNDS("专注轮数", "轮", 1..12),
}

@Composable
private fun NuankeApp(store: AppStore, activity: ComponentActivity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rules by store.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val today by store.todayStats.collectAsStateWithLifecycle(initialValue = DayStats(""))
    val history by store.statsArchive.collectAsStateWithLifecycle(initialValue = com.nuanke.focus.data.StatsArchive())
    val focus by store.focusState.collectAsStateWithLifecycle(initialValue = FocusState())
    var selectedTab by remember { mutableStateOf(MainTab.TODAY) }
    var accessibilityEnabled by remember { mutableStateOf(isGuardEnabled(context)) }
    val guardHealth by ServiceDiagnostics.health.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        StartupCrashGuard.markStartupComplete(activity)
    }

    LaunchedEffect(activity, focus.running) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (focus.running && !FocusTimerService.active) {
                launchSafely(context, "恢复专注") {
                    ContextCompat.startForegroundService(context, Intent(context, FocusTimerService::class.java))
                }
            }
            while (true) {
                accessibilityEnabled = isGuardEnabled(context)
                delay(1_000)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                TabItem(MainTab.TODAY, selectedTab, "今天", Icons.Rounded.Home) { selectedTab = it }
                TabItem(MainTab.RULES, selectedTab, "限制", Icons.Rounded.Security) { selectedTab = it }
                TabItem(MainTab.STATS, selectedTab, "回顾", Icons.Rounded.BarChart) { selectedTab = it }
                TabItem(MainTab.SETTINGS, selectedTab, "设置", Icons.Rounded.Spa) { selectedTab = it }
            }
        },
    ) { padding ->
        when (selectedTab) {
            MainTab.TODAY -> TodayScreen(padding, rules, today, focus, accessibilityEnabled && guardHealth.ready)
            MainTab.RULES -> RulesScreen(padding, store, rules)
            MainTab.STATS -> StatsScreen(padding, rules, today, history.days)
            MainTab.SETTINGS -> SettingsScreen(padding, accessibilityEnabled, guardHealth)
        }
    }
}

@Composable
private fun RowScope.TabItem(
    tab: MainTab,
    selected: MainTab,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBarItem(
        selected = tab == selected,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun TodayScreen(
    padding: PaddingValues,
    rules: List<AppRule>,
    today: DayStats,
    focus: FocusState,
    accessibilityEnabled: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var task by remember { mutableStateOf("") }
    var focusMinutes by remember { mutableIntStateOf(25) }
    var breakMinutes by remember { mutableIntStateOf(5) }
    var rounds by remember { mutableIntStateOf(4) }
    var customValueKind by remember { mutableStateOf<FocusValueKind?>(null) }
    var pendingStart by remember { mutableStateOf<Intent?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let { launchSafely(context, "开始专注") { ContextCompat.startForegroundService(context, it) } }
        pendingStart = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("把这一刻，轻轻留给自己", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("暖刻陪你少刷一会儿，多完成一点点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!accessibilityEnabled) {
            item {
                WarmCard(container = MaterialTheme.colorScheme.primaryContainer) {
                    Text("应用守护尚未就绪", fontWeight = FontWeight.Bold)
                    Text("限制暂不生效。请到设置页查看真实连接状态；若系统开关自动关闭，可复制本机诊断。")
                    FilledTonalButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("去系统设置开启")
                    }
                }
            }
        }
        item {
            WarmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(50.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Timer, contentDescription = null, tint = Sage) }
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("今日专注", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${today.focusMillis / 60_000} 分钟",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        item {
            WarmCard {
                Text(if (focus.running) "这一轮正在生长" else "开始一个专注周期", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (focus.running) {
                    ActiveFocus(focus) {
                        launchSafely(context, "结束专注") {
                            context.startService(Intent(context, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_STOP))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = task,
                        onValueChange = { task = it.take(40) },
                        label = { Text("此刻要完成什么？") },
                        placeholder = { Text("例如：背一组单词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Column {
                            FocusSettingRow(
                                title = "专注",
                                description = "保持投入",
                                selected = focusMinutes,
                                values = listOf(15, 25, 40, 50),
                                onSelect = { focusMinutes = it },
                                onCustom = { customValueKind = FocusValueKind.FOCUS },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                            FocusSettingRow(
                                title = "休息",
                                description = "起身放松",
                                selected = breakMinutes,
                                values = listOf(5, 10, 15),
                                onSelect = { breakMinutes = it },
                                onCustom = { customValueKind = FocusValueKind.BREAK },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                            FocusSettingRow(
                                title = "轮数",
                                description = "循序完成",
                                selected = rounds,
                                values = listOf(1, 2, 4, 6),
                                suffix = "轮",
                                onSelect = { rounds = it },
                                onCustom = { customValueKind = FocusValueKind.ROUNDS },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "专注时会拦截当前已启用的 ${rules.count { it.enabled }} 个限制应用；休息时解除专注拦截，原有用时上限仍生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!accessibilityEnabled) {
                        Text(
                            "先开启应用守护，才可以开始专注并拦截分心应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Terracotta,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = accessibilityEnabled,
                        onClick = {
                            val intent = Intent(context, FocusTimerService::class.java).apply {
                                action = FocusTimerService.ACTION_START
                                putExtra(FocusTimerService.EXTRA_TASK, task)
                                putExtra(FocusTimerService.EXTRA_FOCUS_MINUTES, focusMinutes)
                                putExtra(FocusTimerService.EXTRA_BREAK_MINUTES, breakMinutes)
                                putExtra(FocusTimerService.EXTRA_ROUNDS, rounds)
                                putStringArrayListExtra(
                                    FocusTimerService.EXTRA_BLOCKED_PACKAGES,
                                    ArrayList(rules.filter(AppRule::enabled).map(AppRule::packageName)),
                                )
                            }
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingStart = intent
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                launchSafely(context, "开始专注") { ContextCompat.startForegroundService(context, intent) }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Spa, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("开始专注")
                    }
                }
            }
        }
    }

    customValueKind?.let { kind ->
        val current = when (kind) {
            FocusValueKind.FOCUS -> focusMinutes
            FocusValueKind.BREAK -> breakMinutes
            FocusValueKind.ROUNDS -> rounds
        }
        CustomFocusValueDialog(
            kind = kind,
            current = current,
            onDismiss = { customValueKind = null },
            onConfirm = { value ->
                when (kind) {
                    FocusValueKind.FOCUS -> focusMinutes = value
                    FocusValueKind.BREAK -> breakMinutes = value
                    FocusValueKind.ROUNDS -> rounds = value
                }
                customValueKind = null
            },
        )
    }
}

@Composable
private fun ActiveFocus(state: FocusState, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phaseEndsAtEpochMillis) {
        while (state.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remaining = (state.phaseEndsAtEpochMillis - now).coerceAtLeast(0)
    val phase = if (state.phase == FocusPhase.BREAK) "休息" else "专注"
    Text("$phase · 第 ${state.currentRound}/${state.totalRounds} 轮", color = Sage, fontWeight = FontWeight.Bold)
    Text(formatDuration(remaining), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Terracotta)
    Text(state.taskLabel.ifBlank { "此刻的一件事" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("结束本次专注") }
}

@Composable
private fun FocusSettingRow(
    title: String,
    description: String,
    selected: Int,
    values: List<Int>,
    suffix: String = "分钟",
    onSelect: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Text(
                    "$selected$suffix",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            values.forEach { value ->
                SettingChip(
                    label = "$value$suffix",
                    selected = value == selected,
                    onClick = { onSelect(value) },
                )
            }
            SettingChip(
                label = "自定义",
                selected = selected !in values,
                onClick = onCustom,
            )
        }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun CustomFocusValueDialog(
    kind: FocusValueKind,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember(kind, current) { mutableStateOf(current.toString()) }
    val value = input.toIntOrNull()
    val valid = value != null && value in kind.range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义${kind.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text(kind.title) },
                    suffix = { Text(kind.suffix) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = input.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "可设置 ${kind.range.first}–${kind.range.last}${kind.suffix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (input.isNotEmpty() && !valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(checkNotNull(value)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RulesScreen(padding: PaddingValues, store: AppStore, rules: List<AppRule>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var chooseApp by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppRule?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("限制清单", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("给容易停不下来的应用，预先约定一个边界。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { chooseApp = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("添加需要限制的应用")
            }
        }
        if (rules.isEmpty()) {
            item { WarmCard { Text("还没有限制应用。可以先从最容易让你分心的一个开始。") } }
        }
        items(rules, key = AppRule::packageName) { rule ->
            WarmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppInitial(rule.appLabel)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rule.appLabel, fontWeight = FontWeight.Bold)
                        Text(
                            "单次 ${rule.sessionLimitMinutes} 分钟 · 每日 ${rule.dailyLimitMinutes} 分钟 · 冷静 ${rule.cooldownMinutes} 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { store.enqueue { store.upsertRule(rule.copy(enabled = it)) } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { editing = rule }) { Icon(Icons.Rounded.Edit, contentDescription = "编辑") }
                    IconButton(onClick = { store.enqueue { store.deleteRule(rule.packageName) } }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除")
                    }
                }
            }
        }
    }
    if (chooseApp) {
        AppPickerDialog(
            apps = remember { loadLaunchableApps(context) }.filterNot { app -> rules.any { it.packageName == app.packageName } },
            onDismiss = { chooseApp = false },
            onChoose = {
                chooseApp = false
                editing = AppRule(it.packageName, it.label)
            },
        )
    }
    editing?.let { rule ->
        RuleEditorDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = { store.enqueue { store.upsertRule(it) }; editing = null },
        )
    }
}

@Composable
private fun StatsScreen(padding: PaddingValues, rules: List<AppRule>, today: DayStats, history: List<DayStats>) {
    val totalUsage = today.appUsageMillis.values.sum()
    val recentDays = (history + today)
        .distinctBy(DayStats::date)
        .sortedByDescending(DayStats::date)
        .take(7)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("今天的回顾", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("记录只留在这台手机里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("专注", "${today.focusMillis / 60_000} 分钟", Sage, Modifier.weight(1f))
                MetricCard("完成", "${today.focusCompletions} 轮", SunGold, Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("受限应用", "${totalUsage / 60_000} 分钟", Terracotta, Modifier.weight(1f))
                    MetricCard("拦下进入", "${today.blockedAttempts} 次", Sage, Modifier.weight(1f))
                }
                Text(
                    "同一次打开只记录 1 次，离开后再次进入才会重新计数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item { Text("近 7 天", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            WarmCard {
                recentDays.forEachIndexed { index, day ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.date.takeLast(5), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("专注 ${day.focusMillis / 60_000} 分钟", color = Sage, fontWeight = FontWeight.Medium)
                    }
                    if (index != recentDays.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        item { Text("应用用时", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (today.appUsageMillis.isEmpty()) {
            item { WarmCard { Text("今天还没有产生记录。") } }
        }
        items(today.appUsageMillis.toList().sortedByDescending { it.second }) { (packageName, millis) ->
            val label = rules.firstOrNull { it.packageName == packageName }?.appLabel ?: packageName
            WarmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppInitial(label)
                    Spacer(Modifier.size(12.dp))
                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("${millis / 60_000} 分钟", color = Terracotta, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object Current : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val release: ReleaseInfo) : UpdateUiState
    data class Ready(val release: ReleaseInfo, val apk: java.io.File) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@Composable
private fun SettingsScreen(padding: PaddingValues, accessibilityEnabled: Boolean, health: GuardHealth) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val guardReady = accessibilityEnabled && health.connected && health.ready
    val updater = remember { ManualUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("每一项权限和联网行为都说清楚。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            WarmCard {
                Text("应用守护", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (guardReady) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                        contentDescription = null,
                        tint = if (guardReady) Sage else Terracotta,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(when {
                        !accessibilityEnabled -> "系统开关未开启，限制暂不生效"
                        !health.connected -> "系统已授权，但守护服务未连接"
                        !health.ready -> "守护已连接，正在加载规则"
                        else -> "守护运行中，只识别前台应用包名"
                    })
                }
                health.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开辅助功能设置") }
                Text("若开关自动关闭：先重新开启一次；若仍失败，请复制下方诊断。vivo 的自启动、后台高耗电管理也可能影响运行，请在系统应用设置中检查。无需恢复出厂设置。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    launchSafely(context, "打开应用设置") {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                }) { Text("打开暖刻系统应用设置") }
                OutlinedButton(onClick = {
                    launchSafely(context, "复制本机诊断") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("暖刻守护诊断", ServiceDiagnostics.report(context)),
                        )
                        Toast.makeText(context, "诊断已复制；不会自动上传", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("复制本机诊断") }
                Text("仅包含暖刻版本、连接状态和自身异常位置，不包含聊天、应用清单或学习内容。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            WarmCard {
                Text("隐私说明", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("不读取聊天、文字、图片或控件内容；不收集账号、设备标识或应用清单；所有学习和使用记录仅保存在本机。")
                Text("关闭系统里的“暖刻应用守护”即可立即停止全部拦截。", color = Sage)
            }
        }
        item {
            WarmCard {
                Text("更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("暖刻不会每天自动检查，也没有后台更新任务。只有你点击下面的按钮时才访问 GitHub。")
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when (val state = updateState) {
                    UpdateUiState.Idle -> Button(
                        onClick = {
                            updateState = UpdateUiState.Checking
                            scope.launch {
                                updateState = when (val result = updater.checkLatest()) {
                                    UpdateCheckResult.UpToDate -> UpdateUiState.Current
                                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                                    is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.size(8.dp)); Text("检查更新") }
                    UpdateUiState.Checking -> Text("正在按你的要求连接 GitHub…", color = Sage)
                    UpdateUiState.Current -> {
                        Text("已经是最新版本。", color = Sage, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { updateState = UpdateUiState.Idle }) { Text("再次检查") }
                    }
                    is UpdateUiState.Available -> ReleaseCard(state.release) {
                        updateState = UpdateUiState.Downloading(state.release)
                        scope.launch {
                            updateState = when (val result = updater.downloadAndVerify(state.release)) {
                                is DownloadResult.Ready -> UpdateUiState.Ready(state.release, result.apk)
                                is DownloadResult.Error -> UpdateUiState.Error(result.message)
                            }
                        }
                    }
                    is UpdateUiState.Downloading -> Text("正在下载并校验 ${state.release.version}…", color = Sage)
                    is UpdateUiState.Ready -> {
                        Text("校验通过，可以交给 Android 安装。", color = Sage, fontWeight = FontWeight.Bold)
                        Button(onClick = { launchSafely(context, "打开安装页面") { context.startActivity(updater.installIntent(state.apk)) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("打开系统安装确认")
                        }
                    }
                    is UpdateUiState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { updateState = UpdateUiState.Idle }) { Text("返回") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: ReleaseInfo, onDownload: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("发现 ${release.version}", fontWeight = FontWeight.Bold, color = Terracotta)
    Text(release.title)
    if (release.notes.isNotBlank()) {
        Text(release.notes, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("下载更新") }
}

@Composable
private fun RuleEditorDialog(initial: AppRule, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    var session by remember(initial) { mutableStateOf(initial.sessionLimitMinutes.toString()) }
    var daily by remember(initial) { mutableStateOf(initial.dailyLimitMinutes.toString()) }
    var cooldown by remember(initial) { mutableStateOf(initial.cooldownMinutes.toString()) }
    var reminder by remember(initial) { mutableStateOf(initial.reminder) }
    val valid = listOf(session, daily, cooldown).all { it.toIntOrNull()?.let { value -> value > 0 } == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(initial.appLabel) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberInput(session, { session = it }, "连续使用上限（分钟）")
                NumberInput(daily, { daily = it }, "每日累计上限（分钟）")
                NumberInput(cooldown, { cooldown = it }, "冷静时间（分钟）")
                OutlinedTextField(
                    value = reminder,
                    onValueChange = { reminder = it.take(100) },
                    label = { Text("提醒内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && reminder.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            sessionLimitMinutes = session.toInt(),
                            dailyLimitMinutes = daily.toInt(),
                            cooldownMinutes = cooldown.toInt(),
                            reminder = reminder.trim(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberInput(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class InstalledApp(val packageName: String, val label: String)

@Composable
private fun AppPickerDialog(apps: List<InstalledApp>, onDismiss: () -> Unit, onChoose: (InstalledApp) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要限制的应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(420.dp)) {
                    items(filtered, key = InstalledApp::packageName) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onChoose(app) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppInitial(app.label)
                            Spacer(Modifier.size(12.dp))
                            Column {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AppInitial(label: String) {
    Box(
        Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(1).ifBlank { "·" }, color = Sage, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                color = accent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WarmCard(
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Suppress("DEPRECATION")
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val safe = safetyPackages(context)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .asSequence()
        .filter { it.activityInfo.packageName !in safe }
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy(InstalledApp::packageName)
        .sortedBy(InstalledApp::label)
        .toList()
}

private fun isGuardEnabled(context: Context): Boolean = ServiceDiagnostics.enabled(context)

private inline fun launchSafely(context: Context, operation: String, block: () -> Unit) {
    try { block() } catch (error: RuntimeException) {
        ServiceDiagnostics.error(context, operation, error)
        Toast.makeText(context, "$operation 失败，请查看本机诊断", Toast.LENGTH_LONG).show()
    }
}

private fun formatDuration(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return "%02d:%02d".format(minutes, seconds)
}
