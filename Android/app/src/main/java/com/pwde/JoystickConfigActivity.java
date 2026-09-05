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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/** Per-profile joystick configuration: geometry, sensitivity, deadzone, and a Mobile Legends preset. */
public final class JoystickConfigActivity extends AppCompatActivity {

  private JoystickConfig config;

  private SeekBar radiusBar;
  private SeekBar sensitivityBar;
  private SeekBar deadzoneBar;
  private SeekBar releaseGraceBar;
  private SeekBar moveStepBar;
  private TextView radiusValue;
  private TextView sensitivityValue;
  private TextView deadzoneValue;
  private TextView releaseGraceValue;
  private TextView moveStepValue;

  private JoystickPreview preview;
  private boolean showReference; // Toggleable positioning aid: reference image behind the joystick.
  private float previewRadius; // Live radius shown in {@link #preview} while the slider moves.

  private static final int MAX_RADIUS_PROGRESS = 50; // maps 0.05f..0.30f
  private static final int MAX_SENSITIVITY_PROGRESS = 90; // maps 0.2f..2.0f
  private static final int MAX_DEADZONE_PROGRESS = 30; // maps 0.00f..0.30f
  private static final int MAX_RELEASE_GRACE_PROGRESS = 50; // maps 0ms..500ms
  private static final int MAX_MOVE_STEP_PROGRESS = 28; // maps 2%..30% of radius

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    config = JoystickConfig.load(this);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(48, 48, 48, 48);
    root.setBackgroundColor(getColor(R.color.esports_bg));

    TextView title = new TextView(this);
    title.setText("Joystick settings");
    title.setTextSize(22);
    title.setTextColor(getColor(R.color.esports_text));
    root.addView(title);

    TextView description = new TextView(this);
    description.setText("Head tilt drives a virtual joystick for mobile games.");
    description.setTextColor(getColor(R.color.esports_text_dim));
    root.addView(description);

