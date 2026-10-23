package com.mergetacticsai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.mergetacticsai.accessibility.GameAccessibilityService
import com.mergetacticsai.ai.GeminiProvider
import com.mergetacticsai.automation.AgentController
import com.mergetacticsai.automation.AgentLoop
import com.mergetacticsai.automation.AgentState
import com.mergetacticsai.automation.CallBudget
import com.mergetacticsai.automation.LocalLoop
import com.mergetacticsai.automation.Permissions
import com.mergetacticsai.capture.ScreenCaptureService
import com.mergetacticsai.data.Catalog
import com.mergetacticsai.data.SettingsRepository
import com.mergetacticsai.ui.PickScreen
import com.mergetacticsai.ui.BuildPickScreen
import com.mergetacticsai.ui.DiagnosticsScreen
import com.mergetacticsai.ui.TimingScreen
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.graphics.asImageBitmap
import com.mergetacticsai.map.BoardScan
import com.mergetacticsai.map.MapController
import com.mergetacticsai.map.CombatContext
import com.mergetacticsai.map.MapStore
import com.mergetacticsai.strategy.PositionContext
import com.mergetacticsai.ui.MapScreen
import com.mergetacticsai.ui.OddsScreen
import com.mergetacticsai.odds.OddsController
import com.mergetacticsai.odds.OddsStore
import android.graphics.BitmapFactory
import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.KnownCards
import com.mergetacticsai.data.PendingCards
import com.mergetacticsai.data.TroopDb
import com.mergetacticsai.strategy.LoopKnowledge
import com.mergetacticsai.vision.CardMatcher
import com.mergetacticsai.strategy.TroopPolicy
import com.mergetacticsai.ui.LearnScreen
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import com.mergetacticsai.strategy.StrategyMode
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import com.mergetacticsai.ui.AppBottomBar
import com.mergetacticsai.ui.AppTheme
import com.mergetacticsai.ui.BuildsScreen
import com.mergetacticsai.ui.HomeScreen
import com.mergetacticsai.ui.SettingsScreen
import com.mergetacticsai.ui.TABS
import com.mergetacticsai.util.Logger
import com.mergetacticsai.voice.CommandParser
import com.mergetacticsai.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val controller = AgentController { perms() }
    private lateinit var voice: VoiceController
    private lateinit var repo: SettingsRepository
    private lateinit var loop: AgentLoop
    private lateinit var localLoop: LocalLoop
    private var useGemini by mutableStateOf(false)
    private var screen by mutableStateOf("main")
    private var recBusy by mutableStateOf(false)
    private var crashBusy by mutableStateOf(false)
    private var recNote by mutableStateOf("")
    private var recOn by mutableStateOf(true)
    private var rulerSel by mutableStateOf("")
    private var traitSel by mutableStateOf(emptySet<String>())
    private var modSel by mutableStateOf(emptySet<String>())
    private var strictTraits by mutableStateOf(false)
    private var enabledSel by mutableStateOf(emptySet<String>())
    private var placeSel by mutableStateOf(emptySet<String>())
    private val matcher by lazy { runCatching { CardMatcher.parse(assets.open("card_refs.json").bufferedReader().use { it.readText() }) }.getOrNull() }
    private var known by mutableStateOf(KnownCards())
    private var pendingList by mutableStateOf(emptyList<Pair<Long, Bitmap>>())
    private val troops by lazy { TroopDb.load(this) }
    private val troopById by lazy { troops.associateBy { it.id } }
    private val pendingCards by lazy { PendingCards(this) }
    private val mapCtl by lazy { MapController(MapStore(this)) { id -> troopById[id]?.name ?: "?" } }
    private val mapTroops by lazy { troops.sortedWith(compareBy({ it.cost }, { it.name })) }
    private val oddsCtl by lazy { OddsController(OddsStore(this)) { troops } }
    private val journalDb by lazy { com.mergetacticsai.journal.SqliteJournal(this) }
    private val journalWriter by lazy { com.mergetacticsai.journal.JournalWriter(journalDb) }

    /** Что анализ соперников знает о нас и об игре. Размер пула и слияние берутся с экрана «Вероятности». */
    private fun adaptInput() = com.mergetacticsai.adapt.AdaptInput(
        troops = troopById, traits = traits,
        ownCopies = localLoop.ownCopies, ownUnits = localLoop.ownBoard,
        buildTroops = placeSel, buildTraits = traitSel, radii = mapCtl.radii,
        starFactor = if (mapCtl.starFactor > 1.0001f) mapCtl.starFactor.toDouble() else 1.8,
        params = com.mergetacticsai.adapt.AdaptParams(
            copiesPerTroop = oddsCtl.state.params.copiesPerTroop, copiesPerMerge = oddsCtl.state.params.copiesPerMerge,
        ),
    )
    private val adaptCtl by lazy {
        com.mergetacticsai.adapt.AdaptController(
            com.mergetacticsai.adapt.AdaptStore(this), { adaptInput() }, { mapCtl.layout }, { localLoop.round }, journalWriter,
        )
    }
    private val rulers by lazy { Catalog.load(this, "rulers.json") }
    private val traits by lazy { Catalog.load(this, "traits.json") }
    private val mods by lazy { Catalog.load(this, "modifiers.json") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tick by mutableIntStateOf(0)
    private var keySet by mutableStateOf(false)
    private var maxPerHour by mutableIntStateOf(120)

    private fun perms() = Permissions(
        mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        capture = ScreenCaptureService.running,
        accessibility = GameAccessibilityService.instance != null,
    )

    private fun screenSize(): Pair<Int, Int> {
        val b = windowManager.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else Logger.log("Без микрофона доступен только ввод текстом")
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_CODE, r.resultCode).putExtra(ScreenCaptureService.EXTRA_DATA, data))
            Logger.log("Захват экрана разрешён")
        } else Logger.log("Разрешение на захват экрана не выдано")
        tick++
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        projectionLauncher.launch((getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadScreenshot(uri)
    }

    /** Скриншот из галереи для тактической карты: читаем, ищем занятые клетки, показываем под сеткой. */
    private fun loadScreenshot(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bmp == null) {
                withContext(Dispatchers.Main) { mapCtl.failScan("Не удалось открыть изображение") }
                return@launch
            }
            val res = withContext(Dispatchers.Default) { BoardScan.scan(bmp) }
            withContext(Dispatchers.Main) { mapCtl.applyScan(bmp.asImageBitmap(), res) }
        }
    }

    private fun startListening() { controller.transition(AgentState.LISTENING); voice.start() }

    private fun runCommand(text: String) {
        Logger.log("Команда: $text")
        val msg = controller.handle(CommandParser.parse(text), text)
        Logger.log(msg)
        when (controller.state.value) {
            AgentState.STARTING_GAME -> launchGame()
            AgentState.LISTENING -> controller.transition(AgentState.IDLE)
            AgentState.STOPPED -> { loop.stop(); localLoop.stop() }
            else -> {}
        }
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage("com.supercell.clashroyale")
        if (i == null) {
            Logger.log("Clash Royale не установлена — скачайте её из Google Play")
            controller.transition(AgentState.ERROR); return
        }
        startActivity(i)
        controller.transition(AgentState.WAITING_FOR_GAME)
        Logger.log("Игра запущена. Откройте режим Merge Tactics — ИИ включится сам, если ключ и разрешения готовы.")
        scope.launch { delay(6000); withContext(Dispatchers.Main) { startAi(silent = true) } }
    }

    /**
     * Безопасная проверка аварии: резко останавливаем цикл бота, запускаем заново и сверяем настройки до и после.
     * Игру и приложение не закрываем. Итог попадает в журнал сбоев.
     */
    private fun runCrashTest() {
        if (crashBusy) return
        crashBusy = true
        scope.launch {
            val running = setOf(
                AgentState.DETECTING_MODE, AgentState.ANALYZING, AgentState.PLANNING, AgentState.EXECUTING,
                AgentState.WAITING_FOR_BATTLE, AgentState.OBSERVING_BATTLE, AgentState.ROUND_FINISHED,
            )
            val before = controller.state.value
            val wasRunning = before in running
            val snapBefore = repo.snapshot()
            val id = com.mergetacticsai.util.FaultLog.add(
                "Имитация аварии бота", before.name, "выполняется…",
                "Цикл бота будет остановлен и запущен заново, настройки сверяются до и после.",
            )
            val t0 = System.currentTimeMillis()
            loop.stop(); localLoop.stop()
            controller.transition(AgentState.ERROR)
            Logger.log("Тест аварии: цикл бота остановлен")
            delay(700)
            val flushed = repo.flush()
            val diff = com.mergetacticsai.util.SettingsSnapshot.diff(snapBefore, repo.snapshot())
            val memory = ArrayList<String>()
            if (repo.placeSel() != placeSel) memory.add("сборка")
            if (repo.ruler() != rulerSel) memory.add("правитель")
            if (repo.traits() != traitSel) memory.add("свойства")
            if (repo.modifiers() != modSel) memory.add("модификаторы")
            withContext(Dispatchers.Main) {
                controller.transition(AgentState.IDLE)
                if (wasRunning) startAi(silent = true)
            }
            var restarted = !wasRunning
            if (wasRunning) {
                val until = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < until && controller.state.value !in running) delay(200)
                restarted = controller.state.value in running
            }
            val ms = System.currentTimeMillis() - t0
            val settingsOk = flushed && diff.isEmpty() && memory.isEmpty()
            val changed = (diff + memory).joinToString(", ")
            val result = (if (restarted) {
                if (wasRunning) "бот перезапущен за $ms мс" else "бот не работал, перезапуск не требовался"
            } else "бот не перезапустился (проверьте разрешения и режим ИИ)") +
                "; " + (if (settingsOk) "настройки сохранились" else "настройки изменились: " + changed.ifEmpty { "не удалось записать на диск" })
            com.mergetacticsai.util.FaultLog.update(id, result)
            Logger.log("Тест аварии: $result")
            withContext(Dispatchers.Main) { crashBusy = false }
        }
    }

    private fun startAi(silent: Boolean = false) {
        if (useGemini && repo.apiKey().isBlank()) { if (!silent) Logger.log("Введите API-ключ Gemini или выберите локальный режим"); return }
        if (!perms().canControlGame) { if (!silent) Logger.log("Нужны захват экрана и служба доступности"); return }
        if (!controller.beginAnalysis()) { if (!silent) Logger.log("Сейчас нельзя запустить ИИ"); return }
        loop.stop(); localLoop.stop()
        journalWriter.newSession()
        journalWriter.log("EVENT", if (useGemini) "Запуск: Gemini" else "Запуск: локальный режим", "Пользователь запустил ИИ.", "", "INFO")
        if (useGemini) loop.start(scope) else localLoop.start(scope)
        Logger.log(if (useGemini) "Gemini запущен" else "Локальный режим запущен")
    }

    /**
     * Данные для позиционного ИИ: радиусы, скорости, HP/урон и пометки клеток с экрана «Тактическая карта».
     * Расстановка противника из карты сюда не передаётся: к следующему раунду она устаревает.
     */
    private fun positionContext() = PositionContext(
        radii = mapCtl.radii, speeds = mapCtl.speeds, stats = mapCtl.stats, marks = mapCtl.marks,
        combat = CombatContext(rulerSel, modSel, troopById.mapValues { it.value.trait }, mapCtl.starFactor),
        plan = mapCtl.buildLayout.filterValues { placeSel.isEmpty() || it.troopId in placeSel },
    )

    private fun knowledge() = LoopKnowledge(troopById, TroopPolicy(enabledSel, traitSel, strictTraits), known, traits, matcher, placeSel)

    private fun refreshPending() {
        pendingList = pendingCards.list().mapNotNull { (sig, f) -> BitmapFactory.decodeFile(f.path)?.let { sig to it } }
    }

    private fun troopItems() = troops.sortedWith(compareBy({ it.cost }, { it.name })).map {
        val trait = traits.firstOrNull { tr -> tr.id == it.trait }?.name ?: "без свойства"
        CatalogItem(it.id, "${it.cost} · ${it.name} — $trait" + if (it.legacy) " (Legacy)" else "", it.desc, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mergetacticsai.record.ScreenRecorder.init(this)
        com.mergetacticsai.util.FaultLog.init(this)
        com.mergetacticsai.util.FaultSim.reset()
        recOn = com.mergetacticsai.record.ScreenRecorder.enabled
        repo = SettingsRepository(this)
        com.mergetacticsai.automation.Timing.configure(repo.timingBuy(), repo.timingPlace(), repo.timingSell(), repo.timingMerge(), repo.timingVerify(), repo.timingLoop())
        controller.setConfig(repo.load())
        keySet = repo.apiKey().isNotBlank()
        maxPerHour = repo.maxPerHour()
        useGemini = repo.useGemini()
        rulerSel = repo.ruler(); traitSel = repo.traits(); modSel = repo.modifiers(); strictTraits = repo.strictTraits()
        placeSel = repo.placeSel(); enabledSel = repo.enabledTroops(TroopDb.defaultEnabled(troops)); known = KnownCards.parse(repo.knownCards())
        loop = AgentLoop(
            controller = controller,
            provider = { repo.apiKey().takeIf { it.isNotBlank() }?.let { GeminiProvider(it, repo.model()) } },
            newBudget = { CallBudget(repo.minIntervalMs(), repo.maxPerHour()) },
            perms = { perms() },
            screen = { screenSize() },
            log = { Logger.log(it) },
        )
        localLoop = LocalLoop(controller, { perms() }, { screenSize() }, { Logger.log(it) },
            knowledge = { knowledge() },
            position = { positionContext() },
            oddsParams = { oddsCtl.state.params },
            adaptBias = { adaptCtl.bias() },
            onRound = { adaptCtl.onRound(it) },
            journal = journalWriter,
            onUnknownCard = { sig, bmp ->
                if (pendingCards.save(sig, bmp)) Logger.log("Новая карточка сохранена — назовите её в разделе «Обучение»")
                bmp.recycle()
            })
        voice = VoiceController(this, { runCommand(it) }, {
            Logger.log(it); controller.transition(AgentState.IDLE)
        })
        enableEdgeToEdge()
        setContent {
            val state by controller.state.collectAsState()
            val log by Logger.lines.collectAsState()
            val config by controller.config.collectAsState()
            val info by localLoop.info.collectAsState()
            LaunchedEffect(config) { repo.save(config) }
            tick.let { }
            LaunchedEffect(mapCtl.layout) { runCatching { adaptCtl.autoSync() } }
            val tabIds = TABS.map { it.first }
            BackHandler(enabled = screen != "main") {
                screen = when (screen) {
                    "traits", "mods", "build" -> "builds"
                    "learn" -> "settings"
                    else -> "main"
                }
            }
            val note = "Выбор сохраняется. Бот применит его после этапа распознавания названий карт."
            val traitsText = if (traitSel.isEmpty()) "Не выбраны" else traits.filter { it.id in traitSel }.joinToString { it.name } +
                if (strictTraits) " (строгий)" else " (стратегический)"
            AppTheme {
                Scaffold(bottomBar = { if (screen in tabIds) AppBottomBar(screen) { screen = it } }) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (screen) {
                            "rulers" -> PickScreen("Правители", note, rulers, setOf(rulerSel), false,
                                onToggle = { rulerSel = it; repo.setRuler(it) }, onBack = { screen = "main" }, showBack = false)
                            "traits" -> PickScreen("Свойства", note, traits, traitSel, true,
                                onToggle = { id -> traitSel = if (id in traitSel) traitSel - id else traitSel + id; repo.setTraits(traitSel) },
                                onBack = { screen = "builds" },
                                header = {
                                    OutlinedButton(onClick = { strictTraits = !strictTraits; repo.setStrictTraits(strictTraits) }) {
                                        Text(if (strictTraits) "Режим: строгий (только выбранные свойства)" else "Режим: стратегический (приоритет выбранных)")
                                    }
                                })
                            "troops" -> PickScreen("Войска", "Отметьте войска, которых бот может покупать. В строгом режиме (экран «Свойства») — только их и только выбранных свойств. Legacy выключены по умолчанию.",
                                troopItems(), enabledSel, true,
                                onToggle = { id -> enabledSel = if (id in enabledSel) enabledSel - id else enabledSel + id; repo.setEnabledTroops(enabledSel) },
                                onBack = { screen = "main" }, showBack = false,
                                header = {
                                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            enabledSel = troops.filter { it.trait in traitSel && !it.legacy && it.cost > 0 }.map { it.id }.toSet()
                                            repo.setEnabledTroops(enabledSel)
                                        }) { Text("По свойствам") }
                                        OutlinedButton(onClick = { enabledSel = TroopDb.defaultEnabled(troops); repo.setEnabledTroops(enabledSel) }) { Text("Все текущие") }
                                        OutlinedButton(onClick = { enabledSel = emptySet(); repo.setEnabledTroops(enabledSel) }) { Text("Никого") }
                                    }
                                })
                            "build" -> BuildPickScreen(
                                troops = troops.filter { !it.legacy && it.cost > 0 }.sortedWith(compareBy({ it.cost }, { it.name })),
                                traits = traits, selected = placeSel, max = 7,
                                onToggle = { id ->
                                    if (id in placeSel) placeSel = placeSel - id
                                    else if (placeSel.size >= 7) Logger.log("Уже выбрано 7 карт — уберите одну, чтобы добавить другую")
                                    else placeSel = placeSel + id
                                    repo.setPlaceSel(placeSel)
                                },
                                onClear = { placeSel = emptySet(); repo.setPlaceSel(placeSel) },
                                onBack = { screen = "builds" })
                            "learn" -> LearnScreen(pendingList, troops.sortedWith(compareBy({ it.cost }, { it.name })), known.size(),
                                onAssign = { sig, id ->
                                    val kc = KnownCards.parse(repo.knownCards()); kc.add(sig, id)
                                    repo.setKnownCards(kc.serialize()); known = kc
                                    pendingCards.remove(sig); refreshPending()
                                },
                                onDiscard = { sig -> pendingCards.remove(sig); refreshPending() },
                                onBack = { screen = "settings" })
                            "mods" -> PickScreen("Модификаторы", note, mods, modSel, true,
                                onToggle = { id -> modSel = if (id in modSel) modSel - id else modSel + id; repo.setModifiers(modSel) },
                                onBack = { screen = "builds" })
                            "builds" -> BuildsScreen(
                                config = config, placeCount = placeSel.size, traitsText = traitsText, modsCount = modSel.size,
                                onBuild = { controller.setTargets(CommandParser.splitUnits(it)); Logger.log("Состав: $it") },
                                onOpen = { screen = it },
                            )
                            "settings" -> SettingsScreen(
                                useGemini = useGemini, apiKeySet = keySet, maxPerHour = maxPerHour,
                                captureOn = ScreenCaptureService.running, accessibilityOn = GameAccessibilityService.instance != null,
                                version = versionName(),
                                onSetGemini = { useGemini = it; repo.setUseGemini(it); Logger.log("Режим ИИ: " + if (it) "Gemini" else "локальный") },
                                onSaveAi = { k, m -> repo.saveAi(k, m); keySet = repo.apiKey().isNotBlank(); maxPerHour = repo.maxPerHour(); Logger.log("Настройки ИИ сохранены") },
                                onClearKey = { repo.clearKey(); keySet = false; Logger.log("Ключ Gemini удалён") },
                                onGrantCapture = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                onMic = { if (perms().mic) startListening() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onText = { runCommand(it) },
                                onOpen = { if (it == "learn") refreshPending(); screen = it },
                            )
                            "map" -> MapScreen(
                                mapCtl, mapTroops,
                                onPickScreenshot = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onBack = { screen = "main" },
                                ruler = rulerSel, modifiers = modSel, buildIds = placeSel,
                                names = { id -> troopById[id]?.name ?: (rulers + traits + mods).firstOrNull { it.id == id }?.name ?: id },
                            )
                            "odds" -> OddsScreen(oddsCtl, onBack = { screen = "main" })
                            "adapt" -> com.mergetacticsai.ui.AdaptScreen(adaptCtl, onBack = { screen = "main" })
                            "journal" -> com.mergetacticsai.ui.JournalScreen(journalDb, onBack = { screen = "main" })
                            "record" -> com.mergetacticsai.ui.RecordScreen(
                                enabled = recOn,
                                onToggle = { recOn = it; com.mergetacticsai.record.ScreenRecorder.setEnabled(it) },
                                stats = { com.mergetacticsai.record.ScreenRecorder.stats() },
                                onShare = { shareRecord() },
                                onClear = { com.mergetacticsai.record.ScreenRecorder.clear(); recNote = "Запись очищена" },
                                onBack = { screen = "main" }, busy = recBusy, note = recNote,
                            )
                            "diagnostics" -> DiagnosticsScreen(
                                onBack = { screen = "settings" },
                                botState = { controller.state.value.name },
                                onCrashTest = { runCrashTest() },
                                crashBusy = crashBusy,
                            )
                            "timing" -> TimingScreen(
                                initial = listOf(repo.timingBuy(), repo.timingPlace(), repo.timingSell(), repo.timingMerge(), repo.timingVerify(), repo.timingLoop()),
                                onSave = { v ->
                                    repo.saveTimings(v[0], v[1], v[2], v[3], v[4], v[5])
                                    com.mergetacticsai.automation.Timing.configure(v[0], v[1], v[2], v[3], v[4], v[5])
                                },
                                onBack = { screen = "main" },
                            )
                            else -> HomeScreen(
                                state = state, log = log, config = config, useGemini = useGemini,
                                captureOn = ScreenCaptureService.running, accessibilityOn = GameAccessibilityService.instance != null,
                                info = info,
                                summary = listOf(
                                    "Правитель: " + (rulers.firstOrNull { it.id == rulerSel }?.name ?: "не выбран"),
                                    "Свойства: $traitsText",
                                    "Модификаторов: ${modSel.size} · На арену: ${placeSel.size}/7",
                                    "Войск включено: ${enabledSel.size} · Карточек изучено: ${known.size()}",
                                ),
                                onStartAi = { startAi() },
                                onOpenGame = { runCommand("играй в merge tactics") },
                                onPause = { runCommand("пауза") },
                                onResume = { runCommand("продолжай") },
                                onStop = { runCommand("стоп") },
                                onMode = { controller.setMode(it); Logger.log("Режим: " + if (it == StrategyMode.MERGE_CHEAP) "объединение" else "своя сборка") },
                                onGrantCapture = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                onOpen = { screen = it },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Собирает архив записи (кадры, хронология, журнал решений, лог) и открывает системное окно «Отправить». */
    private fun shareRecord() {
        if (recBusy) return
        recBusy = true; recNote = ""
        scope.launch {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val journalText = runCatching {
                journalDb.recent(500).asReversed().joinToString("\n\n") { e ->
                    fmt.format(java.util.Date(e.at)) + " [" + e.kind + "/" + e.status + "] " + (e.round?.let { "раунд $it " } ?: "") + e.title +
                        "\nПричина: " + e.reason + (if (e.outcome.isNotEmpty()) "\nИтог: " + e.outcome else "") + (if (e.details.isNotEmpty()) "\n" + e.details else "")
                }
            }.getOrDefault("")
            val extra = mapOf("journal.txt" to journalText, "log.txt" to Logger.text())
            val zip = runCatching { com.mergetacticsai.record.ScreenRecorder.export(extra, listOf("Версия: " + versionName())) }.getOrNull()
            withContext(Dispatchers.Main) {
                recBusy = false
                if (zip == null) { recNote = "Не удалось собрать архив или записывать нечего. Запустите бота и дайте ему поработать."; return@withContext }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", zip)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Отправить запись"))
                    recNote = "Архив готов: " + zip.name
                } catch (e: Exception) { recNote = "Не удалось открыть окно отправки: " + e.message }
            }
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")

    override fun onResume() { super.onResume(); tick++ }
    override fun onDestroy() { loop.stop(); localLoop.stop(); scope.cancel(); voice.destroy(); super.onDestroy() }
}
