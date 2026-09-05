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

import static java.lang.Math.max;
import static java.lang.Math.round;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import android.Manifest;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The cursor service of PWDe app. */
@SuppressLint("UnprotectedReceiver") // All of the broadcasts can only be sent by system.
public class CursorAccessibilityService extends AccessibilityService implements LifecycleOwner {
    private static final String TAG = "CursorAccessibilityService";

    /** Limit UI update rate to 60 fps */
    public static final int UI_UPDATE = 16;

    /** Limit the FaceLandmark detect rate. */
    private static final int MIN_PROCESS = 30;




    private static final int IMAGE_ANALYZER_WIDTH = 300;
    private static final int IMAGE_ANALYZER_HEIGHT = 400;
    ServiceUiManager serviceUiManager;
    public CursorController cursorController;
    private JoystickController joystickController;
    private InputModeConfig.InputMode inputMode = InputModeConfig.InputMode.CURSOR;
    private VoiceController voiceController;
    private VoiceCommandConfig voiceConfig;
    private FaceLandmarkerHelper facelandmarkerHelper;
    public WindowManager windowManager;
    private Handler tickFunctionHandler;
    public Point screenSize;

    private ProcessCameraProvider cameraProvider;

    /** Blocking ML operations are performed using this executor */
    private ExecutorService backgroundExecutor;

    private LifecycleRegistry lifecycleRegistry;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private long lastSendMessage = 0;
    private BroadcastReceiver changeServiceStateReceiver;
    private BroadcastReceiver requestServiceStateReceiver;
    private BroadcastReceiver loadSharedConfigBasicReceiver;
    private BroadcastReceiver loadSharedConfigGestureReceiver;
    private BroadcastReceiver enableScorePreviewReceiver;
    private BroadcastReceiver loadProfileReceiver;

    /** This is state of cursor. */
    public enum ServiceState {
        ENABLE,
        DISABLE,
        /** User cannot move cursor but can still perform event from face gesture. */
        PAUSE,
        /**
         * For user to see themself in config page. Remove buttons and make camera feed static.
         */
        GLOBAL_STICK
    }

    private ServiceState serviceState = ServiceState.DISABLE;

    /** The setting app may request the float blendshape score. */
    private String requestedScoreBlendshapeName = "";

