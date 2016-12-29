package de.weis.multisensor_grabber;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF;

/**
 * Created by weis on 27.12.16.
 */

public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    android.hardware.camera2.CameraManager manager;
    CameraCharacteristics characteristics = null;
    SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);

        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);


        prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());

        // initial summary setting
        findPreference("pref_focus_dist").setSummary(prefs.getString("pref_focus_dist", ""));
        findPreference("pref_dir").setSummary(prefs.getString("pref_dir", ""));

        findPreference("pref_dir").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri selected = Uri.parse(prefs.getString("pref_dir", ""));
                intent.setDataAndType(selected, "resource/folder");

                if (intent.resolveActivityInfo(getActivity().getPackageManager(), 0) != null){
                    startActivity(intent);
                } else {}
                return true;
            }
        });

        manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        try {
            characteristics = manager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        final CheckBoxPreference pref_fix_exp = (CheckBoxPreference) findPreference("pref_fix_exp");

        pref_fix_exp.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                //newValue.equals(true);
                populate_exposure_list(newValue);
                return true;
            }
        });

        final CheckBoxPreference pref_fix_foc = (CheckBoxPreference) findPreference("pref_fix_foc");
        pref_fix_foc.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                populate_focus_dist(newValue);
                return true;
            }
        });

        populate_focus_dist(null);
        populate_exposure_list(null);
        populate_resolution_list();
    }

    public void populate_focus_dist(Object val){
        CheckBoxPreference pref_fix_foc = (CheckBoxPreference) findPreference("pref_fix_foc");
        final Preference fp = (Preference) findPreference("pref_focus_dist");

        boolean isChecked;

        if(val == null){
            isChecked = pref_fix_foc.isChecked();
        }else{
            isChecked = val.equals(true);
        }

        if (isChecked){
            fp.setEnabled(true);
        }else{
            fp.setEnabled(false);
        }
    }

    public void populate_exposure_list(Object val){

        CheckBoxPreference pref_fix_exp = (CheckBoxPreference) findPreference("pref_fix_exp");
        final Preference ep = (Preference) findPreference("pref_exposure");

        boolean isChecked;

        if(val == null){
            isChecked = pref_fix_exp.isChecked();
        }else{
            isChecked = val.equals(true);
        }
        if (isChecked) {
            boolean ae_off_supported = false;
            for (Integer mykey : characteristics.get(CONTROL_AE_AVAILABLE_MODES)) {
                if (mykey == CONTROL_AE_MODE_OFF) {
                    ae_off_supported = true;
                }
            }

            if (ae_off_supported) {
                ep.setEnabled(true);
                ep.setSummary("Enabled");
                // set default value of exposure time
                findPreference("pref_exposure").setSummary(prefs.getString("pref_exposure", ""));
            } else {
                ep.setEnabled(false);
                ep.setSummary("Not supported by device");
            }
        }else {
            ep.setEnabled(false);
            ep.setSummary("Fixed exposure not enabled");
        }
    }

    public void populate_resolution_list() {

        Size[] sizes = null;

        if (characteristics != null) {
            sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
        }

        // List dialog to select resolution
        List<String> itemslist = new ArrayList<String>();
        List<String> valueslist = new ArrayList<String>();

        int cnt = 0;
        for (Size size : sizes) {
            itemslist.add(size.getWidth() + "x" + size.getHeight());
            valueslist.add("" + cnt);
            cnt += 1;
        }
        final CharSequence[] entries = itemslist.toArray(new CharSequence[itemslist.size()]);
        final CharSequence[] values = valueslist.toArray(new CharSequence[valueslist.size()]);

        final ListPreference lp = (ListPreference) findPreference("pref_resolutions");
        lp.setEntries(entries);
        lp.setDefaultValue("0");
        lp.setEntryValues(values);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updatePreference(findPreference(key), key);
    }

    private void updatePreference(Preference preference, String key) {
        if (preference == null) return;
        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            listPreference.setSummary(listPreference.getEntry());
            return;
        }
        if (preference instanceof CheckBoxPreference){
            return;
        }
        SharedPreferences sharedPrefs = getPreferenceManager().getSharedPreferences();
        preference.setSummary(sharedPrefs.getString(key, "Default"));
    }
}
