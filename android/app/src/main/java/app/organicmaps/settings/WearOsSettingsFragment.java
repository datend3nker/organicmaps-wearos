package app.organicmaps.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import app.organicmaps.sdk.Framework;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class WearOsSettingsFragment extends BaseXmlSettingsFragment {
    private boolean isUpdatingFromSync = false;

    private final android.content.BroadcastReceiver mSettingsChangedReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            refreshUi();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mChangeListener = (sharedPreferences, key) -> {
        if (key == null || isUpdatingFromSync) return;
        if (key.startsWith("pref_wear_os_")) {
            syncWearOsPreferences();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(mChangeListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
        android.content.IntentFilter filter = new android.content.IntentFilter("app.organicmaps.wear.SETTINGS_CHANGED");
        androidx.core.content.ContextCompat.registerReceiver(requireContext(), mSettingsChangedReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(mSettingsChangedReceiver);
    }

    private void refreshUi() {
        if (!isAdded()) return;
        isUpdatingFromSync = true;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            
            TwoStatePreference mapEnabledPref = findPreference(getString(R.string.pref_wear_os_map_enabled));
            if (mapEnabledPref != null) mapEnabledPref.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_map_enabled), false));

            TwoStatePreference watchLocalPref = findPreference(getString(R.string.pref_wear_os_watch_local_mode));
            if (watchLocalPref != null) watchLocalPref.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_watch_local_mode), false));

            TwoStatePreference standalonePref = findPreference(getString(R.string.pref_wear_os_standalone_mode));
            if (standalonePref != null) standalonePref.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_standalone_mode), false));

            ListPreference backendPref = findPreference(getString(R.string.pref_wear_os_backend));
            if (backendPref != null) backendPref.setValue(prefs.getString(getString(R.string.pref_wear_os_backend), "GMS"));

            ListPreference dlModePref = findPreference(getString(R.string.pref_wear_os_map_download_mode));
            if (dlModePref != null) dlModePref.setValue(prefs.getString(getString(R.string.pref_wear_os_map_download_mode), "PHONE_SYNC"));
            
            TwoStatePreference p3d = findPreference(getString(R.string.pref_wear_os_3d));
            if (p3d != null) p3d.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_3d), true));

            TwoStatePreference p3dBld = findPreference(getString(R.string.pref_wear_os_3d_buildings));
            if (p3dBld != null) p3dBld.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_3d_buildings), true));

            TwoStatePreference pAutoZoom = findPreference(getString(R.string.pref_wear_os_auto_zoom));
            if (pAutoZoom != null) pAutoZoom.setChecked(prefs.getBoolean(getString(R.string.pref_wear_os_auto_zoom), true));

            ListPreference stylePref = findPreference(getString(R.string.pref_wear_os_map_style));
            if (stylePref != null) stylePref.setValue(prefs.getString(getString(R.string.pref_wear_os_map_style), "default"));

            ListPreference unitsPref = findPreference(getString(R.string.pref_wear_os_munits));
            if (unitsPref != null) unitsPref.setValue(prefs.getString(getString(R.string.pref_wear_os_munits), "0"));

            String[] layers = {
                getString(R.string.pref_wear_os_transit),
                getString(R.string.pref_wear_os_biking),
                getString(R.string.pref_wear_os_hiking),
                getString(R.string.pref_wear_os_isolines)
            };
            for (String key : layers) {
                TwoStatePreference p = findPreference(key);
                if (p != null) p.setChecked(prefs.getBoolean(key, false));
            }

            String[] avoids = {
                getString(R.string.pref_wear_os_avoid_tolls),
                getString(R.string.pref_wear_os_avoid_motorways),
                getString(R.string.pref_wear_os_avoid_ferries),
                getString(R.string.pref_wear_os_avoid_unpaved)
            };
            for (String key : avoids) {
                TwoStatePreference p = findPreference(key);
                if (p != null) p.setChecked(prefs.getBoolean(key, false));
            }
        } finally {
            isUpdatingFromSync = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(mChangeListener);
    }

    @Override
    protected int getXmlResources() {
        return R.xml.prefs_wear_os;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initBackendPref();
        initMapLayers();
        initAvoidances();
        initDownloadMode();
        initGeneral();
    }

    private void initBackendPref() {
        final Preference backendPref = findPreference(getString(R.string.pref_wear_os_backend));
        if (backendPref != null) {
            backendPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String nextBackend = (String) newValue;
                
                // 1. Tell the watch to switch too using the OLD sync layer before we kill it
                try {
                    app.organicmaps.wear.WearSyncService.getSyncLayer().sendBackendSwitch(requireContext(), nextBackend);
                } catch (Exception e) {
                    android.util.Log.e("WearOsSettings", "Failed to send switch command", e);
                }

                // 2. Actually update local preference
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putString(getString(R.string.pref_wear_os_backend), nextBackend).apply();

                // 3. Re-init local sync layer
                app.organicmaps.wear.WearSyncService.initSyncLayer(requireContext());

                // 4. Manage Bluetooth service lifecycle
                Intent bluetoothService = new Intent(requireContext(), app.organicmaps.wear.BluetoothMessageListenerService.class);
                if ("BLUETOOTH".equals(nextBackend)) {
                    if (checkBluetoothPermissions()) {
                        requireContext().startService(bluetoothService);
                    }
                } else {
                    requireContext().stopService(bluetoothService);
                }
                return true;
            });
        }
    }

    private boolean checkBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            String[] permissions = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            ArrayList<String> missing = new ArrayList<>();
            for (String p : permissions) {
                if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(p);
                }
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), 100);
                return false;
            }
        }
        return true;
    }

    private void initMapLayers() {
        String[] keys = {
            getString(R.string.pref_wear_os_transit),
            getString(R.string.pref_wear_os_biking),
            getString(R.string.pref_wear_os_hiking),
            getString(R.string.pref_wear_os_isolines),
            getString(R.string.pref_wear_os_3d),
            getString(R.string.pref_wear_os_3d_buildings),
            getString(R.string.pref_wear_os_auto_zoom)
        };
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // Just return true to allow the preference change, 
                    // the mChangeListener will trigger syncWearOsPreferences()
                    return true;
                });
            }
        }
    }

    private void initAvoidances() {
        String[] keys = {
            getString(R.string.pref_wear_os_avoid_tolls),
            getString(R.string.pref_wear_os_avoid_motorways),
            getString(R.string.pref_wear_os_avoid_ferries),
            getString(R.string.pref_wear_os_avoid_unpaved)
        };
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    return true;
                });
            }
        }
    }

    private void initGeneral() {
        String[] keys = {
            getString(R.string.pref_wear_os_map_style),
            getString(R.string.pref_wear_os_munits)
        };
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    return true;
                });
            }
        }
    }

    private void initDownloadMode() {
        Preference pref = findPreference(getString(R.string.pref_wear_os_map_download_mode));
        if (pref instanceof ListPreference) {
            ListPreference lp = (ListPreference) pref;
            lp.setSummary(lp.getEntry());
            lp.setOnPreferenceChangeListener((p, newValue) -> {
                lp.setValue((String) newValue);
                p.setSummary(lp.getEntry());
                return true;
            });
        }
    }

    private void syncWearOsPreferences() {
        try {
            app.organicmaps.wear.WearSyncService.syncPreferences(requireContext());
        } catch (Throwable e) {
            android.util.Log.e("WearOsSettings", "Sync failed", e);
        }
    }
}
