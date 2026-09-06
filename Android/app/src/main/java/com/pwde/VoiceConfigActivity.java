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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-profile voice control configuration: enable/disable on-device recognition, set the spoken
 * phrases used to switch profiles/input modes, and open the visual editor that places the ML skill
 * buttons ("1"/"2"/"3") tap points on the screen.
 */
public final class VoiceConfigActivity extends AppCompatActivity {

  private static final int MIC_PERMISSION_CODE = 301;
  private static final int SECTION_MARGIN_TOP_DP = 24;

  private VoiceCommandConfig config;
  private Switch enableSwitch;
  private Switch containsSwitch;
  private Switch quickFireSwitch;

  /** profile id -> phrase EditText. */
  private final Map<String, EditText> profilePhraseFields = new HashMap<>();
  private final Map<VoiceCommandConfig.Action, EditText> modePhraseFields = new HashMap<>();
  private EditText editPositionsPhraseField;

  /** skill index -> EditText holding the skill's words (comma-separated). */
  private final List<EditText> skillPhraseFields = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    config = VoiceCommandConfig.load(this);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(48, 48, 48, 48);
    root.setBackgroundColor(getColor(R.color.esports_bg));

    TextView title = new TextView(this);
    title.setText("Voice control");
    title.setTextSize(22);
    title.setTextColor(getColor(R.color.esports_text));
    root.addView(title);

    TextView description = new TextView(this);
    description.setText(
        "Bind words to each action below: say a skill's word to tap its button, "
            + "\"up/down/left/right\" to steer, and a profile/mode phrase to switch setups. "
            + "Requires mic permission and the on-device language pack.");
    description.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(description);

    enableSwitch = new Switch(this);
    enableSwitch.setText("Enable voice control");
    enableSwitch.setChecked(config.isEnabled());
    root.addView(enableSwitch);
    enableSwitch.setOnCheckedChangeListener(
        (CompoundButton buttonView, boolean isChecked) -> {
          config.setEnabled(isChecked);
          if (isChecked && !checkMicPermission()) {
            requestMicPermission();
          }
        });

    addSectionTitle(root, "Recognition");
    TextView recognitionHint = new TextView(this);
    recognitionHint.setText(
        "By default a word triggers its action even when it is part of a longer phrase (\"skill "
            + "one\" or \"one one\" both cast one), and the action fires the moment the word is "
            + "heard instead of after you finish speaking.");
    recognitionHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(recognitionHint);

    containsSwitch = new Switch(this);
    containsSwitch.setText("Match the word anywhere in a phrase");
    containsSwitch.setChecked(config.isContainsMatchEnabled());
    root.addView(containsSwitch);
    TextView containsHint = new TextView(this);
    containsHint.setText(
        "On: \"skill one\", \"one one\" and \"cast one now\" all cast one (whole words only — "
            + "\"alone\" does not). Off: the phrase must equal what you said exactly.");
    containsHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(containsHint);
    containsSwitch.setOnCheckedChangeListener(
        (CompoundButton buttonView, boolean isChecked) ->
            config.setContainsMatchEnabled(isChecked));

    quickFireSwitch = new Switch(this);
    quickFireSwitch.setText("Cast the moment the word is heard");
    quickFireSwitch.setChecked(config.isQuickFireEnabled());
    root.addView(quickFireSwitch);
    TextView quickFireHint = new TextView(this);
    quickFireHint.setText(
        "On: casting starts while you are still speaking. Off: it waits for the recognizer to "
            + "finalize the whole utterance, which is slower but never fires on a guess that "
            + "changes later.");
    quickFireHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(quickFireHint);
    quickFireSwitch.setOnCheckedChangeListener(
        (CompoundButton buttonView, boolean isChecked) -> config.setQuickFireEnabled(isChecked));

