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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a virtual joystick (base circle + movable thumb) on top of the game.
 *
 * <p>The view is rendered in a full-screen {@code TYPE_ACCESSIBILITY_OVERLAY} window that is not
 * touchable, so it only provides visual feedback about the head-tilt deflection; the actual game
 * input is emulated with {@code dispatchGesture} swipes by {@link JoystickController}.
 */
public final class JoystickOverlayView extends View {

  private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint baseBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private float centerX;
  private float centerY;
  private float radius;
  private float thumbOffsetX;
  private float thumbOffsetY;

  public JoystickOverlayView(Context context) {
    super(context);
    init();
  }

  public JoystickOverlayView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  private void init() {
    basePaint.setColor(Color.argb(40, 255, 255, 255));
    baseBorderPaint.setStyle(Paint.Style.STROKE);
    baseBorderPaint.setStrokeWidth(4f);
    baseBorderPaint.setColor(Color.argb(100, 255, 255, 255));
    thumbPaint.setColor(Color.argb(140, 105, 190, 255));
  }

  /**
   * @param cx Joystick base center X in pixels.
   * @param cy Joystick base center Y in pixels.
   * @param r Joystick base radius in pixels.
   */
  public void setJoystickParams(float cx, float cy, float r) {
    this.centerX = cx;
    this.centerY = cy;
    this.radius = r;
    invalidate();
  }

  /** @param dx Thumb offset from base center in pixels. @param dy Thumb offset from base center in pixels. */
  public void setThumbOffset(float dx, float dy) {
    this.thumbOffsetX = dx;
    this.thumbOffsetY = dy;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawCircle(centerX, centerY, radius, basePaint);
    canvas.drawCircle(centerX, centerY, radius, baseBorderPaint);
    float thumbX = centerX + thumbOffsetX;
    float thumbY = centerY + thumbOffsetY;
    canvas.drawCircle(thumbX, thumbY, radius * 0.35f, thumbPaint);
    // Direction indicator from base center to thumb.
    if (Math.abs(thumbOffsetX) > 1f || Math.abs(thumbOffsetY) > 1f) {
      canvas.drawLine(centerX, centerY, thumbX, thumbY, baseBorderPaint);
    }
  }
}
