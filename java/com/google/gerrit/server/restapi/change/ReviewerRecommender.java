// Copyright (C) 2016 The Android Open Source Project
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.server.FanOutExecutor;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupMembers;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.change.ReviewerSuggestion;
import com.google.gerrit.server.change.SuggestedReviewer;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.index.change.ChangeField;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.ReviewerStateInternal;
import com.google.gerrit.server.plugincontext.PluginMapContext;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeData.StorageConstraint;
import com.google.gerrit.server.query.change.ChangePredicates;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.eclipse.jgit.lib.Config;

public class ReviewerRecommender {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final long PLUGIN_QUERY_TIMEOUT = 500; // ms

  private static final int MAX_FILE_OVERLAP_PER_CHANGE = 32;
  private static final int MAX_OPEN_REVIEWS_FOR_LOAD = 20;

  private final Config config;
  private final PluginMapContext<ReviewerSuggestion> reviewerSuggestionPluginMap;
  private final Provider<InternalChangeQuery> queryProvider;
  private final Provider<IdentifiedUser> identifiedUser;
  private final ExecutorService executor;
  private final ApprovalsUtil approvalsUtil;
  private final AccountCache accountCache;
  private final GroupMembers groupMembers;
  private final ChangeData.Factory changeDataFactory;

  @Inject
  ReviewerRecommender(
      PluginMapContext<ReviewerSuggestion> reviewerSuggestionPluginMap,
      Provider<InternalChangeQuery> queryProvider,
      Provider<IdentifiedUser> identifiedUser,
      @FanOutExecutor ExecutorService executor,
      ApprovalsUtil approvalsUtil,
      @GerritServerConfig Config config,
      AccountCache accountCache,
      GroupMembers groupMembers,
      ChangeData.Factory changeDataFactory) {
    this.config = config;
    this.queryProvider = queryProvider;
    this.identifiedUser = identifiedUser;
    this.reviewerSuggestionPluginMap = reviewerSuggestionPluginMap;
    this.executor = executor;
    this.approvalsUtil = approvalsUtil;
    this.accountCache = accountCache;
    this.groupMembers = groupMembers;
    this.changeDataFactory = changeDataFactory;
  }

