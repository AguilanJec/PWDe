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

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Executes profile/mode switch commands coming from voice (or the testing station).
 *
 * <p>After the switch the {@code LOAD_PROFILE} broadcast tells {@link CursorAccessibilityService} to
 * reload the per-profile configs (cursor movement, gesture bindings, joystick, voice commands).
 */
public final class VoiceCommandRouter {

  private static final String TAG = "VoiceCommandRouter";

  /** Same action string the service's loadProfileReceiver listens for. */
  static final String ACTION_LOAD_PROFILE = "LOAD_PROFILE";

  private VoiceCommandRouter() {}

  /**
   * Execute a switch command ({@code SWITCH_PROFILE} / {@code SWITCH_MODE_*}). No-op for other
   * actions. Returns a human-readable result (for logs/confirmation UI).
   */
  public static String execute(Context context, VoiceCommandConfig.Command command) {
    if (command == null) {
      return "";
    }
    Context appContext = context.getApplicationContext();
    switch (command.action) {
      case SWITCH_PROFILE:
        return switchProfile(appContext, command.target);
      case SWITCH_MODE_CURSOR:
        return switchMode(appContext, InputModeConfig.InputMode.CURSOR);
      case SWITCH_MODE_JOYSTICK:
        return switchMode(appContext, InputModeConfig.InputMode.JOYSTICK);
      default:
        return "";
    }
  }

  /** Switch the active profile and tell the service to reload its configs. */
  public static String switchProfile(Context context, String profileId) {
    ProfileManager manager = new ProfileManager(context);
    ProfileManager.Profile profile = findProfile(manager, profileId);
    if (profile == null) {
      String message = "Profile no longer exists; switch ignored.";
      Log.w(TAG, message);
      return message;
    }
    ProfileManager.Profile active = manager.getActiveProfile();
    if (active != null && active.id.equals(profile.id)) {
      return "Already on profile \"" + profile.name + "\"";
    }
    manager.setActiveProfile(profile.id);
    context.sendBroadcast(new Intent(ACTION_LOAD_PROFILE));
    return "Switched to profile \"" + profile.name + "\"";
  }

  /** Switch the active input mode and tell the service to reload its configs. */
  public static String switchMode(Context context, InputModeConfig.InputMode mode) {
    InputModeConfig.InputMode current = InputModeConfig.getInputMode(context);
    if (current == mode) {
      return "Already in " + InputModeConfig.getDisplayName(mode) + " mode";
    }
    InputModeConfig.setInputMode(context, mode);
    context.sendBroadcast(new Intent(ACTION_LOAD_PROFILE));
    return "Switched to " + InputModeConfig.getDisplayName(mode) + " mode";
  }

  private static ProfileManager.Profile findProfile(ProfileManager manager, String profileId) {
    if (profileId == null) {
      return null;
    }
    for (ProfileManager.Profile profile : manager.getProfiles()) {
      if (profile.id.equals(profileId)) {
        return profile;
      }
    }
    return null;
  }
}
