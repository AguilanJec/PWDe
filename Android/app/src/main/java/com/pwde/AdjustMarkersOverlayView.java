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
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen, touchable overlay used to drag the voice skill tap markers and the joystick base
 * into place right on top of the running game.
 *
 * <p>Shown while the "edit positions" voice command is active. Unlike the passive {@link
 * SkillTapOverlayView}, this window is touchable: the player (or a helper) reaches out and slides
 * each marker under the real on-screen button. Positions are kept normalized (0..1) and written
 * back through {@link ScreenPlacementConfig}/{@link JoystickConfig} when a drag finishes, so the
 * markers, voice taps and the joystick all keep using the exact spot the player lined them up on.
 */
public final class AdjustMarkersOverlayView extends View {

  private static final int SKILL_MARKER_RADIUS_DP = 26;
  private static final int JOYSTICK_MARKER_RADIUS_DP = 30;
  private static final float LABEL_TEXT_SIZE_DP = 11f;
  private static final float HINT_TEXT_SIZE_DP = 13f;
  private static final float GRAB_SLOP_DP = 56f;

  private final Paint skillFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint skillRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint joystickFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint joystickRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint reachPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint hintBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  /** Full-screen projection over the current view; null until the view is laid out. */
  private ReferenceProjection projection;

  private float[] skillXs = new float[0];
  private float[] skillYs = new float[0];
  private String[] skillLabels = new String[0];
  private float joystickX;
  private float joystickY;
  private float joystickRadius;

  /** Marker currently grabbed: skill index, {@link #DRAG_JOYSTICK}, or {@link #NO_DRAG}. */
  private int draggingMarker = NO_DRAG;
  private float grabOffsetX;
  private float grabOffsetY;
  private float startFingerX;
  private float startFingerY;
  private boolean moved;

  /** Spoken phrase that exits this mode; shown in the on-screen hint. */
  private String finishPhrase = VoiceCommandConfig.DEFAULT_EDIT_POSITIONS_PHRASE;

  private static final int NO_DRAG = -1;
  private static final int DRAG_JOYSTICK = -2;

  /** Receives final (normalized) positions once a drag ends so they can be persisted. */
  public interface MarkerListener {
    void onSkillMarkerMoved(int index, float normX, float normY);

    void onJoystickBaseMoved(float normX, float normY);
  }

  private MarkerListener markerListener;

  public AdjustMarkersOverlayView(Context context) {
    super(context);
    init();
  }

  public AdjustMarkersOverlayView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  private void init() {
    skillFillPaint.setColor(Color.argb(40, 255, 255, 255));
    skillRingPaint.setStyle(Paint.Style.STROKE);
    skillRingPaint.setStrokeWidth(dp(2f));
    skillRingPaint.setColor(Color.argb(200, 255, 255, 255));

    joystickFillPaint.setColor(Color.argb(70, 30, 136, 229));
    joystickRingPaint.setStyle(Paint.Style.STROKE);
    joystickRingPaint.setStrokeWidth(dp(2f));
    joystickRingPaint.setColor(Color.argb(230, 30, 136, 229));

    reachPaint.setStyle(Paint.Style.STROKE);
    reachPaint.setStrokeWidth(dp(1.5f));
    reachPaint.setColor(Color.argb(110, 30, 136, 229));

    labelPaint.setColor(Color.WHITE);
    labelPaint.setTextSize(dp(LABEL_TEXT_SIZE_DP));
    labelPaint.setTextAlign(Paint.Align.CENTER);
    labelPaint.setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK);

