package app.organicmaps.settings

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import app.organicmaps.R
import app.organicmaps.wear.BluetoothMessageListenerService
import app.organicmaps.wear.WearSyncService

class WearOsSettingsFragment : BaseXmlSettingsFragment() {
    private var isUpdatingFromSync = false

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUi()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) {
            startBluetoothService()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        val filter = IntentFilter("app.organicmaps.wear.SETTINGS_CHANGED")
        ContextCompat.registerReceiver(
            requireContext(),
            settingsChangedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(settingsChangedReceiver)
    }

    override fun getXmlResources() = R.xml.prefs_wear_os

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initBackendPref()
        initDownloadMode()
        initPreferences()
    }

    private fun refreshUi() {
        if (!isAdded) return
        isUpdatingFromSync = true
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            fun updateTwoState(resId: Int, default: Boolean = false) {
                val key = getString(resId)
                findPreference<TwoStatePreference>(key)?.isChecked = prefs.getBoolean(key, default)
            }

            fun updateList(resId: Int, default: String) {
                val key = getString(resId)
                findPreference<ListPreference>(key)?.value = prefs.getString(key, default)
            }

            updateTwoState(R.string.pref_wear_os_map_enabled)
            updateTwoState(R.string.pref_wear_os_watch_local_mode)
            updateTwoState(R.string.pref_wear_os_standalone_mode)
            updateList(R.string.pref_wear_os_backend, "GMS")
            updateList(R.string.pref_wear_os_map_download_mode, "PHONE_SYNC")
            updateTwoState(R.string.pref_wear_os_3d, default = true)
            updateTwoState(R.string.pref_wear_os_3d_buildings, default = true)
            updateTwoState(R.string.pref_wear_os_auto_zoom, default = true)
            updateList(R.string.pref_wear_os_map_style, "default")
            updateList(R.string.pref_wear_os_munits, "0")

            listOf(
                R.string.pref_wear_os_transit,
                R.string.pref_wear_os_biking,
                R.string.pref_wear_os_hiking,
                R.string.pref_wear_os_isolines,
                R.string.pref_wear_os_avoid_tolls,
                R.string.pref_wear_os_avoid_motorways,
                R.string.pref_wear_os_avoid_ferries,
                R.string.pref_wear_os_avoid_unpaved
            ).forEach { updateTwoState(it) }

        } finally {
            isUpdatingFromSync = false
        }
    }

    private fun initBackendPref() {
        findPreference<Preference>(getString(R.string.pref_wear_os_backend))?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val nextBackend = newValue as String

                try {
                    WearSyncService.getSyncLayer().sendBackendSwitch(requireContext(), nextBackend)
                } catch (e: Exception) {
                    Log.e("WearOsSettings", "Failed to send switch command", e)
                }

                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                    putString(getString(R.string.pref_wear_os_backend), nextBackend)
                }

                WearSyncService.initSyncLayer(requireContext())

                if (nextBackend == "BLUETOOTH") {
                    if (checkBluetoothPermissions()) {
                        startBluetoothService()
                    }
                } else {
                    stopBluetoothService()
                }
                true
            }
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(missing.toTypedArray())
                return false
            }
        }
        return true
    }

    private fun startBluetoothService() {
        val bluetoothService = Intent(requireContext(), BluetoothMessageListenerService::class.java)
        requireContext().startService(bluetoothService)
    }

    private fun stopBluetoothService() {
        val bluetoothService = Intent(requireContext(), BluetoothMessageListenerService::class.java)
        requireContext().stopService(bluetoothService)
    }

    private fun initPreferences() {
        val keys = listOf(
            R.string.pref_wear_os_transit,
            R.string.pref_wear_os_biking,
            R.string.pref_wear_os_hiking,
            R.string.pref_wear_os_isolines,
            R.string.pref_wear_os_3d,
            R.string.pref_wear_os_3d_buildings,
            R.string.pref_wear_os_auto_zoom,
            R.string.pref_wear_os_avoid_tolls,
            R.string.pref_wear_os_avoid_motorways,
            R.string.pref_wear_os_avoid_ferries,
            R.string.pref_wear_os_avoid_unpaved,
            R.string.pref_wear_os_map_style,
            R.string.pref_wear_os_munits
        )
        keys.forEach { resId ->
            findPreference<Preference>(getString(resId))?.setOnPreferenceChangeListener { _, _ -> true }
        }
    }

    private fun initDownloadMode() {
        (findPreference<Preference>(getString(R.string.pref_wear_os_map_download_mode)) as? ListPreference)?.apply {
            summary = entry
            setOnPreferenceChangeListener { _, newValue ->
                val index = findIndexOfValue(newValue as String)
                if (index >= 0) summary = entries[index]
                true
            }
        }
    }
}
