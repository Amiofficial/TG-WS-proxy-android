package app.tgwsproxy.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.tgwsproxy.Settings
import app.tgwsproxy.core.AppLog
import app.tgwsproxy.core.ProxyConfig
import app.tgwsproxy.core.ProxyState
import app.tgwsproxy.service.ProxyService
import kotlinx.coroutines.delay

@Composable
fun App(settings: Settings) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TabRow(selectedTabIndex = tab) {
                    listOf("Главная", "Настройки", "Журнал").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> HomeScreen(settings)
                    1 -> SettingsScreen(settings)
                    else -> LogsScreen()
                }
            }
        }
    }
}

private fun startProxy(ctx: Context) =
    ContextCompat.startForegroundService(ctx, Intent(ctx, ProxyService::class.java))

private fun stopProxy(ctx: Context) {
    ctx.stopService(Intent(ctx, ProxyService::class.java))
}

private fun human(n: Long): String {
    var v = n.toDouble()
    for (u in arrayOf("Б", "КБ", "МБ", "ГБ")) { if (v < 1024) return "%.1f %s".format(v, u); v /= 1024 }
    return "%.1f ТБ".format(v)
}

@Composable
fun HomeScreen(settings: Settings) {
    val ctx = LocalContext.current
    val running by ProxyState.running.collectAsState()
    val error by ProxyState.error.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) { while (running) { delay(1000); tick++ } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startProxy(ctx) // запускаем в любом случае: без уведомления прокси тоже работает
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(if (running) "Прокси работает" else "Прокси выключен", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (running) "Осталось подключить Telegram: нажмите кнопку ниже."
            else "Нажмите большую кнопку, чтобы запустить.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                when {
                    running -> stopProxy(ctx)
                    Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED ->
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> startProxy(ctx)
                }
            },
            modifier = Modifier.size(160.dp),
            shape = CircleShape,
        ) { Text(if (running) "Выключить" else "Включить", style = MaterialTheme.typography.titleMedium) }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        if (running) {
            Button(onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(settings.link()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(ctx, "Не нашёл Telegram на этом устройстве", Toast.LENGTH_LONG).show()
                }
            }, Modifier.fillMaxWidth()) { Text("Подключить Telegram") }

            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("proxy", settings.link()))
                Toast.makeText(ctx, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth()) { Text("Скопировать ссылку") }

            val st = ProxyState.stats
            key(tick) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Статистика", style = MaterialTheme.typography.titleSmall)
                        Text("Соединений сейчас: ${st.active.get()} (всего ${st.total.get()})")
                        Text("Через WebSocket: ${st.ws.get()}")
                        Text("Запасные пути: CF ${st.cf.get()}, TCP ${st.tcp.get()}")
                        Text("Передано: ↑ ${human(st.bytesUp.get())}  ↓ ${human(st.bytesDown.get())}")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Подсказки", style = MaterialTheme.typography.titleSmall)
                Text("Не грузятся фото и видео? В настройках нажмите «Только DC 4», сохраните и перезапустите прокси.")
                Text("Чтобы Android не выключал прокси в фоне, отключите для приложения оптимизацию батареи (кнопка в настройках).")
            }
        }
    }
}

@Composable
fun SettingsScreen(settings: Settings) {
    val ctx = LocalContext.current
    val running by ProxyState.running.collectAsState()
    var port by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var dc by rememberSaveable { mutableStateOf(settings.dcIpsText) }
    var cf by rememberSaveable { mutableStateOf(settings.cfDomainsText) }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Порт") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = dc, onValueChange = { dc = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            label = { Text("Дата-центры: номер:IP, по одному в строке") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dc = "4:149.154.167.220" }) { Text("Только DC 4") }
            OutlinedButton(onClick = { dc = ProxyConfig.DEFAULT_DC_TEXT }) { Text("По умолчанию") }
        }
        OutlinedTextField(
            value = cf, onValueChange = { cf = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Свои домены Cloudflare (необязательно)") },
            supportingText = { Text("Запасной канал, если прямой не работает. Через пробел или с новой строки.") },
        )
        Button(onClick = {
            val p = port.toIntOrNull()
            msg = if (p == null || p !in 1024..65535) {
                "Порт должен быть от 1024 до 65535"
            } else try {
                ProxyConfig.parseDcIps(dc)
                settings.port = p
                settings.dcIpsText = dc
                settings.cfDomainsText = cf
                if (running) "Сохранено. Перезапустите прокси, чтобы применить." else "Сохранено."
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }, Modifier.fillMaxWidth()) { Text("Сохранить") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        OutlinedButton(onClick = {
            settings.regenerateSecret()
            msg = "Секрет обновлён. Подключите Telegram заново."
        }, Modifier.fillMaxWidth()) { Text("Сгенерировать новый секрет") }

        OutlinedButton(onClick = {
            try {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, "Откройте настройки батареи вручную", Toast.LENGTH_LONG).show()
            }
        }, Modifier.fillMaxWidth()) { Text("Не засыпать в фоне (батарея)") }
    }
}

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", lines.joinToString("\n")))
                Toast.makeText(ctx, "Журнал скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Копировать") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Очистить") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