    hintPaint.setColor(Color.WHITE);
    hintPaint.setTextSize(dp(HINT_TEXT_SIZE_DP));
    hintPaint.setTextAlign(Paint.Align.CENTER);
    hintPaint.setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK);

    hintBackPaint.setColor(Color.argb(150, 0, 0, 0));
  }

  /**
   * @param skillXs Skill positions as normalized (0..1) screen fractions.
   * @param skillYs Skill positions as normalized (0..1) screen fractions.
   * @param skillLabels Label under each skill marker (the voice word bound to the skill).
   * @param joystickX Joystick base center, normalized.
   * @param joystickY Joystick base center, normalized.
   * @param joystickRadius Joystick reach radius, normalized fraction of the smaller screen side.
   */
  public void setModel(
      float[] skillXs,
      float[] skillYs,
      String[] skillLabels,
      float joystickX,
      float joystickY,
      float joystickRadius) {
    int count = Math.min(Math.min(skillXs.length, skillYs.length), skillLabels.length);
    this.skillXs = new float[count];
    this.skillYs = new float[count];
    this.skillLabels = new String[count];
    System.arraycopy(skillXs, 0, this.skillXs, 0, count);
    System.arraycopy(skillYs, 0, this.skillYs, 0, count);
    System.arraycopy(skillLabels, 0, this.skillLabels, 0, count);
    this.joystickX = joystickX;
    this.joystickY = joystickY;
    this.joystickRadius = joystickRadius;
    invalidate();
  }

  public void setMarkerListener(MarkerListener listener) {
    this.markerListener = listener;
  }

  /** Update the phrase shown in the hint (the current configured "edit positions" keyword). */
  public void setFinishPhrase(String phrase) {
    this.finishPhrase =
        phrase == null || phrase.isEmpty()
            ? VoiceCommandConfig.DEFAULT_EDIT_POSITIONS_PHRASE
            : phrase;
    invalidate();
  }

  // Package-private accessors for the geometry tests.

  float skillX(int index) {
    return index >= 0 && index < skillXs.length ? skillXs[index] : 0f;
  }

  float skillY(int index) {
    return index >= 0 && index < skillYs.length ? skillYs[index] : 0f;
  }

  float joystickX() {
    return joystickX;
  }

  float joystickY() {
    return joystickY;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w > 0 && h > 0) {
      projection = new ReferenceProjection(0f, 0f, w, h);
    }
  }

  /** The current full-screen projection, built lazily once the view has a real size. */
  private ReferenceProjection projection() {
    if (projection == null && getWidth() > 0 && getHeight() > 0) {
      projection = new ReferenceProjection(0f, 0f, getWidth(), getHeight());
    }
    return projection;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    float touchX = event.getX();
    float touchY = event.getY();
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        draggingMarker = findGrabbedMarker(touchX, touchY);
        moved = false;
        startFingerX = touchX;
        startFingerY = touchY;
        if (draggingMarker == NO_DRAG) {
          return true; // Consume calibration touches so they never reach the game.
        }
        grabOffsetX = touchX - markerCenterX(draggingMarker);
        grabOffsetY = touchY - markerCenterY(draggingMarker);
        return true;

      case MotionEvent.ACTION_MOVE:
        if (draggingMarker == NO_DRAG || projection() == null) {
          return true;
        }
        moveMarkerTo(draggingMarker, touchX - grabOffsetX, touchY - grabOffsetY);
        moved =
            moved
                || Math.hypot(touchX - startFingerX, touchY - startFingerY) > dp(4f);
        invalidate();
        return true;

      case MotionEvent.ACTION_UP:
        if (draggingMarker != NO_DRAG && moved) {
          notifyMoved(draggingMarker);
        }
        draggingMarker = NO_DRAG;
        return true;

      case MotionEvent.ACTION_CANCEL:
        draggingMarker = NO_DRAG;
        return true;

      default:
        return true;
    }
  }

  private int findGrabbedMarker(float touchX, float touchY) {
    int best = NO_DRAG;
    float bestDistance = dp(GRAB_SLOP_DP);
    for (int i = 0; i < skillXs.length; i++) {
      float distance = distanceTo(i, touchX, touchY);
      if (distance <= bestDistance) {
        best = i;
        bestDistance = distance;
      }
    }
    float joystickDistance = distanceTo(DRAG_JOYSTICK, touchX, touchY);
    if (joystickDistance <= bestDistance) {
      best = DRAG_JOYSTICK;
    }
    return best;
  }

  /** Center X in screen pixels of {@code marker} (skill index or {@link #DRAG_JOYSTICK}). */
  private float markerCenterX(int marker) {
    if (projection() == null) {
      return 0f;
    }
    if (marker == DRAG_JOYSTICK) {
      return projection.pxFromNormX(joystickX);
    }
    return projection.pxFromNormX(skillXs[marker]);
  }

  private float markerCenterY(int marker) {
    if (projection() == null) {
      return 0f;
    }
    if (marker == DRAG_JOYSTICK) {
      return projection.pxFromNormY(joystickY);
    }
    return projection.pxFromNormY(skillYs[marker]);
  }

  private float distanceTo(int marker, float x, float y) {
    float dx = x - markerCenterX(marker);
    float dy = y - markerCenterY(marker);
    return (float) Math.hypot(dx, dy);
  }

  /** Move {@code marker} so its center sits at (px, py), clamped and kept out of the joystick. */
  private void moveMarkerTo(int marker, float centerXpx, float centerYpx) {
    if (projection() == null) {
      return;
    }
    float normX = clampNorm(projection.normFromPxX(centerXpx));
    float normY = clampNorm(projection.normFromPxY(centerYpx));
    if (marker == DRAG_JOYSTICK) {
      joystickX = normX;
      joystickY = normY;
      return;
    }
    float[] pushed =
        pushOutOfJoystick(
            normX,
            normY,
            projection,
            joystickX,
            joystickY,
            joystickReachPx() + dp(JOYSTICK_MARKER_RADIUS_DP) + dp(6));
    skillXs[marker] = pushed[0];
    skillYs[marker] = pushed[1];
  }

  private void notifyMoved(int marker) {
    if (markerListener == null) {
      return;
    }
    if (marker == DRAG_JOYSTICK) {
      markerListener.onJoystickBaseMoved(joystickX, joystickY);
    } else {
      markerListener.onSkillMarkerMoved(marker, skillXs[marker], skillYs[marker]);
    }
  }

  private float joystickReachPx() {
    if (projection() == null) {
      return 0f;
    }
    return projection.screenRadius(joystickRadius);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (projection() == null || getWidth() <= 0 || getHeight() <= 0) {
      return;
    }

    // Joystick reach ring first so skill markers drawn on top of it stay visible.
    float reach = joystickReachPx();
    float jx = projection.pxFromNormX(joystickX);
    float jy = projection.pxFromNormY(joystickY);
    if (reach > 0f) {
      canvas.drawCircle(jx, jy, reach, reachPaint);
    }
    float joystickRadius = dp(JOYSTICK_MARKER_RADIUS_DP);
    canvas.drawCircle(jx, jy, joystickRadius, joystickFillPaint);
    canvas.drawCircle(jx, jy, joystickRadius, joystickRingPaint);
    canvas.drawLine(jx - joystickRadius * 0.4f, jy, jx + joystickRadius * 0.4f, jy, joystickRingPaint);
    canvas.drawLine(jx, jy - joystickRadius * 0.4f, jx, jy + joystickRadius * 0.4f, joystickRingPaint);

    float skillRadius = dp(SKILL_MARKER_RADIUS_DP);
    float labelGap = dp(3f);
    float textSize = labelPaint.getTextSize();
    for (int i = 0; i < skillXs.length; i++) {
      float cx = projection.pxFromNormX(skillXs[i]);
      float cy = projection.pxFromNormY(skillYs[i]);
      canvas.drawCircle(cx, cy, skillRadius, skillFillPaint);
      canvas.drawCircle(cx, cy, skillRadius, skillRingPaint);
      float baseline;
      if (cy + skillRadius + labelGap + textSize > getHeight() - dp(2f)) {
        baseline = cy - skillRadius - labelGap - dp(2f);
      } else {
        baseline = cy + skillRadius + labelGap + textSize;
      }
      canvas.drawText(skillLabels[i], cx, baseline, labelPaint);
    }

    drawHint(canvas);
  }

  private void drawHint(Canvas canvas) {
    String hint =
        "Drag the markers onto the real buttons, then say \""
            + finishPhrase
            + "\" to finish";
    float textWidth = hintPaint.measureText(hint);
    float left = (getWidth() - textWidth) / 2f - dp(14f);
    float top = dp(10f);
    float right = left + textWidth + dp(28f);
    float bottom = top + dp(HINT_TEXT_SIZE_DP) + dp(20f);
    RectF back = new RectF(Math.max(left, 0f), top, Math.min(right, getWidth()), bottom);
    canvas.drawRoundRect(back, dp(8f), dp(8f), hintBackPaint);
    canvas.drawText(hint, getWidth() / 2f, top + dp(HINT_TEXT_SIZE_DP) + dp(10f), hintPaint);
  }

  // ---------------------------------------------------------------------------------------------
  // Pure geometry helpers (package-private static so the unit tests can exercise them directly).
  // ---------------------------------------------------------------------------------------------

  /** Clamp a normalized (0..1) coordinate into the visible screen range. */
  static float clampNorm(float value) {
    return Math.min(1f, Math.max(0f, value));
  }

  /**
   * Push a skill marker's normalized position out of the joystick's reach circle (mirrors the
   * placement editor so a skill marker never hides under the joystick base). The reach is given in
   * screen pixels and already includes any clearance; returns the adjusted normalized position.
   */
  static float[] pushOutOfJoystick(
      float normX,
      float normY,
      ReferenceProjection projection,
      float joystickNormX,
      float joystickNormY,
      float reachRadiusPx) {
    if (projection == null) {
      return new float[] {clampNorm(normX), clampNorm(normY)};
    }
    float jx = projection.pxFromNormX(joystickNormX);
    float jy = projection.pxFromNormY(joystickNormY);
    float mx = projection.pxFromNormX(normX);
    float my = projection.pxFromNormY(normY);
    float dx = mx - jx;
    float dy = my - jy;
    float distance = (float) Math.hypot(dx, dy);
    if (distance >= reachRadiusPx) {
      return new float[] {clampNorm(normX), clampNorm(normY)};
    }
    // Push outward along the joystick-center ray; when the marker sits exactly on the center,
    // push it straight to the right edge of the reach so it can never hide under the base.
    float unitX;
    float unitY;
    if (distance > 0.0001f) {
      unitX = dx / distance;
      unitY = dy / distance;
    } else {
      unitX = 1f;
      unitY = 0f;
    }
    return new float[] {
      clampNorm(projection.normFromPxX(jx + unitX * reachRadiusPx)),
      clampNorm(projection.normFromPxY(jy + unitY * reachRadiusPx))
    };
  }

  private float dp(float value) {
    return value * getResources().getDisplayMetrics().density;
  }
}
