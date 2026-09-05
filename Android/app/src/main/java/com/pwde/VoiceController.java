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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Wraps a {@link SpeechRecognizer} and drives it continuously.
 *
 * <p>Recognized text is handed to {@link Listener#onVoiceCommand(String)}. After each result the
 * recognizer is restarted so it keeps listening while the user plays. Transient errors (busy
 * recognizer, short timeouts, network hiccups) are retried with a backoff instead of stopping; if
 * offline recognition is repeatedly unavailable the controller falls back to online recognition.
 * Fatal conditions (no recognizer on the device, missing mic permission, persistent failure) stop
 * listening and report the reason through {@link Listener#onVoiceStatus(String)} so the UI can tell
 * the user what to fix.
 *
 * <p>Running depends on the device having speech recognition (e.g. the Google app plus the chosen
 * language pack) and the microphone permission being granted.
 */
public final class VoiceController implements RecognitionListener {

  private static final String TAG = "VoiceController";
  private static final long RESTART_DELAY_MS = 300L;
  private static final long MAX_BACKOFF_MS = 2000L;

  /** Stop after this many consecutive (non-silence) errors to avoid an infinite retry loop. */
  private static final int MAX_CONSECUTIVE_ERRORS = 6;

  /**
   * Well-known system recognition services, tried in order before the platform default. Some
   * devices (for example some Lenovo/Motorola builds) make a broken default service that starts
   * sessions but never invokes any {@link RecognitionListener} callback; binding to a specific
   * Google service when it is installed avoids those silent hangs.
   */
  private static final ComponentName[] PREFERRED_SYSTEM_RECOGNIZERS = {
    new ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"),
    new ComponentName(
        "com.google.android.as",
        "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"),
  };

  public interface Listener {
    void onVoiceCommand(String phrase);

    /** Optional diagnostics (availability, error reasons). Default no-op keeps lambdas working. */
    default void onVoiceStatus(String status) {}

    /** Live partial transcript of what the recognizer is currently hearing. */
    default void onVoicePartial(String partial) {}

    /** Live microphone level, normalized to 0..1 (UI meter only, never used for commands). */
    default void onVoiceLevel(float normalizedLevel) {}
  }

  private final Context context;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean running;
  private boolean preferOffline = true;

  /**
   * When set, a live (partial) transcript that satisfies this matcher fires {@link
   * Listener#onVoiceCommand(String)} right away and the current session is ended, so a skill casts
   * the moment its word is heard instead of waiting for the utterance to finish. Null disables the
   * fast path (commands then only fire on final results).
   */
  private Predicate<String> quickFireMatcher;

  /** True from the moment a partial match fires until the next session starts; blocks double fires. */
  private boolean quickFired;

  /** True while the current recognizer is the on-device one (Android 12+). */
  private boolean usingOnDevice;

  /** True after we already recreated the recognizer as the network-backed one. */
  private boolean fellBackToSystem;
  private int consecutiveErrors;
  private long retryDelayMs = RESTART_DELAY_MS;
  private float lastLevel = -1f;

  /** Give a fresh recognizer session this long to produce its first callback. */
  private static final long CALLBACK_TIMEOUT_MS = 7000L;

  private boolean callbackReceived;
  private final Runnable callbackWatchdog = () -> handleCallbackTimeout();

  public VoiceController(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  public boolean isRunning() {
    return running;
  }

  /**
   * Enable/disable the fast path: when {@code matcher} is non-null, any live partial transcript it
   * accepts immediately fires {@link Listener#onVoiceCommand(String)} and ends the current session
   * (the pending final result is discarded, so a single utterance fires once). Pass {@code null}
   * to keep firing only on final results.
   */
  public void setQuickFireMatcher(Predicate<String> matcher) {
    quickFireMatcher = matcher;
  }

  /** Marks the current session as alive, canceling the unresponsive-recognizer watchdog. */
  private void noteCallback() {
    if (!callbackReceived) {
      callbackReceived = true;
      handler.removeCallbacks(callbackWatchdog);
    }
  }

  /**
   * A recognizer session produced no callback at all (not even onReadyForSpeech or
   * onRmsChanged). Some devices/setups hang silently instead of raising an error; reconnect
   * and tell the user what is happening rather than sitting on "Listening..." forever.
   */
  private void handleCallbackTimeout() {
    if (!running || callbackReceived) {
      return;
    }
    consecutiveErrors++;
    if (usingOnDevice && !fellBackToSystem) {
      Log.w(TAG, "On-device recognizer produced no callback for " + CALLBACK_TIMEOUT_MS
          + "ms; switching to the system recognizer");
      listener.onVoiceStatus("On-device recognition is not responding; switching to online...");
      switchToSystemRecognizer();
    } else {
      Log.w(TAG, "System recognizer produced no callback for " + CALLBACK_TIMEOUT_MS
          + "ms; reconnecting");
      listener.onVoiceStatus("Recognition service is not responding; reconnecting...");
      recreateSystemRecognizer();
    }
    if (!running) {
      return;
    }
    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      listener.onVoiceStatus("Voice stopped: the recognition service did not respond.");
      stop();
      return;
    }
    retryDelayMs = RESTART_DELAY_MS;
    scheduleRestart(RESTART_DELAY_MS);
  }

  /** Destroy the current recognizer and recreate it as the system one, if available. */
  private boolean recreateSystemRecognizer() {
    if (recognizer != null) {
      recognizer.cancel();
      recognizer.destroy();
      recognizer = null;
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      listener.onVoiceStatus("No speech recognition service is available on this device.");
      stop();
      return false;
    }
    recognizer = createSystemRecognizer();
    if (recognizer != null) {
      recognizer.setRecognitionListener(this);
      return true;
    }
    listener.onVoiceStatus("Could not create the speech recognizer.");
    stop();
    return false;
  }

  public void start() {
    if (running) {
      return;
    }
    // Prefer the system recognizer. The on-device recognizer can report "available" while
    // silently hanging for the current locale (VAD fires, but no RMS/partial/final callbacks),
    // and Speech Services by Google falls back to network or installed packs as needed.
    if (!createRecognizer()) {
      String status =
          "Speech recognition is not available on this device. Install/update \"Speech "
              + "Services by Google\" (or the Google app), download the on-device speech "
              + "language pack, then retry from this screen.";
      Log.w(TAG, status);
      listener.onVoiceStatus(status);
      return;
    }
    running = true;
    consecutiveErrors = 0;
    retryDelayMs = RESTART_DELAY_MS;
    fellBackToSystem = false;
    Log.i(
        TAG,
        "start: locale="
            + Locale.getDefault().toLanguageTag()
            + " onDeviceAvailable="
            + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            + " defaultService="
            + defaultRecognitionServiceName());
    listener.onVoiceStatus("Listening...");
    startListening();
    Log.i(TAG, usingOnDevice ? "on-device voice recognition started" : "system voice recognition started");
  }

  /** Create (and attach a listener to) the best available recognizer. Returns false when none. */
  private boolean createRecognizer() {
    if (recognizer != null) {
      return true;
    }
    // Use the system recognizer first. The on-device recognizer can be "available" (some
    // language pack is installed) yet silently hang for Locale.getDefault(): VAD fires
    // ("Heard speech...") but no RMS, no partial results and no final result ever arrive.
    // The platform default (Speech Services by Google on most devices) falls back to the
    // network or an installed pack as appropriate. The on-device recognizer stays as a
    // fallback for devices with no system recognition service at all.
    usingOnDevice = false;
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
      recognizer = createSystemRecognizer();
    }
    if (recognizer == null
        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
      try {
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        usingOnDevice = recognizer != null;
      } catch (RuntimeException e) {
        Log.w(TAG, "Could not create the on-device recognizer", e);
        recognizer = null;
      }
    }
    if (recognizer == null) {
      Log.w(TAG, "SpeechRecognizer creation returned null");
      return false;
    }
    recognizer.setRecognitionListener(this);
    return true;
  }

  /**
   * Create the network-backed system recognizer. Try the platform default first: on Samsung and
   * most other devices this is Speech Services by Google (com.google.android.tts), the correct
   * general-purpose recognizer. The Google app's GoogleRecognitionService is often not visible
   * to third-party apps, and Android System Intelligence's AiAiSpeechRecognitionService can
   * accept a session but then only return NO_MATCH; both are still tried below as targeted
   * alternatives when the platform default cannot be created.
   */
  private SpeechRecognizer createSystemRecognizer() {
    usingOnDevice = false;
    try {
      SpeechRecognizer created = SpeechRecognizer.createSpeechRecognizer(context);
      if (created != null) {
        Log.i(
            TAG,
            "system recognizer: platform default (" + defaultRecognitionServiceName() + ")");
        return created;
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not create the platform default recognizer", e);
    }
    for (ComponentName candidate : PREFERRED_SYSTEM_RECOGNIZERS) {
      if (!isRecognitionServiceAvailable(candidate)) {
        continue;
      }
      try {
        SpeechRecognizer created = SpeechRecognizer.createSpeechRecognizer(context, candidate);
        if (created != null) {
          Log.i(TAG, "system recognizer: " + candidate.flattenToShortString());
          return created;
        }
      } catch (RuntimeException e) {
        Log.w(TAG, "Could not create preferred recognizer " + candidate.flattenToShortString(), e);
      }
    }
    return SpeechRecognizer.createSpeechRecognizer(context);
  }

  /** Resolve the platform's default RecognitionService name, for diagnostics only. */
  private String defaultRecognitionServiceName() {
    try {
      ResolveInfo info =
          context
              .getPackageManager()
              .resolveService(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
      if (info != null && info.serviceInfo != null) {
        return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
            .flattenToShortString();
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not resolve the default recognition service", e);
    }
    return "unknown";
  }

  /** Returns true when the given RecognitionService component is installed and enabled. */
  private boolean isRecognitionServiceAvailable(ComponentName component) {
    Intent probe = new Intent(RecognitionService.SERVICE_INTERFACE);
    probe.setComponent(component);
    try {
      for (ResolveInfo info : context.getPackageManager().queryIntentServices(probe, 0)) {
        if (info != null
            && info.serviceInfo != null
            && component.equals(
                new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name))) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not query recognition service " + component.flattenToShortString(), e);
    }
    return false;
  }

  /**
   * Replace the unusable on-device recognizer with the network-backed system recognizer. This is
   * the actual fallback that {@link #onError} triggers once.
   */
  private void switchToSystemRecognizer() {
    usingOnDevice = false;
    preferOffline = false;
    if (!recreateSystemRecognizer()) {
      return;
    }
    consecutiveErrors = 0;
    retryDelayMs = RESTART_DELAY_MS;
    listener.onVoiceStatus("Offline recognition unavailable; switched to online recognition...");
  }

  public void stop() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    quickFired = false;
    if (recognizer != null) {
      recognizer.cancel();
      recognizer.destroy();
      recognizer = null;
    }
    usingOnDevice = false;
    Log.i(TAG, "voice recognition stopped");
  }

  private Intent buildIntent() {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    // NOTE: EXTRA_LANGUAGE is deliberately NOT forced. Forcing Locale.getDefault() made the
    // on-device recognizer hang with zero callbacks ("green meter never moves, no errors") when
    // no language pack exists for that locale, and pointed the Google fallback at the wrong
    // language so clear speech returned NO_MATCH. Let each backend use its own default instead.
    // Only ask the on-device recognizer to prefer offline. Asking the system recognizer to
    // prefer offline can make some devices hang in a session with no callbacks at all when
    // no offline language pack is installed (observed as: green meter never moves, no errors).
    if (usingOnDevice) {
      intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
    }
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
    return intent;
  }

  /** Cancel any in-flight session before starting the next one to avoid ERROR_CLIENT/BUSY. */
  private void startListening() {
    if (!running || recognizer == null) {
      return;
    }
    quickFired = false;
    try {
      recognizer.cancel();
      recognizer.startListening(buildIntent());
      callbackReceived = false;
      handler.removeCallbacks(callbackWatchdog);
      handler.postDelayed(callbackWatchdog, CALLBACK_TIMEOUT_MS);
    } catch (SecurityException e) {
      Log.w(TAG, "Missing microphone permission", e);
      listener.onVoiceStatus(
          "Microphone permission missing; voice disabled. Grant it in Settings > Apps > PWDe.");
      stop();
    } catch (IllegalStateException e) {
      // Recognizer in a bad state (e.g. another recognition session is active); retry shortly.
      Log.w(TAG, "startListening failed, retrying", e);
      listener.onVoiceStatus("Speech recognizer busy; retrying...");
      scheduleRestart(RESTART_DELAY_MS);
    }
  }

  private void scheduleRestart(long delayMs) {
    handler.postDelayed(
        () -> {
          if (running) {
            startListening();
          }
        },
        delayMs);
  }

  private void handleText(Bundle results) {
    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (matches == null || matches.isEmpty()) {
      return;
    }
    String best = matches.get(0);
    Log.i(TAG, "heard: " + best);
    listener.onVoiceCommand(best);
  }

  static String errorLabel(int error) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
        return "on-device language pack unavailable";
      }
      if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
        return "language not supported";
      }
    }
    switch (error) {
      case SpeechRecognizer.ERROR_AUDIO:
        return "audio capture error";
      case SpeechRecognizer.ERROR_CLIENT:
        return "client error (recognizer busy/reset)";
      case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
        return "missing mic permission";
      case SpeechRecognizer.ERROR_NETWORK:
        return "network error";
      case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
        return "network timeout";
      case SpeechRecognizer.ERROR_NO_MATCH:
        return "no speech match";
      case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
        return "recognizer busy";
      case SpeechRecognizer.ERROR_SERVER:
        return "server error";
      case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
        return "server disconnected";
      case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
        return "speech timeout";
      default:
        return "unknown error " + error;
    }
  }

  @Override
  public void onReadyForSpeech(Bundle params) {
    if (running) {
      noteCallback();
      listener.onVoiceStatus("Ready for speech — speak now.");
    }
  }

  @Override
  public void onBeginningOfSpeech() {
    if (running) {
      noteCallback();
      listener.onVoiceStatus("Heard speech...");
    }
  }

  @Override
  public void onRmsChanged(float rmsdB) {
    noteCallback();
    // UI meter only: never influenced command logic.
    float level = normalizeLevel(rmsdB);
    if (Math.abs(level - lastLevel) >= 0.03f) {
      lastLevel = level;
      listener.onVoiceLevel(level);
    }
  }

  /** Map a SpeechRecognizer RMS value (roughly -2..10 dB) to 0..1 for display. */
  static float normalizeLevel(float rmsDb) {
    return Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
  }

  @Override
  public void onBufferReceived(byte[] buffer) {}

  @Override
  public void onEndOfSpeech() {
    if (running) {
      noteCallback();
      listener.onVoiceStatus("Processing speech...");
    }
  }

  @Override
  public void onError(int error) {
    // Ignore events that arrive after we intentionally stopped (e.g. cancel()).
    if (!running) {
      return;
    }
    noteCallback();
    Log.w(TAG, "recognition error: " + error + " (" + errorLabel(error) + ")");
    consecutiveErrors++;

    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
      listener.onVoiceStatus(
          "Microphone permission missing; voice disabled. Grant it in Settings > Apps > PWDe.");
      stop();
      return;
    }

    // Silence is normal for a hands-free session; restart quietly without backoff.
    if (error == SpeechRecognizer.ERROR_NO_MATCH
        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
      consecutiveErrors = 0;
      retryDelayMs = RESTART_DELAY_MS;
      scheduleRestart(RESTART_DELAY_MS);
      return;
    }

    // On-device recognition is unusable (typical cause: the language pack for the device
    // locale was never downloaded). Actually replace it with the network-backed system
    // recognizer instead of just retrying the same broken recognizer. Do this once.
    boolean languageError =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED);
    if (usingOnDevice
        && !fellBackToSystem
        && (error == SpeechRecognizer.ERROR_NETWORK
            || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            || error == SpeechRecognizer.ERROR_SERVER
            || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            || languageError)) {
      fellBackToSystem = true;
      switchToSystemRecognizer();
      // The switch resets consecutiveErrors/backoff; start a fresh session right away and keep
      // the "switched to online recognition" status visible instead of the retry message below.
      // If there is no system service either, switchToSystemRecognizer() already stopped and
      // reported, so just return.
      if (running) {
        scheduleRestart(RESTART_DELAY_MS);
      }
      return;
    } else if (preferOffline
        && (error == SpeechRecognizer.ERROR_NETWORK
            || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            || error == SpeechRecognizer.ERROR_SERVER
            || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) {
      // A system recognizer asked to prefer offline: allow the network from now on.
      preferOffline = false;
      listener.onVoiceStatus("Offline recognition unavailable; falling back to online...");
    }

    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      listener.onVoiceStatus(
          "Voice stopped after repeated recognition failures (" + errorLabel(error) + ").");
      stop();
      return;
    }

    retryDelayMs = Math.min(retryDelayMs * 2, MAX_BACKOFF_MS);
    listener.onVoiceStatus(
        "Recognition issue (" + errorLabel(error) + "); retrying in " + retryDelayMs + "ms...");
    scheduleRestart(retryDelayMs);
  }

  @Override
  public void onResults(Bundle results) {
    noteCallback();
    consecutiveErrors = 0;
    retryDelayMs = RESTART_DELAY_MS;
    handleText(results);
    scheduleRestart(RESTART_DELAY_MS);
  }

  @Override
  public void onPartialResults(Bundle partialResults) {
    noteCallback();
    // Show partials for diagnostics; when a matcher is configured the live transcript can also
    // fire the command immediately (see setQuickFireMatcher) so a skill casts the moment its
    // word is heard instead of waiting for the final result.
    ArrayList<String> matches =
        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (matches == null || matches.isEmpty()) {
      return;
    }
    String partial = matches.get(0);
    Log.i(TAG, "heard (partial): " + partial);
    listener.onVoicePartial(partial);
    if (quickFireMatcher != null && !quickFired && quickFireMatcher.test(partial)) {
      quickFired = true;
      Log.i(TAG, "quick-fire command from live transcript: " + partial);
      listener.onVoiceCommand(partial);
      // End this session so the pending final result of the same utterance cannot fire the
      // command again; a fresh listening session starts after the usual restart delay.
      recognizer.cancel();
      scheduleRestart(RESTART_DELAY_MS);
    }
  }

  @Override
  public void onEvent(int eventType, Bundle params) {}
}