    // Live mini preview: the joystick base sits where the profile puts it and grows/shrinks
    // with the radius slider, optionally over the bundled reference image.
    preview = new JoystickPreview(this);
    root.addView(
        preview,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.round(240 * getResources().getDisplayMetrics().density)));
    previewRadius = config.radius;

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

    radiusValue = new TextView(this);
    radiusValue.setTextColor(getColor(R.color.esports_text));
    radiusValue.setGravity(Gravity.END);
    root.addView(radiusValue);
    radiusBar = addSlider(root, "Radius", MAX_RADIUS_PROGRESS);
    setRadiusProgress(config.radius);
    radiusBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            previewRadius = radiusFromProgress(progress);
            radiusValue.setText(String.format("Radius %d%%", Math.round(previewRadius * 100)));
            if (preview != null) {
              preview.invalidate();
            }
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {}

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {}
        });

    sensitivityValue = new TextView(this);
    sensitivityValue.setTextColor(getColor(R.color.esports_text));
    sensitivityValue.setGravity(Gravity.END);
    root.addView(sensitivityValue);
    sensitivityBar = addSlider(root, "Sensitivity", MAX_SENSITIVITY_PROGRESS);
    setSensitivityProgress(config.sensitivity);

    deadzoneValue = new TextView(this);
    deadzoneValue.setTextColor(getColor(R.color.esports_text));
    deadzoneValue.setGravity(Gravity.END);
    root.addView(deadzoneValue);
    deadzoneBar = addSlider(root, "Dead zone", MAX_DEADZONE_PROGRESS);
    setDeadzoneProgress(config.deadzone);

    releaseGraceValue = new TextView(this);
    releaseGraceValue.setTextColor(getColor(R.color.esports_text));
    releaseGraceValue.setGravity(Gravity.END);
    root.addView(releaseGraceValue);
    releaseGraceBar = addSlider(root, "Release grace (ms)", MAX_RELEASE_GRACE_PROGRESS);
    setReleaseGraceProgress(config.releaseGraceMs);

    moveStepValue = new TextView(this);
    moveStepValue.setTextColor(getColor(R.color.esports_text));
    moveStepValue.setGravity(Gravity.END);
    root.addView(moveStepValue);
    moveStepBar = addSlider(root, "Move step (%)", MAX_MOVE_STEP_PROGRESS);
    setMoveStepProgress(config.moveStep);

    LinearLayout buttonRow = new LinearLayout(this);
    buttonRow.setOrientation(LinearLayout.HORIZONTAL);
    buttonRow.setGravity(Gravity.CENTER);
    buttonRow.setLayoutParams(
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    Button mlPreset = new Button(this);
    mlPreset.setText("ML preset");
    mlPreset.setOnClickListener(v -> applyMlPreset());
    buttonRow.addView(mlPreset);

    Button reset = new Button(this);
    reset.setText("Reset");
    reset.setOnClickListener(v -> {
      config.resetToDefaults();
      setRadiusProgress(config.radius);
      setSensitivityProgress(config.sensitivity);
      setDeadzoneProgress(config.deadzone);
      setReleaseGraceProgress(config.releaseGraceMs);
      setMoveStepProgress(config.moveStep);
    });
    buttonRow.addView(reset);
    root.addView(buttonRow);

    Button position = new Button(this);
    position.setText("Position on screen\u2026");
    position.setOnClickListener(
        v -> {
          // Persist the current slider state first so the editor starts from these settings.
          applySlidersToConfig();
          config.save(this);
          Intent intent = new Intent(this, PlacementEditorActivity.class);
          intent.putExtra(PlacementEditorActivity.EXTRA_MODE, PlacementEditorActivity.MODE_JOYSTICK);
          startActivity(intent);
        });
    root.addView(position);

    Button save = new Button(this);
    save.setText("Save");
    save.setOnClickListener(v -> saveAndClose());
    root.addView(save);

    // All settings live in a scroll container so the list stays reachable on short landscape
    // screens. Nothing in this activity needs free drag space (the preview is not draggable),
    // so wrapping everything is safe here.
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    setContentView(scroll);
  }

  private SeekBar addSlider(LinearLayout root, String label, int max) {
    TextView text = new TextView(this);
    text.setText(label);
    text.setTextColor(getColor(R.color.esports_text));
    root.addView(text);

    SeekBar bar = new SeekBar(this);
    bar.setMax(max);
    root.addView(bar);
    return bar;
  }

  private void setRadiusProgress(float radius) {
    radiusBar.setProgress(Math.round((radius - 0.05f) / 0.25f * MAX_RADIUS_PROGRESS));
    radiusValue.setText(String.format("Radius %d%%", Math.round(radius * 100)));
  }

  private float radiusFromProgress(int progress) {
    return 0.05f + (progress / (float) MAX_RADIUS_PROGRESS) * 0.25f;
  }

  private void setSensitivityProgress(float sensitivity) {
    sensitivityBar.setProgress(Math.round((sensitivity - 0.2f) / 1.8f * MAX_SENSITIVITY_PROGRESS));
    sensitivityValue.setText(String.format("Sensitivity %.1fx", sensitivity));
  }

  private float sensitivityFromProgress(int progress) {
    return 0.2f + (progress / (float) MAX_SENSITIVITY_PROGRESS) * 1.8f;
  }

  private void setDeadzoneProgress(float deadzone) {
    deadzoneBar.setProgress(Math.round(deadzone / 0.30f * MAX_DEADZONE_PROGRESS));
    deadzoneValue.setText(String.format("Dead zone %.2f", deadzone));
  }

  private float deadzoneFromProgress(int progress) {
    return (progress / (float) MAX_DEADZONE_PROGRESS) * 0.30f;
  }

  private void setReleaseGraceProgress(float graceMs) {
    releaseGraceBar.setProgress(Math.round(graceMs / 500f * MAX_RELEASE_GRACE_PROGRESS));
    releaseGraceValue.setText(String.format("Release grace %d ms", Math.round(graceMs)));
  }

  private float releaseGraceFromProgress(int progress) {
    return (progress / (float) MAX_RELEASE_GRACE_PROGRESS) * 500f;
  }

  private void setMoveStepProgress(float moveStep) {
    moveStepBar.setProgress(Math.round((moveStep - 0.02f) / 0.28f * MAX_MOVE_STEP_PROGRESS));
    moveStepValue.setText(String.format("Move step %d%%", Math.round(moveStep * 100)));
  }

  private float moveStepFromProgress(int progress) {
    return 0.02f + (progress / (float) MAX_MOVE_STEP_PROGRESS) * 0.28f;
  }

  private void applyMlPreset() {
    // Mobile Legends: Bang Bang default: joystick bottom-left, centred neutral head.
    config.centerX = 0.18f;
    config.centerY = 0.82f;
    config.neutralX = 0.5f;
    config.neutralY = 0.5f;
    config.radius = 0.15f;
    setRadiusProgress(config.radius);
  }

  private void saveAndClose() {
    applySlidersToConfig();
    config.save(this);
    sendBroadcast(new Intent("LOAD_PROFILE"));
    finish();
  }

  /** Copy the current slider positions into {@link #config} (does not persist). */
  private void applySlidersToConfig() {
    config.radius = radiusFromProgress(radiusBar.getProgress());
    config.sensitivity = sensitivityFromProgress(sensitivityBar.getProgress());
    config.deadzone = deadzoneFromProgress(deadzoneBar.getProgress());
    config.releaseGraceMs = releaseGraceFromProgress(releaseGraceBar.getProgress());
    config.moveStep = moveStepFromProgress(moveStepBar.getProgress());
  }

  /**
   * Mini preview of the joystick base over the profile's phone screen. Draws the base at the
   * profile's center with the radius currently selected on the slider, over the reference image
   * when the "Background reference" switch is on.
   */
  private final class JoystickPreview extends View {

    private static final int INSET_DP = 12;

    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect = new RectF();
    private final RectF imageRect = new RectF();
    private final Path clipPath = new Path();
    private final Bitmap referenceBitmap; // The toggleable positioning aid.

    JoystickPreview(JoystickConfigActivity activity) {
      super(activity);
      screenPaint.setColor(Color.parseColor("#16181D"));
      scrimPaint.setColor(Color.argb(90, 0, 0, 0));
      basePaint.setColor(Color.argb(80, 255, 255, 255));
      baseBorderPaint.setStyle(Paint.Style.STROKE);
      baseBorderPaint.setStrokeWidth(dp(2));
      baseBorderPaint.setColor(Color.argb(160, 255, 255, 255));
      referenceBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.background_reference);
    }

    private int dp(int value) {
      return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Marker coordinate space for this frame. Always the inset phone "screen"; when the
     * reference image is on, the largest centered region of that screen whose aspect ratio
     * matches the image (contain-fit), so the base position hits the picture 1:1. Fills
     * {@link #screenRect} (phone area) and, while the reference is shown, {@link #imageRect};
     * returns the rect the joystick base must be drawn in, or null when too small to draw.
     */
    private RectF contentRect() {
      float left = dp(INSET_DP);
      float top = dp(INSET_DP);
      float right = getWidth() - dp(INSET_DP);
      float bottom = getHeight() - dp(INSET_DP);
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
        // screen stays visible (never cropped) so the base maps 1:1 onto the picture.
        // content == imageRect here; the letterboxed area outside keeps the plain phone look.
        clipPath.reset();
        clipPath.addRoundRect(screenRect, dp(16), dp(16), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        // Source rect is the whole bitmap (null), destination is imageRect.
        canvas.drawBitmap(referenceBitmap, null, imageRect, null);
        // Dim the reference slightly so the joystick base stays readable.
        canvas.drawRoundRect(imageRect, dp(16), dp(16), scrimPaint);
        canvas.restore();
      }

      float cx = content.left + config.centerX * content.width();
      float cy = content.top + config.centerY * content.height();
      float radius = Math.min(content.width(), content.height()) * previewRadius;
      canvas.drawCircle(cx, cy, radius, basePaint);
      canvas.drawCircle(cx, cy, radius, baseBorderPaint);
      // Center marker so the exact base anchor is visible.
      canvas.drawCircle(cx, cy, dp(3), baseBorderPaint);
    }
  }
}
