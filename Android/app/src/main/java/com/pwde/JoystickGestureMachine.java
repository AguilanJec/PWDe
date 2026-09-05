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

/**
 * Pure (Android-free) state machine that decides, tick by tick, which accessibility stroke the
 * joystick emulation should dispatch next.
 *
 * <p>The goal is that a deflected joystick reads to the target app as ONE continuous finger press
 * (down at the base, drag out to the deflection target, hold while deflected, lift when the head
 * returns to neutral) instead of the previous behaviour of firing a fresh short swipe on every
 * frame — which the target app experienced as a stream of quick taps and misbehaved while held.
 *
 * <p>Every input is an explicit parameter and there are no Android imports, so the machine can be
 * unit-tested on a plain JVM. {@link JoystickController} supplies screen-space geometry and turns
 * each returned {@link Step} into a chained accessibility gesture.
 */
public final class JoystickGestureMachine {

  /** Kind of stroke the controller should dispatch next. */
  public enum Kind {
    /** Finger goes down at the joystick base and drags out to the deflection target. */
    PRESS_DRAG,
    /** The already-pressed finger drags from its current position to a new target. */
    DRAG,
    /** The pressed finger lifts; only {@code toX}/{@code toY} (the finger position) matter. */
    RELEASE
  }

  /** One dispatch decision produced by {@link #update}. */
  public static final class Step {
    public final Kind kind;
    public final float fromX;
    public final float fromY;
    public final float toX;
    public final float toY;

    private Step(Kind kind, float fromX, float fromY, float toX, float toY) {
      this.kind = kind;
      this.fromX = fromX;
      this.fromY = fromY;
      this.toX = toX;
      this.toY = toY;
    }

    static Step pressDrag(float baseX, float baseY, float targetX, float targetY) {
      return new Step(Kind.PRESS_DRAG, baseX, baseY, targetX, targetY);
    }

    static Step drag(float fromX, float fromY, float toX, float toY) {
      return new Step(Kind.DRAG, fromX, fromY, toX, toY);
    }

    static Step release(float x, float y) {
      return new Step(Kind.RELEASE, x, y, x, y);
    }
  }

  private enum Phase {
    IDLE,
    /** A finger is down and may be parked at {@link #fingerX}/{@link #fingerY}. */
    PRESSED
  }

  /** A finger that lost deflection must stay neutral for this long before it is lifted. */
  private final float releaseGraceMs;

  /** Minimum spacing between two dispatched strokes, to avoid flooding the event queue. */
  private final long minIntervalMs;

  private Phase phase = Phase.IDLE;
  private float fingerX;
  private float fingerY;
  private float lastTargetX;
  private float lastTargetY;
  private long lastDispatchAtMs = Long.MIN_VALUE / 2;
  private long deflectionLostAtMs = -1L;

  /**
   * @param releaseGraceMs debounce (ms) applied before a held finger is released once the head
   *     stops deflecting — absorbs jitter around the deadzone so a held push does not flicker.
   * @param minIntervalMs minimum time (ms) between two dispatched strokes.
   */
  public JoystickGestureMachine(float releaseGraceMs, long minIntervalMs) {
    this.releaseGraceMs = Math.max(0f, releaseGraceMs);
    this.minIntervalMs = Math.max(0L, minIntervalMs);
  }

  /** Whether the machine currently believes a finger is held down. */
  public boolean isPressed() {
    return phase == Phase.PRESSED;
  }

  /**
   * Advance the machine for one tick.
   *
   * @param nowMs monotonic clock time of this tick.
   * @param deflected whether the head deflection is currently beyond the deadzone.
   * @param baseX/baseY screen-space joystick base center (where the finger first goes down).
   * @param targetX/targetY screen-space deflection target (where the thumb currently is).
   * @param minMovePx minimum target displacement that justifies a new drag stroke.
   * @return the stroke to dispatch, or {@code null} when nothing should be dispatched this tick.
   */
  public Step update(
      long nowMs,
      boolean deflected,
      float baseX,
      float baseY,
      float targetX,
      float targetY,
      float minMovePx) {
    if (deflected) {
      deflectionLostAtMs = -1L;
      if (phase == Phase.IDLE) {
        // Deflection onset: press down at the base and drag out to the target in one stroke.
        phase = Phase.PRESSED;
        lastTargetX = targetX;
        lastTargetY = targetY;
        lastDispatchAtMs = nowMs;
        // The next continuation stroke must begin where this stroke ends, i.e. at the target.
        fingerX = targetX;
        fingerY = targetY;
        return Step.pressDrag(baseX, baseY, targetX, targetY);
      }
      // Already pressed: only dispatch when the target moved enough and enough time passed,
      // so holding still never produces a stream of strokes.
      if (nowMs - lastDispatchAtMs >= minIntervalMs && movedEnough(targetX, targetY, minMovePx)) {
        Step step = Step.drag(fingerX, fingerY, targetX, targetY);
        fingerX = targetX;
        fingerY = targetY;
        lastTargetX = targetX;
        lastTargetY = targetY;
        lastDispatchAtMs = nowMs;
        return step;
      }
      return null;
    }

    // Not deflected.
    if (phase == Phase.IDLE) {
      return null;
    }
    if (deflectionLostAtMs < 0L) {
      deflectionLostAtMs = nowMs;
    }
    if (nowMs - deflectionLostAtMs >= releaseGraceMs) {
      // Grace expired: lift the finger exactly where it is held (no new press).
      phase = Phase.IDLE;
      deflectionLostAtMs = -1L;
      return Step.release(fingerX, fingerY);
    }
    // Still inside the release grace window: keep holding in place.
    return null;
  }

  /**
   * Lift the finger immediately, regardless of grace timers, without waiting for the next
   * {@link #update}. Used when the mode/service is torn down mid-hold.
   *
   * @return a release step if a finger was down, otherwise {@code null}.
   */
  public Step forceRelease() {
    if (phase != Phase.PRESSED) {
      return null;
    }
    phase = Phase.IDLE;
    deflectionLostAtMs = -1L;
    return Step.release(fingerX, fingerY);
  }

  /** Drop all state without emitting any stroke (e.g. controller teardown). */
  public void reset() {
    phase = Phase.IDLE;
    deflectionLostAtMs = -1L;
    lastDispatchAtMs = Long.MIN_VALUE / 2;
  }

  private boolean movedEnough(float targetX, float targetY, float minMovePx) {
    float dx = targetX - lastTargetX;
    float dy = targetY - lastTargetY;
    return (dx * dx + dy * dy) >= minMovePx * minMovePx;
  }
}
