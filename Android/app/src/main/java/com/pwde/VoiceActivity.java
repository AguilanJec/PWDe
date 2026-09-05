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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Voice module: voice command setup. */
public class VoiceActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_voice);
    getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (getSupportActionBar() != null) {
      getSupportActionBar().hide();
    }

    findViewById(R.id.backButton).setOnClickListener(v -> finish());

    setupRow(
        R.id.voiceSettingsRow,
        R.drawable.ic_mic_24,
        R.color.esports_accent_2,
        "Voice control",
        "Say commands to cast skills and steer",
        v -> startActivity(new Intent(this, VoiceConfigActivity.class)));
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
