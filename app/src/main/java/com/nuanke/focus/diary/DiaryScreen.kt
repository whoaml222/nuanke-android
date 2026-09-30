package com.nuanke.focus.diary

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuanke.focus.data.AppStore
import com.nuanke.focus.data.DayStats
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryRoot(activity: DiaryActivity, repository: DiaryRepository, store: AppStore) {
    val unlocked = activity.unlocked
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(if (activity.editorId == null || !unlocked) "随记" else "留给自己的一页") },
            navigationIcon = { IconButton(onClick = {
                if (unlocked && activity.editorId != null) activity.editorId = null else activity.leave("TODAY")
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            actions = { if (unlocked) IconButton(onClick = activity::lock) { Icon(Icons.Rounded.Lock, "锁定随记") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        ) },
        bottomBar = { if (activity.editorId == null || !unlocked) DiaryNavigation(activity) },
    ) { padding ->
        if (!unlocked) {
            Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(24.dp))
                Text("这里，安心记录自己", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("验证后才能查看文字和照片。离开随记或切到后台后，会重新锁定。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = activity::authenticate, enabled = !activity.authBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (activity.authBusy) "等待系统验证…" else "验证并打开")
                }
                TextButton(onClick = activity::openSecuritySettings) { Text("设置手机锁屏密码") }
                if (activity.authMessage.isNotBlank()) Text(activity.authMessage, color = MaterialTheme.colorScheme.error)
            }
        } else {
            DiaryUnlocked(activity, repository, store, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DiaryNavigation(activity: DiaryActivity) {
    NavigationBar {
        listOf(Triple("TODAY", "今天", Icons.Rounded.Home), Triple("RULES", "限制", Icons.Rounded.Security),
            Triple("DIARY", "随记", Icons.Rounded.EditNote), Triple("STATS", "回顾", Icons.Rounded.BarChart)).forEach { (key, title, icon) ->
            NavigationBarItem(selected = key == "DIARY", onClick = { if (key != "DIARY") activity.leave(key) },
                icon = { Icon(icon, null) }, label = { Text(title) })
        }
    }
}

@Composable
private fun DiaryUnlocked(activity: DiaryActivity, repository: DiaryRepository, store: AppStore, modifier: Modifier) {
    val entries by repository.entries.collectAsStateWithLifecycle()
    val saveStatus by repository.status.collectAsStateWithLifecycle()
    val today by store.todayStats.collectAsStateWithLifecycle(initialValue = DayStats(LocalDate.now().toString()))
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var loadFailure by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var trash by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loadAttempt) {
        loadFailure = false
        try { repository.load(); loaded = true } catch (_: Exception) { loadFailure = true }
    }
    LaunchedEffect(activity.photoRequest, loaded) {
        val request = activity.photoRequest ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        activity.photoRequest = null
        busy = true
        try {
            val count = withContext(kotlinx.coroutines.NonCancellable) { repository.addPhotos(request.first, request.second) }
            message = "已加入 $count 张照片，每篇最多 6 张。"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { message = "照片未导入，请检查图片是否可读及手机剩余空间，再重试。" }
        finally { busy = false }
    }
    BackHandler(activity.editorId != null) { activity.editorId = null }
    if (!loaded) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            if (loadFailure) {
                Text("暂时无法打开本机日记。原数据未被清除，请勿卸载或清除应用数据。")
                TextButton(onClick = { loadAttempt++ }) { Text("重试") }
            } else { CircularProgressIndicator(); Text("正在打开本机日记…") }
        }
        return
    }
    val current = entries.firstOrNull { it.id == activity.editorId }
    Column(modifier.fillMaxSize()) {
        if (activity.authMessage.isNotBlank()) {
            Text(activity.authMessage, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        if (saveStatus.startsWith("保存失败")) {
            TextButton(onClick = repository::retrySave, modifier = Modifier.fillMaxWidth()) {
                Text(saveStatus, color = MaterialTheme.colorScheme.error)
            }
        }
        if (message.isNotBlank()) {
            TextButton(onClick = { message = "" }, modifier = Modifier.fillMaxWidth()) { Text(message) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (current != null) {
            DiaryEditor(current, today, repository, saveStatus, busy,
                onPhotos = { activity.choosePhotos(current.id) },
                onTrash = { repository.edit(current.id) { it.copy(deletedAt = System.currentTimeMillis()) }; activity.editorId = null })
        } else {
            DiaryList(entries, trash, onTrash = { trash = !trash }, onTools = { tools = true },
                onCreate = { runCatching { activity.editorId = repository.create() }.onFailure { message = "暂时无法新建，请检查空间或先备份。" } },
                onOpen = { activity.editorId = it.id },
                onRestore = { entry -> repository.edit(entry.id) { it.copy(deletedAt = null) } },
                onDelete = { deleteId = it.id })
        }
    }
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null }, title = { Text("永久删除这篇随记？") },
        text = { Text("文字和日记内的照片副本将无法恢复；相册原图不受影响。") },
        confirmButton = { TextButton(onClick = {
            val id = deleteId ?: return@TextButton
            deleteId = null
            scope.launch {
                try { repository.permanentlyDelete(id) }
                catch (_: Exception) { message = "删除未完成，请重试。" }
            }
        }) { Text("永久删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("保留") } },
    )
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("备份与隐私") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("日记和照片在本机加密保存，不上传。卸载或清除数据会丢失未备份的内容。")
            Text("备份包含回收站，以独立密码加密。请妥善保管密码，暖刻无法找回。单个备份上限 64 MB。")
            Text("恢复只添加本机不存在的日记，不覆盖已有内容。文件选择器若选了云盘，备份文件会由你选择的服务保存。")
            OutlinedButton(onClick = { tools = false; activity.chooseBackup(true) }) { Text("创建加密备份") }
            OutlinedButton(onClick = { tools = false; activity.chooseBackup(false) }) { Text("恢复加密备份") }
        }
    }, confirmButton = { TextButton(onClick = { tools = false }) { Text("知道了") } })
    val document = activity.documentRequest
    if (document != null) BackupPasswordDialog(document.first, busy,
        onDismiss = { if (!busy) activity.documentRequest = null },
        onSubmit = { password ->
            busy = true
            scope.launch {
                try {
                    if (document.first) { repository.export(document.second, password); message = "加密备份已保存，请妥善保管密码。" }
                    else { val count = repository.restore(document.second, password); message = "已恢复 $count 篇随记，已有内容未被覆盖。" }
                    activity.documentRequest = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = if (document.first) "备份未完成：检查剩余空间与 64 MB 大小限制。未完成文件不可用于恢复。" else "恢复失败：密码错误、文件损坏、版本不支持或空间不足。原日记未被覆盖。" }
                finally { password.fill('\u0000'); busy = false }
            }
        })
}

@Composable
private fun DiaryList(entries: List<DiaryEntry>, trash: Boolean, onTrash: () -> Unit, onTools: () -> Unit,
    onCreate: () -> Unit, onOpen: (DiaryEntry) -> Unit, onRestore: (DiaryEntry) -> Unit, onDelete: (DiaryEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val context = LocalContext.current
    val visible = entries.filter { (it.deletedAt != null) == trash && it.matches(query) &&
        (query.isNotBlank() || (day?.toString()?.let { chosen -> it.date == chosen } ?: it.date.startsWith(month.toString()))) }
        .sortedWith(compareByDescending<DiaryEntry> { it.date }.thenByDescending { it.createdAt })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(if (trash) "暂时收起的片段" else "把这一刻，留给自己", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(if (trash) "删除 30 天后，下次打开随记时自动清理。" else "不必写得很好，一句话也值得留下。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!trash) FilledTonalButton(onClick = onCreate, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Text("写一篇") }
                OutlinedButton(onClick = onTrash, modifier = Modifier.weight(1f)) { Text(if (trash) "返回随记" else "回收站") }
                IconButton(onClick = onTools) { Icon(Icons.Rounded.MoreHoriz, "备份与隐私") }
            }
        }
        item { OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("搜索文字、心情或标签") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { month = month.minusMonths(1); day = null }) { Icon(Icons.Rounded.ChevronLeft, "上个月") }
                TextButton(onClick = {
                    val selected = day ?: month.atDay(1)
                    DatePickerDialog(context, { _, year, m, d -> day = LocalDate.of(year, m + 1, d); month = YearMonth.from(day) },
                        selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                }) { Text(if (query.isNotBlank()) "搜索全部日期" else day?.toString() ?: "${month.year} 年 ${month.monthValue} 月") }
                IconButton(onClick = { month = month.plusMonths(1); day = null }) { Icon(Icons.Rounded.ChevronRight, "下个月") }
            }
            if (day != null) TextButton(onClick = { day = null }) { Text("显示整月") }
        }
        if (visible.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (trash) Icons.Rounded.Inventory2 else Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.secondary)
                Text(if (query.isNotBlank()) "没有找到这段文字" else if (trash) "这里暂时没有日记" else "这一页，还等着你的故事")
                Text("可以切换月份，或写下此刻的感受。", style = MaterialTheme.typography.bodySmall)
            } }
        }
        items(visible, key = { it.id }) { entry ->
            Card(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.date + if (entry.mood.isNotBlank()) " · ${entry.mood}" else "", color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge)
                    Text(entry.title.ifBlank { if (entry.isEmpty) "待续的一页" else "无题，也很好" }, style = MaterialTheme.typography.titleMedium)
                    if (entry.body.isNotBlank()) Text(entry.body, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.photos.isNotEmpty()) Text("${entry.photos.size} 张照片", style = MaterialTheme.typography.labelMedium)
                    if (entry.tags.isNotBlank()) Text(entry.tags, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (trash) Row {
                        TextButton(onClick = { onRestore(entry) }) { Text("恢复") }
                        TextButton(onClick = { onDelete(entry) }) { Text("永久删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryEditor(entry: DiaryEntry, today: DayStats, repository: DiaryRepository, saveStatus: String, busy: Boolean,
    onPhotos: () -> Unit, onTrash: () -> Unit) {
    val context = LocalContext.current
    val readOnly = entry.deletedAt != null || busy
    var options by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var expandedPhoto by remember { mutableStateOf<String?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    fun edit(transform: (DiaryEntry) -> DiaryEntry) { repository.edit(entry.id, transform) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !readOnly, onClick = {
                    val date = LocalDate.parse(entry.date)
                    DatePickerDialog(context, { _, y, m, d -> edit { it.copy(date = LocalDate.of(y, m + 1, d).toString()) } },
                        date.year, date.monthValue - 1, date.dayOfMonth).show()
                }) { Text(entry.date) }
                Text(saveStatus, modifier = Modifier.weight(1f).clickable { repository.retrySave() }, style = MaterialTheme.typography.labelSmall,
                    color = if (saveStatus.startsWith("保存失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
            }
        }
        if (entry.deletedAt != null) item { Text("这篇在回收站中。返回列表恢复后，可以继续编辑。") }
        item { OutlinedTextField(entry.title, { value -> edit { it.copy(title = value.take(120)) } }, Modifier.fillMaxWidth(),
            placeholder = { Text("标题可不填") }, enabled = !readOnly, singleLine = true) }
        item { OutlinedTextField(entry.body, { value -> edit { it.copy(body = value.take(100_000)) } },
            Modifier.fillMaxWidth().heightIn(min = 240.dp), maxLines = 16, enabled = !readOnly,
            placeholder = { Text("今天，有什么想留给自己？\n\n开心、疲惫，或只是平淡的一天，都可以写在这里。") }) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onPhotos, enabled = !readOnly && entry.photos.size < DiaryEntry.MAX_PHOTOS,
                    label = { Text("照片 ${entry.photos.size}/6") }, leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { options = !options }, enabled = !readOnly, label = { Text("心情与标签") })
                AssistChip(onClick = { prompt = listOf("今天有什么小事让你开心？", "有什么想对现在的自己说？", "今天最想记住的是哪一个瞬间？").random() },
                    enabled = !readOnly, label = { Text("一点灵感") })
            }
        }
        if (prompt != null) item { Text(prompt!!, color = MaterialTheme.colorScheme.secondary) }
        if (options) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("开心", "平静", "疲惫", "低落", "期待").forEach { mood ->
                        FilterChip(selected = entry.mood == mood, onClick = { edit { it.copy(mood = if (it.mood == mood) "" else mood) } },
                            enabled = !readOnly, label = { Text(mood) })
                    }
                }
                OutlinedTextField(entry.tags, { value -> edit { it.copy(tags = value.take(120)) } }, Modifier.fillMaxWidth(),
                    enabled = !readOnly, singleLine = true, label = { Text("标签（选填，如：生活 学习）") })
            }
        }
        items(entry.photos, key = { it }) { id ->
            Column {
                DiaryPhoto(repository, id, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).clickable { expandedPhoto = id })
                if (!readOnly) TextButton(onClick = { edit { it.copy(photos = it.photos - id) } }) { Text("移除这张照片") }
            }
        }
        if (!readOnly) item {
            Column {
                TextButton(enabled = entry.body.length < 99_900, onClick = {
                    val snapshot = "${today.date} · 专注 ${today.focusMillis / 60_000} 分钟，完成 ${today.focusCompletions} 轮。"
                    edit { it.copy(body = (it.body + if (it.body.isBlank()) snapshot else "\n\n$snapshot").take(100_000)) }
                }) { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("插入今日专注记录") }
                TextButton(onClick = { confirmTrash = true }) { Text("移入回收站") }
            }
        }
    }
    if (confirmTrash) AlertDialog(onDismissRequest = { confirmTrash = false }, title = { Text("暂时收起这一篇？") },
        text = { Text("移入回收站后，30 天内可以恢复。") },
        confirmButton = { TextButton(onClick = { confirmTrash = false; onTrash() }) { Text("移入回收站") } },
        dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("继续写") } })
    if (expandedPhoto != null) AlertDialog(onDismissRequest = { expandedPhoto = null },
        text = { DiaryPhoto(repository, expandedPhoto!!, Modifier.fillMaxWidth().height(420.dp), ContentScale.Fit) },
        confirmButton = { TextButton(onClick = { expandedPhoto = null }) { Text("关闭") } })
}

@Composable
private fun DiaryPhoto(repository: DiaryRepository, id: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    var bitmap by remember(id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = try { repository.image(id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "日记照片", modifier, contentScale = scale)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("照片加载中或暂时不可用") }
}

@Composable
private fun BackupPasswordDialog(export: Boolean, busy: Boolean, onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (export) "设置备份密码" else "输入备份密码") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (export) "使用 8–128 个字符，建议用较长且独立的密码。密码无法找回。" else "请输入创建此备份时设置的密码，不是手机锁屏密码。恢复不覆盖已有日记。")
            OutlinedTextField(password, { password = it.take(128) }, label = { Text("备份密码") }, enabled = !busy,
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (export) OutlinedTextField(confirmation, { confirmation = it.take(128) }, label = { Text("再输入一次") }, enabled = !busy,
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy && password.length >= 8 && (!export || password == confirmation), onClick = {
        val chars = password.toCharArray(); password = ""; confirmation = ""; onSubmit(chars)
    }) { Text(if (export) "加密并保存" else "验证并恢复") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
