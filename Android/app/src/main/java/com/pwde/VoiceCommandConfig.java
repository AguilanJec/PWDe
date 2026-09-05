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
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-profile voice-command mapping (on-device, offline speech recognition).
 *
 * <p>Each profile maps a spoken phrase (e.g. "1", "up", "left", "skill", "cursor mode") to an
 * {@link Action}. The Mobile Legends preset ships skill taps for "1"/"2"/"3" plus joystick pushes
 * for the four directions. Switching actions (profile/mode) carry an optional {@code target}: the
 * profile id for {@link Action#SWITCH_PROFILE}; {@code null} for the fixed mode actions. Skill tap
 * points are stored normalized so they survive screen changes.
 */
public final class VoiceCommandConfig {

  /** Action taken when a spoken phrase is recognized. */
  public enum Action {
    NONE,
    SKILL_1,
    SKILL_2,
    SKILL_3,
    JOYSTICK_UP,
    JOYSTICK_DOWN,
    JOYSTICK_LEFT,
    JOYSTICK_RIGHT,
    /** Switch the active profile; {@link Command#target} holds the target profile id. */
    SWITCH_PROFILE,
    SWITCH_MODE_CURSOR,
    SWITCH_MODE_JOYSTICK
  }

  /** Returns true for actions that change the active profile or input mode. */
  public static boolean isSwitchAction(Action action) {
    return action == Action.SWITCH_PROFILE
        || action == Action.SWITCH_MODE_CURSOR
        || action == Action.SWITCH_MODE_JOYSTICK;
  }

  /** Returns true for the joystick steering actions (their words are not editable in the UI). */
  public static boolean isJoystickAction(Action action) {
    return action == Action.JOYSTICK_UP
        || action == Action.JOYSTICK_DOWN
        || action == Action.JOYSTICK_LEFT
        || action == Action.JOYSTICK_RIGHT;
  }

  /** @return the action cast by skill {@code index} (0-based): 0 -> {@link Action#SKILL_1}. */
  public static Action skillAction(int index) {
    return Action.values()[Action.SKILL_1.ordinal() + index];
  }

  private static final String KEY_ENABLED = "voice_enabled";

  // Commands as a JSON array of {"phrase": "...", "action": "SKILL_1", "target": "..."}.
  private static final String KEY_COMMANDS = "voice_commands";

  /**
   * When true a configured phrase matches anywhere inside the heard text (see
   * {@link #getCommandForPhrase}).
   */
  private static final String KEY_MATCH_CONTAINS = "voice_match_contains";

  /** When true a command fires as soon as the live transcript matches, before the utterance ends. */
  private static final String KEY_QUICK_FIRE = "voice_quick_fire";

  public static final int SKILL_COUNT = 3;

  /** Default spoken words that cast each skill (used when no word is bound yet). */
  public static final String[] DEFAULT_SKILL_PHRASES = {"1", "2", "3"};

  private static final String[][] DEFAULT_COMMANDS = {
    {"1", "SKILL_1"},
    {"one", "SKILL_1"},
    {"2", "SKILL_2"},
    {"two", "SKILL_2"},
    {"3", "SKILL_3"},
    {"three", "SKILL_3"},
    {"up", "JOYSTICK_UP"},
    {"down", "JOYSTICK_DOWN"},
    {"left", "JOYSTICK_LEFT"},
    {"right", "JOYSTICK_RIGHT"},
  };

  private static final String[][] DEFAULT_MODE_COMMANDS = {
    {"cursor mode", "SWITCH_MODE_CURSOR"},
    {"joystick mode", "SWITCH_MODE_JOYSTICK"},
  };

  // Default profile phrases by profile index (see ensureSwitchDefaults below).
  private static final String[] DEFAULT_PROFILE_PHRASES = {
    "profile one", "profile two", "profile three"
  };

  private boolean enabled;

  /** True: match a configured phrase anywhere inside the heard text (whole words, longest wins). */
  private boolean containsMatch = true;

  /** True: fire commands from live (partial) transcripts instead of waiting for the final result. */
  private boolean quickFire = true;

  private final List<Command> commands = new ArrayList<>();

  public static final class Command {
    public final String phrase;
    public final Action action;

    /** Target for switching actions: profile id for {@link Action#SWITCH_PROFILE}. */
    public final String target;

    public Command(String phrase, Action action) {
      this(phrase, action, null);
    }

    public Command(String phrase, Action action, String target) {
      this.phrase = normalize(phrase);
      this.action = action;
      this.target = target;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * @return true when a configured phrase matches anywhere inside what was heard (whole words),
   *     false when the heard text must equal the phrase exactly.
   */
  public boolean isContainsMatchEnabled() {
    return containsMatch;
  }

  public void setContainsMatchEnabled(boolean enabled) {
    this.containsMatch = enabled;
  }

  /** @return true when commands fire from live transcripts as soon as they match. */
  public boolean isQuickFireEnabled() {
    return quickFire;
  }

  public void setQuickFireEnabled(boolean enabled) {
    this.quickFire = enabled;
  }

  public List<Command> getCommands() {
    return commands;
  }

  /**
   * First command whose phrase matches {@code text}, or {@code null} when nothing matches. In
   * {@link #containsMatch} mode the phrase only needs to appear anywhere inside the heard text as
   * whole words (so "skill one" and "one one" both cast via "one"); otherwise the heard text must
   * equal the phrase exactly. When several phrases match inside one utterance the longest phrase
   * wins (ties keep the first configured command), so "profile one" switches the profile instead
   * of firing the shorter skill word "one" it contains.
   */
  public Command getCommandForPhrase(String text) {
    String normalized = normalize(text);
    if (containsMatch) {
      return getCommandContainedIn(normalized);
    }
    for (Command command : commands) {
      if (!command.phrase.isEmpty() && command.phrase.equals(normalized)) {
        return command;
      }
    }
    return null;
  }

  /**
   * Whole-word contains matching: a phrase must appear in {@code normalizedText} as a contiguous
   * run of words. Word boundaries are the spaces between words, so "one" never matches inside
   * "alone"/"phone". The longest matching phrase wins; ties keep the first configured command.
   */
  private Command getCommandContainedIn(String normalizedText) {
    if (normalizedText.isEmpty()) {
      return null;
    }
    String[] heardWords = normalizedText.split("\\s+");
    Command best = null;
    int bestWords = -1;
    int bestChars = -1;
    for (Command command : commands) {
      if (command.phrase.isEmpty()) {
        continue;
      }
      String[] phraseWords = command.phrase.split("\\s+");
      if (indexOfRun(heardWords, phraseWords) < 0) {
        continue;
      }
      if (phraseWords.length > bestWords
          || (phraseWords.length == bestWords && command.phrase.length() > bestChars)) {
        best = command;
        bestWords = phraseWords.length;
        bestChars = command.phrase.length();
      }
    }
    return best;
  }

  /** Index of the first place {@code run} appears as contiguous words in {@code words}, or -1. */
  private static int indexOfRun(String[] words, String[] run) {
    if (run.length == 0 || run.length > words.length) {
      return -1;
    }
    for (int i = 0; i + run.length <= words.length; i++) {
      boolean matches = true;
      for (int j = 0; j < run.length && matches; j++) {
        matches = run[j].equals(words[i + j]);
      }
      if (matches) {
        return i;
      }
    }
    return -1;
  }

  public Action getActionForPhrase(String text) {
    Command command = getCommandForPhrase(text);
    return command == null ? Action.NONE : command.action;
  }

  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    return text.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "").trim();
  }

  /**
   * Split a comma-separated list of spoken words (as typed in the skill-word fields) into
   * normalized phrases. Empty tokens are dropped, so every token is a usable trigger word.
   */
  public static List<String> parsePhraseList(String text) {
    List<String> phrases = new ArrayList<>();
    if (text == null) {
      return phrases;
    }
    for (String raw : text.split(",")) {
      String normalized = normalize(raw);
      if (!normalized.isEmpty()) {
        phrases.add(normalized);
      }
    }
    return phrases;
  }

  /** @return the configured spoken phrase that switches to the given profile, or default. */
  public String getProfileSwitchPhrase(String profileId) {
    for (Command command : commands) {
      if (command.action == Action.SWITCH_PROFILE && profileId.equals(command.target)) {
        return command.phrase;
      }
    }
    return defaultProfilePhrase(profileId);
  }

  /**
   * Set (or clear, when {@code phrase} is blank) the phrase that switches to the given profile.
   * Replaces any previous phrase bound to the same profile.
   */
  public void setProfileSwitchPhrase(String profileId, String phrase) {
    Iterator<Command> it = commands.iterator();
    while (it.hasNext()) {
      Command command = it.next();
      if (command.action == Action.SWITCH_PROFILE && profileId.equals(command.target)) {
        it.remove();
      }
    }
    String normalized = normalize(phrase);
    if (!normalized.isEmpty()) {
      commands.add(new Command(normalized, Action.SWITCH_PROFILE, profileId));
    }
  }

  /** @return the configured spoken phrase for a mode switch action, or its default. */
  public String getModeSwitchPhrase(Action action) {
    for (Command command : commands) {
      if (command.action == action) {
        return command.phrase;
      }
    }
    for (String[] pair : DEFAULT_MODE_COMMANDS) {
      if (pair[1].equals(action.name())) {
        return pair[0];
      }
    }
    return "";
  }

  /** Set (or clear, when blank) the phrase for a mode switch action. */
  public void setModeSwitchPhrase(Action action, String phrase) {
    Iterator<Command> it = commands.iterator();
    while (it.hasNext()) {
      if (it.next().action == action) {
        it.remove();
      }
    }
    String normalized = normalize(phrase);
    if (!normalized.isEmpty()) {
      commands.add(new Command(normalized, action));
    }
  }

  /**
   * @return every spoken word currently bound to skill {@code index} (all of them trigger the
   *     same tap), or the built-in default word when the skill has no binding yet.
   */
  public List<String> getSkillPhrases(int index) {
    List<String> phrases = new ArrayList<>();
    Action action = skillAction(index);
    for (Command command : commands) {
      if (command.action == action && !command.phrase.isEmpty()) {
        phrases.add(command.phrase);
      }
    }
    if (phrases.isEmpty() && index >= 0 && index < DEFAULT_SKILL_PHRASES.length) {
      phrases.add(DEFAULT_SKILL_PHRASES[index]);
    }
    return phrases;
  }

  /**
   * Bind the given words to skill {@code index}, replacing any previously bound words (including
   * the built-in defaults/synonyms). An empty list clears the mapping; {@link
   * #getSkillPhrases(int)} then falls back to the default word for display.
   */
  public void setSkillPhrases(int index, List<String> phrases) {
    Action action = skillAction(index);
    Iterator<Command> it = commands.iterator();
    while (it.hasNext()) {
      if (it.next().action == action) {
        it.remove();
      }
    }
    if (phrases == null) {
      return;
    }
    for (String phrase : phrases) {
      String normalized = normalize(phrase);
      if (!normalized.isEmpty()) {
        commands.add(new Command(normalized, action));
      }
    }
  }

  /** @return true when {@code phrase} is already used by another command (ignoring {@code ignore}). */
  public boolean hasPhraseConflict(String phrase, Command ignore) {
    String normalized = normalize(phrase);
    if (normalized.isEmpty()) {
      return false;
    }
    for (Command command : commands) {
      if (command != ignore && command.phrase.equals(normalized)) {
        return true;
      }
    }
    return false;
  }

  /** Default spoken phrase for a profile based on its position in the profile list. */
  private String defaultProfilePhrase(String profileId) {
    ProfileManager manager = new ProfileManager(context);
    List<ProfileManager.Profile> profiles = manager.getProfiles();
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).id.equals(profileId) && i < DEFAULT_PROFILE_PHRASES.length) {
        return DEFAULT_PROFILE_PHRASES[i];
      }
    }
    return "";
  }

  public float getSkillX(int index) {
    return new ScreenPlacementConfig(context).getSkillX(index);
  }

  public float getSkillY(int index) {
    return new ScreenPlacementConfig(context).getSkillY(index);
  }

  public void setSkillPoint(int index, float x, float y) {
    new ScreenPlacementConfig(context).setSkillPoint(index, x, y);
  }

  private final Context context;

  public VoiceCommandConfig(Context context) {
    this.context = context;
  }

  public static VoiceCommandConfig load(Context context) {
    VoiceCommandConfig config = new VoiceCommandConfig(context);
    SharedPreferences prefs = new ProfileManager(context).getConfigSharedPreferences();
    config.enabled = prefs.getBoolean(KEY_ENABLED, false);
    config.containsMatch = prefs.getBoolean(KEY_MATCH_CONTAINS, true);
    config.quickFire = prefs.getBoolean(KEY_QUICK_FIRE, true);
    config.commands.clear();
    String raw = prefs.getString(KEY_COMMANDS, "");
    if (raw.isEmpty()) {
      for (String[] pair : DEFAULT_COMMANDS) {
        config.commands.add(new Command(pair[0], Action.valueOf(pair[1])));
      }
    } else {
      config.commands.addAll(parseCommands(raw));
    }
    config.pruneStaleProfileCommands();
    config.ensureSwitchDefaults();
    return config;
  }

  private static List<Command> parseCommands(String raw) {
    List<Command> result = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length(); i++) {
        JSONObject obj = array.getJSONObject(i);
        String phrase = obj.getString("phrase");
        String actionName = obj.getString("action");
        String target = obj.isNull("target") ? null : obj.optString("target", null);
        result.add(new Command(phrase, Action.valueOf(actionName), target));
      }
    } catch (JSONException | IllegalArgumentException e) {
      // Fall back to the built-in defaults on corrupt values.
      result.clear();
      for (String[] pair : DEFAULT_COMMANDS) {
        result.add(new Command(pair[0], Action.valueOf(pair[1])));
      }
    }
    return result;
  }

  private void pruneStaleProfileCommands() {
    ProfileManager manager = new ProfileManager(context);
    List<String> validIds = new ArrayList<>();
    for (ProfileManager.Profile profile : manager.getProfiles()) {
      validIds.add(profile.id);
    }
    Iterator<Command> it = commands.iterator();
    while (it.hasNext()) {
      Command command = it.next();
      if (command.action == Action.SWITCH_PROFILE && !validIds.contains(command.target)) {
        it.remove();
      }
    }
  }

  /** Make sure mode-switch and profile-switch commands exist (defaults for missing ones). */
  private void ensureSwitchDefaults() {
    boolean hasCursor = false;
    boolean hasJoystick = false;
    for (Command command : commands) {
      if (command.action == Action.SWITCH_MODE_CURSOR) {
        hasCursor = true;
      } else if (command.action == Action.SWITCH_MODE_JOYSTICK) {
        hasJoystick = true;
      }
    }
    if (!hasCursor) {
      commands.add(new Command("cursor mode", Action.SWITCH_MODE_CURSOR));
    }
    if (!hasJoystick) {
      commands.add(new Command("joystick mode", Action.SWITCH_MODE_JOYSTICK));
    }

    if (!hasSwitchForAnyProfile()) {
      ProfileManager manager = new ProfileManager(context);
      List<ProfileManager.Profile> profiles = manager.getProfiles();
      for (int i = 0; i < profiles.size() && i < DEFAULT_PROFILE_PHRASES.length; i++) {
        commands.add(
            new Command(DEFAULT_PROFILE_PHRASES[i], Action.SWITCH_PROFILE, profiles.get(i).id));
      }
    }
  }

  private boolean hasSwitchForAnyProfile() {
    for (Command command : commands) {
      if (command.action == Action.SWITCH_PROFILE) {
        return true;
      }
    }
    return false;
  }

  public void save(Context context) {
    JSONArray array = new JSONArray();
    for (Command command : commands) {
      JSONObject obj = new JSONObject();
      try {
        obj.put("phrase", command.phrase);
        obj.put("action", command.action.name());
        if (command.target != null) {
          obj.put("target", command.target);
        }
        array.put(obj);
      } catch (JSONException e) {
        // Should not happen; skip the entry.
      }
    }
    new ProfileManager(context)
        .getConfigSharedPreferences()
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .putBoolean(KEY_MATCH_CONTAINS, containsMatch)
        .putBoolean(KEY_QUICK_FIRE, quickFire)
        .putString(KEY_COMMANDS, array.toString())
        .apply();
  }
}
