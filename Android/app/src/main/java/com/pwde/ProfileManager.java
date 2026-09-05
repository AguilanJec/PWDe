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
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manages named configuration profiles. Every profile owns its own set of settings (cursor speed,
 * gesture bindings, joystick and voice mappings) so a user can keep separate setups - e.g. one for
 * Mobile Legends: Bang Bang and one for browsing.
 *
 * <p>Metadata (the list of profiles and which one is active) lives in the "PWDeProfiles" preference
 * file. The actual configuration values live in a per-profile file named "PWDeConfig_{profileId}".
 */
public final class ProfileManager {

  private static final String TAG = "ProfileManager";

  private static final String PROFILES_PREF_FILE = "PWDeProfiles";
  private static final String KEY_PROFILES = "profiles";
  private static final String KEY_ACTIVE_PROFILE_ID = "active_profile_id";
  private static final String CONFIG_PREF_FILE_PREFIX = "PWDeConfig_";

  /** A single named profile. */
  public static final class Profile {
    public final String id;
    public final String name;

    public Profile(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  private final Context context;
  private final SharedPreferences profilesPreferences;

  public ProfileManager(Context context) {
    this.context = context.getApplicationContext();
    this.profilesPreferences =
        this.context.getSharedPreferences(PROFILES_PREF_FILE, Context.MODE_PRIVATE);
    ensureActiveProfile();
  }

  /** The raw preferences object holding the list of profiles and the active id. */
  public SharedPreferences getProfilesPreferences() {
    return profilesPreferences;
  }

  /** @return all profiles in insertion order. */
  public List<Profile> getProfiles() {
    List<Profile> list = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(profilesPreferences.getString(KEY_PROFILES, "[]"));
      for (int i = 0; i < array.length(); i++) {
        JSONObject object = array.getJSONObject(i);
        list.add(new Profile(object.getString("id"), object.getString("name")));
      }
    } catch (JSONException e) {
      Log.e(TAG, "Failed to parse profiles", e);
    }
    return list;
  }

  /** @return the active profile, or {@code null} if the stored id no longer matches a profile. */
  public Profile getActiveProfile() {
    String activeId = profilesPreferences.getString(KEY_ACTIVE_PROFILE_ID, null);
    for (Profile p : getProfiles()) {
      if (p.id.equals(activeId)) {
        return p;
      }
    }
    return null;
  }

  /**
   * Guarantee there is a valid active profile. If none exists yet (or the stored id is stale) this
   * falls back to the first profile, otherwise creates and activates a "Default" profile.
   *
   * @return the active profile's id.
   */
  public String ensureActiveProfile() {
    Profile active = getActiveProfile();
    if (active != null) {
      return active.id;
    }

    List<Profile> profiles = getProfiles();
    if (!profiles.isEmpty()) {
      setActiveProfile(profiles.get(0).id);
      return profiles.get(0).id;
    }

    String newId = createProfile("Default");
    setActiveProfile(newId);
    return newId;
  }

  /** Create a new profile (empty name is replaced with an auto-generated one). @return new id. */
  public String createProfile(String name) {
    if (name == null || name.trim().isEmpty()) {
      name = "Profile " + (getProfiles().size() + 1);
    }
    String id = UUID.randomUUID().toString();
    List<Profile> profiles = getProfiles();
    profiles.add(new Profile(id, name.trim()));
    saveProfiles(profiles);
    return id;
  }

  /** Rename an existing profile. Empty names are ignored. */
  public void renameProfile(String id, String newName) {
    if (newName == null || newName.trim().isEmpty()) {
      return;
    }
    List<Profile> profiles = getProfiles();
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).id.equals(id)) {
        profiles.set(i, new Profile(id, newName.trim()));
        saveProfiles(profiles);
        return;
      }
    }
  }

  /**
   * Delete a profile and its config file. If it was the active profile, activation moves to the
   * first remaining profile (or a fresh "Default" if none remain).
   */
  public void deleteProfile(String id) {
    List<Profile> profiles = getProfiles();
    List<Profile> remaining = new ArrayList<>();
    for (Profile p : profiles) {
      if (!p.id.equals(id)) {
        remaining.add(p);
      }
    }
    saveProfiles(remaining);

    if (id.equals(profilesPreferences.getString(KEY_ACTIVE_PROFILE_ID, null))) {
      if (remaining.isEmpty()) {
        String newId = createProfile("Default");
        setActiveProfile(newId);
      } else {
        setActiveProfile(remaining.get(0).id);
      }
    }

    // Drop the deleted profile's config values.
    context
        .getSharedPreferences(CONFIG_PREF_FILE_PREFIX + id, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
  }

  /** Set the profile that new/read configuration operations should target. */
  public void setActiveProfile(String id) {
    profilesPreferences.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply();
  }

  /** SharedPreferences holding the active profile's configuration values. */
  public SharedPreferences getConfigSharedPreferences() {
    return context.getSharedPreferences(
        CONFIG_PREF_FILE_PREFIX + ensureActiveProfile(), Context.MODE_PRIVATE);
  }

  /** The preference file name that stores configuration for a specific profile. */
  public static String configPrefFileName(String profileId) {
    return CONFIG_PREF_FILE_PREFIX + profileId;
  }

  private void saveProfiles(List<Profile> profiles) {
    JSONArray array = new JSONArray();
    for (Profile p : profiles) {
      JSONObject object = new JSONObject();
      try {
        object.put("id", p.id);
        object.put("name", p.name);
      } catch (JSONException e) {
        Log.e(TAG, "Failed to build profile json", e);
      }
      array.put(object);
    }
    profilesPreferences.edit().putString(KEY_PROFILES, array.toString()).apply();
  }
}