  public List<Account.Id> suggestReviewers(
      ReviewerState reviewerState,
      @Nullable ChangeNotes changeNotes,
      String query,
      ProjectState projectState,
      ImmutableList<Account.Id> candidateList)
      throws IOException, NoSuchProjectException {
    logger.atFine().log("query: %s, candidates: %s", query, candidateList);

    Map<Account.Id, MutableDouble> candidateScores = new LinkedHashMap<>();
    candidateList.stream().forEach(id -> candidateScores.put(id, new MutableDouble(0)));

    // Get the user's recent changes and add them as candidates
    double recentChangeCandidatesWeight = config.getInt("addReviewer", "baseWeight", 1);
    logger.atFine().log("recentChangeCandidatesWeight: %s", recentChangeCandidatesWeight);
    ImmutableList<ChangeData> changes =
        queryRecentChanges(ChangePredicates.owner(identifiedUser.get().getAccountId()));
    getMatchingReviewers(changes, query)
        .forEach(
            reviewerCandidate ->
                candidateScores
                    .computeIfAbsent(reviewerCandidate, (ignored) -> new MutableDouble(0))
                    .add(recentChangeCandidatesWeight));

    if (Strings.isNullOrEmpty(query) && candidateScores.isEmpty()) {
      // There are no candidates for the default reviewer suggestion (= suggestion for an empty
      // query). Fallback to suggesting the reviewers of recent changes in the same project.
      changes = queryRecentChanges(ChangePredicates.project(projectState.getNameKey()));

      // Since we are suggesting default reviewers here (query is empty) we do not need to call
      // getMatchingReviewers here, but we can include the reviewers directly.
      getReviewers(changes)
          .forEach(reviewerId -> candidateScores.put(reviewerId, new MutableDouble(0)));

      if (candidateScores.isEmpty()) {
        // There are still no candidates for the default reviewer suggestion. Fallback to suggesting
        // the project owners.
        groupMembers
            .listAccounts(SystemGroupBackend.PROJECT_OWNERS, projectState.getNameKey())
            .stream()
            .map(Account::id)
            .forEach(projectOwnerId -> candidateScores.put(projectOwnerId, new MutableDouble(0)));
      }
    }

    logger.atFine().log("Base candidate scores: %s", candidateScores);

    ImmutableSet<Account.Id> projectOwners =
        groupMembers.listAccounts(SystemGroupBackend.PROJECT_OWNERS, projectState.getNameKey())
            .stream()
            .map(Account::id)
            .collect(toImmutableSet());

    Project.NameKey targetProject = projectState.getNameKey();
    ImmutableSet<String> targetFiles = ImmutableSet.of();
    ImmutableList<ChangeData> projectHistory = ImmutableList.of();
    ImmutableList<ChangeData> ownerHistory = ImmutableList.of();
    if (changeNotes != null) {
      ChangeData targetCd = changeDataFactory.create(changeNotes.load());
      targetFiles = ImmutableSet.copyOf(targetCd.currentFilePaths());
      projectHistory = queryHistoryFromNoteDb(ChangePredicates.project(targetProject));
      ownerHistory =
          queryHistoryFromNoteDb(
              ChangePredicates.owner(identifiedUser.get().getAccountId()));
      logger.atFine().log(
          "Algorithmic context: targetFiles=%s projectHistory=%s ownerHistory=%s",
          targetFiles.size(),
          projectHistory.size(),
          ownerHistory.size());
    }

    // Algorithmic scoring: score = w1·ownership + w2·familiarity + w3·engagement
    //                                + w4·crossRepo - w5·loadPenalty
    // All weights are tunable in gerrit.config under [algorithmicReviewer].
    double w1 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w1"), 0.35);
    double w2 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w2"), 0.30);
    double w3 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w3"), 0.20);
    double w4 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w4"), 0.10);
    double w5 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w5"), 0.05);
    int diversityCap = config.getInt("algorithmicReviewer", "diversityCap", 2);
    logger.atFine().log(
        "algorithmicReviewer weights — w1=%s w2=%s w3=%s w4=%s w5=%s diversityCap=%s",
        w1, w2, w3, w4, w5, diversityCap);

    applyOwnershipScores(candidateScores, projectOwners, w1);
    applyFileFamiliarityScores(candidateScores, targetFiles, projectHistory, w2);
    ImmutableList<ChangeData> engagementHistory = dedupeByChangeId(ownerHistory, projectHistory);
    applyEngagementScores(candidateScores, engagementHistory, w3);
    applyCrossRepoScores(candidateScores, targetFiles, ownerHistory, targetProject, w4);
    applyLoadPenalties(candidateScores, projectHistory, w5);
    applyDiversityCap(candidateScores, targetFiles, projectHistory, diversityCap);

    // Send the query along with a candidate list to all plugins and merge the
    // results. Plugins don't necessarily need to use the candidates list, they
    // can also return non-candidate account ids.
    List<Callable<Set<SuggestedReviewer>>> tasks =
        new ArrayList<>(reviewerSuggestionPluginMap.plugins().size());
    List<Double> weights = new ArrayList<>(reviewerSuggestionPluginMap.plugins().size());

    reviewerSuggestionPluginMap.runEach(
        extension -> {
          tasks.add(
              () ->
                  extension
                      .get()
                      .suggestReviewers(
                          projectState.getNameKey(),
                          changeNotes != null ? changeNotes.getChangeId() : null,
                          query,
                          candidateScores.keySet()));
          String key = extension.getPluginName() + "-" + extension.getExportName();
          String pluginWeight = config.getString("addReviewer", key, "weight");
          if (Strings.isNullOrEmpty(pluginWeight)) {
            pluginWeight = "1";
          }
          logger.atFine().log("weight for %s: %s", key, pluginWeight);
          try {
            weights.add(Double.parseDouble(pluginWeight));
          } catch (NumberFormatException e) {
            logger.atSevere().withCause(e).log("Exception while parsing weight for %s", key);
            weights.add(1d);
          }
        });

    try {
      List<Future<Set<SuggestedReviewer>>> futures =
          executor.invokeAll(tasks, PLUGIN_QUERY_TIMEOUT, TimeUnit.MILLISECONDS);
      Iterator<Double> weightIterator = weights.iterator();
      for (Future<Set<SuggestedReviewer>> f : futures) {
        double weight = weightIterator.next();
        for (SuggestedReviewer s : f.get()) {
          if (candidateScores.containsKey(s.account)) {
            candidateScores.get(s.account).add(s.score * weight);
          } else {
            candidateScores.put(s.account, new MutableDouble(s.score * weight));
          }
        }
      }
      logger.atFine().log("Candidate scores: %s", candidateScores);
    } catch (ExecutionException | InterruptedException e) {
      logger.atSevere().withCause(e).log("Exception while suggesting reviewers");
      return ImmutableList.of();
    }

    if (changeNotes != null) {
      // Remove change owner
      if (candidateScores.remove(changeNotes.getChange().getOwner()) != null) {
        logger.atFine().log("Remove change owner %s", changeNotes.getChange().getOwner());
      }

      // Remove existing reviewers
      approvalsUtil
          .getReviewers(changeNotes)
          .byState(ReviewerStateInternal.fromReviewerState(reviewerState))
          .forEach(
              r -> {
                if (candidateScores.remove(r) != null) {
                  logger.atFine().log("Remove existing reviewer %s", r);
                }
              });
    }

    // Sort results
    Stream<Map.Entry<Account.Id, MutableDouble>> sorted =
        candidateScores.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Collections.reverseOrder()));
    List<Account.Id> sortedSuggestions = sorted.map(Map.Entry::getKey).collect(toList());
    logger.atFine().log("Sorted suggestions: %s", sortedSuggestions);
    return sortedSuggestions;
  }

  private ImmutableList<ChangeData> queryRecentChanges(Predicate<ChangeData> predicate) {
    int numberOfRelevantChanges = config.getInt("suggest", "relevantChanges", 50);
    return queryProvider
        .get()
        .setLimit(numberOfRelevantChanges)
        .setRequestedFields(ChangeField.REVIEWER_SPEC)
        .query(predicate);
  }

  /**
   * Loads changes from NoteDb (no stored index fields) so {@link ChangeData#currentFilePaths()},
   * {@link ChangeData#reviewers()}, and approvals are available for algorithmic scoring.
   */
  private ImmutableList<ChangeData> queryHistoryFromNoteDb(Predicate<ChangeData> predicate) {
    int numberOfRelevantChanges = config.getInt("suggest", "relevantChanges", 50);
    ImmutableList<ChangeData> list =
        queryProvider.get().setLimit(numberOfRelevantChanges).noFields().query(predicate);
    for (ChangeData cd : list) {
      cd.setStorageConstraint(StorageConstraint.INDEX_PRIMARY_NOTEDB_SECONDARY);
    }
    return list;
  }

  private static ImmutableList<ChangeData> dedupeByChangeId(
      ImmutableList<ChangeData> a, ImmutableList<ChangeData> b) {
    Map<Change.Id, ChangeData> byId = new LinkedHashMap<>();
    for (ChangeData cd : a) {
      byId.putIfAbsent(cd.getId(), cd);
    }
    for (ChangeData cd : b) {
      byId.putIfAbsent(cd.getId(), cd);
    }
    return ImmutableList.copyOf(byId.values());
  }

  private ImmutableList<Account.Id> getReviewers(ImmutableList<ChangeData> changes) {
    return changes.stream().flatMap(cd -> cd.reviewers().all().stream()).collect(toImmutableList());
  }

  private ImmutableList<Account.Id> getMatchingReviewers(
      ImmutableList<ChangeData> changes, String query) {
    ImmutableList<Account.Id> reviewerIds = getReviewers(changes);
    Map<Account.Id, AccountState> reviewerStates =
        accountCache.get(ImmutableSet.copyOf(reviewerIds));
    return reviewerIds.stream()
        .filter(reviewerId -> accountMatchesQuery(reviewerStates.get(reviewerId), query))
        .collect(toImmutableList());
  }

  /**
   * Project-owner proxy until CODEOWNERS integration: {@code w1} per candidate who is a project
   * owner.
   */
  private void applyOwnershipScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<Account.Id> projectOwners,
      double weight) {
    logger.atFine().log("applyOwnershipScores: projectOwners=%s weight=%s", projectOwners, weight);
    for (Account.Id id : candidateScores.keySet()) {
      if (projectOwners.contains(id)) {
        candidateScores.get(id).add(weight);
      }
    }
  }

  /**
   * File familiarity from overlap between this change's files and other changes in the same
   * project the candidate has reviewed.
   */
  private void applyFileFamiliarityScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<String> targetFiles,
      ImmutableList<ChangeData> projectHistory,
      double weight) {
    if (targetFiles.isEmpty() || projectHistory.isEmpty()) {
      logger.atFine().log("applyFileFamiliarityScores: skip (empty target or history)");
      return;
    }
    logger.atFine().log("applyFileFamiliarityScores: weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      double add = 0;
      for (ChangeData cd : projectHistory) {
        if (!cd.reviewers().all().contains(id)) {
          continue;
        }
        int overlap =
            Math.min(
                MAX_FILE_OVERLAP_PER_CHANGE,
                ReviewerHistoryScoring.pathOverlapCount(
                    targetFiles, ImmutableSet.copyOf(cd.currentFilePaths())));
        if (overlap > 0) {
          add += overlap;
        }
      }
      if (add > 0) {
        candidateScores.get(id).add(weight * add);
      }
    }
  }

  /** Engagement from non-zero label votes on changes the candidate has touched as a reviewer. */
  private void applyEngagementScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableList<ChangeData> history,
      double weight) {
    if (history.isEmpty()) {
      logger.atFine().log("applyEngagementScores: skip (empty history)");
      return;
    }
    logger.atFine().log("applyEngagementScores: weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      double votes = 0;
      for (ChangeData cd : history) {
        if (!cd.reviewers().all().contains(id)) {
          continue;
        }
        for (PatchSetApproval pa : cd.approvals().values()) {
          if (pa.accountId().equals(id) && pa.value() != 0) {
            votes += 1;
          }
        }
      }
      if (votes > 0) {
        candidateScores.get(id).add(weight * Math.log1p(votes));
      }
    }
  }

  /**
   * Cross-repo signal: file overlap on the current user's recent changes in other projects where
   * the candidate was a reviewer.
   */
  private void applyCrossRepoScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<String> targetFiles,
      ImmutableList<ChangeData> ownerHistory,
      Project.NameKey targetProject,
      double weight) {
    if (targetFiles.isEmpty() || ownerHistory.isEmpty()) {
      logger.atFine().log("applyCrossRepoScores: skip (empty target or history)");
      return;
    }
    logger.atFine().log("applyCrossRepoScores: weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      double add = 0;
      for (ChangeData cd : ownerHistory) {
        if (cd.project().equals(targetProject)) {
          continue;
        }
        if (!cd.reviewers().all().contains(id)) {
          continue;
        }
        int overlap =
            Math.min(
                MAX_FILE_OVERLAP_PER_CHANGE,
                ReviewerHistoryScoring.pathOverlapCount(
                    targetFiles, ImmutableSet.copyOf(cd.currentFilePaths())));
        if (overlap > 0) {
          add += overlap;
        }
      }
      if (add > 0) {
        candidateScores.get(id).add(weight * add);
      }
    }
  }

  /** Penalize candidates who already hold many reviewer slots on open changes in this project. */
  private void applyLoadPenalties(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableList<ChangeData> projectHistory,
      double weight) {
    if (projectHistory.isEmpty()) {
      return;
    }
    logger.atFine().log("applyLoadPenalties: weight=%s", weight);
    Map<Account.Id, Integer> openReviewCount = new HashMap<>();
    for (ChangeData cd : projectHistory) {
      Change ch = cd.change();
      if (ch == null || !ch.getStatus().isOpen()) {
        continue;
      }
      for (Account.Id reviewer : cd.reviewers().all()) {
        openReviewCount.merge(reviewer, 1, Integer::sum);
      }
    }
    for (Account.Id id : candidateScores.keySet()) {
      int c = Math.min(MAX_OPEN_REVIEWS_FOR_LOAD, openReviewCount.getOrDefault(id, 0));
      if (c > 0) {
        candidateScores.get(id).add(-weight * Math.log1p(c));
      }
    }
  }

  /**
   * Keep at most {@code cap} reviewers per top-level path cluster derived from shared files between
   * the target change and project history (fallback cluster {@code _other}).
   */
  private void applyDiversityCap(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<String> targetFiles,
      ImmutableList<ChangeData> projectHistory,
      int cap) {
    if (cap <= 0 || candidateScores.size() <= 1 || targetFiles.isEmpty()) {
      logger.atFine().log("applyDiversityCap: skip cap=%s", cap);
      return;
    }
    Map<Account.Id, String> clusterByAccount = new HashMap<>();
    for (Account.Id id : candidateScores.keySet()) {
      clusterByAccount.put(id, diversityClusterForAccount(id, targetFiles, projectHistory));
    }
    List<Account.Id> sortedByScore =
        candidateScores.entrySet().stream()
            .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
            .map(Map.Entry::getKey)
            .collect(toList());
    Set<Account.Id> keep = new LinkedHashSet<>();
    Map<String, Integer> perCluster = new HashMap<>();
    for (Account.Id id : sortedByScore) {
      String cluster = clusterByAccount.get(id);
      if (!cluster.equals("_other")) {
        int n = perCluster.getOrDefault(cluster, 0);
        if (n >= cap) {
          continue;
        }
        perCluster.put(cluster, n + 1);
      }
      keep.add(id);
    }
    candidateScores.keySet().removeIf(id -> !keep.contains(id));
    logger.atFine().log("applyDiversityCap: cap=%s kept=%s", cap, keep.size());
  }

  private static String diversityClusterForAccount(
      Account.Id id, ImmutableSet<String> targetFiles, ImmutableList<ChangeData> projectHistory) {
    String best = null;
    for (ChangeData cd : projectHistory) {
      if (!cd.reviewers().all().contains(id)) {
        continue;
      }
      for (String p : cd.currentFilePaths()) {
        if (!targetFiles.contains(p)) {
          continue;
        }
        String cluster = ReviewerHistoryScoring.pathTopLevelCluster(p);
        if (best == null || cluster.compareTo(best) < 0) {
          best = cluster;
        }
      }
    }
    return best != null ? best : "_other";
  }

  private static double parseConfigDouble(String value, double defaultValue) {
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private boolean accountMatchesQuery(AccountState accountState, String query) {
    if (accountState == null) {
      return false;
    }
    Account account = accountState.account();
    if (account.isActive()) {
      if (Strings.isNullOrEmpty(query)
          || (account.fullName() != null && account.fullName().startsWith(query))
          || (account.preferredEmail() != null && account.preferredEmail().startsWith(query))) {
        return true;
      }
    }
    return false;
  }
}
