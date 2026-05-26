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

public class WearOsSettingsFragment extends BaseXmlSettingsFragment {
    private final SharedPreferences.OnSharedPreferenceChangeListener mChangeListener = (sharedPreferences, key) -> {
        if (key == null) return;
        if (key.startsWith("pref_wear_os_") || 
            key.equals("transit_enabled") || 
            key.equals("biking_enabled") || 
            key.equals("hiking_enabled") || 
            key.equals("isolines_enabled") ||
            key.equals("avoid_tolls") || 
            key.equals("avoid_motorways")) {
            syncWearOsPreferences();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(mChangeListener);
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
    }

    private void initBackendPref() {
        final Preference backendPref = findPreference(getString(R.string.pref_wear_os_backend));
        if (backendPref != null) {
            backendPref.setOnPreferenceChangeListener((preference, newValue) -> {
                app.organicmaps.wear.WearSyncService.initSyncLayer(requireContext());
                Intent bluetoothService = new Intent(requireContext(), app.organicmaps.wear.BluetoothMessageListenerService.class);
                if ("BLUETOOTH".equals(newValue)) {
                    requireContext().startService(bluetoothService);
                } else {
                    requireContext().stopService(bluetoothService);
                }
                return true;
            });
        }
    }

    private void initMapLayers() {
        String[] keys = {"transit_enabled", "biking_enabled", "hiking_enabled", "isolines_enabled"};
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (boolean) newValue;
                    if ("transit_enabled".equals(key)) Framework.nativeSetTransitSchemeEnabled(enabled);
                    else if ("biking_enabled".equals(key)) Framework.nativeSetCyclingLayerEnabled(enabled);
                    else if ("hiking_enabled".equals(key)) Framework.nativeSetHikingLayerEnabled(enabled);
                    else if ("isolines_enabled".equals(key)) Framework.nativeSetIsolinesLayerEnabled(enabled);
                    return true;
                });
            }
        }
    }

    private void initAvoidances() {
        String[] keys = {"avoid_tolls", "avoid_motorways"};
        for (String key : keys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    RoadType type = "avoid_tolls".equals(key) ? RoadType.Toll : RoadType.Motorway;
                    if ((boolean) newValue) RoutingOptions.addOption(type);
                    else RoutingOptions.removeOption(type);
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
