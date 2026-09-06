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
import android.content.SharedPreferences;

/**
 * Per-profile configuration for the virtual joystick.
 *
 * <p>All geometry is stored as normalized values so it stays valid across screen sizes and
 * orientations. The neutral head position (the head coordinate treated as "no input") is recorded
 * during calibration and then the joystick deflection is computed from how far the head has moved
 * away from it.
 */
public final class JoystickConfig {

  // Normalized (0..1) position of the joystick base on the screen.
  private static final String KEY_CENTER_X = "joystick_center_x";
  private static final String KEY_CENTER_Y = "joystick_center_y";

  // Radius as a fraction of the smaller screen dimension.
  private static final String KEY_RADIUS = "joystick_radius";

  // Neutral head position in normalized mediapipe-image coordinates.
  private static final String KEY_NEUTRAL_X = "joystick_neutral_x";
  private static final String KEY_NEUTRAL_Y = "joystick_neutral_y";

  private static final String KEY_SENSITIVITY = "joystick_sensitivity";
  private static final String KEY_DEADZONE = "joystick_deadzone";

  // Release grace: how long the head must stay neutral before the held finger is lifted.
  private static final String KEY_RELEASE_GRACE_MS = "joystick_release_grace_ms";
  // Move step: how far (as a fraction of the joystick radius) the deflection target must move
  // before a new drag stroke is dispatched while holding.
  private static final String KEY_MOVE_STEP = "joystick_move_step";

  // Sensible defaults. Joystick sits bottom-left, tuned for one-handed mobile MOBA play.
  private static final float DEFAULT_CENTER_X = 0.18f;
  private static final float DEFAULT_CENTER_Y = 0.82f;
  private static final float DEFAULT_RADIUS = 0.15f;
  private static final float DEFAULT_NEUTRAL_X = 0.5f;
  private static final float DEFAULT_NEUTRAL_Y = 0.5f;
  private static final float DEFAULT_SENSITIVITY = 2.0f;
  private static final float DEFAULT_DEADZONE = 0.08f;
  private static final float DEFAULT_RELEASE_GRACE_MS = 150f;
  private static final float DEFAULT_MOVE_STEP = 0.10f;

  public float centerX;
  public float centerY;
  public float radius;
  public float neutralX;
  public float neutralY;
  public float sensitivity;
  public float deadzone;
  public float releaseGraceMs;
  public float moveStep;

  public static JoystickConfig load(Context context) {
    SharedPreferences prefs = new ProfileManager(context).getConfigSharedPreferences();
    JoystickConfig config = new JoystickConfig();
    config.centerX = prefs.getFloat(KEY_CENTER_X, DEFAULT_CENTER_X);
    config.centerY = prefs.getFloat(KEY_CENTER_Y, DEFAULT_CENTER_Y);
    config.radius = prefs.getFloat(KEY_RADIUS, DEFAULT_RADIUS);
    config.neutralX = prefs.getFloat(KEY_NEUTRAL_X, DEFAULT_NEUTRAL_X);
    config.neutralY = prefs.getFloat(KEY_NEUTRAL_Y, DEFAULT_NEUTRAL_Y);
    config.sensitivity = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
    config.deadzone = prefs.getFloat(KEY_DEADZONE, DEFAULT_DEADZONE);
    config.releaseGraceMs = prefs.getFloat(KEY_RELEASE_GRACE_MS, DEFAULT_RELEASE_GRACE_MS);
    config.moveStep = prefs.getFloat(KEY_MOVE_STEP, DEFAULT_MOVE_STEP);
    return config;
  }

  public void save(Context context) {
    new ProfileManager(context)
        .getConfigSharedPreferences()
        .edit()
        .putFloat(KEY_CENTER_X, centerX)
        .putFloat(KEY_CENTER_Y, centerY)
        .putFloat(KEY_RADIUS, radius)
        .putFloat(KEY_NEUTRAL_X, neutralX)
        .putFloat(KEY_NEUTRAL_Y, neutralY)
        .putFloat(KEY_SENSITIVITY, sensitivity)
        .putFloat(KEY_DEADZONE, deadzone)
        .putFloat(KEY_RELEASE_GRACE_MS, releaseGraceMs)
        .putFloat(KEY_MOVE_STEP, moveStep)
        .apply();
  }

  /** Restore defaults (used by a "Reset" action in the config screen). */
  public void resetToDefaults() {
    centerX = DEFAULT_CENTER_X;
    centerY = DEFAULT_CENTER_Y;
    radius = DEFAULT_RADIUS;
    neutralX = DEFAULT_NEUTRAL_X;
    neutralY = DEFAULT_NEUTRAL_Y;
    sensitivity = DEFAULT_SENSITIVITY;
    deadzone = DEFAULT_DEADZONE;
    releaseGraceMs = DEFAULT_RELEASE_GRACE_MS;
    moveStep = DEFAULT_MOVE_STEP;
  }
}
