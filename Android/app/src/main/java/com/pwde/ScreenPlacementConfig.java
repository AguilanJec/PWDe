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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-profile store of the on-screen placements (skill tap points, joystick base) that the visual
 * placement editor reads and writes, and that tap dispatch uses.
 *
 * <p>All coordinates are normalized (0..1) fractions of the screen. Skill placements live here as a
 * JSON list; the joystick base anchor keeps living in {@link JoystickConfig} (same preference file,
 * same normalized convention) so geometry, radius and tuning stay together. Older profiles stored
 * skill points in per-index preference keys ({@code voice_skill_x_<i>}); those are still honoured
 * as a seed when no placement list exists yet.
 */
public final class ScreenPlacementConfig {

  private static final String KEY_PLACEMENTS = "screen_placements_v1";

  // Legacy keys seeded from VoiceCommandConfig's previous numeric storage.
  private static final String LEGACY_KEY_SKILL_X_PREFIX = "voice_skill_x_";
  private static final String LEGACY_KEY_SKILL_Y_PREFIX = "voice_skill_y_";

  // Default skill button positions (right edge) tuned for a mobile MOBA like ML.
  public static final float[] DEFAULT_SKILL_X = {0.86f, 0.86f, 0.86f};
  public static final float[] DEFAULT_SKILL_Y = {0.55f, 0.70f, 0.85f};
  public static final String[] SKILL_LABELS = {"Skill 1", "Skill 2", "Skill 3"};

  private final Context context;

  /** Currently loaded placements (only SKILL_TAP entries are stored here). */
  private final List<ScreenPlacement> skills = new ArrayList<>();

  public ScreenPlacementConfig(Context context) {
    this.context = context.getApplicationContext();
    load();
  }

  /** All skill tap placements, in skill order. */
  public List<ScreenPlacement> getSkills() {
    return skills;
  }

  /** @return the number of skill tap slots (valid indices are {@code [0, skillCount())}). */
  public static int skillCount() {
    return SKILL_LABELS.length;
  }

  public float getSkillX(int index) {
    return placementAt(index).x;
  }

  public float getSkillY(int index) {
    return placementAt(index).y;
  }

  /** Set a skill tap point (clamped to 0..1) and persist it for the active profile. */
  public void setSkillPoint(int index, float x, float y) {
    if (index < 0 || index >= skills.size()) {
      return;
    }
    skills.set(index, skills.get(index).withPosition(x, y));
    save();
  }

  public void save() {
    JSONArray array = new JSONArray();
    for (ScreenPlacement placement : skills) {
      try {
        array.put(placement.toJson());
      } catch (JSONException e) {
        // Unserializable placement; skip.
      }
    }
    prefs().edit().putString(KEY_PLACEMENTS, array.toString()).apply();
  }

  private ScreenPlacement placementAt(int index) {
    if (index >= 0 && index < skills.size()) {
      return skills.get(index);
    }
    // Safe fallback for out-of-range indices (matches the previous defaults-based API).
    if (index >= 0 && index < SKILL_LABELS.length) {
      return ScreenPlacement.skill(
          index, SKILL_LABELS[index], DEFAULT_SKILL_X[index], DEFAULT_SKILL_Y[index]);
    }
    return ScreenPlacement.skill(index, "Skill " + (index + 1), 0.5f, 0.5f);
  }

  private void load() {
    skills.clear();
    List<ScreenPlacement> stored = new ArrayList<>();
    String raw = prefs().getString(KEY_PLACEMENTS, null);
    if (raw != null) {
      try {
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
          ScreenPlacement placement = ScreenPlacement.fromJson(array.getJSONObject(i));
          if (placement.kind == ScreenPlacement.Kind.SKILL_TAP) {
            stored.add(placement);
          }
        }
      } catch (JSONException e) {
        stored.clear();
      }
    }
    // Always expose exactly skillCount() slots: stored entries win, missing tail slots fall back
    // to the legacy numeric keys (when present) or the tuned defaults. A shorter-than-expected
    // stored list therefore never yields bogus 0,0 taps, and nothing is persisted until a marker
    // actually moves.
    SharedPreferences prefs = prefs();
    for (int i = 0; i < SKILL_LABELS.length; i++) {
      if (i < stored.size()) {
        skills.add(stored.get(i));
        continue;
      }
      float x =
          prefs.contains(LEGACY_KEY_SKILL_X_PREFIX + i)
              ? prefs.getFloat(LEGACY_KEY_SKILL_X_PREFIX + i, DEFAULT_SKILL_X[i])
              : DEFAULT_SKILL_X[i];
      float y =
          prefs.contains(LEGACY_KEY_SKILL_Y_PREFIX + i)
              ? prefs.getFloat(LEGACY_KEY_SKILL_Y_PREFIX + i, DEFAULT_SKILL_Y[i])
              : DEFAULT_SKILL_Y[i];
      skills.add(ScreenPlacement.skill(i, SKILL_LABELS[i], x, y));
    }
  }

  private SharedPreferences prefs() {
    return new ProfileManager(context).getConfigSharedPreferences();
  }
}