    addSectionTitle(root, "Spoken phrase to switch profiles");
    ProfileManager profileManager = new ProfileManager(this);
    for (ProfileManager.Profile profile : profileManager.getProfiles()) {
      TextView profileLabel = new TextView(this);
      profileLabel.setText("\"" + profile.name + "\"");
      profileLabel.setTextColor(getColor(R.color.esports_text));
      root.addView(profileLabel);

      EditText phrase = new EditText(this);
      phrase.setSingleLine(true);
      phrase.setHint("e.g. profile one");
      phrase.setText(config.getProfileSwitchPhrase(profile.id));
      profilePhraseFields.put(profile.id, phrase);
      root.addView(phrase);
    }

    addSectionTitle(root, "Spoken phrase to switch input mode");
    for (VoiceCommandConfig.Action action :
        new VoiceCommandConfig.Action[] {
          VoiceCommandConfig.Action.SWITCH_MODE_CURSOR,
          VoiceCommandConfig.Action.SWITCH_MODE_JOYSTICK
        }) {
      TextView modeLabel = new TextView(this);
      modeLabel.setText(InputModeConfig.getDisplayName(modeToInputMode(action)) + " mode");
      modeLabel.setTextColor(getColor(R.color.esports_text));
      root.addView(modeLabel);

      EditText phrase = new EditText(this);
      phrase.setSingleLine(true);
      phrase.setHint("e.g. " + config.getModeSwitchPhrase(action));
      phrase.setText(config.getModeSwitchPhrase(action));
      modePhraseFields.put(action, phrase);
      root.addView(phrase);
    }

    addSectionTitle(root, "Hands-free marker placement");
    TextView editPositionsHint = new TextView(this);
    editPositionsHint.setText(
        "While voice control is active in a game, say this keyword to toggle \"edit positions\" "
            + "mode on and off. When it is on, head controls pause, the skill markers and the "
            + "joystick base become draggable on the screen, and touches only move those markers "
            + "(they never reach the game). Drag them over the real buttons, then say the keyword "
            + "again to save the new positions and resume control.");
    editPositionsHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(editPositionsHint);

    editPositionsPhraseField = new EditText(this);
    editPositionsPhraseField.setSingleLine(true);
    editPositionsPhraseField.setHint(VoiceCommandConfig.DEFAULT_EDIT_POSITIONS_PHRASE);
    editPositionsPhraseField.setText(config.getEditPositionsPhrase());
    root.addView(editPositionsPhraseField);

    addSectionTitle(root, "Words that cast each skill");
    TextView skillWordsHint = new TextView(this);
    skillWordsHint.setText(
        "Type the word(s) you will say to cast each skill, separated by commas "
            + "(e.g. \"1, one\"). How the words are matched (exact or anywhere in a phrase) and "
            + "how soon they fire is set under \"Recognition\" above.");
    skillWordsHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(skillWordsHint);

    for (int i = 0; i < VoiceCommandConfig.SKILL_COUNT; i++) {
      TextView skillLabel = new TextView(this);
      skillLabel.setText(ScreenPlacementConfig.SKILL_LABELS[i]);
      skillLabel.setTextColor(getColor(R.color.esports_text));
      root.addView(skillLabel);

      EditText words = new EditText(this);
      words.setSingleLine(true);
      words.setHint("e.g. " + VoiceCommandConfig.DEFAULT_SKILL_PHRASES[i]);
      words.setText(TextUtils.join(", ", config.getSkillPhrases(i)));
      skillPhraseFields.add(words);
      root.addView(words);
    }

    addSectionTitle(root, "Where the skill buttons sit");
    TextView skillHint = new TextView(this);
    skillHint.setText(
        "Mark where each skill's button sits in your game (which word taps which button "
            + "is set above) on a screen preview instead of typing coordinates.");
    skillHint.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(skillHint);

    Button positionSkills = new Button(this);
    positionSkills.setText("Position skills on screen\u2026");
    positionSkills.setOnClickListener(
        v -> {
          Intent intent = new Intent(this, PlacementEditorActivity.class);
          intent.putExtra(
              PlacementEditorActivity.EXTRA_MODE, PlacementEditorActivity.MODE_VOICE_SKILLS);
          startActivity(intent);
        });
    root.addView(positionSkills);

