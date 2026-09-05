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

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * Visual placement editor: instead of typing raw 0..1 coordinates, the user drags on-screen
 * markers to the exact spot a skill button (or the virtual joystick base) sits in their game.
 *
 * <p>Coordinates are stored normalized (0..1) through {@link ScreenPlacementConfig} (voice skill
 * taps) or {@link JoystickConfig} (joystick base center), so positions survive orientation and
 * screen-size changes and stay per-profile.
 */
public final class PlacementEditorActivity extends AppCompatActivity {

  public static final String EXTRA_MODE = "pwde_placement_mode";
  public static final String MODE_VOICE_SKILLS = "voice_skills";
  public static final String MODE_JOYSTICK = "joystick";

  /** One draggable marker with its normalized (0..1) position on the preview. */
  private static final class Marker {
    final int index; // Skill index (0..2); -1 for the joystick base.
    final String label;
    float x;
    float y;

    Marker(int index, String label, float x, float y) {
      this.index = index;
      this.label = label;
      this.x = x;
      this.y = y;
    }
  }

  private String mode;
  private final List<Marker> markers = new ArrayList<>();
  private PlacementPreview preview;
  private boolean showReference; // Toggleable positioning aid: reference image behind the marker.

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mode = getIntent().getStringExtra(EXTRA_MODE);
    if (!MODE_VOICE_SKILLS.equals(mode)) {
      mode = MODE_JOYSTICK;
    }
    loadMarkers();

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(16), dp(16), dp(16));
    root.setBackgroundColor(getColor(R.color.esports_bg));

    TextView title = new TextView(this);
    title.setText(
        MODE_VOICE_SKILLS.equals(mode) ? "Skill positions" : "Joystick position");
    title.setTextSize(22);
    title.setTextColor(getColor(R.color.esports_text));
    root.addView(title);

    TextView description = new TextView(this);
    if (MODE_VOICE_SKILLS.equals(mode)) {
      description.setText(
          "Drag each marker to the exact spot of its button in your game. "
              + "The voice words from your Voice settings are: "
              + voiceWordsSummary());
    } else {
      description.setText(
          "Drag the joystick to where you want its base to sit. Head tilt drives a virtual "
              + "thumb that starts from this spot.");
    }
    description.setTextColor(getColor(R.color.esports_text_dim));
    if (isLandscape()) {
      // Short landscape screens must keep the fixed chrome compact so the draggable preview
      // below keeps enough height; cap the prose rows at two lines.
      description.setMaxLines(2);
      description.setEllipsize(TextUtils.TruncateAt.END);
    }
    root.addView(description);

    preview = new PlacementPreview(this);
    preview.setLayoutParams(
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, /* weight= */ 1f));

    // Both modes are positioned against a real game screen, so offer an optional reference
    // image (background_reference) to line the markers up. Visual aid only - never persisted.
    LinearLayout referenceRow = new LinearLayout(this);
    referenceRow.setOrientation(LinearLayout.HORIZONTAL);
    referenceRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView referenceLabel = new TextView(this);
    referenceLabel.setText("Background reference");
    referenceLabel.setTextColor(getColor(R.color.esports_text));
    referenceRow.addView(
        referenceLabel,
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    Switch referenceSwitch = new Switch(this);
    referenceSwitch.setOnCheckedChangeListener(
        (button, checked) -> {
          showReference = checked;
          preview.invalidate();
        });
    referenceRow.addView(referenceSwitch);
    root.addView(referenceRow);

    root.addView(preview);

    TextView hint = new TextView(this);
    hint.setText("Tip: keep your head centred while you drag; tap and slide a marker.");
    hint.setTextColor(getColor(R.color.esports_text_dim));
    hint.setGravity(Gravity.CENTER_HORIZONTAL);
    if (isLandscape()) {
      hint.setMaxLines(2);
      hint.setEllipsize(TextUtils.TruncateAt.END);
    }
    root.addView(hint);

    LinearLayout buttons = new LinearLayout(this);
    buttons.setOrientation(LinearLayout.HORIZONTAL);

    Button reset = new Button(this);
    reset.setText("Reset");
    reset.setOnClickListener(v -> resetMarkers());
    buttons.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    Button cancel = new Button(this);
    cancel.setText("Cancel");
    cancel.setOnClickListener(v -> finish());
    buttons.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    Button save = new Button(this);
    save.setText("Save");
    save.setOnClickListener(v -> saveAndClose());
    buttons.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    root.addView(buttons);
    setContentView(root);
  }

  private void loadMarkers() {
    markers.clear();
    if (MODE_VOICE_SKILLS.equals(mode)) {
      ScreenPlacementConfig placements = new ScreenPlacementConfig(this);
      List<ScreenPlacement> skills = placements.getSkills();
      for (int i = 0; i < skills.size(); i++) {
        ScreenPlacement placement = skills.get(i);
        markers.add(new Marker(i, placement.label, placement.x, placement.y));
      }
    } else {
      JoystickConfig config = JoystickConfig.load(this);
      markers.add(new Marker(-1, "Joystick base", config.centerX, config.centerY));
    }
  }

  /** Compact summary of the current per-skill voice words, e.g. Skill 1 says "1, one". */
  private String voiceWordsSummary() {
    VoiceCommandConfig config = VoiceCommandConfig.load(this);
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < ScreenPlacementConfig.skillCount(); i++) {
      parts.add(
          ScreenPlacementConfig.SKILL_LABELS[i]
              + " says \""
              + TextUtils.join(", ", config.getSkillPhrases(i))
              + "\"");
    }
    return TextUtils.join(", ", parts);
  }

  private void resetMarkers() {
    if (MODE_VOICE_SKILLS.equals(mode)) {
      for (int i = 0; i < markers.size(); i++) {
        Marker marker = markers.get(i);
        marker.x = ScreenPlacementConfig.DEFAULT_SKILL_X[marker.index];
        marker.y = ScreenPlacementConfig.DEFAULT_SKILL_Y[marker.index];
      }
    } else {
      markers.get(0).x = 0.18f; // Matches JoystickConfig.DEFAULT_CENTER_X.
      markers.get(0).y = 0.82f; // Matches JoystickConfig.DEFAULT_CENTER_Y.
    }
    preview.invalidate();
  }

  private void saveAndClose() {
    if (MODE_VOICE_SKILLS.equals(mode)) {
      ScreenPlacementConfig placements = new ScreenPlacementConfig(this);
      for (Marker marker : markers) {
        placements.setSkillPoint(marker.index, marker.x, marker.y);
      }
    } else {
      JoystickConfig config = JoystickConfig.load(this);
      config.centerX = markers.get(0).x;
      config.centerY = markers.get(0).y;
      config.save(this);
    }
    Toast.makeText(this, "Positions saved.", Toast.LENGTH_SHORT).show();
    sendBroadcast(new Intent("LOAD_PROFILE"));
    finish();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private boolean isLandscape() {
    return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
  }

  /** Draws the "screen" and lets the user drag each marker. Positions are reported in 0..1. */
  private final class PlacementPreview extends View {

    private static final int MARKER_RADIUS_DP = 30;
    private static final int GRAB_SLOP_DP = 44;
    private static final int INSET_DP = 12;

    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridOnImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect = new RectF();
    private final RectF imageRect = new RectF();
    private final Path clipPath = new Path();
    private final Bitmap referenceBitmap; // The toggleable positioning aid.

    private Marker dragging;
    private float dragOffsetX;
    private float dragOffsetY;
    private final float joystickRadius;

    PlacementPreview(PlacementEditorActivity activity) {
      super(activity);
      joystickRadius = JoystickConfig.load(activity).radius;
      screenPaint.setStyle(Paint.Style.FILL);
      screenPaint.setColor(Color.parseColor("#16181D"));
      screenPaint.setStrokeWidth(0f);
      gridPaint.setStyle(Paint.Style.STROKE);
      gridPaint.setColor(Color.parseColor("#2A2E37"));
      gridPaint.setStrokeWidth(dp(1));
      gridOnImagePaint.setStyle(Paint.Style.STROKE);
      gridOnImagePaint.setColor(Color.argb(150, 255, 255, 255));
      gridOnImagePaint.setStrokeWidth(dp(1));
      scrimPaint.setColor(Color.argb(90, 0, 0, 0));
      referenceBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background_reference);
      basePaint.setStyle(Paint.Style.FILL);
      basePaint.setColor(Color.parseColor("#3D7BFF"));
      ringPaint.setStyle(Paint.Style.STROKE);
      ringPaint.setColor(Color.parseColor("#16181D"));
      ringPaint.setStrokeWidth(dp(3));
      textPaint.setColor(Color.WHITE);
      textPaint.setTextSize(dp(12));
      textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Toggle the reference image on/off (off = plain screen + grid). */
    void setShowReference(boolean show) {
      showReference = show;
      invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      int height = MeasureSpec.getSize(heightMeasureSpec);
      // The preview is always a phone screen shape: tall when portrait, wide when landscape.
      setMeasuredDimension(width, height);
    }

    /**
     * Computes the marker coordinate space for this frame. Always the inset phone "screen";
     * when the reference image is on, the largest centered region of that screen whose aspect
     * ratio matches the image (contain-fit), so normalized positions hit the picture 1:1.
     * Fills {@link #screenRect} (phone area) and, while the reference is shown, {@link
     * #imageRect}; returns the rect markers, grid and joystick reach must be interpreted
     * against, or null when the view is too small to draw.
     */
    private RectF contentRect() {
      float left = getPaddingLeft() + dp(INSET_DP);
      float top = getPaddingTop() + dp(INSET_DP);
      float right = getWidth() - getPaddingRight() - dp(INSET_DP);
      float bottom = getHeight() - getPaddingBottom() - dp(INSET_DP);
      if (right <= left || bottom <= top) {
        return null;
      }
      screenRect.set(left, top, right, bottom);
      if (showReference && referenceBitmap != null) {
        float scale =
            Math.min((right - left) / referenceBitmap.getWidth(),
                (bottom - top) / referenceBitmap.getHeight());
        float drawWidth = referenceBitmap.getWidth() * scale;
        float drawHeight = referenceBitmap.getHeight() * scale;
        imageRect.set(
            left + (right - left - drawWidth) / 2f,
            top + (bottom - top - drawHeight) / 2f,
            left + (right - left + drawWidth) / 2f,
            top + (bottom - top + drawHeight) / 2f);
        return imageRect;
      }
      return screenRect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      RectF content = contentRect();
      if (content == null) {
        return;
      }
      canvas.drawRoundRect(screenRect, dp(16), dp(16), screenPaint);

      if (showReference && referenceBitmap != null) {
        // Contain-fit the reference image inside the rounded phone area: the whole game
        // screen stays visible (never cropped) so marker positions map 1:1 onto the picture.
        // content == imageRect here; the letterboxed area outside keeps the plain phone look.
        clipPath.reset();
        clipPath.addRoundRect(screenRect, dp(16), dp(16), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        // Source rect is the whole bitmap (null), destination is imageRect.
        canvas.drawBitmap(referenceBitmap, null, imageRect, null);
        // Dim the reference slightly so the marker and its reach circle stay readable.
        canvas.drawRoundRect(imageRect, dp(16), dp(16), scrimPaint);
        canvas.restore();
      }

      // Grid so the user can line markers up (brighter when drawn over the reference image).
      Paint grid = showReference ? gridOnImagePaint : gridPaint;
      for (int i = 1; i <= 2; i++) {
        float y = content.top + content.height() * i / 3f;
        canvas.drawLine(content.left, y, content.right, y, grid);
        float x = content.left + content.width() * i / 3f;
        canvas.drawLine(x, content.top, x, content.bottom, grid);
      }

      for (Marker marker : markers) {
        float cx = content.left + marker.x * content.width();
        float cy = content.top + marker.y * content.height();
        boolean isJoystick = marker.index < 0;
        int radius = dp(MARKER_RADIUS_DP);
        if (isJoystick) {
          // The joystick also shows the reachable thumb area (radius from JoystickConfig).
          float reach = Math.min(content.width(), content.height()) * joystickRadius;
          Paint reachPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
          reachPaint.setStyle(Paint.Style.STROKE);
          reachPaint.setColor(Color.parseColor("#553D7BFF"));
          reachPaint.setStrokeWidth(dp(2));
          canvas.drawCircle(cx, cy, reach, reachPaint);
        }
        canvas.drawCircle(cx, cy, radius, basePaint);
        canvas.drawCircle(cx, cy, radius, ringPaint);
        canvas.drawText(marker.label, cx, cy + textPaint.getTextSize() * 0.4f, textPaint);
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      RectF content = contentRect();
      if (content == null) {
        return false;
      }

      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragging = markerNear(event.getX(), event.getY(), content);
          if (dragging != null) {
            float cx = content.left + dragging.x * content.width();
            float cy = content.top + dragging.y * content.height();
            dragOffsetX = event.getX() - cx;
            dragOffsetY = event.getY() - cy;
            return true;
          }
          return false;
        case MotionEvent.ACTION_MOVE:
          if (dragging != null) {
            float cx = event.getX() - dragOffsetX;
            float cy = event.getY() - dragOffsetY;
            dragging.x = clamp((cx - content.left) / content.width());
            dragging.y = clamp((cy - content.top) / content.height());
            invalidate();
            return true;
          }
          return false;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          dragging = null;
          return true;
        default:
          return false;
      }
    }

    private Marker markerNear(float x, float y, RectF content) {
      float slop = dp(GRAB_SLOP_DP);
      Marker best = null;
      float bestDistance = slop * slop;
      for (Marker marker : markers) {
        float cx = content.left + marker.x * content.width();
        float cy = content.top + marker.y * content.height();
        float dx = x - cx;
        float dy = y - cy;
        float distance = dx * dx + dy * dy;
        if (distance <= bestDistance) {
          bestDistance = distance;
          best = marker;
        }
      }
      return best;
    }
  }

  private static float clamp(float value) {
    return Math.min(1f, Math.max(0f, value));
  }
}
