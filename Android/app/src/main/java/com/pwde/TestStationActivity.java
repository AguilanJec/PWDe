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
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.pwde.CursorAccessibilityService.ServiceState;
import com.pwde.BlendshapeEventTriggerConfig.BlendshapeAndThreshold;
import com.pwde.BlendshapeEventTriggerConfig.EventType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-app testing station: live diagnostics for face tracking/gestures, voice recognition,
 * profile/mode switching and the cursor pipeline.
 *
 * <p>This screen only displays tracking &amp; gesture signals; it does NOT perform identity
 * recognition and no unique face representation is stored (see README).
 */
@SuppressLint({"UnspecifiedRegisterReceiverFlag", "UnprotectedReceiver"})
public final class TestStationActivity extends AppCompatActivity {

  private static final String TAG = "TestStationActivity";
  /** Speech recognizes roughly -2..10 dB (see {@link VoiceController#normalizeLevel(float)}). */
  private static final int MIC_PERMISSION_CODE = 401;

  private VoiceCommandConfig config;
  private VoiceController voiceController;

  private TextView faceStatusText;
  private TextView gestureValuesText;
  private TextView voiceStatusText;
  private TextView voiceListeningText;
  private TextView voiceHeardText;
  private TextView voiceStatsText;
  private TextView voiceSimulateResultText;
  private TextView profileModeText;
  private TextView switchResultText;
  private TextView cursorSmokeText;
  private ProgressBar voiceLevelBar;
  private FrameLayout cameraPlaceholder;
  private LinearLayout profileModeRows;
  private LinearLayout phraseListContainer;
  private EditText simulatePhraseInput;
  private Button voiceTestButton;

  // Voice test counters (reset each time the mic test starts).
  private int utterances;
  private int matched;
  private int unknown;

  private boolean serviceWasEnabled = false;
  private boolean previewActive = false;
  private boolean cameraPlaceholderLaidOut = false;

  private BroadcastReceiver scoreReceiver;
  private BroadcastReceiver stateReceiver;
  private BroadcastReceiver switchReceiver;
  private BroadcastReceiver loadProfileReceiver;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    config = VoiceCommandConfig.load(this);
    // The testing area always starts in Cursor mode (joystick is not included in the tests).
    VoiceCommandRouter.switchMode(this, InputModeConfig.InputMode.CURSOR);
    voiceController =
        new VoiceController(
            this,
            new VoiceController.Listener() {
              @Override
              public void onVoiceCommand(String phrase) {
                runOnUiThread(() -> onVoiceHeard(phrase));
              }

              @Override
              public void onVoiceStatus(String status) {
                runOnUiThread(() -> voiceStatusText.setText(status));
              }

              @Override
              public void onVoicePartial(String partial) {
                // Qualify with the activity class: unqualified onVoicePartial(...) inside this
                // anonymous Listener would resolve to the listener method itself and recurse
                // forever (StackOverflowError) as soon as a partial result arrives.
                runOnUiThread(() -> TestStationActivity.this.onVoicePartial(partial));
              }

              @Override
              public void onVoiceLevel(float level) {
                runOnUiThread(() -> voiceLevelBar.setProgress(Math.round(level * 100)));
              }
            });

