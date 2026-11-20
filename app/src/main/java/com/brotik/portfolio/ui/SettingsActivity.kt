package com.brotik.portfolio.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.brotik.portfolio.App
import com.brotik.portfolio.BuildConfig
import com.brotik.portfolio.R
import com.brotik.portfolio.core.Alerts
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.Updater
import com.brotik.portfolio.data.AlertPrefs
import com.brotik.portfolio.data.DownloadBackups
import com.brotik.portfolio.databinding.ActivitySettingsBinding
import com.brotik.portfolio.notify.AlertNotifier
import com.brotik.portfolio.widget.WidgetRenderer
import com.brotik.portfolio.work.RefreshScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val app: App get() = App.instance
    private val alertPrefs get() = AlertPrefs(this)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            app.backup.setTreeUri(uri)
            updateUi()
            val offered = offerRestoreIfNewer()
            if (!offered) doBackup(manual = false)
        }
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateAlertsUi() }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val p = app.store.load() ?: return@registerForActivityResult
        val result = app.backup.exportTo(uri, p)
        toast(
            if (result.isSuccess) getString(R.string.export_done)
            else getString(R.string.export_failed, result.exceptionOrNull()?.message.orEmpty())
        )
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.import_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_json) { _, _ -> doImport(uri) }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()

        b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.chooseFolder.setOnClickListener { pickFolder.launch(null) }
        b.backupNow.setOnClickListener { doBackup(manual = true) }
        b.autoBackup.isChecked = app.backup.autoBackup()
        b.autoBackup.setOnCheckedChangeListener { _, checked -> app.backup.setAutoBackup(checked) }

        b.export.setOnClickListener {
            exportFile.launch("portfolio-" + SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()) + ".json")
        }
        b.importBtn.setOnClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }
        b.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)

        b.checkUpdates.setOnClickListener { checkUpdates() }
        b.installUpdate.setOnClickListener { installUpdate() }

        setupAlerts()
        b.downloadFind.setOnClickListener { findDownloadBackups() }

        updateUi()
        updateAlertsUi()
        RefreshScheduler.ensureScheduled(this)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun updateUi() {
        val folder = app.backup.folderName()
        b.folder.text = folder ?: getString(R.string.backup_folder_none)
        val last = app.backup.lastBackupAt()
        b.lastBackup.text = if (last > 0) getString(R.string.backup_last, Fmt.dateTime(last))
        else getString(R.string.backup_never)
        b.autoBackup.isChecked = app.backup.autoBackup()
        b.downloadHint.text = if (app.backup.lastSavedToDownloads()) {
            if (last > 0) getString(R.string.download_status, Fmt.dateTime(last))
            else getString(R.string.download_status_never)
        } else {
            getString(R.string.download_status_failed)
        }
    }

    // ------------------------------------------------------------ уведомления

    private fun setupAlerts() {
        val s = alertPrefs.settings()
        b.alertsEnabled.isChecked = s.enabled
        b.alertsBelow.isChecked = s.belowAvg
        b.alertsTarget.isChecked = s.target
        b.thresholdInput.setText(plain(s.thresholdPct.coerceIn(0.5, 90.0)))

        b.alertsEnabled.setOnCheckedChangeListener { _, checked ->
            alertPrefs.setEnabled(checked)
            updateAlertsUi()
            if (checked) RefreshScheduler.ensureScheduled(this)
        }
        b.alertsBelow.setOnCheckedChangeListener { _, checked -> alertPrefs.setBelowAvg(checked) }
        b.alertsTarget.setOnCheckedChangeListener { _, checked -> alertPrefs.setTarget(checked) }
        b.thresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveThreshold()
        }
        b.alertsCheck.setOnClickListener { checkAlertsNow() }
        b.alertsAllow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast(getString(R.string.alerts_perm_needed))
            }
        }
    }

    private fun updateAlertsUi() {
        val allowed = AlertNotifier.allowed(this)
        b.alertsAllow.visibility = if (allowed) android.view.View.GONE else android.view.View.VISIBLE
        b.alertsHint.text = if (allowed) {
            getString(R.string.alerts_spam_hint)
        } else {
            getString(R.string.alerts_perm_needed)
        }
        val enabled = alertPrefs.settings().enabled
        val alpha = if (enabled) 1f else 0.6f
        b.alertsBelow.alpha = alpha
        b.thresholdWrap.alpha = alpha
        b.alertsTarget.alpha = alpha
        b.alertsCheck.isEnabled = enabled
    }

    /** Порог: пусто/мусор — откат к прошлому значению. */
    private fun saveThreshold() {
        val current = alertPrefs.settings().thresholdPct
        val parsed = parseNum(b.thresholdInput.text?.toString())
        if (parsed == null || parsed <= 0.0) {
            b.thresholdWrap.error = getString(R.string.invalid_number)
            b.thresholdInput.setText(plain(current))
            return
        }
        b.thresholdWrap.error = null
        val clamped = parsed.coerceIn(0.5, 90.0)
        alertPrefs.setThreshold(clamped)
        b.thresholdInput.setText(plain(clamped))
    }

    /** Ручная проверка: тянем цены, считаем условия и показываем уведомления сразу. */
    private fun checkAlertsNow() {
        saveThreshold()
        val settings = alertPrefs.settings()
        if (!settings.enabled) {
            toast(getString(R.string.alerts_check_off))
            return
        }
        val portfolio = app.store.loadOrSeed()
        val symbols = portfolio.assets.map { it.symbol }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.priceFetcher(symbols) } }
            val prices = result.getOrNull().orEmpty()
            if (prices.isEmpty()) {
                val reason = result.exceptionOrNull()?.message ?: getString(R.string.no_data)
                toast(getString(R.string.alerts_check_failed, reason))
                return@launch
            }
            val alerts = Alerts.evaluate(
                assets = portfolio.assets,
                prices = prices,
                settings = settings,
                lastSent = alertPrefs.lastSent(),
                ignoreThrottle = true,
            )
            val shown = AlertNotifier.show(this@SettingsActivity, alerts)
            // Метку ставим только для новых пар: ручная проверка не должна глушить фон на сутки.
            alertPrefs.recordIfNew(shown)
            runCatching { WidgetRenderer.refreshAll(this@SettingsActivity) }
            if (isStalePrices()) {
                toast(getString(R.string.alerts_check_stale))
            }
            toast(
                when {
                    alerts.isEmpty() -> getString(R.string.alerts_check_none)
                    shown.isEmpty() -> getString(R.string.alerts_check_no_perm)
                    else -> getString(R.string.alerts_check_found, shown.size)
                }
            )
        }
    }

    /** Цены из кэша старше двух часов — проверка по ним может врать. */
    private fun isStalePrices(): Boolean {
        val ts = app.prices.lastUpdateAt()
        return ts <= 0L || System.currentTimeMillis() - ts > 2 * 60 * 60 * 1000L
    }

    // ------------------------------------------------------- копии в Загрузках

    private fun findDownloadBackups() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { app.backup.downloads.list() }
            if (files.isEmpty()) {
                b.downloadHint.text = getString(R.string.download_none_hint)
                toast(getString(R.string.download_none))
                return@launch
            }
            b.downloadHint.text = getString(R.string.download_found, files.size)
            val labels = files.map {
                it.name + " · " + Fmt.dateTime(it.modifiedAt)
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(getString(R.string.download_title))
                .setItems(labels) { _, which -> confirmDownloadRestore(files[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDownloadRestore(file: DownloadBackups.StoredBackup) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.download_import_confirm, file.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_ok) { _, _ ->
                lifecycleScope.launch {
                    val restored = withContext(Dispatchers.IO) { app.backup.downloads.read(file) }
                    if (restored == null) {
                        toast(getString(R.string.import_failed, file.name))
                        return@launch
                    }
                    app.store.save(restored)
                    toast(getString(R.string.restore_done))
                    updateUi()
                    runCatching { WidgetRenderer.refreshAll(this@SettingsActivity) }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ прочее

    private fun doBackup(manual: Boolean) {
        val p = app.store.loadOrSeed()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.backup.backup(p) } }
            if (result.isSuccess) {
                updateUi()
                if (manual) {
                    toast(
                        if (app.backup.hasFolder()) getString(R.string.backup_done)
                        else getString(R.string.backup_download_only)
                    )
                }
            } else if (manual) {
                toast(getString(R.string.backup_failed, result.exceptionOrNull()?.message.orEmpty()))
            }
        }
    }

    /** true, если показали диалог восстановления. */
    private fun offerRestoreIfNewer(): Boolean {
        val latestTs = app.backup.readLatestTimestamp()
        if (latestTs <= 0L) return false
        val localTs = app.store.lastModified()
        if (localTs >= latestTs) return false
        val candidate = app.backup.readLatest() ?: return false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_title)
            .setMessage(getString(R.string.restore_found, Fmt.dateTime(latestTs)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_ok) { _, _ ->
                app.store.save(candidate)
                toast(getString(R.string.restore_done))
                updateUi()
                doBackup(manual = false)
            }
            .show()
        return true
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { app.backup.importFrom(uri) }
            val p = result.getOrNull()
            if (p == null) {
                toast(getString(R.string.import_failed, result.exceptionOrNull()?.message.orEmpty()))
                return@launch
            }
            app.store.save(p)
            toast(getString(R.string.import_ok, p.assets.sumOf { it.entries.size }))
            withContext(Dispatchers.IO) { runCatching { app.backup.backup(p) } }
            updateUi()
        }
    }

    private fun parseNum(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim().replace(" ", "").replace('\u00A0', ' ').replace(',', '.')
        return cleaned.toDoubleOrNull()
    }

    private fun plain(v: Double): String {
        val format = java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols(Locale.US))
        return format.format(v)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        // Вернулись с экрана «Установка неизвестных приложений» — если разрешение включили,
        // снимаем подсказку и сразу пробуем скачать.
        if (waitingForPermission && Updater.canInstall(this)) {
            waitingForPermission = false
            b.updateStatus.text = getString(R.string.updates_permission_ok)
        }
    }

    // ------------------------------------------------------------ обновления

    private var waitingForPermission = false
    private var pendingRelease: Updater.Release? = null
    private var updateBusy = false

    private fun checkUpdates() {
        if (updateBusy) return
        updateBusy = true
        b.checkUpdates.isEnabled = false
        b.installUpdate.visibility = android.view.View.GONE
        b.updateStatus.text = getString(R.string.updates_checking)
        lifecycleScope.launch {
            val check = runCatching { Updater.check() }
                .getOrElse { Updater.Check(null, it.message.orEmpty()) }
            val release = check.release
            updateBusy = false
            b.checkUpdates.isEnabled = true
            if (release == null) {
                b.updateStatus.text = getString(R.string.updates_error, check.error.orEmpty())
                return@launch
            }
            if (release.tag.isBlank()) {
                b.updateStatus.text = getString(R.string.updates_error, "релиз не найден")
                return@launch
            }
            if (release.apkUrl.isBlank()) {
                b.updateStatus.text = getString(R.string.updates_no_apk, release.tag)
                return@launch
            }
            if (Updater.compare(release.tag, BuildConfig.VERSION_NAME) > 0) {
                pendingRelease = release
                val suffix = release.notes.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
                b.updateStatus.text =
                    getString(R.string.updates_available, release.tag, BuildConfig.VERSION_NAME) + suffix
                b.installUpdate.visibility = android.view.View.VISIBLE
            } else {
                pendingRelease = null
                b.updateStatus.text = getString(R.string.updates_latest, BuildConfig.VERSION_NAME)
            }
        }
    }

    private fun installUpdate() {
        val release = pendingRelease ?: return
        if (updateBusy) return
        if (!Updater.canInstall(this)) {
            waitingForPermission = true
            b.updateStatus.text = getString(R.string.updates_permission_hint)
            toast(getString(R.string.updates_allow))
            Updater.openInstallPermission(this)
            return
        }
        updateBusy = true
        b.installUpdate.isEnabled = false
        b.updateStatus.text = getString(R.string.updates_downloading, 0)
        lifecycleScope.launch {
            val result = Updater.download(this@SettingsActivity, release) { pct ->
                runOnUiThread { if (!isFinishing) b.updateStatus.text = getString(R.string.updates_downloading, pct) }
            }
            updateBusy = false
            b.installUpdate.isEnabled = true
            val file = result.getOrElse {
                b.updateStatus.text = getString(R.string.updates_error, it.message.orEmpty())
                return@launch
            }
            b.updateStatus.text = getString(R.string.updates_ready)
            runCatching { Updater.install(this@SettingsActivity, file) }.onFailure {
                b.updateStatus.text = getString(R.string.updates_error, it.message.orEmpty())
            }
        }
    }
}

