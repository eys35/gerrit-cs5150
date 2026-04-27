// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.restapi.change;

import com.google.gerrit.extensions.common.AccountVisibility;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.eclipse.jgit.lib.Config;

/** Suggest reviewers using only external (GitHub-ingested) activity signals. */
public class SuggestGitReviewers extends SuggestChangeReviewers {
  @Inject
  SuggestGitReviewers(
      AccountVisibility av,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> self,
      @GerritServerConfig Config cfg,
      ReviewersUtil reviewersUtil,
      ProjectCache projectCache) {
    super(av, permissionBackend, self, cfg, reviewersUtil, projectCache);
    setExternalOnly(true);
  }
}