    buildUi();
  }

  // ---------------------------------------------------------------------------------------------
  // UI
  // ---------------------------------------------------------------------------------------------

  private void buildUi() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(48), dp(48), dp(48), dp(48));
    root.setBackgroundColor(getColor(R.color.esports_bg));
    scroll.addView(root);

    TextView title = new TextView(this);
    title.setText("Testing station");
    title.setTextSize(22);
    title.setTextColor(getColor(R.color.esports_text));
    root.addView(title);

    TextView description = new TextView(this);
    description.setText(
        "Diagnose face tracking & gestures, voice commands, profile/mode switching and "
            + "cursor movement. No identity recognition is performed and nothing is stored.");
    description.setTextColor(getColor(R.color.esports_text_dim));
    LinearLayout.LayoutParams descriptionParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(8);
    root.addView(description, descriptionParams);

    buildFaceSection(root);
    buildVoiceSection(root);
    buildProfileModeSection(root);
    buildCursorSmokeSection(root);

    setContentView(scroll);
    refreshProfileModeSection();
    rebuildPhraseList();
  }

  /** Creates a rounded card with an accent-bar header; returns its content container. */
  private LinearLayout addCard(LinearLayout root, String title) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackgroundResource(R.drawable.card_bg);
    card.setPadding(dp(28), dp(20), dp(28), dp(24));
    LinearLayout.LayoutParams cardParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cardParams.topMargin = dp(24);
    root.addView(card, cardParams);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(header);

    View accentBar = new View(this);
    accentBar.setBackgroundResource(R.drawable.test_accent_bar);
    header.addView(
        accentBar, new LinearLayout.LayoutParams(dp(4), dp(20), 0f));

    TextView headerTitle = new TextView(this);
    headerTitle.setText(title);
    headerTitle.setTextSize(16);
    headerTitle.setTypeface(headerTitle.getTypeface(), android.graphics.Typeface.BOLD);
    headerTitle.setTextColor(getColor(R.color.esports_accent));
    LinearLayout.LayoutParams titleParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    titleParams.leftMargin = dp(12);
    header.addView(headerTitle, titleParams);

    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams bodyParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    bodyParams.topMargin = dp(16);
    card.addView(body, bodyParams);
    return body;
  }

  private TextView addBodyText(
      LinearLayout body, String text, int colorRes, float textSize, boolean monospace) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(textSize);
    view.setTextColor(getColor(colorRes));
    if (monospace) {
      view.setTypeface(android.graphics.Typeface.MONOSPACE);
    }
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(10);
    body.addView(view, params);
    return view;
  }

  private void buildFaceSection(LinearLayout root) {
    LinearLayout body = addCard(root, "1. Face & gestures");
    addBodyText(
        body,
        "Enable PWDe (camera + accessibility) for the live camera window. Tracking and gesture "
            + "signals are shown below; thresholds come from your saved bindings.",
        R.color.esports_text_dim,
        13f,
        false);

    cameraPlaceholder = new FrameLayout(this);
    cameraPlaceholder.setBackgroundResource(R.drawable.circle_icon_bg);
    android.widget.FrameLayout.LayoutParams cameraParams =
        new android.widget.FrameLayout.LayoutParams(dp(300), dp(400), Gravity.CENTER_HORIZONTAL);
    cameraParams.topMargin = dp(12);
    body.addView(cameraPlaceholder, cameraParams);
    cameraPlaceholder
        .getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                cameraPlaceholderLaidOut = true;
                flyInCameraBox();
                cameraPlaceholder.getViewTreeObserver().removeOnGlobalLayoutListener(this);
              }
            });

    faceStatusText =
        addBodyText(body, "Waiting for PWDe service state...", R.color.esports_text, 13f, false);

    gestureValuesText =
        addBodyText(body, "No live values yet.", R.color.esports_text, 12f, true);
  }

  private void buildVoiceSection(LinearLayout root) {
    LinearLayout body = addCard(root, "2. Voice commands");

    voiceStatusText =
        addBodyText(body, voiceAvailabilityText(), R.color.esports_text_dim, 13f, false);

    voiceListeningText =
        addBodyText(body, "● Not listening", R.color.esports_text_dim, 14f, false);

    voiceLevelBar = new ProgressBar(
        this, null, android.R.attr.progressBarStyleHorizontal);
    voiceLevelBar.setMax(100);
    voiceLevelBar.setProgress(0);
    voiceLevelBar.setProgressTintList(
        ColorStateList.valueOf(getColor(R.color.esports_accent)));
    LinearLayout.LayoutParams levelParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    levelParams.topMargin = dp(8);
    body.addView(voiceLevelBar, levelParams);

    voiceHeardText =
        addBodyText(
            body,
            "What the recognizer hears will appear here live.",
            R.color.esports_text,
            15f,
            false);

    voiceStatsText =
        addBodyText(body, "Utterances: 0  ·  Matched: 0  ·  Unknown: 0", R.color.esports_text_dim, 13f, false);

    voiceTestButton = new Button(this);
    voiceTestButton.setText("Start microphone test");
    voiceTestButton.setOnClickListener(v -> toggleVoiceTest());
    LinearLayout.LayoutParams buttonParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    buttonParams.topMargin = dp(12);
    body.addView(voiceTestButton, buttonParams);

    TextView phrasesLabel =
        addBodyText(
            body, "Phrases that do something — tap to try:", R.color.esports_accent, 14f, false);
    phrasesLabel.setTypeface(phrasesLabel.getTypeface(), android.graphics.Typeface.BOLD);

    phraseListContainer = new LinearLayout(this);
    phraseListContainer.setOrientation(LinearLayout.VERTICAL);
    body.addView(phraseListContainer);

    TextView simulateLabel =
        addBodyText(body, "Or type a phrase:", R.color.esports_text_dim, 13f, false);

    simulatePhraseInput = new EditText(this);
    simulatePhraseInput.setSingleLine(true);
    simulatePhraseInput.setHint("e.g. cursor mode");
    LinearLayout.LayoutParams inputParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    inputParams.topMargin = dp(6);
    body.addView(simulatePhraseInput, inputParams);

    Button simulateButton = new Button(this);
    simulateButton.setText("Simulate phrase");
    simulateButton.setOnClickListener(v -> simulatePhrase());
    LinearLayout.LayoutParams simulateParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    simulateParams.topMargin = dp(8);
    body.addView(simulateButton, simulateParams);

    voiceSimulateResultText =
        addBodyText(body, "", R.color.esports_accent, 13f, false);
  }

  private void buildProfileModeSection(LinearLayout root) {
    LinearLayout body = addCard(root, "3. Profiles & input mode");
    profileModeText = addBodyText(body, "", R.color.esports_text, 14f, false);

    profileModeRows = new LinearLayout(this);
    profileModeRows.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams rowsParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rowsParams.topMargin = dp(10);
    body.addView(profileModeRows, rowsParams);

    switchResultText = addBodyText(body, "", R.color.esports_accent, 13f, false);
  }

  private void buildCursorSmokeSection(LinearLayout root) {
    LinearLayout body = addCard(root, "4. Cursor smoke test");
    cursorSmokeText =
        addBodyText(
            body,
            "Head position is shown live here. No touch events are dispatched from this "
                + "screen; start PWDe to see the cursor move in a game.",
            R.color.esports_text,
            13f,
            true);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  // ---------------------------------------------------------------------------------------------
  // Receivers / lifecycle
  // ---------------------------------------------------------------------------------------------

  private void registerReceivers() {
    scoreReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            renderScores(intent);
          }
        };

    stateReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            int stateIndex =
                intent.getIntExtra("state", ServiceState.DISABLE.ordinal());
            ServiceState state = ServiceState.values()[stateIndex];
            serviceWasEnabled = state != ServiceState.DISABLE;
            faceStatusText.setText(
                serviceWasEnabled
                    ? "PWDe service is enabled (live preview active)."
                    : "PWDe service is NOT enabled. Enable it from the home screen, then return here.");
          }
        };

    switchReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            if (message != null) {
              switchResultText.setText(message);
              refreshProfileModeSection();
            }
          }
        };

    loadProfileReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            config = VoiceCommandConfig.load(context);
            refreshProfileModeSection();
            rebuildPhraseList();
          }
        };

    if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
      registerReceiver(scoreReceiver, new IntentFilter("BLENDSHAPE_SCORES"), RECEIVER_EXPORTED);
      registerReceiver(stateReceiver, new IntentFilter("SERVICE_STATE_GESTURE"), RECEIVER_EXPORTED);
      registerReceiver(switchReceiver, new IntentFilter("VOICE_SWITCH_RESULT"), RECEIVER_EXPORTED);
      registerReceiver(loadProfileReceiver, new IntentFilter("LOAD_PROFILE"), RECEIVER_EXPORTED);
    } else {
      registerReceiver(scoreReceiver, new IntentFilter("BLENDSHAPE_SCORES"));
      registerReceiver(stateReceiver, new IntentFilter("SERVICE_STATE_GESTURE"));
      registerReceiver(switchReceiver, new IntentFilter("VOICE_SWITCH_RESULT"));
      registerReceiver(loadProfileReceiver, new IntentFilter("LOAD_PROFILE"));
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    registerReceivers();
    requestServiceState();
    if (cameraPlaceholderLaidOut) {
      flyInCameraBox();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    disablePreview();
    voiceController.stop();
    unregisterReceivers();
  }

  private void unregisterReceivers() {
    try {
      unregisterReceiver(scoreReceiver);
      unregisterReceiver(stateReceiver);
      unregisterReceiver(switchReceiver);
      unregisterReceiver(loadProfileReceiver);
    } catch (IllegalArgumentException ignored) {
      // Already unregistered.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Face / gesture section
  // ---------------------------------------------------------------------------------------------

  private void requestServiceState() {
    Intent intent = new Intent("REQUEST_SERVICE_STATE");
    intent.putExtra("state", "gesture");
    sendBroadcast(intent);
  }

  /** Put the service in the static camera-preview state and ask for all blendshape scores. */
  private void flyInCameraBox() {
    if (previewActive) {
      return;
    }
    Intent changeState = new Intent("CHANGE_SERVICE_STATE");
    changeState.putExtra("state", ServiceState.GLOBAL_STICK.ordinal());
    sendBroadcast(changeState);

    int[] locationOnScreen = new int[2];
    cameraPlaceholder.getLocationOnScreen(locationOnScreen);
    Intent flyIn = new Intent("FLY_IN_FLOAT_WINDOW");
    flyIn.putExtra("positionX", locationOnScreen[0]);
    flyIn.putExtra("positionY", locationOnScreen[1]);
    flyIn.putExtra("width", cameraPlaceholder.getWidth());
    flyIn.putExtra("height", cameraPlaceholder.getHeight());
    sendBroadcast(flyIn);

    Intent enablePreview = new Intent("ENABLE_SCORE_PREVIEW");
    enablePreview.putExtra("enable", true);
    enablePreview.putExtra("blendshapesName", "*");
    sendBroadcast(enablePreview);
    previewActive = true;
  }

  private void disablePreview() {
    if (previewActive) {
      Intent enablePreview = new Intent("ENABLE_SCORE_PREVIEW");
      enablePreview.putExtra("enable", false);
      enablePreview.putExtra("blendshapesName", "*");
      sendBroadcast(enablePreview);

      Intent changeState = new Intent("CHANGE_SERVICE_STATE");
      changeState.putExtra(
          "state",
          (serviceWasEnabled ? ServiceState.ENABLE : ServiceState.DISABLE).ordinal());
      sendBroadcast(changeState);
      previewActive = false;
    }
  }

  private void renderScores(Intent intent) {
    String[] names = intent.getStringArrayExtra("names");
    float[] scores = intent.getFloatArrayExtra("scores");
    float headX = intent.getFloatExtra("headX", -1f);
    float headY = intent.getFloatExtra("headY", -1f);
    boolean faceVisible = intent.getBooleanExtra("faceVisible", false);

    StringBuilder sb = new StringBuilder();
    if (faceVisible) {
      sb.append("Face visible in frame.\n");
    } else {
      sb.append("No face in frame yet (check lighting / camera).\n");
    }
    if (names != null && scores != null && names.length == scores.length) {
      Map<String, Float> live = new HashMap<>();
      for (int i = 0; i < names.length; i++) {
        live.put(names[i], scores[i]);
      }
      BlendshapeEventTriggerConfig triggerConfig = new BlendshapeEventTriggerConfig(this);
      for (Map.Entry<EventType, BlendshapeAndThreshold> entry :
          triggerConfig.getAllConfig().entrySet()) {
        BlendshapeAndThreshold binding = entry.getValue();
        if (binding == null || binding.shape() == BlendshapeEventTriggerConfig.Blendshape.NONE) {
          continue;
        }
        Float liveScore = live.get(binding.shape().name());
        float score = liveScore == null ? 0f : liveScore;
        boolean triggered = score >= binding.threshold();
        sb.append(
                String.format(
                    "%-22s",
                    BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(entry.getKey())))
            .append(" ")
            .append(BlendshapeEventTriggerConfig.BEAUTIFY_BLENDSHAPE_NAME.get(binding.shape()))
            .append(": ")
            .append(String.format("%.2f", score))
            .append(" / ")
            .append(String.format("%.2f", binding.threshold()))
            .append(triggered ? "  -> TRIGGERED\n" : "\n");
      }
    } else {
      sb.append("Gesture signals will appear here (requires PWDe service running).\n");
    }
    gestureValuesText.setText(sb.toString());

    if (headX >= 0f && headY >= 0f) {
      cursorSmokeText.setText(
          String.format(
              "Head position (normalized): x=%.2f y=%.2f  — this drives the %s when PWDe is "
                  + "active in a game. No events are dispatched from this screen.",
              headX, headY, InputModeConfig.getDisplayName(InputModeConfig.getInputMode(this))));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Voice section
  // ---------------------------------------------------------------------------------------------

  private String voiceAvailabilityText() {
    StringBuilder sb = new StringBuilder("Voice diagnostics: ");
    if (!checkMicPermission()) {
      sb.append("microphone permission MISSING. ");
    } else {
      sb.append("microphone permission granted. ");
    }
    if (!VoiceCommandConfig.load(this).isEnabled()) {
      sb.append("Voice control is currently DISABLED in the Voice settings.");
    } else {
      sb.append("Voice control is enabled in the Voice settings.");
    }
    boolean onDeviceAvailable =
        VERSION.SDK_INT >= VERSION_CODES.S
            && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
    boolean recognizerAvailable =
        onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(this);
    if (!recognizerAvailable) {
      sb.append(
          " Speech recognition service NOT found: install/update \"Speech Services by Google\" "
              + "(or the Google app) and download the on-device language pack.");
    } else {
      sb.append(
          onDeviceAvailable
              ? " On-device speech recognition available."
              : " Speech recognition service available (may use network).");
    }
    return sb.toString();
  }

  private void toggleVoiceTest() {
    if (voiceController.isRunning()) {
      voiceController.stop();
      voiceTestButton.setText("Start microphone test");
      voiceListeningText.setText("● Not listening");
      voiceListeningText.setTextColor(getColor(R.color.esports_text_dim));
      voiceLevelBar.setProgress(0);
      voiceHeardText.setText("Voice test stopped.");
      voiceStatusText.setText(voiceAvailabilityText());
      return;
    }
    if (!checkMicPermission()) {
      requestMicPermission();
      return;
    }
    utterances = 0;
    matched = 0;
    unknown = 0;
    updateVoiceStats();
    voiceTestButton.setText("Stop microphone test");
    voiceListeningText.setText("● Listening... speak a configured phrase");
    voiceListeningText.setTextColor(getColor(R.color.esports_accent));
    voiceHeardText.setText("What the recognizer hears will appear here live.");
    voiceLevelBar.setProgress(0);
    voiceController.start();
  }

  private void onVoicePartial(String partial) {
    voiceHeardText.setText("Heard so far: \"" + partial + "\"");
  }

  private void onVoiceHeard(String phrase) {
    utterances++;
    VoiceCommandConfig.Command command = config.getCommandForPhrase(phrase);
    if (command == null) {
      unknown++;
      updateVoiceStats();
      voiceHeardText.setText("You said: \"" + phrase + "\" — no matching command.");
      return;
    }
    matched++;
    updateVoiceStats();
    voiceHeardText.setText("You said: \"" + phrase + "\" -> " + describeCommand(command));
    if (VoiceCommandConfig.isSwitchAction(command.action)) {
      switchResultText.setText(VoiceCommandRouter.execute(this, command));
      refreshProfileModeSection();
    }
  }

  private void updateVoiceStats() {
    voiceStatsText.setText(
        "Utterances: " + utterances + "  ·  Matched: " + matched + "  ·  Unknown: " + unknown);
  }

  private void simulatePhrase() {
    runSimulatedPhrase(simulatePhraseInput.getText().toString());
  }

  private void runSimulatedPhrase(String raw) {
    VoiceCommandConfig.Command command = config.getCommandForPhrase(raw);
    if (command == null) {
      voiceSimulateResultText.setText("No command matches \"" + raw.trim() + "\".");
      return;
    }
    StringBuilder result = new StringBuilder("Simulated \"").append(command.phrase).append("\" -> ");
    result.append(describeCommand(command));
    if (VoiceCommandConfig.isSwitchAction(command.action)) {
      result.append(" — ").append(VoiceCommandRouter.execute(this, command));
      refreshProfileModeSection();
    } else {
      result.append(" (this action only executes while PWDe is active in a game)");
    }
    voiceSimulateResultText.setText(result.toString());
  }

  /** Rebuilds the tappable list of every configured phrase. */
  private void rebuildPhraseList() {
    phraseListContainer.removeAllViews();
    Set<String> seen = new HashSet<>();
    for (VoiceCommandConfig.Command command : config.getCommands()) {
      if (command.phrase.isEmpty() || !seen.add(command.phrase)) {
        continue;
      }
      phraseListContainer.addView(createPhraseRow(command));
    }
  }

  private View createPhraseRow(VoiceCommandConfig.Command command) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);

    LinearLayout textColumn = new LinearLayout(this);
    textColumn.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams textColumnParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    row.addView(textColumn, textColumnParams);

    TextView phrase = new TextView(this);
    phrase.setText("\"" + command.phrase + "\"");
    phrase.setTextSize(14);
    phrase.setTypeface(phrase.getTypeface(), android.graphics.Typeface.BOLD);
    phrase.setTextColor(getColor(R.color.esports_text));
    textColumn.addView(phrase);

    TextView action = new TextView(this);
    action.setText(describeCommand(command));
    action.setTextSize(12);
    action.setTextColor(getColor(R.color.esports_text_dim));
    textColumn.addView(action);

    Button tryButton = new Button(this);
    tryButton.setText("Try");
    tryButton.setOnClickListener(v -> runSimulatedPhrase(command.phrase));
    LinearLayout.LayoutParams tryParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tryParams.leftMargin = dp(12);
    row.addView(tryButton, tryParams);

    LinearLayout.LayoutParams rowParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rowParams.topMargin = dp(10);
    row.setLayoutParams(rowParams);
    return row;
  }

  private String describeCommand(VoiceCommandConfig.Command command) {
    switch (command.action) {
      case SWITCH_PROFILE:
        return "switch to profile \""
            + profileNameFor(command.target)
            + "\"";
      case SWITCH_MODE_CURSOR:
        return "switch to Cursor mode";
      case SWITCH_MODE_JOYSTICK:
        return "switch to Joystick mode";
      case SKILL_1:
      case SKILL_2:
      case SKILL_3:
        return "tap skill "
            + (command.action.ordinal() - VoiceCommandConfig.Action.SKILL_1.ordinal() + 1);
      case JOYSTICK_UP:
        return "push joystick up";
      case JOYSTICK_DOWN:
        return "push joystick down";
      case JOYSTICK_LEFT:
        return "push joystick left";
      case JOYSTICK_RIGHT:
        return "push joystick right";
      default:
        return command.action.name();
    }
  }

  private String profileNameFor(String profileId) {
    if (profileId == null) {
      return "?";
    }
    for (ProfileManager.Profile profile : new ProfileManager(this).getProfiles()) {
      if (profile.id.equals(profileId)) {
        return profile.name;
      }
    }
    return profileId;
  }

  private void refreshProfileModeSection() {
    ProfileManager manager = new ProfileManager(this);
    ProfileManager.Profile active = manager.getActiveProfile();
    profileModeText.setText(
        "Active profile: "
            + (active == null ? "none" : active.name)
            + "  ·  Input mode: "
            + InputModeConfig.getDisplayName(InputModeConfig.getInputMode(this))
            + "  ·  Voice enabled: "
            + (VoiceCommandConfig.load(this).isEnabled() ? "yes" : "no"));

    profileModeRows.removeAllViews();
    for (ProfileManager.Profile profile : manager.getProfiles()) {
      final String profileId = profile.id;
      Button button = new Button(this);
      button.setText(
          (active != null && active.id.equals(profileId) ? "Active: " : "Switch to \"")
              + profile.name
              + (active != null && active.id.equals(profileId) ? "\"" : "\" (say \""
                  + config.getProfileSwitchPhrase(profileId)
                  + "\")"));
      button.setOnClickListener(
          v -> {
            String result = VoiceCommandRouter.switchProfile(this, profileId);
            switchResultText.setText(result);
            refreshProfileModeSection();
          });
      profileModeRows.addView(button);
    }

    Button cursorButton = new Button(this);
    cursorButton.setText(
        "Cursor mode (say \""
            + config.getModeSwitchPhrase(VoiceCommandConfig.Action.SWITCH_MODE_CURSOR)
            + "\")");
    cursorButton.setOnClickListener(
        v -> {
          switchResultText.setText(
              VoiceCommandRouter.switchMode(this, InputModeConfig.InputMode.CURSOR));
          refreshProfileModeSection();
        });
    profileModeRows.addView(cursorButton);
  }

  // ---------------------------------------------------------------------------------------------
  // Permissions
  // ---------------------------------------------------------------------------------------------

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
    if (requestCode == MIC_PERMISSION_CODE) {
      if (checkMicPermission()) {
        toggleVoiceTest();
      } else {
        Toast.makeText(
                this,
                "Microphone permission is needed for the voice test (you can also simulate phrases).",
                Toast.LENGTH_LONG)
            .show();
        voiceStatusText.setText(voiceAvailabilityText());
      }
    }
  }
}
