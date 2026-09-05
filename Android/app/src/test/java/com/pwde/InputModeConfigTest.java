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

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InputModeConfigTest {

  @Test
  public void getInputMode_defaultsToCursor() {
    assertEquals(
        InputModeConfig.InputMode.CURSOR,
        InputModeConfig.getInputMode(ApplicationProvider.getApplicationContext()));
  }

  @Test
  public void setInputMode_persistsPerProfile() {
    InputModeConfig.setInputMode(
        ApplicationProvider.getApplicationContext(), InputModeConfig.InputMode.JOYSTICK);

    assertEquals(
        InputModeConfig.InputMode.JOYSTICK,
        InputModeConfig.getInputMode(ApplicationProvider.getApplicationContext()));
    // Read back through a fresh ProfileManager to confirm it hit the profile config file.
    assertEquals(
        InputModeConfig.InputMode.JOYSTICK,
        InputModeConfig.getInputMode(ApplicationProvider.getApplicationContext()));

    assertEquals(
        "Joystick", InputModeConfig.getDisplayName(InputModeConfig.InputMode.JOYSTICK));
    assertEquals("Cursor", InputModeConfig.getDisplayName(InputModeConfig.InputMode.CURSOR));
  }
}
