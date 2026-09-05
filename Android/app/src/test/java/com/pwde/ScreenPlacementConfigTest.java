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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for the unified on-screen placement store used by the placement editor. */
@RunWith(AndroidJUnit4.class)
public class ScreenPlacementConfigTest {

  private static final String KEY_PLACEMENTS = "screen_placements_v1";
  private static final String LEGACY_X_PREFIX = "voice_skill_x_";
  private static final String LEGACY_Y_PREFIX = "voice_skill_y_";

  @Test
  public void defaultsAndCount_whenNothingStored() {
    ScreenPlacementConfig config =
        new ScreenPlacementConfig(ApplicationProvider.getApplicationContext());
    assertEquals(3, ScreenPlacementConfig.skillCount());
    assertEquals(3, config.getSkills().size());
    // Tuned MOBA defaults on the right edge.
    assertEquals(0.86f, config.getSkillX(0), 0.001f);
    assertEquals(0.55f, config.getSkillY(0), 0.001f);
    assertEquals(0.86f, config.getSkillX(2), 0.001f);
    assertEquals(0.85f, config.getSkillY(2), 0.001f);
  }

  @Test
  public void setSkillPoint_clampsAndRoundTrips() {
    Context context = ApplicationProvider.getApplicationContext();
    ScreenPlacementConfig config = new ScreenPlacementConfig(context);
    config.setSkillPoint(1, 0.5f, 0.42f);
    // Coordinates outside 0..1 are clamped, never stored raw.
    config.setSkillPoint(0, -1f, 2f);

    ScreenPlacementConfig reloaded = new ScreenPlacementConfig(context);
    assertEquals(0.0f, reloaded.getSkillX(0), 0.001f);
    assertEquals(1.0f, reloaded.getSkillY(0), 0.001f);
    assertEquals(0.5f, reloaded.getSkillX(1), 0.001f);
    assertEquals(0.42f, reloaded.getSkillY(1), 0.001f);
    // Untouched slot keeps its default.
    assertEquals(0.86f, reloaded.getSkillX(2), 0.001f);
  }

  @Test
  public void outOfRangeIndex_fallsBackSafely() {
    ScreenPlacementConfig config =
        new ScreenPlacementConfig(ApplicationProvider.getApplicationContext());
    // Indices outside [0, skillCount()) return a centred fallback instead of (0,0) or crashing.
    assertEquals(0.5f, config.getSkillX(3), 0.001f);
    assertEquals(0.5f, config.getSkillY(7), 0.001f);
    assertEquals(0.5f, config.getSkillX(-1), 0.001f);
  }

  @Test
  public void shortStoredList_isPaddedWithDefaults() {
    Context context = ApplicationProvider.getApplicationContext();
    // Persist a list with only one entry (e.g. written by an older/partial editor).
    prefs(context)
        .edit()
        .putString(
            KEY_PLACEMENTS,
            "[{\"kind\":\"SKILL_TAP\",\"label\":\"Skill 1\",\"index\":0,"
                + "\"x\":0.31,\"y\":0.62}]")
        .apply();

    ScreenPlacementConfig config = new ScreenPlacementConfig(context);
    assertEquals(3, config.getSkills().size());
    assertEquals(0.31f, config.getSkillX(0), 0.001f);
    assertEquals(0.62f, config.getSkillY(0), 0.001f);
    // Missing tail slots fall back to tuned defaults, never (0,0).
    assertEquals(0.86f, config.getSkillX(2), 0.001f);
    assertEquals(0.85f, config.getSkillY(2), 0.001f);
  }

  @Test
  public void legacyNumericKeys_seedPositionsWhenNoListStored() {
    Context context = ApplicationProvider.getApplicationContext();
    prefs(context)
        .edit()
        .putFloat(LEGACY_X_PREFIX + 1, 0.77f)
        .putFloat(LEGACY_Y_PREFIX + 1, 0.33f)
        .apply();

    ScreenPlacementConfig config = new ScreenPlacementConfig(context);
    assertEquals(0.77f, config.getSkillX(1), 0.001f);
    assertEquals(0.33f, config.getSkillY(1), 0.001f);
    // Slots without a legacy key keep the defaults.
    assertEquals(0.86f, config.getSkillX(0), 0.001f);
    assertEquals(0.55f, config.getSkillY(0), 0.001f);
  }

  @Test
  public void placements_areIsolatedPerProfile() {
    Context context = ApplicationProvider.getApplicationContext();
    ProfileManager manager = new ProfileManager(context);
    String original = manager.ensureActiveProfile();

    String other = manager.createProfile("Placement Isolation");
    manager.setActiveProfile(other);
    new ScreenPlacementConfig(context).setSkillPoint(0, 0.31f, 0.62f);

    // Back on the original profile the placement list is untouched.
    manager.setActiveProfile(original);
    assertEquals(0.86f, new ScreenPlacementConfig(context).getSkillX(0), 0.001f);

    // And the other profile still sees its own placement.
    manager.setActiveProfile(other);
    assertEquals(0.31f, new ScreenPlacementConfig(context).getSkillX(0), 0.001f);

    // Restore activation and clean up so later tests are unaffected.
    manager.setActiveProfile(original);
    manager.deleteProfile(other);
    assertTrue(manager.getActiveProfile() != null);
  }

  private static SharedPreferences prefs(Context context) {
    return new ProfileManager(context).getConfigSharedPreferences();
  }
}
