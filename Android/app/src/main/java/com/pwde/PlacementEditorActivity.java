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
import android.content.SharedPreferences;
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
import android.widget.SeekBar;
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
  // Positioning aid: the reference image behind the markers. On by default so the picture is the
  // background the markers line up against; the switch just hides it when a plain screen is wanted.
  private boolean showReference = true;
  private int markerOpacity; // 0..100, opacity of the draggable markers over the reference.
  private int referenceDim; // 0..100, black dim applied to the reference image.

  private static final String PREFS_NAME = "placement_editor_visuals";
  private static final String KEY_MARKER_OPACITY = "marker_opacity";
  private static final String KEY_REFERENCE_DIM = "reference_dim";
  private static final int DEFAULT_MARKER_OPACITY = 85;
  private static final int DEFAULT_REFERENCE_DIM = 0;

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

    // Visual-aid preferences: marker opacity and reference dim. Stored so the user's last
    // mapping setup is restored next time.
    SharedPreferences visualPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    markerOpacity = visualPrefs.getInt(KEY_MARKER_OPACITY, DEFAULT_MARKER_OPACITY);
    referenceDim = visualPrefs.getInt(KEY_REFERENCE_DIM, DEFAULT_REFERENCE_DIM);
    preview.setMarkerOpacity(markerOpacity / 100f);
    preview.setReferenceDim(referenceDim);

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
    referenceSwitch.setChecked(showReference); // Picture is the background until the user hides it.
    referenceSwitch.setOnCheckedChangeListener(
        (button, checked) -> {
          showReference = checked;
          preview.invalidate();
        });
    referenceRow.addView(referenceSwitch);
    root.addView(referenceRow);

    root.addView(addVisualAidSlider(root, "Marker opacity", markerOpacity, visualPrefs, true));
    root.addView(addVisualAidSlider(root, "Reference dim", referenceDim, visualPrefs, false));

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

  /** Adds a labelled slider row for one visual aid and returns the row (already added to root). */
  private LinearLayout addVisualAidSlider(
      LinearLayout root, String label, int value, SharedPreferences prefs, boolean marker) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);

    TextView labelView = new TextView(this);
    labelView.setText(label);
    labelView.setTextColor(getColor(R.color.esports_text));
    labelView.setMaxLines(1);
    labelView.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(
        labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    SeekBar bar = new SeekBar(this);
    bar.setMax(100);
    bar.setProgress(clampInt(value, 0, 100));
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (marker) {
              markerOpacity = progress;
              preview.setMarkerOpacity(progress / 100f);
            } else {
              referenceDim = progress;
              preview.setReferenceDim(progress);
            }
            preview.invalidate();
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {}

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {
            prefs
                .edit()
                .putInt(marker ? KEY_MARKER_OPACITY : KEY_REFERENCE_DIM, seekBar.getProgress())
                .apply();
          }
        });
    row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
    return row;
  }

  private static int clampInt(int value, int min, int max) {
    return Math.min(max, Math.max(min, value));
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
    // Where the reference picture is actually drawn: an aspect-preserving (contain) fit of the
    // bitmap inside screenRect, centred. Markers are interpreted against this exact rectangle so
    // what is lined up over a button in the picture lands at the same spot on the game screen.
    private final RectF pictureRect = new RectF();
    private final Path clipPath = new Path();
    private final Bitmap referenceBitmap; // The toggleable positioning aid.

    private Marker dragging;
    private float dragOffsetX;
    private float dragOffsetY;
    private final JoystickConfig joystickConfig;
    private final float joystickRadius;
    private float markerOpacity = 0.85f;
    private int referenceDim = 0;

    PlacementPreview(PlacementEditorActivity activity) {
      super(activity);
      joystickConfig = JoystickConfig.load(activity);
      joystickRadius = joystickConfig.radius;
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

    /** Set marker/joystick-reach opacity (0..1) so the reference can be seen through them. */
    void setMarkerOpacity(float opacity) {
      markerOpacity = clamp(opacity);
      invalidate();
    }

    /** Set the black dim applied to the reference image (0..100). */
    void setReferenceDim(int dim) {
      referenceDim = clampInt(dim, 0, 100);
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
     * Builds the reference-to-screen projection for this frame and fills {@link #screenRect} (the
     * inset phone "screen" area). Markers, the grid and the joystick reach are always interpreted
     * against the reference image's contain-fit projection, the same space the runtime overlay
     * uses, so what is lined up here lands at the same on-screen spot on any device. Returns null
     * when the view is too small to draw.
     */
    private ReferenceProjection projection() {
      float left = getPaddingLeft() + dp(INSET_DP);
      float top = getPaddingTop() + dp(INSET_DP);
      float right = getWidth() - getPaddingRight() - dp(INSET_DP);
      float bottom = getHeight() - getPaddingBottom() - dp(INSET_DP);
      if (right <= left || bottom <= top) {
        return null;
      }
      screenRect.set(left, top, right, bottom);
      fitPictureRect();
      // Markers live in the reference picture's space: the aspect-preserving box the picture is
      // drawn into. A marker placed over a button in the picture therefore maps to the same
      // normalized point on the real game screen at runtime (the runtime projects onto the full
      // screen), so the picture can be shown edge-to-edge without warping and placement stays
      // screen-size independent.
      return new ReferenceProjection(
          pictureRect.left, pictureRect.top, pictureRect.width(), pictureRect.height());
    }

    /** Compute the aspect-preserving (contain) box the reference picture is drawn into. */
    private void fitPictureRect() {
      pictureRect.set(screenRect);
      if (referenceBitmap == null) {
        return;
      }
      float imageAspect = referenceBitmap.getWidth() / (float) referenceBitmap.getHeight();
      float screenAspect = screenRect.width() / screenRect.height();
      if (imageAspect >= screenAspect) {
        // Image is wider (or equal): fit to the width and centre vertically.
        float height = screenRect.width() / imageAspect;
        float top = screenRect.top + (screenRect.height() - height) / 2f;
        pictureRect.set(screenRect.left, top, screenRect.right, top + height);
      } else {
        // Image is taller: fit to the height and centre horizontally.
        float width = screenRect.height() * imageAspect;
        float left = screenRect.left + (screenRect.width() - width) / 2f;
        pictureRect.set(left, screenRect.top, left + width, screenRect.bottom);
      }
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      ReferenceProjection projection = projection();
      if (projection == null) {
        return;
      }
      canvas.drawRoundRect(screenRect, dp(16), dp(16), screenPaint);

      if (showReference && referenceBitmap != null) {
        // The reference is a full screenshot of the game screen. Draw it aspect-preserving
        // (contain fit, centred inside the phone area) so it never warps, then interpret every
        // marker against pictureRect — the same normalized space the runtime projects onto the
        // full game screen — so what is lined up here lands on the same spot when the game runs.
        clipPath.reset();
        clipPath.addRoundRect(screenRect, dp(16), dp(16), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawBitmap(referenceBitmap, null, pictureRect, null);
        if (referenceDim > 0) {
          scrimPaint.setColor(Color.argb(referenceDim * 255 / 100, 0, 0, 0));
          canvas.drawRoundRect(screenRect, dp(16), dp(16), scrimPaint);
        }
        canvas.restore();
      }

      // Grid so the user can line markers up (brighter when drawn over the reference image). The
      // grid is drawn over the picture box so it shares the same space the markers use.
      Paint grid = showReference ? gridOnImagePaint : gridPaint;
      for (int i = 1; i <= 2; i++) {
        float y = pictureRect.top + pictureRect.height() * i / 3f;
        canvas.drawLine(pictureRect.left, y, pictureRect.right, y, grid);
        float x = pictureRect.left + pictureRect.width() * i / 3f;
        canvas.drawLine(x, pictureRect.top, x, pictureRect.bottom, grid);
      }

      // Apply the mapping opacity so the reference image stays visible through the markers.
      basePaint.setColor(Color.argb(Math.round(markerOpacity * 255), 0x3D, 0x7B, 0xFF));
      ringPaint.setColor(Color.argb(Math.round(markerOpacity * 255), 0x16, 0x18, 0x1D));
      textPaint.setColor(Color.argb(Math.round(markerOpacity * 255), 255, 255, 255));

      for (Marker marker : markers) {
        float cx = projection.pxFromNormX(marker.x);
        float cy = projection.pxFromNormY(marker.y);
        boolean isJoystick = marker.index < 0;
        int radius = dp(MARKER_RADIUS_DP);
        if (isJoystick) {
          // The joystick also shows the reachable thumb area (radius from JoystickConfig).
          float reach = projection.screenRadius(joystickRadius);
          Paint reachPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
          reachPaint.setStyle(Paint.Style.STROKE);
          reachPaint.setColor(Color.argb(Math.round(0x55 * markerOpacity), 0x3D, 0x7B, 0xFF));
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
      ReferenceProjection projection = projection();
      if (projection == null) {
        return false;
      }

      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragging = markerNear(event.getX(), event.getY(), projection);
          if (dragging != null) {
            float cx = projection.pxFromNormX(dragging.x);
            float cy = projection.pxFromNormY(dragging.y);
            dragOffsetX = event.getX() - cx;
            dragOffsetY = event.getY() - cy;
            return true;
          }
          return false;
        case MotionEvent.ACTION_MOVE:
          if (dragging != null) {
            float cx = event.getX() - dragOffsetX;
            float cy = event.getY() - dragOffsetY;
            dragging.x = clamp(projection.normFromPxX(cx));
            dragging.y = clamp(projection.normFromPxY(cy));
            // Skill buttons must never sit on the joystick base (bottom-left), so as a marker is
            // dragged push it out of the joystick's reach. The joystick marker itself is exempt.
            if (dragging.index >= 0) {
              pushOutOfJoystick(dragging, projection);
            }
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

    private Marker markerNear(float x, float y, ReferenceProjection projection) {
      float slop = dp(GRAB_SLOP_DP);
      Marker best = null;
      float bestDistance = slop * slop;
      for (Marker marker : markers) {
        float cx = projection.pxFromNormX(marker.x);
        float cy = projection.pxFromNormY(marker.y);
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

    /** Push a skill marker out of the joystick's reach circle so buttons never cover the base. */
    private void pushOutOfJoystick(Marker marker, ReferenceProjection projection) {
      float jx = projection.pxFromNormX(joystickConfig.centerX);
      float jy = projection.pxFromNormY(joystickConfig.centerY);
      float reach =
          projection.screenRadius(joystickConfig.radius) + dp(MARKER_RADIUS_DP) + dp(6);
      float mx = projection.pxFromNormX(marker.x);
      float my = projection.pxFromNormY(marker.y);
      float dx = mx - jx;
      float dy = my - jy;
      float distance = (float) Math.hypot(dx, dy);
      if (distance >= reach || distance <= 0.0001f) {
        return;
      }
      float scale = reach / distance;
      float nx = jx + dx * scale;
      float ny = jy + dy * scale;
      marker.x = clamp(projection.normFromPxX(nx));
      marker.y = clamp(projection.normFromPxY(ny));
    }
  }

  private static float clamp(float value) {
    return Math.min(1f, Math.max(0f, value));
  }
}
