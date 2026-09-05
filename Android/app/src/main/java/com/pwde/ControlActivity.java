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

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Control module: gestures, input mode, cursor speed and joystick settings. */
public class ControlActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_control);
    getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (getSupportActionBar() != null) {
      getSupportActionBar().hide();
    }

    findViewById(R.id.backButton).setOnClickListener(v -> finish());

    setupRow(
        R.id.bindingRow,
        R.drawable.baseline_settings_24,
        R.color.esports_accent,
        "Set up gestures",
        "Select which facial gesture you want to use for each action",
        v -> startActivity(new Intent(this, CursorBinding.class)));

    setupRow(
        R.id.inputModeRow,
        R.drawable.baseline_accessibility_new_24,
        R.color.esports_accent_2,
        "Input mode",
        "How head movement controls your game",
        v -> showInputModeDialog());

    setupRow(
        R.id.speedRow,
        R.drawable.baseline_mouse_24,
        R.color.esports_accent,
        "Adjust cursor speed",
        "Your cursor will follow your head movement",
        v -> startActivity(new Intent(this, CursorSpeed.class)));

    setupRow(
        R.id.joystickSettingsRow,
        R.drawable.baseline_open_with_24,
        R.color.esports_accent_2,
        "Joystick settings",
        "Tune the head-tilt joystick (center, size, dead zone)",
        v -> startActivity(new Intent(this, JoystickConfigActivity.class)));
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshInputModeText();
  }

  // TODO: Merge with MainActivity.showInputModeDialog once dialogs are shared.
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

  private void refreshInputModeText() {
    InputModeConfig.InputMode mode = InputModeConfig.getInputMode(this);
    View row = findViewById(R.id.inputModeRow);
    if (row != null) {
      TextView value = row.findViewById(R.id.settingValue);
      value.setText(InputModeConfig.getDisplayName(mode));
      value.setVisibility(View.VISIBLE);
    }
  }

  /** Wire a shared setting row: icon, title, description and click action. */
  private void setupRow(int rowId, int iconRes, int tintColor, String title, String description,
      View.OnClickListener onClick) {
    View row = findViewById(rowId);
    row.setOnClickListener(onClick);
    ImageView icon = row.findViewById(R.id.settingIcon);
    icon.setImageResource(iconRes);
    icon.setColorFilter(ContextCompat.getColor(this, tintColor));
    ((TextView) row.findViewById(R.id.settingTitle)).setText(title);
    ((TextView) row.findViewById(R.id.settingDescription)).setText(description);
  }
}
