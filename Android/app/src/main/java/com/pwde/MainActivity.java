/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pwde;


import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.splashscreen.SplashScreen;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final int MIC_PERMISSION_CODE = 201;
    private static final String KEY_FIRST_RUN = "PWDeFirstRun";

    private final String TAG = "MainActivity";

    private Intent cursorServiceIntent;

    private SharedPreferences preferences;
    private ProfileManager profileManager;
    private boolean isServiceBound = false;
    private boolean keep = true;



    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // Handle the splash screen transition.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);


        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences("PWDeLocalConfig", Context.MODE_PRIVATE);
        profileManager = new ProfileManager(this);
        try {
            TextView versionNumber = findViewById(R.id.versionNumber);
            String versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0 ).versionName;
            versionNumber.setText(versionName);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }



        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }


        findViewById(R.id.controlModuleCard).setOnClickListener(v -> {
            Intent intent = new Intent(this, ControlActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.voiceModuleCard).setOnClickListener(v -> {
            Intent intent = new Intent(this, VoiceActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.helpModuleCard).setOnClickListener(v -> {
            Intent intent = new Intent(this, HelpActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.testStationModuleCard).setOnClickListener(v -> {
            Intent intent = new Intent(this, TestStationActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.helpButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);
        });

        refreshProfileNameText();
        findViewById(R.id.profileRow).setOnClickListener(v -> showProfileDialog());
        findViewById(R.id.inputModeRow).setOnClickListener(v -> showInputModeDialog());


        Switch pwdeToggleSwitch = findViewById(R.id.pwdeToggleSwitch);


        //Check if service is enabled.
        checkIfServiceEnabled();

        // Receive service state message and force toggle the switch accordingly
        BroadcastReceiver toggleStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "toggleStateReceiver onReceive");
                if (intent.getAction().equals("SERVICE_STATE")) {
                    int stateIndex = intent.getIntExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
                    switch (CursorAccessibilityService.ServiceState.values()[stateIndex]) {
                        case ENABLE:
                            pwdeToggleSwitch.setChecked(true);
                        case PAUSE:
                            pwdeToggleSwitch.setChecked(true);
                        case GLOBAL_STICK:
                            pwdeToggleSwitch.setChecked(true);
                            break;
                        case DISABLE:
                            pwdeToggleSwitch.setChecked(false);
                            break;
                    }

                }
            }

        };
        registerReceiver(toggleStateReceiver, new IntentFilter("SERVICE_STATE"), RECEIVER_EXPORTED);


        // Toggle switch interaction.
        pwdeToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(!checkAccessibilityPermission()){
                pwdeToggleSwitch.setChecked(false);
                CameraDialog();
            }
            else if(isChecked){
                wakeUpService();
            }
            else {
                sleepCursorService();
            }

        });


        if(isFirstLaunch()){
            // Assign some default binding so user can navigate around.
            Log.i(TAG, "First launch, assign default binding");
            BlendshapeEventTriggerConfig.writeBindingConfig(this, BlendshapeEventTriggerConfig.Blendshape.OPEN_MOUTH,
                    BlendshapeEventTriggerConfig.EventType.CURSOR_TOUCH, 20);
            BlendshapeEventTriggerConfig.writeBindingConfig(this, BlendshapeEventTriggerConfig.Blendshape.MOUTH_LEFT,
                    BlendshapeEventTriggerConfig.EventType.DRAG_TOGGLE, 20);
            BlendshapeEventTriggerConfig.writeBindingConfig(this, BlendshapeEventTriggerConfig.Blendshape.MOUTH_RIGHT,
                    BlendshapeEventTriggerConfig.EventType.CURSOR_RESET, 20);
            preferences.edit().putBoolean(KEY_FIRST_RUN, false).apply();

            // Goto tutorial page.
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);




        }

    }



    private void refreshProfileNameText() {
        ProfileManager.Profile active = profileManager.getActiveProfile();
        TextView nameText = findViewById(R.id.profileNameText);
        if (nameText != null) {
            nameText.setText(active != null ? active.name : "Default");
        }
    }

    /** Show the profile switcher / management dialog. */
    private void showProfileDialog() {
        List<ProfileManager.Profile> profiles = profileManager.getProfiles();
        ProfileManager.Profile active = profileManager.getActiveProfile();
        CharSequence[] names = new CharSequence[profiles.size()];
        int activeIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            names[i] = profiles.get(i).name;
            if (active != null && profiles.get(i).id.equals(active.id)) {
                activeIndex = i;
            }
        }
        if (profiles.isEmpty()) {
            onNewProfile();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Choose profile")
            .setSingleChoiceItems(names, activeIndex, (dialog, which) -> {
                ProfileManager.Profile selected = profiles.get(which);
                if (active == null || !selected.id.equals(active.id)) {
                    profileManager.setActiveProfile(selected.id);
                    onActiveProfileChanged();
                }
                dialog.dismiss();
            })
            .setPositiveButton("New", (d, w) -> onNewProfile())
            .setNeutralButton("Rename", (d, w) -> onRenameProfile())
            .setNegativeButton("Delete", (d, w) -> onDeleteProfile())
            .show();
    }

    private void onNewProfile() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText("Profile " + (profileManager.getProfiles().size() + 1));
        new AlertDialog.Builder(this)
            .setTitle("New profile")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String id = profileManager.createProfile(input.getText().toString());
                profileManager.setActiveProfile(id);
                onActiveProfileChanged();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void onRenameProfile() {
        ProfileManager.Profile active = profileManager.getActiveProfile();
        if (active == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(active.name);
        input.setSelection(active.name.length());
        new AlertDialog.Builder(this)
            .setTitle("Rename profile")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                profileManager.renameProfile(active.id, input.getText().toString());
                onActiveProfileChanged();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void onDeleteProfile() {
        ProfileManager.Profile active = profileManager.getActiveProfile();
        if (active == null) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage("Delete \"" + active.name + "\"?")
            .setPositiveButton("Delete", (d, w) -> {
                profileManager.deleteProfile(active.id);
                onActiveProfileChanged();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Refresh the UI and tell the accessibility service to reload for the active profile. */
    private void onActiveProfileChanged() {
        refreshProfileNameText();
        refreshInputModeText();
        sendBroadcast(new Intent("LOAD_PROFILE"));
    }

    private void refreshInputModeText() {
        InputModeConfig.InputMode mode = InputModeConfig.getInputMode(this);
        ((TextView) findViewById(R.id.inputModeValue)).setText(InputModeConfig.getDisplayName(mode));
    }

    private void showInputModeDialog() {
        InputModeConfig.InputMode[] modes = InputModeConfig.InputMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = InputModeConfig.getDisplayName(modes[i]);
        }
        int checked = InputModeConfig.getInputMode(this).ordinal();
        new AlertDialog.Builder(this)
                .setTitle("Input mode")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    InputModeConfig.setInputMode(this, modes[which]);
                    refreshInputModeText();
                    sendBroadcast(new Intent("LOAD_PROFILE"));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setupUi(){


    }

    /**Send broadcast to service request service enable state
     * Service should send back its state via SERVICE_STATE message*/
    public void checkIfServiceEnabled() {
        // send broadcast to service to check its state.
        Intent intent = new Intent("REQUEST_SERVICE_STATE");
        intent.putExtra("state", "main");
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfileNameText();
        refreshInputModeText();
        if(!isFirstLaunch()){
            CameraDialog();
        }

        checkIfServiceEnabled();

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && checkCameraPermission()) {
            // Continue the enable flow once the camera is granted.
            wakeUpService();
        } else if (requestCode == MIC_PERMISSION_CODE) {
            // Continue even when denied: main controls still work, voice stays disabled.
            wakeUpService();
        }
    }

    private void CameraDialog() {
        // Check Camera Permission
        if(!checkCameraPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Allow PWDe to access \nthe camera?";
            builder.setTitle("Access Camera");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestCameraPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantCamera");
                startActivity(intent);
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        } else {
            AccessibilityDialog();
        }
    }

    public void AccessibilityDialog(){
        // Check Accessibility Permission
        if(!checkAccessibilityPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Full control is appropriate for apps \nthat help you with accessibility \nneeds, but not for most apps.";
            builder.setTitle("Allow PWDe to have full control of your device?");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestAccessibilityPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantAccessibility");
                startActivity(intent);
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        }
    }

    /**
     * Check the local preferences if this is the first time user launch the app.
     * @return boolean flag
     */
    private boolean isFirstLaunch() {
        return preferences.getBoolean(KEY_FIRST_RUN , true);
    }



    public void wakeUpService(){
        Log.i(TAG, "MainActivity wakeUpService");
        findViewById(R.id.pwdeToggleSwitch).setEnabled(false);
        if (!checkAccessibilityPermission()){
            Log.i(TAG, "MainActivity RequestAccessibilityPermission");
            RequestAccessibilityPermission();
            return;
        }
        if (!checkCameraPermission()){
            Log.i(TAG, "MainActivity RequestCameraPermission");
            RequestCameraPermission();
            return;
        }
        if (VoiceCommandConfig.load(this).isEnabled() && !checkMicPermission()){
            Log.i(TAG, "MainActivity RequestMicrophonePermission (voice enabled)");
            MicDialog();
            return;
        }


        // Run onStartCommand in service, currently doing nothing.
        cursorServiceIntent = new Intent(this, CursorAccessibilityService.class);
        startService(cursorServiceIntent);

        // Send broadcast to wake up service.
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        intent.putExtra("state", CursorAccessibilityService.ServiceState.ENABLE.ordinal());
        sendBroadcast(intent);

        Intent intentFlyOut = new Intent("FLY_OUT_FLOAT_WINDOW");
        sendBroadcast(intentFlyOut);
        findViewById(R.id.pwdeToggleSwitch).setEnabled(true);
    }
    public void sleepCursorService(){
        Log.i(TAG, "sleepCursorService");
        findViewById(R.id.pwdeToggleSwitch).setEnabled(false);
        // Send broadcast to stop service (sleep mode).
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        intent.putExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
        sendBroadcast(intent);
        if (isServiceBound) {
            isServiceBound = false;
        }
        cursorServiceIntent = null;
        findViewById(R.id.pwdeToggleSwitch).setEnabled(true);

    }

    public boolean checkAccessibilityPermission() {
        int enabled = 0;
        final String pwdeServiceName = this.getPackageName()
            + "/"
            + this.getPackageName()
            + "."
            + CursorAccessibilityService.class.getSimpleName();

        Log.i(TAG, "PWDe service name: "+pwdeServiceName);

        try {
            enabled = Settings.Secure.getInt(
                    this.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            // Handle the exception
        }

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        if (enabled == 1) {
            String allAccessibilityServices = Settings.Secure.getString(
                    this.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );

            if (allAccessibilityServices != null) {
                splitter.setString(allAccessibilityServices);
                while (splitter.hasNext()) {
                    String accessibilityService = splitter.next();
                    if (accessibilityService.equalsIgnoreCase(pwdeServiceName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // Request accessibility permission using intent
    public void RequestAccessibilityPermission()
    {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }


    public boolean checkCameraPermission()
    {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }


    // Request camera permission using basic requestPermissions method
    public void RequestCameraPermission()
    {

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.CAMERA
        },CAMERA_PERMISSION_CODE);
    }

    public boolean checkMicPermission()
    {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask for the microphone permission needed by the voice controls. */
    public void MicDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String alertMsg = "Allow PWDe to use the microphone\nfor voice controls?";
        builder.setTitle("Access Microphone");
        builder.setMessage(alertMsg);
        builder.setPositiveButton("Allow", (dialog, which) -> {
            RequestMicrophonePermission();
            dialog.dismiss();
        });
        builder.setNegativeButton("Deny", (dialog, which) -> {
            dialog.cancel();
            Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
            intent.putExtra("permission", "grantMicrophone");
            startActivity(intent);
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setTextColor(getResources().getColor(R.color.blue));
            Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            negativeButton.setTextColor(getResources().getColor(R.color.blue));
        });
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.show();
        Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        positiveButton.setTransformationMethod(null);
        Button negativeButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        negativeButton.setTransformationMethod(null);
    }

    public void RequestMicrophonePermission()
    {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO
        },MIC_PERMISSION_CODE);
    }









}




