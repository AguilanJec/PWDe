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
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/** Profile module: list, create, rename, delete and switch configuration profiles. */
public class ProfileActivity extends AppCompatActivity {

  private ProfileManager profileManager;
  private ProfileAdapter adapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_profile);
    getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (getSupportActionBar() != null) {
      getSupportActionBar().hide();
    }

    profileManager = new ProfileManager(this);

    findViewById(R.id.backButton).setOnClickListener(v -> finish());
    findViewById(R.id.newProfileButton).setOnClickListener(v -> onNewProfile());

    ListView list = findViewById(R.id.profileList);
    adapter = new ProfileAdapter();
    list.setAdapter(adapter);
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshList();
  }

  private void refreshList() {
    adapter.notifyDataSetChanged();
  }

  /** Switch to the tapped profile. */
  private void activateProfile(String id) {
    if (id == null) {
      return;
    }
    ProfileManager.Profile active = profileManager.getActiveProfile();
    if (active != null && id.equals(active.id)) {
      return;
    }
    profileManager.setActiveProfile(id);
    onProfileChanged();
  }

  /** Tell the accessibility service to reload the active profile's config. */
  private void onProfileChanged() {
    refreshList();
    sendBroadcast(new Intent("LOAD_PROFILE"));
  }

  private void onNewProfile() {
    showNameDialog("New profile", "Profile " + (profileManager.getProfiles().size() + 1), name -> {
      String id = profileManager.createProfile(name);
      profileManager.setActiveProfile(id);
      onProfileChanged();
    });
  }

  private void onRenameProfile() {
    ProfileManager.Profile active = profileManager.getActiveProfile();
    if (active == null) {
      return;
    }
    showNameDialog("Rename profile", active.name, name -> {
      profileManager.renameProfile(active.id, name);
      onProfileChanged();
    });
  }

  private void onDeleteProfile() {
    ProfileManager.Profile active = profileManager.getActiveProfile();
    if (active == null) {
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Delete profile")
        .setMessage("Delete \"" + active.name + "\"?")
        .setPositiveButton("Delete", (d, w) -> {
          profileManager.deleteProfile(active.id);
          onProfileChanged();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void showNameDialog(String title, String initial, NameCallback callback) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setText(initial);
    input.setSelection(initial.length());
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton("Save", (d, w) -> callback.onName(input.getText().toString()))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private interface NameCallback {
    void onName(String name);
  }

  private class ProfileAdapter extends BaseAdapter {

    @Override
    public int getCount() {
      return profileManager.getProfiles().size();
    }

    @Override
    public ProfileManager.Profile getItem(int position) {
      return profileManager.getProfiles().get(position);
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View view =
          convertView != null
              ? convertView
              : getLayoutInflater().inflate(R.layout.item_profile, parent, false);

      ProfileManager.Profile profile = getItem(position);
      ProfileManager.Profile active = profileManager.getActiveProfile();
      boolean isActive = active != null && profile.id.equals(active.id);

      TextView name = view.findViewById(R.id.profileItemName);
      name.setText(profile.name);

      TextView activeLabel = view.findViewById(R.id.profileItemActive);
      ImageView check = view.findViewById(R.id.profileActiveCheck);
      check.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
      activeLabel.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);

      view.setOnClickListener(v -> activateProfile(profile.id));
      view.findViewById(R.id.profileItemSettings).setOnClickListener(v -> showManageDialog(profile));
      return view;
    }
  }

  /** Per-profile management dialog: rename or delete the active profile. */
  private void showManageDialog(ProfileManager.Profile profile) {
    String[] actions = {"Rename", "Delete"};
    new AlertDialog.Builder(this)
        .setTitle(profile.name)
        .setItems(actions, (dialog, which) -> {
          if (which == 0) {
            showNameDialog("Rename profile", profile.name, name -> {
              profileManager.renameProfile(profile.id, name);
              onProfileChanged();
            });
          } else {
            profileManager.deleteProfile(profile.id);
            onProfileChanged();
          }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}
