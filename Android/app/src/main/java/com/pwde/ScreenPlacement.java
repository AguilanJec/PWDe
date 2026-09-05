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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single user-placed on-screen marker used by the visual placement editor.
 *
 * <p>Markers describe where the app should tap or press (skill buttons, the virtual joystick
 * base). All coordinates are normalized (0..1) fractions of the screen, so the same profile works
 * across screen sizes and orientations.
 */
public final class ScreenPlacement {

  /** What this marker controls on the screen. */
  public enum Kind {
    /** A tap point (e.g. a MOBA skill button) triggered by voice/tap actions. */
    SKILL_TAP,
    /** The virtual-joystick base center. */
    JOYSTICK_BASE
  }

  private static final String KEY_KIND = "kind";
  private static final String KEY_LABEL = "label";
  private static final String KEY_INDEX = "index";
  private static final String KEY_X = "x";
  private static final String KEY_Y = "y";

  public final Kind kind;
  public final String label;

  /** Index of a {@link Kind#SKILL_TAP} (e.g. 0 = skill 1); unused for the joystick base. */
  public final int index;

  /** Normalized (0..1) position on the screen. */
  public float x;
  public float y;

  public ScreenPlacement(Kind kind, String label, int index, float x, float y) {
    this.kind = kind;
    this.label = label;
    this.index = index;
    this.x = clamp(x);
    this.y = clamp(y);
  }

  public static ScreenPlacement skill(int index, String label, float x, float y) {
    return new ScreenPlacement(Kind.SKILL_TAP, label, index, x, y);
  }

  public static ScreenPlacement joystickBase(float x, float y) {
    return new ScreenPlacement(Kind.JOYSTICK_BASE, "Joystick", -1, x, y);
  }

  /** @return a copy of this placement with updated normalized coordinates. */
  public ScreenPlacement withPosition(float newX, float newY) {
    return new ScreenPlacement(kind, label, index, newX, newY);
  }

  public JSONObject toJson() throws JSONException {
    JSONObject object = new JSONObject();
    object.put(KEY_KIND, kind.name());
    object.put(KEY_LABEL, label);
    object.put(KEY_INDEX, index);
    object.put(KEY_X, x);
    object.put(KEY_Y, y);
    return object;
  }

  public static ScreenPlacement fromJson(JSONObject object) throws JSONException {
    Kind kind = Kind.valueOf(object.getString(KEY_KIND));
    String label = object.optString(KEY_LABEL, kind == Kind.JOYSTICK_BASE ? "Joystick" : "");
    int index = kind == Kind.JOYSTICK_BASE ? -1 : object.getInt(KEY_INDEX);
    return new ScreenPlacement(kind, label, index, (float) object.getDouble(KEY_X), (float) object.getDouble(KEY_Y));
  }

  private static float clamp(float value) {
    return Math.min(1f, Math.max(0f, value));
  }
}
