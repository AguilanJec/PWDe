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
public class JoystickConfigTest {

  @Test
  public void defaults_areSane() {
    JoystickConfig config = JoystickConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(0.18f, config.centerX, 0.001f);
    assertEquals(0.82f, config.centerY, 0.001f);
    assertEquals(0.15f, config.radius, 0.001f);
    assertEquals(0.5f, config.neutralX, 0.001f);
    assertEquals(0.5f, config.neutralY, 0.001f);
    assertEquals(1.0f, config.sensitivity, 0.001f);
    assertEquals(0.08f, config.deadzone, 0.001f);
    assertEquals(150f, config.releaseGraceMs, 0.001f);
    assertEquals(0.10f, config.moveStep, 0.001f);
  }

  @Test
  public void saveAndLoad_persistsPerProfile() {
    JoystickConfig config = JoystickConfig.load(ApplicationProvider.getApplicationContext());
    config.radius = 0.22f;
    config.sensitivity = 1.6f;
    config.deadzone = 0.15f;
    config.releaseGraceMs = 300f;
    config.moveStep = 0.25f;
    config.save(ApplicationProvider.getApplicationContext());

    JoystickConfig reloaded = JoystickConfig.load(ApplicationProvider.getApplicationContext());
    assertEquals(0.22f, reloaded.radius, 0.001f);
    assertEquals(1.6f, reloaded.sensitivity, 0.001f);
    assertEquals(0.15f, reloaded.deadzone, 0.001f);
    assertEquals(300f, reloaded.releaseGraceMs, 0.001f);
    assertEquals(0.25f, reloaded.moveStep, 0.001f);
  }

  @Test
  public void resetToDefaults_restoresInitialValues() {
    JoystickConfig config = JoystickConfig.load(ApplicationProvider.getApplicationContext());
    config.radius = 0.9f;
    config.resetToDefaults();
    assertEquals(0.15f, config.radius, 0.001f);
    assertEquals(1.0f, config.sensitivity, 0.001f);
    assertEquals(0.08f, config.deadzone, 0.001f);
    assertEquals(150f, config.releaseGraceMs, 0.001f);
    assertEquals(0.10f, config.moveStep, 0.001f);
  }
}