    /** Should we send blendshape score to front-end or not. */
    private boolean shouldSendScore = false;

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "ObsoleteSdkInt"})
    private void defineAndRegisterBroadcastMessageReceivers() {

        // Initialize the broadcast receiver
        loadSharedConfigBasicReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String configName = intent.getStringExtra("configName");
                    cursorController.cursorMovementConfig.updateOneConfigFromSharedPreference(configName);
                }
            };

        loadSharedConfigGestureReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String configName = intent.getStringExtra("configName");
                    cursorController.blendshapeEventTriggerConfig.updateOneConfigFromSharedPreference(
                        configName);
                }
            };

        changeServiceStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int receivedEnumValue = intent.getIntExtra("state", -1);
                    Log.i(TAG, "changeServiceStateReceiver: " + ServiceState.values()[receivedEnumValue]);

                    // Target state to be changing.
                    switch (ServiceState.values()[receivedEnumValue]) {
                        case ENABLE:
                            enableService();
                            break;
                        case DISABLE:
                            disableService();
                            break;
                        case PAUSE:
                            togglePause();
                            break;
                        case GLOBAL_STICK:
                            enterGlobalStickState();
                            break;
                    }
                }
            };

        requestServiceStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String state = intent.getStringExtra("state");
                    sendBroadcastServiceState(state);
                }
            };

        enableScorePreviewReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    shouldSendScore = intent.getBooleanExtra("enable", false);
                    requestedScoreBlendshapeName = intent.getStringExtra("blendshapesName");
                }
            };

        loadProfileReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "loadProfileReceiver: reloading configs for active profile");
                    cursorController.cursorMovementConfig.updateAllConfigFromSharedPreference();
                    cursorController.blendshapeEventTriggerConfig.updateAllConfigFromSharedPreference();
                    reloadInputMode();
                    reloadVoiceConfig();
                }
            };

        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            registerReceiver(
                changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"), RECEIVER_EXPORTED);
            registerReceiver(
                requestServiceStateReceiver,
                new IntentFilter("REQUEST_SERVICE_STATE"),
                RECEIVER_EXPORTED);
            registerReceiver(
                loadSharedConfigBasicReceiver,
                new IntentFilter("LOAD_SHARED_CONFIG_BASIC"),
                RECEIVER_EXPORTED);
            registerReceiver(
                loadSharedConfigGestureReceiver,
                new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"),
                RECEIVER_EXPORTED);
            registerReceiver(
                enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"), RECEIVER_EXPORTED);
            registerReceiver(
                loadProfileReceiver, new IntentFilter("LOAD_PROFILE"), RECEIVER_EXPORTED);
            registerReceiver(
                serviceUiManager.flyInWindowReceiver,
                new IntentFilter("FLY_IN_FLOAT_WINDOW"),
                RECEIVER_EXPORTED);
            registerReceiver(
                serviceUiManager.flyOutWindowReceiver,
                new IntentFilter("FLY_OUT_FLOAT_WINDOW"),
                RECEIVER_EXPORTED);
        } else {
            registerReceiver(changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"));
            registerReceiver(requestServiceStateReceiver, new IntentFilter("REQUEST_SERVICE_STATE"));
            registerReceiver(loadSharedConfigBasicReceiver, new IntentFilter("LOAD_SHARED_CONFIG_BASIC"));
            registerReceiver(
                loadSharedConfigGestureReceiver, new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"));
            registerReceiver(enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"));
            registerReceiver(loadProfileReceiver, new IntentFilter("LOAD_PROFILE"));
            registerReceiver(
                serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"));
            registerReceiver(
                serviceUiManager.flyOutWindowReceiver, new IntentFilter("FLY_OUT_FLOAT_WINDOW"));
        }
    }

    /** Get current service state. */
    public ServiceState getServiceState() {
        return serviceState;
    }

    /**
     * One-time service setup. This will run immediately after user toggle grant Accessibility
     *
     * <p>permission.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

        windowManager = ContextCompat.getSystemService(this, WindowManager.class);

        cursorController = new CursorController(this);
        serviceUiManager = new ServiceUiManager(this, windowManager);

        joystickController = new JoystickController(this, serviceUiManager);
        reloadInputMode();
        voiceConfig = VoiceCommandConfig.load(this);
        voiceController = new VoiceController(this, this::onVoiceCommand);

        screenSize = new Point();
        windowManager.getDefaultDisplay().getRealSize(screenSize);

        lifecycleRegistry = new LifecycleRegistry(this::getLifecycle);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        defineAndRegisterBroadcastMessageReceivers();

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor();

        backgroundExecutor.execute(
            () -> {
                facelandmarkerHelper = new FaceLandmarkerHelper();
                facelandmarkerHelper.setFrontCameraOrientation(CameraHelper.checkFrontCameraOrientation(this));
                facelandmarkerHelper.setRotation(windowManager.getDefaultDisplay().getRotation());
                facelandmarkerHelper.start();
                facelandmarkerHelper.init(this);
            });

        setImageAnalyzer();

        // Initialize the Handler
        tickFunctionHandler = new Handler();
        tickFunctionHandler.postDelayed(tick, 0);
    }

    /** Set image property to match the MediaPipe model. - Using RGBA 8888. - Lowe the resolution. */
    private ImageAnalysis imageAnalyzer =
        new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                new ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        new ResolutionStrategy(
                            new Size(IMAGE_ANALYZER_WIDTH, IMAGE_ANALYZER_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
            .build();

    /**
     * Tick function of the service. This function runs every {@value UI_UPDATE}
     *
     * <p>Milliseconds. 1. Update cursor location on screen. 2. Dispatch event. 2. Change status icon.
     */
    private final Runnable tick =
        new Runnable() {
            @Override
            public void run() {
                if (facelandmarkerHelper == null) {
                    // Back-off.
                    tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
                    return;
                }

                switch (serviceState) {
                    case GLOBAL_STICK:
                        if (shouldSendScore) {
                            sendBroadcastScore();
                        }
                        // Testing/config screens only need the score broadcasts; do NOT fall
                        // through into ENABLE and dispatch cursor/gesture events while they are
                        // open (this previously made the station react to head movement and
                        // could trigger actions while the user was speaking).
                        break;
                    case ENABLE:
                        // Drag drag line if in drag mode.
                        if (cursorController.isDragging) {
                            serviceUiManager.updateDragLine(cursorController.getCursorPositionXY());
                        }

                        // Use for smoothing.
                        int gapFrames =
                            round(max(((float) facelandmarkerHelper.gapTimeMs / (float) UI_UPDATE), 1.0f));

                        if (inputMode == InputModeConfig.InputMode.JOYSTICK) {
                            joystickController.update(
                                facelandmarkerHelper.getHeadCoordXY(),
                                facelandmarkerHelper.mpInputWidth,
                                facelandmarkerHelper.mpInputHeight,
                                screenSize.x,
                                screenSize.y);
                        } else {
                            cursorController.updateInternalCursorPosition(
                                facelandmarkerHelper.getHeadCoordXY(),
                                gapFrames, screenSize.x, screenSize.y
                            );

                            // Actually update the UI cursor image.
                            serviceUiManager.updateCursorImagePositionOnScreen(
                                cursorController.getCursorPositionXY()
                            );
                        }

                        dispatchEvent();

                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);

                        serviceUiManager.updateDebugTextOverlay(
                            facelandmarkerHelper.preprocessTimeMs,
                            facelandmarkerHelper.mediapipeTimeMs,
                            serviceState == ServiceState.PAUSE);
                        break;

                    case PAUSE:
                        // In PAUSE state user cannot move cursor
                        // but still can perform some event from face gesture.
                        dispatchEvent();

                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);

                        serviceUiManager.updateDebugTextOverlay(
                            facelandmarkerHelper.preprocessTimeMs,
                            facelandmarkerHelper.mediapipeTimeMs,
                            getServiceState() == ServiceState.PAUSE);
                        break;

                    default:
                        break;
                }

                serviceUiManager.updateStatusIcon(
                    serviceState == ServiceState.PAUSE, checkFaceVisibleInFrame());

                tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
            }
        };

    /** Assign function to image analyzer to send it to MediaPipe */
    private void setImageAnalyzer() {
        imageAnalyzer.setAnalyzer(
            backgroundExecutor,
            imageProxy -> {
                if ((SystemClock.uptimeMillis() - lastSendMessage) > MIN_PROCESS) {

                    // Create a new message and attach image.
                    Message msg = Message.obtain();
                    msg.obj = imageProxy;

                    if ((facelandmarkerHelper != null) && (facelandmarkerHelper.getHandler() != null)) {
                        // Send message to the thread to process.
                        facelandmarkerHelper.getHandler().sendMessage(msg);
                        lastSendMessage = SystemClock.uptimeMillis();
                    }

                } else {
                    // It will be closed by FaceLandmarkHelper.
                    imageProxy.close();
                }
            });
    }

    /** Send out blendshape score for visualize in setting page. */
    private void sendBroadcastScore() {
        if (!shouldSendScore) {
            return;
        }

        // " * " requests all scores at once (used by the testing station).
        if ("*".equals(requestedScoreBlendshapeName)) {
            sendAllBlendshapeScores();
            return;
        }

        // Get float score of the requested blendshape.
        try {
            BlendshapeEventTriggerConfig.Blendshape enumValue =
                BlendshapeEventTriggerConfig.Blendshape.valueOf(requestedScoreBlendshapeName);

            float score = facelandmarkerHelper.getBlendshapes()[enumValue.value];
            Intent intent = new Intent(requestedScoreBlendshapeName);
            intent.putExtra("score", score);
            sendBroadcast(intent);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "No Blendshape named " + requestedScoreBlendshapeName);
        }
    }

    /** Broadcast live blendshape, head-position and face-visibility values to the UI. */
    private void sendAllBlendshapeScores() {
        BlendshapeEventTriggerConfig.Blendshape[] shapes =
            BlendshapeEventTriggerConfig.Blendshape.values();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Float> scores = new ArrayList<>();
        float[] blendshapes = facelandmarkerHelper.getBlendshapes();
        for (BlendshapeEventTriggerConfig.Blendshape shape : shapes) {
            if (shape == BlendshapeEventTriggerConfig.Blendshape.NONE) {
                continue;
            }
            names.add(shape.name());
            scores.add(blendshapes[shape.value]);
        }
        float[] scoreArray = new float[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            scoreArray[i] = scores.get(i);
        }
        Intent intent = new Intent("BLENDSHAPE_SCORES");
        intent.putExtra("names", names.toArray(new String[0]));
        intent.putExtra("scores", scoreArray);
        float[] headCoord = facelandmarkerHelper.getHeadCoordXY();
        intent.putExtra("headX", headCoord[0] / facelandmarkerHelper.mpInputWidth);
        intent.putExtra("headY", headCoord[1] / facelandmarkerHelper.mpInputHeight);
        intent.putExtra("faceVisible", facelandmarkerHelper.isFaceVisible);
        sendBroadcast(intent);
    }

    private void sendBroadcastServiceState(String state) {
        Intent intent;
        if (state.equals("main")) {
            intent = new Intent("SERVICE_STATE");
        } else {
            intent = new Intent("SERVICE_STATE_GESTURE");
        }
        intent.putExtra("state", serviceState.ordinal());
        sendBroadcast(intent);
    }

    /** Called from startService in MainActivity. After user click the "Start" button. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        serviceUiManager.cameraBoxView.findViewById(R.id.popBtn).setBackground(null);

        return START_STICKY;
    }

    /** Toggle between Pause <-> ENABLE. */
    public void togglePause() {
        switch (serviceState) {
            case ENABLE:
                // Already enable, goto pause mode.
                serviceState = ServiceState.PAUSE;
                break;

            case PAUSE:
                // In pause mode, enable it.
                serviceState = ServiceState.ENABLE;
                break;
            default:
        }
        applyInputModeUi();
        updateVoiceState();
        serviceUiManager.setCameraBoxDraggable(true);
    }

    /**
     * Enter {@link ServiceState#GLOBAL_STICK} state.
     * For binding gesture size page.
     * Remove buttons and make camera feed static.
     */
    public void enterGlobalStickState() {
        Log.i(TAG, "enterGlobalStickState");
        switch (serviceState) {
            case PAUSE:
                togglePause();
                break;
            case DISABLE:
                enableService();
                break;
            default:
                break;
        }
        serviceState = ServiceState.GLOBAL_STICK;
        serviceUiManager.setCameraBoxDraggable(false);
        applyInputModeUi();
        updateVoiceState();
    }

    /** Enable PWDe service. */
    public void enableService() {
        Log.i(TAG, "enableService, current: "+serviceState);

        switch (serviceState) {
            case ENABLE:
                return;


            case DISABLE:
                //Start camera.
                cameraProviderFuture = ProcessCameraProvider.getInstance(this);
                cameraProviderFuture.addListener(
                        () -> {
                            try {
                                cameraProvider = cameraProviderFuture.get();
                                CameraHelper.bindPreview(
                                        cameraProvider, serviceUiManager.innerCameraImageView, imageAnalyzer, this);
                            } catch (ExecutionException | InterruptedException e) {
                                Log.e(TAG, "cameraProvider failed to get provider future: " + e.getMessage());
                            }
                        },
                        ContextCompat.getMainExecutor(this));

                facelandmarkerHelper.resumeThread();
                setImageAnalyzer();

            case PAUSE:
            case GLOBAL_STICK:

                break;
            default:
        }

        serviceUiManager.showAllWindows();
        serviceUiManager.fitCameraBoxToScreen();
        serviceUiManager.setCameraBoxDraggable(true);


        serviceState = ServiceState.ENABLE;
        applyInputModeUi();
        updateVoiceState();

    }

    /** Disable PWDe service. */
    public void disableService() {
        Log.i(TAG, "disableService");
        switch (serviceState) {
            case ENABLE:
            case GLOBAL_STICK:
            case PAUSE:
                serviceUiManager.hideAllWindows();
                joystickController.clear();
                stopVoice();
                serviceUiManager.setCameraBoxDraggable(true);

                // stop the service functions.
                facelandmarkerHelper.pauseThread();
                imageAnalyzer.clearAnalyzer();

                // Stop camera.
                cameraProviderFuture = ProcessCameraProvider.getInstance(this);
                cameraProviderFuture.addListener(
                    () -> {
                        try {
                            cameraProvider = cameraProviderFuture.get();
                            cameraProvider.unbindAll();
                        } catch (ExecutionException | InterruptedException e) {
                            Log.e(TAG, "cameraProvider failed to get provider future: " + e.getMessage());
                        }
                    },
                    ContextCompat.getMainExecutor(this));
                serviceState = ServiceState.DISABLE;

                break;
            default:
                break;
        }
    }

    /** Reload the per-profile input mode and joystick config, then show/hide UI. */
    private void reloadInputMode() {
        inputMode = InputModeConfig.getInputMode(this);
        joystickController.setConfig(JoystickConfig.load(this));
        applyInputModeUi();
    }

    /** Show/hide the floating cursor and joystick overlay based on the current mode/state. */
    private void applyInputModeUi() {
        boolean enabled = serviceState == ServiceState.ENABLE || serviceState == ServiceState.GLOBAL_STICK;
        if (inputMode == InputModeConfig.InputMode.JOYSTICK) {
            serviceUiManager.hideCursor();
            if (!enabled) {
                joystickController.clear();
            }
        } else {
            joystickController.clear();
            if (enabled) {
                serviceUiManager.showCursor();
            }
        }
    }

    /** Reload voice commands from the active profile and (re)start/stop the recognizer. */
    private void reloadVoiceConfig() {
        voiceConfig = VoiceCommandConfig.load(this);
        updateVoiceState();
    }

    /** Start the recognizer only when enabled, ENABLE mode, and the mic permission is granted. */
    private void updateVoiceState() {
        if (voiceController == null || voiceConfig == null) {
            return;
        }
        boolean active = voiceShouldRun();
        if (active) {
            // Fast path: when quick-fire is enabled, a live partial transcript already matching a
            // configured command casts immediately (see VoiceController#setQuickFireMatcher).
            voiceController.setQuickFireMatcher(
                voiceConfig.isQuickFireEnabled() ? this::matchesVoiceCommand : null);
            voiceController.start();
        } else {
            if (voiceConfig.isEnabled() && serviceState == ServiceState.ENABLE) {
                Log.w(TAG, "RECORD_AUDIO permission not granted; voice disabled.");
            }
            stopVoice();
        }
        updateSkillTapOverlay(active);
    }

    /** Voice recognition may run only when enabled, ENABLE mode, and mic permission is granted. */
    private boolean voiceShouldRun() {
        return voiceConfig != null
            && voiceConfig.isEnabled()
            && serviceState == ServiceState.ENABLE
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Show faint on-screen markers at the skill tap points while voice control is active (they
     * are visual reference for aligning the taps with the real game buttons) and hide them
     * otherwise. Positions are recomputed from the current screen size on every call.
     */
    private void updateSkillTapOverlay(boolean voiceActive) {
        if (serviceUiManager == null || screenSize == null) {
            return;
        }
        if (!voiceActive || screenSize.x <= 0 || screenSize.y <= 0) {
            serviceUiManager.hideSkillTapMarkers();
            return;
        }
        ScreenPlacementConfig placements = new ScreenPlacementConfig(this);
        int count = ScreenPlacementConfig.skillCount();
        float[] xs = new float[count];
        float[] ys = new float[count];
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            xs[i] = placements.getSkillX(i) * screenSize.x;
            ys[i] = placements.getSkillY(i) * screenSize.y;
            List<String> words = voiceConfig.getSkillPhrases(i);
            labels[i] = words.isEmpty() ? "Skill " + (i + 1) : words.get(0);
        }
        serviceUiManager.showSkillTapMarkers(xs, ys, labels);
    }

    private void stopVoice() {
        if (voiceController != null) {
            voiceController.stop();
        }
    }

    /** Voice command callback: match the phrase to a configured command and execute it. */
    private void onVoiceCommand(String phrase) {
        VoiceCommandConfig.Command command = voiceConfig.getCommandForPhrase(phrase);
        if (command != null) {
            Log.i(TAG, "voice command: " + phrase + " -> " + command.action);
            executeVoiceAction(command);
        }
    }

    /**
     * True when the heard text matches a configured command; used by the quick-fire fast path so
     * the live transcript can cast before the utterance ends. Reads the current {@code voiceConfig}
     * field, so a profile/mode reload stays in effect.
     */
    private boolean matchesVoiceCommand(String phrase) {
        return voiceConfig.getCommandForPhrase(phrase) != null;
    }

    private void executeVoiceAction(VoiceCommandConfig.Command command) {
        switch (command.action) {
            case SKILL_1:
            case SKILL_2:
            case SKILL_3:
                tapSkill(command);
                break;
            case JOYSTICK_UP:
            case JOYSTICK_DOWN:
            case JOYSTICK_LEFT:
            case JOYSTICK_RIGHT:
                pushJoystick(command);
                break;
            case SWITCH_PROFILE:
            case SWITCH_MODE_CURSOR:
            case SWITCH_MODE_JOYSTICK:
                String message = VoiceCommandRouter.execute(this, command);
                Log.i(TAG, message);
                Intent result = new Intent("VOICE_SWITCH_RESULT");
                result.putExtra("message", message);
                sendBroadcast(result);
                break;
            default:
                break;
        }
    }

    /** Tap the configured on-screen point for a skill. */
    private void tapSkill(VoiceCommandConfig.Command command) {
        int index = command.action.ordinal() - VoiceCommandConfig.Action.SKILL_1.ordinal();
        int x = (int) (voiceConfig.getSkillX(index) * screenSize.x);
        int y = (int) (voiceConfig.getSkillY(index) * screenSize.y);
        dispatchGesture(CursorUtils.createClick(x, y, 0, 250), null, null);
    }

    /** Emulate a brief joystick push in the given direction. */
    private void pushJoystick(VoiceCommandConfig.Command command) {
        JoystickConfig joystickConfig = JoystickConfig.load(this);
        int cx = (int) (joystickConfig.centerX * screenSize.x);
        int cy = (int) (joystickConfig.centerY * screenSize.y);
        int radius = (int) (joystickConfig.radius * Math.min(screenSize.x, screenSize.y));
        int offsetX = 0;
        int offsetY = 0;
        switch (command.action) {
            case JOYSTICK_UP:
                offsetY = -radius;
                break;
            case JOYSTICK_DOWN:
                offsetY = radius;
                break;
            case JOYSTICK_LEFT:
                offsetX = -radius;
                break;
            case JOYSTICK_RIGHT:
                offsetX = radius;
                break;
            default:
                return;
        }
        dispatchGesture(CursorUtils.createSwipe(cx, cy, offsetX, offsetY, 100), null, null);
    }

    /** Destroy PWDe service and unregister broadcasts. */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disableService();
        stopVoice();
        disableSelf();
        // Unregister when the service is destroyed
        unregisterReceiver(changeServiceStateReceiver);
        unregisterReceiver(loadSharedConfigBasicReceiver);
        unregisterReceiver(loadSharedConfigGestureReceiver);
        unregisterReceiver(requestServiceStateReceiver);
        unregisterReceiver(enableScorePreviewReceiver);
        unregisterReceiver(loadProfileReceiver);
        unregisterReceiver(serviceUiManager.flyInWindowReceiver);
        unregisterReceiver(serviceUiManager.flyOutWindowReceiver);

        super.onDestroy();
    }



    /** Function for perform {@link BlendshapeEventTriggerConfig.EventType} actions. */
    private void dispatchEvent() {
        // Check what event to dispatch.
        BlendshapeEventTriggerConfig.EventType event =
            cursorController.createCursorEvent(facelandmarkerHelper.getBlendshapes());


        switch (event) {
            case NONE:
                return;
            case DRAG_TOGGLE:
                break;
            default:
                // Cancel drag if user perform any other event.
                cursorController.prepareDragEnd(0, 0);
                serviceUiManager.fullScreenCanvas.clearDragLine();
                break;
        }



        switch (serviceState) {
            case GLOBAL_STICK:
            case ENABLE:
                // Check event type and dispatch it.
                DispatchEventHelper.checkAndDispatchEvent(
                    this,
                    cursorController,
                    serviceUiManager,
                    event);
                break;

            case PAUSE:
                // In PAUSE state user can only perform togglePause
                // with face gesture.
                if (event == BlendshapeEventTriggerConfig.EventType.CURSOR_PAUSE) {
                    togglePause();
                }
                if (cursorController.isDragging) {
                    serviceUiManager.fullScreenCanvas.clearDragLine();
                    cursorController.prepareDragEnd(0, 0);
                }
                break;
            default:
                break;
        }
    }

    private Boolean checkFaceVisibleInFrame() {
        if (facelandmarkerHelper == null) {
            return false;
        }
        return facelandmarkerHelper.isFaceVisible;
    }




    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);


        // Temporary hide UIs while screen is rotating.
        serviceUiManager.hideAllWindows();

        windowManager.getDefaultDisplay().getRealSize(screenSize);


        // Rotate mediapipe input.
        if (windowManager != null && facelandmarkerHelper != null) {
            int newRotation = windowManager.getDefaultDisplay().getRotation();
            facelandmarkerHelper.setRotation(newRotation);
        }

        // On-going drag event will be cancel when screen is rotate.
        cursorController.prepareDragEnd(0, 0);
        serviceUiManager.fullScreenCanvas.clearDragLine();

        switch (serviceState)  {
            case ENABLE:
            case GLOBAL_STICK:
                serviceUiManager.showAllWindows();
                updateSkillTapOverlay(
                    voiceConfig != null
                        && voiceConfig.isEnabled()
                        && serviceState == ServiceState.ENABLE);
            case PAUSE:
                serviceUiManager.showCameraBox();
                break;
            case DISABLE:
                break;

        }


    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}
