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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Geometry of the touch-drag calibration overlay: normalized round-trips, edge clamping and the
 * skill-marker push-out that keeps markers clear of the joystick reach.
 */
@RunWith(AndroidJUnit4.class)
public class AdjustMarkersOverlayViewTest {

  private static final float VIEW_WIDTH = 1080f;
  private static final float VIEW_HEIGHT = 2400f;

  /** Joystick defaults used by {@link #newView}: center (0.18, 0.82), radius 0.15. */
  private static final float JOYSTICK_X = 0.18f;
  private static final float JOYSTICK_Y = 0.82f;

  private static final class Moved {
    Integer skillIndex;
    float skillX;
    float skillY;
    float joystickX;
    float joystickY;
    boolean joystickMoved;
  }

  @Test
  public void dragSkillMarker_movesNormalizedPositionAndNotifies() {
    Moved moved = new Moved();
    AdjustMarkersOverlayView view = newView(moved);

    // Skill 0 starts at (0.5, 0.5) -> screen (540, 1200). Drag it 100px right and down.
    drag(view, 540f, 1200f, 640f, 1300f);

    assertEquals(Integer.valueOf(0), moved.skillIndex);
    assertEquals(0.5f + 100f / VIEW_WIDTH, moved.skillX, 0.001f);
    assertEquals(0.5f + 100f / VIEW_HEIGHT, moved.skillY, 0.001f);
    // The view model itself reflects the move (visual geometry stays consistent).
    assertEquals(moved.skillX, view.skillX(0), 0.001f);
    assertEquals(moved.skillY, view.skillY(0), 0.001f);
  }

  @Test
  public void dragJoystickBase_movesBaseAndNotifies() {
    Moved moved = new Moved();
    AdjustMarkersOverlayView view = newView(moved);

    // Joystick starts at (0.18, 0.82) -> (194.4, 1968). Move it 100px left, 100px up.
    drag(view, 194.4f, 1968f, 94.4f, 1868f);

    assertNull(moved.skillIndex);
    assertTrue(moved.joystickMoved);
    assertEquals(0.18f - 100f / VIEW_WIDTH, moved.joystickX, 0.001f);
    assertEquals(0.82f - 100f / VIEW_HEIGHT, moved.joystickY, 0.001f);
    assertEquals(moved.joystickX, view.joystickX(), 0.001f);
    assertEquals(moved.joystickY, view.joystickY(), 0.001f);
  }

  @Test
  public void dragBeyondScreen_clampsToEdges() {
    Moved moved = new Moved();
    AdjustMarkersOverlayView view = newView(moved);

    drag(view, 540f, 1200f, 5000f, -4000f);

    assertEquals(1f, moved.skillX, 0.001f);
    assertEquals(0f, moved.skillY, 0.001f);
  }

  @Test
  public void skillDraggedOntoJoystickCenter_isPushedToReachEdge() {
    Moved moved = new Moved();
    AdjustMarkersOverlayView view = newView(moved);
    float density = view.getResources().getDisplayMetrics().density;
    float reachPx = 162f; // 0.15 radius fraction of the 1080px smaller screen side.
    float clearancePx = density * (30f + 6f); // joystick marker radius + gap, in dp.

    // Drag skill 0 (starts at (0.5, 0.5)) exactly onto the joystick center.
    drag(view, 540f, 1200f, 194.4f, 1968f);

    double distance = markerDistancePx(moved.skillX, moved.skillY);
    assertTrue(
        "expected distance ~" + (reachPx + clearancePx) + " but was " + distance,
        Math.abs(distance - (reachPx + clearancePx)) < 1.0);
  }

  @Test
  public void skillInsideReach_isPushedBackOutsideEvenForSmallDrags() {
    Moved moved = new Moved();
    AdjustMarkersOverlayView view = newView(moved);
    float density = view.getResources().getDisplayMetrics().density;
    float reachPx = 162f;
    float clearancePx = density * (30f + 6f);

    // Skill 1 begins at (0.19, 0.80) -> inside the joystick reach (distance ~49px < 162px).
    // Even a small drag must push it back out of the reach circle.
    drag(view, 205.2f, 1920f, 215.2f, 1930f);

    assertEquals(Integer.valueOf(1), moved.skillIndex);
    double distance = markerDistancePx(moved.skillX, moved.skillY);
    assertTrue(
        "marker left inside the reach (" + distance + ")",
        distance >= reachPx + clearancePx - 1.0);
  }

  private static double markerDistancePx(float skillNormX, float skillNormY) {
    double dx = (skillNormX - JOYSTICK_X) * VIEW_WIDTH;
    double dy = (skillNormY - JOYSTICK_Y) * VIEW_HEIGHT;
    return Math.hypot(dx, dy);
  }

  private static AdjustMarkersOverlayView newView(final Moved moved) {
    AdjustMarkersOverlayView view =
        new AdjustMarkersOverlayView(ApplicationProvider.getApplicationContext());
    view.setModel(
        new float[] {0.5f, 0.19f, 0.75f},
        new float[] {0.5f, 0.80f, 0.85f},
        new String[] {"one", "two", "three"},
        JOYSTICK_X,
        JOYSTICK_Y,
        0.15f);
    view.setMarkerListener(
        new AdjustMarkersOverlayView.MarkerListener() {
          @Override
          public void onSkillMarkerMoved(int index, float normX, float normY) {
            moved.skillIndex = index;
            moved.skillX = normX;
            moved.skillY = normY;
          }

          @Override
          public void onJoystickBaseMoved(float normX, float normY) {
            moved.joystickMoved = true;
            moved.joystickX = normX;
            moved.joystickY = normY;
          }
        });
    view.layout(0, 0, (int) VIEW_WIDTH, (int) VIEW_HEIGHT);
    return view;
  }

  private static void drag(
      AdjustMarkersOverlayView view, float downX, float downY, float upX, float upY) {
    long now = SystemClock.uptimeMillis();
    view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, downX, downY, 0));
    // Intermediate moves so the drag registers as actual movement.
    view.onTouchEvent(
        MotionEvent.obtain(
            now, now + 10, MotionEvent.ACTION_MOVE, (downX + upX) / 2f, (downY + upY) / 2f, 0));
    view.onTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, upX, upY, 0));
    view.onTouchEvent(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, upX, upY, 0));
  }
}
