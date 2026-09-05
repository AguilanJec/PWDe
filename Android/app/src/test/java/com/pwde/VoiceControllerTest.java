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

import android.speech.SpeechRecognizer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VoiceControllerTest {

  @Test
  public void errorLabels_coverCommonCodes() {
    assertEquals("no speech match", VoiceController.errorLabel(SpeechRecognizer.ERROR_NO_MATCH));
    assertEquals(
        "speech timeout",
        VoiceController.errorLabel(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
    assertEquals(
        "missing mic permission",
        VoiceController.errorLabel(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
    assertEquals(
        "recognizer busy",
        VoiceController.errorLabel(SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
    assertEquals("network error", VoiceController.errorLabel(SpeechRecognizer.ERROR_NETWORK));
  }

  @Test
  public void errorLabels_fallBackForUnknownCodes() {
    assertEquals("unknown error 999", VoiceController.errorLabel(999));
  }

  @Test
  public void levelNormalizer_clampsToUnitRange() {
    assertEquals(0f, VoiceController.normalizeLevel(-10f), 0.0001f);
    assertEquals(0f, VoiceController.normalizeLevel(-2f), 0.0001f);
    assertEquals(0.1666667f, VoiceController.normalizeLevel(0f), 0.0001f);
    assertEquals(1f, VoiceController.normalizeLevel(10f), 0.0001f);
    assertEquals(1f, VoiceController.normalizeLevel(30f), 0.0001f);
  }
}
