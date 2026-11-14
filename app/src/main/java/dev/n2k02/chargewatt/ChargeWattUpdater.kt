package dev.n2k02.chargewatt

import android.app.Activity
import android.content.Context
import android.os.Build
import dev.n2k02.updater.UpdateConfig
import dev.n2k02.updater.UpdateInstallGuard
import dev.n2k02.updater.UpdaterController
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Identical shared-updater integration contract to mRadio.
 * Never duplicate the updater UI or a hand-written APK installer.
 * The signed public channel manifest is preferred, with GitHub releases fallback.
 */
internal object ChargeWattUpdater {
    private lateinit var app: Context
    private var controllerInstance: UpdaterController? = null
    val experimentalEnabled = MutableStateFlow(false)
    // There are no app-owned recording/playback services to suspend for installation.
    val installGuard = UpdateInstallGuard { true }

    fun init(context: Context) {
        app = context.applicationContext
        if (controllerInstance != null) return
        experimentalEnabled.value = prefs().getBoolean("experimentalEnabled", false)
        controllerInstance = createController()
    }

    private fun prefs() = app.getSharedPreferences("app_updater", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun versionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun createController() = UpdaterController(
        context = app,
        config = UpdateConfig(
            repository = "N2K02/ChargeWatt",
            packageName = app.packageName,
            installedVersionCode = versionCode(),
            experimentalEnabled = experimentalEnabled.value,
        ),
        offerPreferences = prefs(),
    )

    val controller: UpdaterController
        get() = requireNotNull(controllerInstance) { "ChargeWattUpdater.init must be called first" }

    fun setExperimentalEnabled(enabled: Boolean) {
        if (experimentalEnabled.value == enabled) return
        controller.cancel()
        prefs().edit().putBoolean("experimentalEnabled", enabled).apply()
        experimentalEnabled.value = enabled
        controllerInstance = createController()
        controller.check(manual = true)
    }

    fun onResume(activity: Activity) {
        controller.onHostResume(activity, installGuard)
    }
}
