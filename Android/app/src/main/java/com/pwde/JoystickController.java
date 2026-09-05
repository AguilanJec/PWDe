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

import static java.lang.Math.max;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.Log;

/**
 * Maps head-tilt to a virtual-joystick deflection, renders the thumb, and emulates touch.
 *
 * <p>A deflected joystick is emulated as ONE continuous finger press (down at the base, drag out
 * to the deflection target, hold while deflected, lift when the head returns to neutral) rather
 * than a stream of quick taps. {@link JoystickGestureMachine} decides which stroke is needed each
 * tick; this class translates those decisions into chained accessibility gestures.
 *
 * <p>Accessibility services cannot keep a raw {@code ACTION_DOWN} alive arbitrarily long, so the
 * sustained press is achieved by dispatching continuation strokes ({@code willContinue = true}):
 * each stroke parks the finger at its end point without lifting it, and the next stroke continues
 * the same pointer. The final release stroke has {@code willContinue = false} and lifts the
 * finger. This behaviour must be verified on-device per game (see JoystickConfigActivity tuning).
 */
public final class JoystickController {

  private static final String TAG = "JoystickController";

  /** Minimum spacing between two dispatched strokes (avoids flooding the gesture queue). */
  private static final long STROKE_MIN_INTERVAL_MS = 30;

  /** How long the final stationary release stroke keeps the finger down before lifting it. */
  private static final long RELEASE_DURATION_MS = 40;

  /** Drag speed: longer strokes for longer moves, bounded so the finger tracks promptly. */
  private static final float STROKE_MS_PER_PX = 0.6f;
  private static final long STROKE_MIN_DURATION_MS = 25;
  private static final long STROKE_MAX_DURATION_MS = 120;

  /** Smallest target movement (px) that justifies a new drag stroke. */
  private static final float MIN_MOVE_PX = 2f;

  private final CursorAccessibilityService service;
  private final ServiceUiManager serviceUiManager;
  private JoystickConfig config;
  private JoystickGestureMachine machine;

  public JoystickController(CursorAccessibilityService service, ServiceUiManager serviceUiManager) {
    this.service = service;
    this.serviceUiManager = serviceUiManager;
    this.machine = new JoystickGestureMachine(0f, STROKE_MIN_INTERVAL_MS);
  }

  /** Swap in a freshly-loaded per-profile config. */
  public void setConfig(JoystickConfig config) {
    // Lift any finger still held down under the previous geometry before switching.
    releaseIfPressed();
    this.config = config;
    this.machine =
        new JoystickGestureMachine(
            config == null ? 0f : config.releaseGraceMs, STROKE_MIN_INTERVAL_MS);
  }

  public void clear() {
    releaseIfPressed();
    machine.reset();
    serviceUiManager.hideJoystick();
  }

  /** If a finger is currently held down, lift it now (best effort, e.g. mode switch/teardown). */
  private void releaseIfPressed() {
    if (service == null || machine == null || !machine.isPressed()) {
      return;
    }
    JoystickGestureMachine.Step release = machine.forceRelease();
    if (release != null) {
      dispatchStroke(release);
    }
  }

  /**
   * Compute deflection from head tilt and (when deflected) emulate pushing the joystick.
   *
   * @param headCoordXy Forehead landmark position in the mediapipe input image (pixels).
   * @param mpW Mediapipe input width (pixels).
   * @param mpH Mediapipe input height (pixels).
   * @param screenW Screen width (pixels).
   * @param screenH Screen height (pixels).
   */
  public void update(float[] headCoordXy, int mpW, int mpH, int screenW, int screenH) {
    if (config == null || serviceUiManager == null || mpW <= 0 || mpH <= 0) {
      return;
    }

    float nx = headCoordXy[0] / mpW;
    float ny = headCoordXy[1] / mpH;

    float dx = (nx - config.neutralX) * config.sensitivity;
    float dy = (ny - config.neutralY) * config.sensitivity;
    float mag = (float) Math.sqrt(dx * dx + dy * dy);

    float knobMagnitude = 0f;
    float dirX = 0f;
    float dirY = 0f;
    if (mag > config.deadzone && mag > 0f) {
      float clamped = Math.min(mag, 1f);
      dirX = dx / mag;
      dirY = dy / mag;
      knobMagnitude = clamped;
    }

    int cx = (int) (config.centerX * screenW);
    int cy = (int) (config.centerY * screenH);
    int radius = (int) (config.radius * Math.min(screenW, screenH));

    float offsetX = dirX * knobMagnitude * radius;
    float offsetY = dirY * knobMagnitude * radius;

    serviceUiManager.updateJoystick(cx, cy, radius, offsetX, offsetY);

    boolean deflected = knobMagnitude > 0f;
    if (!deflected && !machine.isPressed()) {
      return;
    }

    float minMovePx = max(MIN_MOVE_PX, config.moveStep * radius);
    float targetX = clamp(cx + offsetX, 0, screenW - 1);
    float targetY = clamp(cy + offsetY, 0, screenH - 1);

    JoystickGestureMachine.Step step =
        machine.update(
            SystemClock.elapsedRealtime(),
            deflected,
            cx,
            cy,
            targetX,
            targetY,
            minMovePx);
    if (step != null) {
      dispatchStroke(step);
    }
  }

  /** The base center in screen pixels (used by the calibration screen). */
  public int[] getBaseCenter(int screenW, int screenH) {
    if (config == null) {
      return new int[] {0, 0};
    }
    return new int[] {(int) (config.centerX * screenW), (int) (config.centerY * screenH)};
  }

  private void dispatchStroke(JoystickGestureMachine.Step step) {
    if (service == null) {
      return;
    }
    GestureDescription gesture;
    if (step.kind == JoystickGestureMachine.Kind.RELEASE) {
      Path path = new Path();
      path.moveTo(step.toX, step.toY);
      // Stationary final stroke: completes the continued gesture and lifts the finger.
      gesture = buildGesture(path, RELEASE_DURATION_MS, /* willContinue= */ false);
    } else {
      Path path = new Path();
      path.moveTo(step.fromX, step.fromY);
      path.lineTo(step.toX, step.toY);
      gesture =
          buildGesture(
              path,
              strokeDurationMs(
                  (float) Math.hypot(step.toX - step.fromX, step.toY - step.fromY)),
              /* willContinue= */ true);
    }
    boolean dispatched = service.dispatchGesture(gesture, /* callback= */ null, /* handler= */ null);
    if (!dispatched) {
      Log.w(TAG, "dispatchGesture rejected the " + step.kind + " stroke; retrying next tick");
    }
  }

  private static GestureDescription buildGesture(Path path, long durationMs, boolean willContinue) {
    return new GestureDescription.Builder()
        .addStroke(
            new GestureDescription.StrokeDescription(
                path, /* startTime= */ 0, max(durationMs, 1), willContinue))
        .build();
  }

  private static long strokeDurationMs(float distancePx) {
    return Math.round(clamp(distancePx * STROKE_MS_PER_PX, STROKE_MIN_DURATION_MS, STROKE_MAX_DURATION_MS));
  }

  private static float clamp(float value, float min, float max) {
    return Math.min(Math.max(value, min), max);
  }
}
