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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VoiceCommandConfigTest {

  @Test
  public void defaultCommands_matchExpectedActions() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("1"));
    assertEquals(VoiceCommandConfig.Action.SKILL_2, config.getActionForPhrase("2"));
    assertEquals(VoiceCommandConfig.Action.SKILL_3, config.getActionForPhrase("3"));
    assertEquals(VoiceCommandConfig.Action.JOYSTICK_UP, config.getActionForPhrase("Up"));
    assertEquals(VoiceCommandConfig.Action.JOYSTICK_DOWN, config.getActionForPhrase("down"));
    assertEquals(VoiceCommandConfig.Action.JOYSTICK_LEFT, config.getActionForPhrase(" left! "));
    assertEquals(VoiceCommandConfig.Action.JOYSTICK_RIGHT, config.getActionForPhrase("Right"));
  }

  @Test
  public void unknownPhrase_yieldsNone() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("banana"));
  }

  @Test
  public void disabledByDefault() {
    assertTrue(!VoiceCommandConfig.load(ApplicationProvider.getApplicationContext()).isEnabled());
  }

  @Test
  public void skillPointsDefaultToRightEdge() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(0.86f, config.getSkillX(0), 0.001f);
    assertEquals(0.55f, config.getSkillY(0), 0.001f);
    assertEquals(0.85f, config.getSkillY(2), 0.001f);
  }

  @Test
  public void saveAndReload_roundTrips() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    config.setEnabled(true);
    config.setSkillPoint(0, 0.9f, 0.4f);
    config.save(ApplicationProvider.getApplicationContext());

    VoiceCommandConfig reloaded = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertTrue(reloaded.isEnabled());
    assertEquals(0.9f, reloaded.getSkillX(0), 0.001f);
    assertEquals(0.4f, reloaded.getSkillY(0), 0.001f);
  }

  @Test
  public void switchDefaults_existWhenNotConfigured() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(
        VoiceCommandConfig.Action.SWITCH_MODE_CURSOR,
        config.getActionForPhrase("cursor mode"));
    assertEquals(
        VoiceCommandConfig.Action.SWITCH_MODE_JOYSTICK,
        config.getActionForPhrase("joystick mode"));

    VoiceCommandConfig.Command profileOne = config.getCommandForPhrase("profile one");
    assertNotNull(profileOne);
    assertEquals(VoiceCommandConfig.Action.SWITCH_PROFILE, profileOne.action);
    assertNotNull(profileOne.target);
    assertTrue(hasProfileWithId(ApplicationProvider.getApplicationContext(), profileOne.target));
  }

  @Test
  public void profilePhrase_roundTripsWithTarget() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    ProfileManager manager = new ProfileManager(context);
    String profileId = manager.createProfile("Gaming");

    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setProfileSwitchPhrase(profileId, "play game");
    config.save(context);

    VoiceCommandConfig reloaded = VoiceCommandConfig.load(context);
    assertEquals("play game", reloaded.getProfileSwitchPhrase(profileId));
    VoiceCommandConfig.Command command = reloaded.getCommandForPhrase("PLAY GAME!");
    assertNotNull(command);
    assertEquals(profileId, command.target);
  }

  @Test
  public void clearingProfilePhrase_removesSwitchCommand() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    ProfileManager manager = new ProfileManager(context);
    String profileId = manager.createProfile("Second");

    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setProfileSwitchPhrase(profileId, "second one");
    config.setProfileSwitchPhrase(profileId, "");
    assertNull(config.getCommandForPhrase("second one"));
  }

  @Test
  public void duplicatePhrase_isDetected() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    // "up" is a default joystick command.
    assertTrue(config.hasPhraseConflict("UP", null));
    assertFalse(config.hasPhraseConflict("banana", null));
  }

  @Test
  public void staleProfileTarget_isPrunedOnLoad() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    ProfileManager manager = new ProfileManager(context);
    String profileId = manager.createProfile("Temp");

    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setProfileSwitchPhrase(profileId, "temp profile");
    config.save(context);

    manager.deleteProfile(profileId);
    VoiceCommandConfig reloaded = VoiceCommandConfig.load(context);
    assertNull(reloaded.getCommandForPhrase("temp profile"));
  }

  private static boolean hasProfileWithId(android.content.Context context, String profileId) {
    for (ProfileManager.Profile profile : new ProfileManager(context).getProfiles()) {
      if (profile.id.equals(profileId)) {
        return true;
      }
    }
    return false;
  }
}
