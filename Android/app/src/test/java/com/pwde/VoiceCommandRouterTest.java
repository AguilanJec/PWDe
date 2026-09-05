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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VoiceCommandRouterTest {

  private Context context() {
    return ApplicationProvider.getApplicationContext();
  }

  @Test
  public void switchMode_persistsAndReports() {
    InputModeConfig.setInputMode(context(), InputModeConfig.InputMode.CURSOR);
    String message = VoiceCommandRouter.switchMode(context(), InputModeConfig.InputMode.JOYSTICK);
    assertTrue(message.contains("Joystick"));
    assertEquals(InputModeConfig.InputMode.JOYSTICK, InputModeConfig.getInputMode(context()));
  }

  @Test
  public void switchMode_sameMode_isNoop() {
    InputModeConfig.setInputMode(context(), InputModeConfig.InputMode.JOYSTICK);
    String message = VoiceCommandRouter.switchMode(context(), InputModeConfig.InputMode.JOYSTICK);
    assertTrue(message.contains("Already"));
  }

  @Test
  public void switchProfile_activatesTarget() {
    ProfileManager manager = new ProfileManager(context());
    String targetId = manager.createProfile("Gaming");
    String message = VoiceCommandRouter.switchProfile(context(), targetId);
    assertTrue(message.contains("Gaming"));
    assertEquals(targetId, manager.getActiveProfile().id);
  }

  @Test
  public void unknownProfile_doesNotChangeActive() {
    ProfileManager manager = new ProfileManager(context());
    String originalId = manager.getActiveProfile().id;
    VoiceCommandRouter.switchProfile(context(), "does-not-exist");
    assertEquals(originalId, manager.getActiveProfile().id);
  }

  @Test
  public void nonSwitchCommand_isIgnored() {
    VoiceCommandConfig.Command command =
        new VoiceCommandConfig.Command("1", VoiceCommandConfig.Action.SKILL_1);
    assertEquals("", VoiceCommandRouter.execute(context(), command));
  }

  @Test
  public void switchCommand_carriesTarget() {
    VoiceCommandConfig.Command command =
        new VoiceCommandConfig.Command("alex", VoiceCommandConfig.Action.SWITCH_PROFILE, "target-id");
    assertEquals("target-id", command.target);
    assertFalse(VoiceCommandConfig.isSwitchAction(VoiceCommandConfig.Action.SKILL_1));
    assertTrue(VoiceCommandConfig.isSwitchAction(VoiceCommandConfig.Action.SWITCH_PROFILE));
  }
}
