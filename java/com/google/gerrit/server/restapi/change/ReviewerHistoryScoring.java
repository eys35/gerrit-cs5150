// Copyright (C) 2025 The Android Open Source Project
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

import com.google.common.collect.Sets;
import java.util.Set;

/**
 * Small, testable helpers for history-based reviewer scoring used by {@link ReviewerRecommender}.
 */
final class ReviewerHistoryScoring {
  private ReviewerHistoryScoring() {}

  static int pathOverlapCount(Set<String> target, Set<String> other) {
    if (target.isEmpty() || other.isEmpty()) {
      return 0;
    }
    return Sets.intersection(target, other).size();
  }

  /** First path segment (e.g. {@code src} in {@code src/main/Foo.java}) as a diversity bucket. */
  static String pathTopLevelCluster(String path) {
    if (path == null || path.isEmpty()) {
      return "_";
    }
    int slash = path.indexOf('/');
    return slash < 0 ? path : path.substring(0, slash);
  }
}
