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
 * Draws faint on-screen markers at the voice skill tap points on top of the game.
 *
 * <p>Rendered in a full-screen {@code TYPE_ACCESSIBILITY_OVERLAY} window that is not touchable,
 * mirroring {@link JoystickOverlayView}: the view is visual reference only. It shows where the
 * skill-word voice commands will tap so players can line the markers up with the actual skill
 * buttons of the game (positions are passed in screen pixels, the same space the taps use).
 */
public final class SkillTapOverlayView extends View {

  private static final int MARKER_RADIUS_DP = 24;
  private static final float LABEL_TEXT_SIZE_DP = 11f;

  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private float[] xs = new float[0];
  private float[] ys = new float[0];
  private String[] labels = new String[0];

  public SkillTapOverlayView(Context context) {
    super(context);
    init();
  }

  public SkillTapOverlayView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  private void init() {
    // Translucent centre keeps the actual game button visible under the marker.
    fillPaint.setColor(Color.argb(45, 255, 255, 255));
    ringPaint.setStyle(Paint.Style.STROKE);
    ringPaint.setStrokeWidth(dp(2f));
    ringPaint.setColor(Color.argb(220, 255, 255, 255));
    labelPaint.setColor(Color.WHITE);
    labelPaint.setTextSize(dp(LABEL_TEXT_SIZE_DP));
    labelPaint.setTextAlign(Paint.Align.CENTER);
    labelPaint.setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK);
  }

  /**
   * @param markerXs Skill tap X positions in screen pixels.
   * @param markerYs Skill tap Y positions in screen pixels.
   * @param markerLabels The spoken word bound to each skill (shown under the marker).
   */
  public void setSkillPoints(float[] markerXs, float[] markerYs, String[] markerLabels) {
    int count = Math.min(Math.min(markerXs.length, markerYs.length), markerLabels.length);
    xs = new float[count];
    ys = new float[count];
    labels = new String[count];
    System.arraycopy(markerXs, 0, xs, 0, count);
    System.arraycopy(markerYs, 0, ys, 0, count);
    System.arraycopy(markerLabels, 0, labels, 0, count);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float radius = dp(MARKER_RADIUS_DP);
    float labelGap = dp(3f);
    for (int i = 0; i < xs.length; i++) {
      float cx = xs[i];
      float cy = ys[i];
      canvas.drawCircle(cx, cy, radius, fillPaint);
      canvas.drawCircle(cx, cy, radius, ringPaint);
      canvas.drawText(
          labels[i], cx, cy + radius + labelGap + labelPaint.getTextSize(), labelPaint);
    }
  }

  private float dp(float value) {
    return value * getResources().getDisplayMetrics().density;
  }
}
