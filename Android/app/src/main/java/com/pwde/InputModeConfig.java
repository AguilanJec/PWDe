package com.pwde;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-profile input mode for head-movement control.
 *
 * <p>Once a user selects the "Joystick" mode, head movement drives a virtual joystick
 * instead of the floating cursor. The mode is stored per profile so different games
 * (or players) can use different control schemes.
 */
public final class InputModeConfig {

    public enum InputMode {
        CURSOR,
        JOYSTICK
    }

    private static final String KEY_INPUT_MODE = "input_mode";

    private InputModeConfig() {
    }

    public static InputMode getInputMode(Context context) {
        SharedPreferences prefs = new ProfileManager(context).getConfigSharedPreferences();
        int ordinal = prefs.getInt(KEY_INPUT_MODE, InputMode.CURSOR.ordinal());
        if (ordinal < 0 || ordinal >= InputMode.values().length) {
            return InputMode.CURSOR;
        }
        return InputMode.values()[ordinal];
    }

    public static void setInputMode(Context context, InputMode mode) {
        new ProfileManager(context).getConfigSharedPreferences()
                .edit()
                .putInt(KEY_INPUT_MODE, mode.ordinal())
                .apply();
    }

    public static String getDisplayName(InputMode mode) {
        return mode == InputMode.JOYSTICK ? "Joystick" : "Cursor";
    }
}
