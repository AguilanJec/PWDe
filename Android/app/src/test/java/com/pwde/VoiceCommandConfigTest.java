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
import java.util.Arrays;
import java.util.List;
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
    // Exact matching keeps this test focused on the cleared mapping: in contains mode "second
    // one" would still match the default skill word "one" (covered by containsMatch tests below).
    config.setContainsMatchEnabled(false);
    config.setProfileSwitchPhrase(profileId, "second one");
    config.setProfileSwitchPhrase(profileId, "");
    assertNull(config.getCommandForPhrase("second one"));
  }

  @Test
  public void skillPhrases_includeDefaultSynonyms() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    List<String> skillOne = config.getSkillPhrases(0);
    assertTrue(skillOne.contains("1"));
    assertTrue(skillOne.contains("one"));
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("one"));
  }

  @Test
  public void customSkillWords_replaceDefaultsAndRoundTrip() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setSkillPhrases(0, VoiceCommandConfig.parsePhraseList("fire, ult ,cast "));
    config.save(context);

    VoiceCommandConfig reloaded = VoiceCommandConfig.load(context);
    assertEquals(Arrays.asList("fire", "ult", "cast"), reloaded.getSkillPhrases(0));
    assertEquals(VoiceCommandConfig.Action.SKILL_1, reloaded.getActionForPhrase("FIRE"));
    assertEquals(VoiceCommandConfig.Action.SKILL_1, reloaded.getActionForPhrase("ULT!"));
    assertEquals(VoiceCommandConfig.Action.NONE, reloaded.getActionForPhrase("one"));
  }

  @Test
  public void clearingSkillWords_removesMappingAndFallsBackToDefault() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setSkillPhrases(0, VoiceCommandConfig.parsePhraseList("fire"));
    config.setSkillPhrases(0, VoiceCommandConfig.parsePhraseList(""));

    assertNull(config.getCommandForPhrase("fire"));
    assertNull(config.getCommandForPhrase("1"));
    // Display fallback exposes the default word, but it is no longer stored as a command.
    assertEquals("1", config.getSkillPhrases(0).get(0));
  }

  @Test
  public void skillsAreIndependent_eachKeepsItsOwnWords() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    config.setSkillPhrases(1, VoiceCommandConfig.parsePhraseList("blink"));

    assertEquals(Arrays.asList("1", "one"), config.getSkillPhrases(0));
    assertEquals(Arrays.asList("blink"), config.getSkillPhrases(1));
    assertEquals(Arrays.asList("3", "three"), config.getSkillPhrases(2));
  }

  @Test
  public void parsePhraseList_splitsAndNormalizesTokens() {
    assertEquals(Arrays.asList("1", "one"), VoiceCommandConfig.parsePhraseList(" 1 , one "));
    assertEquals(Arrays.asList("cast fire"), VoiceCommandConfig.parsePhraseList("  Cast Fire!! ,,, "));
    assertTrue(VoiceCommandConfig.parsePhraseList(" , , ").isEmpty());
    assertTrue(VoiceCommandConfig.parsePhraseList(null).isEmpty());
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

  @Test
  public void matchingToggles_areOnByDefault() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertTrue(config.isContainsMatchEnabled());
    assertTrue(config.isQuickFireEnabled());
  }

  @Test
  public void containsMatch_matchesWordAnywhereInPhrase() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("skill one"));
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("one one"));
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("use number 1 now"));
    assertEquals(VoiceCommandConfig.Action.SKILL_2, config.getActionForPhrase("I said two"));
    assertEquals(VoiceCommandConfig.Action.JOYSTICK_UP, config.getActionForPhrase("move up"));
    assertEquals(
        VoiceCommandConfig.Action.SWITCH_MODE_CURSOR,
        config.getActionForPhrase("switch to cursor mode please"));
  }

  @Test
  public void containsMatch_requiresWholeWords() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    // "one" inside "phone"/"alone" and "up" inside "upgrade" are not whole words.
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("phone"));
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("alone in the dark"));
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("upgrade the app"));
  }

  @Test
  public void containsMatch_longestPhraseWins() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    // "profile one" contains the skill word "one" but the longer switch phrase must win.
    VoiceCommandConfig.Command command = config.getCommandForPhrase("profile one");
    assertNotNull(command);
    assertEquals(VoiceCommandConfig.Action.SWITCH_PROFILE, command.action);
    // Ambiguous same-length matches keep the first configured command (list order): "two one".
    assertEquals(
        VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("use two one words"));
  }

  @Test
  public void containsMatch_requiresPhraseWordsTogether() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    // "cursor" and "mode" must be adjacent to form the "cursor mode" phrase.
    assertEquals(
        VoiceCommandConfig.Action.SWITCH_MODE_CURSOR, config.getActionForPhrase("cursor mode on"));
    assertEquals(
        VoiceCommandConfig.Action.NONE, config.getActionForPhrase("switch mode to cursor"));
  }

  @Test
  public void exactMode_matchesOnlyTheWholePhrase() {
    VoiceCommandConfig config = VoiceCommandConfig.load(ApplicationProvider.getApplicationContext());
    config.setContainsMatchEnabled(false);
    assertEquals(VoiceCommandConfig.Action.SKILL_1, config.getActionForPhrase("one"));
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("skill one"));
    assertEquals(VoiceCommandConfig.Action.NONE, config.getActionForPhrase("one one"));
  }

  @Test
  public void matchingToggles_roundTripThroughSave() {
    android.content.Context context = ApplicationProvider.getApplicationContext();
    VoiceCommandConfig config = VoiceCommandConfig.load(context);
    config.setContainsMatchEnabled(false);
    config.setQuickFireEnabled(false);
    config.save(context);

    VoiceCommandConfig reloaded = VoiceCommandConfig.load(context);
    assertFalse(reloaded.isContainsMatchEnabled());
    assertFalse(reloaded.isQuickFireEnabled());
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
