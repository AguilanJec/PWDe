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

import com.pwde.JoystickGestureMachine.Kind;
import com.pwde.JoystickGestureMachine.Step;
import org.junit.Test;

/**
 * Unit tests for the pure joystick gesture state machine. These guard the behaviour the user
 * asked for: a deflected joystick dispatches ONE continuous press/drag/hold/release instead of a
 * rapid stream of short strokes.
 */
public class JoystickGestureMachineTest {

  private static final long NOW = 1_000_000L;
  private static final float BASE_X = 100f;
  private static final float BASE_Y = 800f;
  private static final float TARGET_X = 150f;
  private static final float TARGET_Y = 760f;
  private static final float MIN_MOVE_PX = 10f;
  private static final float RELEASE_GRACE_MS = 150f;
  private static final long MIN_INTERVAL_MS = 30L;

  private JoystickGestureMachine machine() {
    return new JoystickGestureMachine(RELEASE_GRACE_MS, MIN_INTERVAL_MS);
  }

  private static Step update(
      JoystickGestureMachine machine, long nowMs, boolean deflected, float tx, float ty) {
    return machine.update(nowMs, deflected, BASE_X, BASE_Y, tx, ty, MIN_MOVE_PX);
  }

  @Test
  public void neutralHead_whileIdle_dispatchesNothing() {
    JoystickGestureMachine machine = machine();
    for (int i = 0; i < 5; i++) {
      assertNull(update(machine, NOW + i * 16, false, BASE_X, BASE_Y));
    }
    assertFalse(machine.isPressed());
  }

  @Test
  public void deflectionOnset_pressesAtBaseAndDragsToTarget() {
    JoystickGestureMachine machine = machine();
    Step step = update(machine, NOW, true, TARGET_X, TARGET_Y);
    assertNotNull(step);
    assertEquals(Kind.PRESS_DRAG, step.kind);
    assertEquals(BASE_X, step.fromX, 0.001f);
    assertEquals(BASE_Y, step.fromY, 0.001f);
    assertEquals(TARGET_X, step.toX, 0.001f);
    assertEquals(TARGET_Y, step.toY, 0.001f);
    assertTrue(machine.isPressed());
  }

  @Test
  public void holdingStill_dispatchesNoAdditionalStrokes() {
    // The core regression test for the old behaviour: holding a deflection used to dispatch a
    // fresh short swipe on EVERY frame. Now a stationary hold must stay silent after the press.
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    for (int i = 1; i <= 30; i++) {
      assertNull("no extra stroke expected while holding still", update(machine, NOW + i * 16, true, TARGET_X, TARGET_Y));
    }
    assertTrue(machine.isPressed());
  }

