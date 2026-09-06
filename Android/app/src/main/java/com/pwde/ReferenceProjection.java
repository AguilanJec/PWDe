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
 * Maps normalized (0..1) coordinates onto a viewport region of the phone screen.
 *
 * <p>Placements (skill buttons and the joystick base) are stored as fractions of the screen, so a
 * marker placed at the same relative spot in a full-screen reference picture must land at that
 * exact screen fraction on any device. This projection therefore maps each axis linearly across
 * the FULL viewport: {@code px = left + nx * width}, {@code py = top + ny * height}. The bundled
 * reference image is drawn into the same full viewport (edge-to-edge, no letterboxing), so what
 * the user sees in the placement editor is what the runtime overlay and taps actually use.
 *
 * <p>The reference image's own pixel dimensions are irrelevant for this mapping; only the viewport
 * matters. This is intentional: the reference is a full screenshot of the game screen, and the
 * normalized coordinate system treats the whole screen as 0..1 on both axes.
 */
public final class ReferenceProjection {

  private final float viewportLeft;
  private final float viewportTop;
  private final float viewportWidth;
  private final float viewportHeight;
  private final float minScreenDim;

  /**
   * @param viewportLeft Viewport left in screen pixels (0 for a full-screen overlay).
   * @param viewportTop Viewport top in screen pixels (0 for a full-screen overlay).
   * @param viewportWidth Viewport width in screen pixels.
   * @param viewportHeight Viewport height in screen pixels.
   */
  public ReferenceProjection(
      float viewportLeft, float viewportTop, float viewportWidth, float viewportHeight) {
    this.viewportLeft = viewportLeft;
    this.viewportTop = viewportTop;
    this.viewportWidth = viewportWidth;
    this.viewportHeight = viewportHeight;
    this.minScreenDim = Math.min(viewportWidth, viewportHeight);
  }

  /** Viewport width passed to the constructor (screen pixels). */
  public float viewportWidth() {
    return viewportWidth;
  }

  /** Viewport height passed to the constructor (screen pixels). */
  public float viewportHeight() {
    return viewportHeight;
  }

  /** The smaller viewport dimension, used to size the joystick base consistently. */
  public float minScreenDim() {
    return minScreenDim;
  }

  /** Screen X for a normalized (0..1) screen-space X. */
  public float pxFromNormX(float nx) {
    return viewportLeft + nx * viewportWidth;
  }

  /** Screen Y for a normalized (0..1) screen-space Y. */
  public float pxFromNormY(float ny) {
    return viewportTop + ny * viewportHeight;
  }

  /** Normalized screen-space X for a screen X. */
  public float normFromPxX(float px) {
    return (px - viewportLeft) / viewportWidth;
  }

  /** Normalized screen-space Y for a screen Y. */
  public float normFromPxY(float py) {
    return (py - viewportTop) / viewportHeight;
  }

  /** A radius expressed as a fraction of the smaller viewport dimension, in screen pixels. */
  public float screenRadius(float fraction) {
    return fraction * minScreenDim;
  }

  // Destination rect (screen pixels) into which the reference image is drawn. This is exactly the
  // full viewport: the picture is stretched edge-to-edge so every button in it sits at the same
  // normalized coordinate the runtime uses. No letterboxing, no cropping.
  public float destLeft() {
    return viewportLeft;
  }

  public float destTop() {
    return viewportTop;
  }

  public float destWidth() {
    return viewportWidth;
  }

  public float destHeight() {
    return viewportHeight;
  }
}