    Button save = new Button(this);
    save.setText("Save");
    save.setOnClickListener(
        v -> {
          if (!collectPhrases()) {
            return;
          }
          config.save(this);
          Toast.makeText(this, "Voice settings saved.", Toast.LENGTH_SHORT).show();
          sendBroadcast(new Intent("LOAD_PROFILE"));
          finish();
        });
    root.addView(save);

    // Many rows (per-profile and per-mode phrase fields); scroll container so nothing is
    // clipped on short landscape screens.
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    setContentView(scroll);
  }

  private void addSectionTitle(LinearLayout root, String text) {
    TextView label = new TextView(this);
    label.setText(text);
    label.setTextSize(16);
    label.setTextColor(getColor(R.color.esports_text));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(SECTION_MARGIN_TOP_DP);
    root.addView(label, params);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  /**
   * Apply the skill/profile/mode words to the config, rejecting duplicate or reserved spoken
   * words (two commands can never share a phrase, and the joystick words are fixed).
   */
  private boolean collectPhrases() {
    // Joystick steering words are not editable on this screen, so they are reserved.
    Map<String, Boolean> used = new HashMap<>();
    for (VoiceCommandConfig.Command command : config.getCommands()) {
      if (!command.phrase.isEmpty() && VoiceCommandConfig.isJoystickAction(command.action)) {
        used.put(command.phrase, Boolean.TRUE);
      }
    }

    List<String> newPhrases = new ArrayList<>();
    for (EditText field : skillPhraseFields) {
      newPhrases.addAll(VoiceCommandConfig.parsePhraseList(field.getText().toString()));
    }
    newPhrases.addAll(textOf(profilePhraseFields.values()));
    for (EditText field : modePhraseFields.values()) {
      newPhrases.add(field.getText().toString());
    }
    newPhrases.add(editPositionsPhraseField.getText().toString());
    for (String phrase : newPhrases) {
      String normalized = VoiceCommandConfig.normalize(phrase);
      if (normalized.isEmpty()) {
        continue;
      }
      if (used.put(normalized, Boolean.TRUE) != null) {
        Toast.makeText(
                this,
                "Duplicate spoken word \"" + normalized
                    + "\". Each command needs a different word.",
                Toast.LENGTH_SHORT)
            .show();
        return false;
      }
    }

    for (int i = 0; i < skillPhraseFields.size(); i++) {
      config.setSkillPhrases(
          i, VoiceCommandConfig.parsePhraseList(skillPhraseFields.get(i).getText().toString()));
    }
    for (Map.Entry<String, EditText> entry : profilePhraseFields.entrySet()) {
      config.setProfileSwitchPhrase(entry.getKey(), entry.getValue().getText().toString());
    }
    for (Map.Entry<VoiceCommandConfig.Action, EditText> entry : modePhraseFields.entrySet()) {
      config.setModeSwitchPhrase(entry.getKey(), entry.getValue().getText().toString());
    }
    config.setEditPositionsPhrase(editPositionsPhraseField.getText().toString());
    return true;
  }

  private List<String> textOf(Iterable<EditText> fields) {
    List<String> result = new ArrayList<>();
    for (EditText field : fields) {
      result.add(field.getText().toString());
    }
    return result;
  }

  private static InputModeConfig.InputMode modeToInputMode(VoiceCommandConfig.Action action) {
    return action == VoiceCommandConfig.Action.SWITCH_MODE_JOYSTICK
        ? InputModeConfig.InputMode.JOYSTICK
        : InputModeConfig.InputMode.CURSOR;
  }

  private boolean checkMicPermission() {
    return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestMicPermission() {
    ActivityCompat.requestPermissions(
        this, new String[] {Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == MIC_PERMISSION_CODE && !checkMicPermission()) {
      Toast.makeText(
              this,
              "Voice control needs the microphone permission. You can grant it in Settings.",
              Toast.LENGTH_SHORT)
          .show();
    }
  }
}