  @Test
  public void smallTargetJitter_withinMinMove_dispatchesNothing() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    // Sub-threshold jitter around the target must not generate strokes.
    for (int i = 1; i <= 10; i++) {
      assertNull(update(machine, NOW + i * 16, true, TARGET_X + 2f, TARGET_Y - 2f));
    }
  }

  @Test
  public void targetMovesBeyondMinMove_dispatchesDragFromFinger() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    float newX = TARGET_X + 60f;
    float newY = TARGET_Y;
    Step step = update(machine, NOW + 40, true, newX, newY);
    assertNotNull(step);
    assertEquals(Kind.DRAG, step.kind);
    assertEquals(TARGET_X, step.fromX, 0.001f); // continues from where the finger is parked
    assertEquals(TARGET_Y, step.fromY, 0.001f);
    assertEquals(newX, step.toX, 0.001f);
  }

  @Test
  public void fastTargetChanges_areRateLimitedToLatestTarget() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    // A move within the 30 ms interval after the press is throttled (no extra stroke yet)...
    assertNull(update(machine, NOW + 10, true, TARGET_X + 60f, TARGET_Y));
    // ...once the interval elapses a drag to the latest target is dispatched...
    Step first = update(machine, NOW + 40, true, TARGET_X + 60f, TARGET_Y);
    assertNotNull(first);
    assertEquals(Kind.DRAG, first.kind);
    assertEquals(TARGET_X, first.fromX, 0.001f);
    assertEquals(TARGET_X + 60f, first.toX, 0.001f);
    // ...and another big move right after is throttled again, then caught up on the next tick.
    assertNull(update(machine, NOW + 50, true, TARGET_X + 120f, TARGET_Y));
    Step second = update(machine, NOW + 80, true, TARGET_X + 120f, TARGET_Y);
    assertNotNull(second);
    assertEquals(Kind.DRAG, second.kind);
    assertEquals(TARGET_X + 60f, second.fromX, 0.001f);
    assertEquals(TARGET_X + 120f, second.toX, 0.001f);
  }

  @Test
  public void release_requiresGraceWindow() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    // Deflection lost at NOW+100: inside the 150 ms grace the finger is still held.
    assertNull(update(machine, NOW + 100, false, BASE_X, BASE_Y));
    assertTrue("still pressed inside the release grace window", machine.isPressed());
    // Still inside the grace window 100 ms later.
    assertNull(update(machine, NOW + 200, false, BASE_X, BASE_Y));
    // After the grace expires (NOW+100 + 150) the finger lifts exactly where it is held.
    Step release = update(machine, NOW + 260, false, BASE_X, BASE_Y);
    assertNotNull(release);
    assertEquals(Kind.RELEASE, release.kind);
    assertEquals(TARGET_X, release.toX, 0.001f);
    assertEquals(TARGET_Y, release.toY, 0.001f);
    assertFalse(machine.isPressed());
    // And it stays idle afterwards.
    assertNull(update(machine, NOW + 300, false, BASE_X, BASE_Y));
  }

  @Test
  public void deadzoneJitter_doesNotReleaseTheHold() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    // Head dips below threshold briefly then comes back: no release may occur.
    assertNull(update(machine, NOW + 20, false, BASE_X, BASE_Y));
    assertTrue(machine.isPressed());
    Step resume = update(machine, NOW + 40, true, TARGET_X + 80f, TARGET_Y);
    assertNotNull(resume);
    assertTrue(machine.isPressed());
  }

  @Test
  public void forceRelease_liftsImmediatelyAndStaysIdle() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    Step release = machine.forceRelease();
    assertNotNull(release);
    assertEquals(Kind.RELEASE, release.kind);
    assertFalse(machine.isPressed());
    // A second force release is a no-op and a later deflection starts a fresh press.
    assertNull(machine.forceRelease());
    Step press = update(machine, NOW + 50, true, TARGET_X, TARGET_Y);
    assertNotNull(press);
    assertEquals(Kind.PRESS_DRAG, press.kind);
  }

  @Test
  public void reset_dropsPressedStateWithoutReleasing() {
    JoystickGestureMachine machine = machine();
    update(machine, NOW, true, TARGET_X, TARGET_Y);
    machine.reset();
    assertFalse(machine.isPressed());
    assertNull(update(machine, NOW + 100, false, BASE_X, BASE_Y));
  }

  @Test
  public void continuousHoldAndRelease_producesExactlyOneOfEachStroke() {
    // End-to-end: press -> hold through many ticks -> graceful release.
    JoystickGestureMachine machine = machine();
    Step press = update(machine, NOW, true, TARGET_X, TARGET_Y);
    assertEquals(Kind.PRESS_DRAG, press.kind);
    for (int i = 1; i <= 100; i++) {
      assertNull(update(machine, NOW + i * 16, true, TARGET_X, TARGET_Y));
    }
    // Grace starts when deflection is first lost (NOW + 1600 in this loop), then release.
    assertNull(update(machine, NOW + 1700, false, BASE_X, BASE_Y));
    Step release = update(machine, NOW + 1900, false, BASE_X, BASE_Y);
    assertEquals(Kind.RELEASE, release.kind);
    assertFalse(machine.isPressed());
  }
}
