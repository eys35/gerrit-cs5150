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
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
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
import java.util.Comparator;
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
  private static final double EXTENSION_OVERLAP_FACTOR = 0.25d;

  private final Config config;
  private final PluginMapContext<ReviewerSuggestion> reviewerSuggestionPluginMap;
  private final Provider<InternalChangeQuery> queryProvider;
  private final Provider<IdentifiedUser> identifiedUser;
  private final ExecutorService executor;
  private final ApprovalsUtil approvalsUtil;
  private final AccountCache accountCache;
  private final GroupMembers groupMembers;
  private final ChangeData.Factory changeDataFactory;
  private final ExternalActivityStore externalActivityStore;
  private final ThreadLocal<ImmutableMap<Account.Id, Double>> lastNormalizedScores =
      ThreadLocal.withInitial(ImmutableMap::of);

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
      ChangeData.Factory changeDataFactory,
      ExternalActivityStore externalActivityStore) {
    this.config = config;
    this.queryProvider = queryProvider;
    this.identifiedUser = identifiedUser;
    this.reviewerSuggestionPluginMap = reviewerSuggestionPluginMap;
    this.executor = executor;
    this.approvalsUtil = approvalsUtil;
    this.accountCache = accountCache;
    this.groupMembers = groupMembers;
    this.changeDataFactory = changeDataFactory;
    this.externalActivityStore = externalActivityStore;
  }

  public List<Account.Id> suggestReviewers(
      ReviewerState reviewerState,
      @Nullable ChangeNotes changeNotes,
      String query,
      ProjectState projectState,
      ImmutableList<Account.Id> candidateList)
      throws IOException, NoSuchProjectException {
    return suggestReviewers(
        reviewerState,
        changeNotes,
        query,
        projectState,
        candidateList,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false);
  }

  public List<Account.Id> suggestReviewers(
      ReviewerState reviewerState,
      @Nullable ChangeNotes changeNotes,
      String query,
      ProjectState projectState,
      ImmutableList<Account.Id> candidateList,
      @Nullable Double wOwnership,
      @Nullable Double wFileFamiliarity,
      @Nullable Double wEngagement,
      @Nullable Double wCrossRepo,
      @Nullable Double wAvailability)
      throws IOException, NoSuchProjectException {
    return suggestReviewers(
        reviewerState,
        changeNotes,
        query,
        projectState,
        candidateList,
        wOwnership,
        wFileFamiliarity,
        wEngagement,
        wCrossRepo,
        wAvailability,
        null,
        null,
        false);
  }

  /**
   * Suggest reviewers with optional per-request weight overrides.
   *
   * <p>{@code wRecent} and {@code wContrib} (both nullable) are user-provided multipliers for the
   * algorithmic scorer's "recent activity" and "contributions" signal groups respectively. {@code
   * null} leaves the server's configured weights ({@code [algorithmicReviewer] w1..w5}) untouched;
   * a value of {@code 1.0} is a no-op; {@code 0} disables the signal group; larger values amplify
   * it. Values are pre-clamped to {@code >= 0} by the caller.
   */
  public List<Account.Id> suggestReviewers(
      ReviewerState reviewerState,
      @Nullable ChangeNotes changeNotes,
      String query,
      ProjectState projectState,
      ImmutableList<Account.Id> candidateList,
      @Nullable Double wRecent,
      @Nullable Double wContrib)
      throws IOException, NoSuchProjectException {
    return suggestReviewers(
        reviewerState,
        changeNotes,
        query,
        projectState,
        candidateList,
        null,
        null,
        null,
        null,
        null,
        wRecent,
        wContrib,
        false);
  }

  public List<Account.Id> suggestReviewers(
      ReviewerState reviewerState,
      @Nullable ChangeNotes changeNotes,
      String query,
      ProjectState projectState,
      ImmutableList<Account.Id> candidateList,
      @Nullable Double wOwnership,
      @Nullable Double wFileFamiliarity,
      @Nullable Double wEngagement,
      @Nullable Double wCrossRepo,
      @Nullable Double wAvailability,
      @Nullable Double wRecent,
      @Nullable Double wContrib,
      boolean externalOnly)
      throws IOException, NoSuchProjectException {
    logger.atFine().log(
        "query: %s, candidates: %s, wRecent: %s, wContrib: %s, externalOnly: %s",
        query, candidateList, wRecent, wContrib, externalOnly);

    Map<Account.Id, MutableDouble> candidateScores = new LinkedHashMap<>();
    candidateList.stream().forEach(id -> candidateScores.put(id, new MutableDouble(0)));

    ImmutableList<ChangeData> changes = ImmutableList.of();
    if (externalOnly) {
      candidateScores.clear();
      int externalSeedLimit = config.getInt("algorithmicReviewer", "externalSeedLimit", 1000);
      externalActivityStore.topAccountsByActivity(externalSeedLimit).stream()
          .forEach(id -> candidateScores.put(id, new MutableDouble(0)));
      logger.atFine().log(
          "Reviewer scoring stage: external seeding produced %s candidates (limit=%s)",
          candidateScores.size(), externalSeedLimit);
    } else {
      // Get the user's recent changes and add them as candidates
      double recentChangeCandidatesWeight = config.getInt("addReviewer", "baseWeight", 1);
      logger.atFine().log("recentChangeCandidatesWeight: %s", recentChangeCandidatesWeight);
      changes =
          queryRecentChanges(
              Predicate.and(
                  ChangePredicates.owner(identifiedUser.get().getAccountId()),
                  ChangePredicates.project(projectState.getNameKey())));
      getMatchingReviewers(changes, query)
          .forEach(
              reviewerCandidate ->
                  candidateScores
                      .computeIfAbsent(reviewerCandidate, (ignored) -> new MutableDouble(0))
                      .add(recentChangeCandidatesWeight));
      logger.atFine().log(
          "Reviewer scoring stage: initial candidate seeding produced %s candidates",
          candidateScores.size());
    }

    if (!externalOnly && Strings.isNullOrEmpty(query) && candidateScores.isEmpty()) {
      // There are no candidates for the default reviewer suggestion (= suggestion for an empty
      // query). Fallback to suggesting the reviewers of recent changes in the same project.
      changes = queryRecentChanges(ChangePredicates.project(projectState.getNameKey()));

      // Since we are suggesting default reviewers here (query is empty) we do not need to call
      // getMatchingReviewers here, but we can include the reviewers directly.
      getReviewers(changes)
          .forEach(reviewerId -> candidateScores.put(reviewerId, new MutableDouble(0)));

      if (candidateScores.isEmpty()) {
        // Still empty on a fresh demo / new project. Seed from external-activity accounts so the
        // inline "Use suggested reviewers" box can populate without requiring typed query input.
        int externalSeedLimit = config.getInt("algorithmicReviewer", "externalSeedLimit", 25);
        externalActivityStore.topAccountsByActivity(externalSeedLimit).stream()
            .forEach(id -> candidateScores.put(id, new MutableDouble(0)));
      }

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
    logger.atFine().log(
        "Reviewer scoring stage: after fallback seeding there are %s candidates",
        candidateScores.size());

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
    if (wOwnership != null) {
      w1 = wOwnership;
    }
    if (wFileFamiliarity != null) {
      w2 = wFileFamiliarity;
    }
    if (wEngagement != null) {
      w3 = wEngagement;
    }
    if (wCrossRepo != null) {
      w4 = wCrossRepo;
    }
    if (wAvailability != null) {
      w5 = wAvailability;
    }
    double total = w1 + w2 + w3 + w4 + w5;
    if (total > 0) {
      w1 /= total;
      w2 /= total;
      w3 /= total;
      w4 /= total;
      w5 /= total;
    }
    int diversityCap = config.getInt("algorithmicReviewer", "diversityCap", 2);

    // Apply per-request weight overrides from the UI sliders. `wRecent` scales the recent-activity
    // signals (file familiarity and engagement); `wContrib` scales the contributions signals
    // (project ownership and cross-repo overlap).
    if (wRecent != null) {
      w2 *= wRecent;
      w3 *= wRecent;
    }
    if (wContrib != null) {
      w1 *= wContrib;
      w4 *= wContrib;
    }

    logger.atFine().log(
        "algorithmicReviewer weights — w1=%s w2=%s w3=%s w4=%s w5=%s diversityCap=%s"
            + " (wRecent=%s wContrib=%s)",
        w1, w2, w3, w4, w5, diversityCap, wRecent, wContrib);

    applyOwnershipScores(candidateScores, projectOwners, w1);
    applyFileFamiliarityScores(candidateScores, targetFiles, projectHistory, w2);
    ImmutableList<ChangeData> engagementHistory = dedupeByChangeId(ownerHistory, projectHistory);
    applyEngagementScores(candidateScores, engagementHistory, w3);
    applyCrossRepoScores(candidateScores, targetFiles, ownerHistory, targetProject, w4);
    // External-activity (e.g. GitHub) signals contribute to the same weighted buckets, so the UI
    // sliders' wRecent/wContrib multipliers already scale them. The store is empty (and these are
    // no-ops) unless `algorithmicReviewer.externalActivityFile` is configured and populated.
    applyExternalOwnershipScores(candidateScores, targetProject, w1);
    applyExternalFileFamiliarityScores(candidateScores, targetFiles, w2);
    applyExternalEngagementScores(candidateScores, w3);
    applyExternalCrossRepoScores(candidateScores, targetFiles, targetProject, w4);
    applyExternalAvailabilityPenalties(candidateScores, w5);
    applyLoadPenalties(candidateScores, projectHistory, w5);
    applyDiversityCap(candidateScores, targetFiles, projectHistory, diversityCap);

    if (!externalOnly) {
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
        lastNormalizedScores.set(ImmutableMap.of());
        return ImmutableList.of();
      }
    }

    if (changeNotes != null) {
      int beforePrune = candidateScores.size();
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
      logger.atFine().log(
          "Reviewer scoring stage: pruned owner/existing reviewers from %s to %s candidates",
          beforePrune, candidateScores.size());
    }

    // Sort results
    Stream<Map.Entry<Account.Id, MutableDouble>> sorted =
        candidateScores.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Collections.reverseOrder()));
    List<Account.Id> sortedSuggestions = sorted.map(Map.Entry::getKey).collect(toList());
    lastNormalizedScores.set(normalizeScores(candidateScores));
    logger.atFine().log(
        "Reviewer scoring stage: returning %s ranked candidates: %s",
        sortedSuggestions.size(), sortedSuggestions);
    return sortedSuggestions;
  }

  ImmutableMap<Account.Id, Double> consumeLastNormalizedScores() {
    ImmutableMap<Account.Id, Double> scores = lastNormalizedScores.get();
    lastNormalizedScores.set(ImmutableMap.of());
    return scores;
  }

  private static ImmutableMap<Account.Id, Double> normalizeScores(
      Map<Account.Id, MutableDouble> candidateScores) {
    if (candidateScores.isEmpty()) return ImmutableMap.of();
    ImmutableMap.Builder<Account.Id, Double> out = ImmutableMap.builder();
    for (Map.Entry<Account.Id, MutableDouble> e : candidateScores.entrySet()) {
      out.put(e.getKey(), e.getValue().doubleValue());
    }
    return out.buildOrThrow();
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
   * External-source ownership proxy: candidates with activity in the target project get an
   * ownership-style bonus.
   */
  private void applyExternalOwnershipScores(
      Map<Account.Id, MutableDouble> candidateScores, Project.NameKey targetProject, double weight) {
    if (externalActivityStore.isEmpty() || candidateScores.isEmpty()) {
      return;
    }
    logger.atFine().log("applyExternalOwnershipScores: project=%s weight=%s", targetProject, weight);
    String targetProjectName = targetProject.get();
    for (Account.Id id : candidateScores.keySet()) {
      int rowsInTargetProject = 0;
      for (ExternalActivityStore.Row r : externalActivityStore.rowsFor(id)) {
        if (targetProjectName.equals(r.project)) {
          rowsInTargetProject += 1;
        }
      }
      if (rowsInTargetProject > 0) {
        candidateScores.get(id).add(weight * Math.log1p(rowsInTargetProject));
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
    Map<String, Integer> targetExtensionCounts = extensionCounts(targetFiles);
    for (Account.Id id : candidateScores.keySet()) {
      double add = 0;
      for (ChangeData cd : projectHistory) {
        if (!cd.reviewers().all().contains(id)) {
          continue;
        }
        ImmutableSet<String> candidateFiles = ImmutableSet.copyOf(cd.currentFilePaths());
        int overlap =
            Math.min(
                MAX_FILE_OVERLAP_PER_CHANGE,
                ReviewerHistoryScoring.pathOverlapCount(targetFiles, candidateFiles));
        if (overlap > 0) {
          add += overlap;
          continue;
        }
        // No exact path overlap: add a smaller language/file-type proxy via extension overlap.
        int extensionOverlap = extensionOverlapCount(targetExtensionCounts, candidateFiles);
        if (extensionOverlap > 0) {
          add += EXTENSION_OVERLAP_FACTOR * extensionOverlap;
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

  /**
   * External-source counterpart of {@link #applyFileFamiliarityScores}: adds {@code weight} per
   * file-path overlap recorded in the external activity snapshot. Cheap loop because the snapshot
   * is pre-indexed by {@code (Account.Id, file_path)}.
   */
  private void applyExternalFileFamiliarityScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<String> targetFiles,
      double weight) {
    if (externalActivityStore.isEmpty() || targetFiles.isEmpty() || candidateScores.isEmpty()) {
      return;
    }
    logger.atFine().log("applyExternalFileFamiliarityScores: weight=%s", weight);
    Map<String, Integer> targetExtensionCounts = extensionCounts(targetFiles);
    for (Account.Id id : candidateScores.keySet()) {
      ImmutableListMultimap<String, ExternalActivityStore.Row> byFile =
          externalActivityStore.rowsByFileFor(id);
      if (byFile.isEmpty()) {
        continue;
      }
      int overlap = 0;
      for (String path : targetFiles) {
        if (byFile.containsKey(path)) {
          overlap += Math.min(MAX_FILE_OVERLAP_PER_CHANGE, byFile.get(path).size());
        }
      }
      if (overlap == 0) {
        int extensionOverlap = extensionOverlapCount(targetExtensionCounts, byFile.keySet());
        if (extensionOverlap > 0) {
          candidateScores.get(id).add(weight * EXTENSION_OVERLAP_FACTOR * extensionOverlap);
          continue;
        }
      }
      if (overlap > 0) {
        candidateScores.get(id).add(weight * overlap);
      }
    }
  }

  private static int extensionOverlapCount(
      Map<String, Integer> targetExtensionCounts, Iterable<String> candidatePaths) {
    if (targetExtensionCounts.isEmpty()) {
      return 0;
    }
    Map<String, Integer> candidateExtensionCounts = extensionCounts(candidatePaths);
    int overlap = 0;
    for (Map.Entry<String, Integer> e : targetExtensionCounts.entrySet()) {
      int candidateCount = candidateExtensionCounts.getOrDefault(e.getKey(), 0);
      if (candidateCount > 0) {
        overlap += Math.min(e.getValue(), candidateCount);
      }
    }
    return overlap;
  }

  private static Map<String, Integer> extensionCounts(Iterable<String> paths) {
    Map<String, Integer> counts = new HashMap<>();
    for (String path : paths) {
      String ext = fileExtension(path);
      if (ext == null) {
        continue;
      }
      counts.merge(ext, 1, Integer::sum);
    }
    return counts;
  }

  @Nullable
  private static String fileExtension(@Nullable String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    if (dot <= slash + 1 || dot == path.length() - 1) {
      return null;
    }
    return path.substring(dot + 1).toLowerCase();
  }

  /** External-source counterpart of {@link #applyEngagementScores}, scored on non-zero votes. */
  private void applyExternalEngagementScores(
      Map<Account.Id, MutableDouble> candidateScores, double weight) {
    if (externalActivityStore.isEmpty() || candidateScores.isEmpty()) {
      return;
    }
    logger.atFine().log("applyExternalEngagementScores: weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      double votes = 0;
      for (ExternalActivityStore.Row r : externalActivityStore.rowsFor(id)) {
        if (r.vote != 0) {
          votes += 1;
        }
      }
      if (votes > 0) {
        candidateScores.get(id).add(weight * Math.log1p(votes));
      }
    }
  }

  /**
   * External-source counterpart of {@link #applyCrossRepoScores}. We don't have the change-author
   * relationship here (unlike Gerrit NoteDb), so the proxy is "did the candidate review files
   * matching the target files in any *other* project?" — exactly the cross-repo signal we want.
   */
  private void applyExternalCrossRepoScores(
      Map<Account.Id, MutableDouble> candidateScores,
      ImmutableSet<String> targetFiles,
      Project.NameKey targetProject,
      double weight) {
    if (externalActivityStore.isEmpty() || targetFiles.isEmpty() || candidateScores.isEmpty()) {
      return;
    }
    logger.atFine().log("applyExternalCrossRepoScores: weight=%s", weight);
    String targetProjectName = targetProject.get();
    for (Account.Id id : candidateScores.keySet()) {
      int overlap = 0;
      for (ExternalActivityStore.Row r : externalActivityStore.rowsFor(id)) {
        if (r.project == null || r.project.equals(targetProjectName)) {
          continue;
        }
        if (targetFiles.contains(r.filePath)) {
          overlap += 1;
          if (overlap >= MAX_FILE_OVERLAP_PER_CHANGE) {
            break;
          }
        }
      }
      if (overlap > 0) {
        candidateScores.get(id).add(weight * overlap);
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
   * External-source availability penalty proxy: candidates with heavy external review activity are
   * penalized similarly to Gerrit's in-project open-review load.
   */
  private void applyExternalAvailabilityPenalties(
      Map<Account.Id, MutableDouble> candidateScores, double weight) {
    if (externalActivityStore.isEmpty() || candidateScores.isEmpty()) {
      return;
    }
    logger.atFine().log("applyExternalAvailabilityPenalties: weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      int activityLoad =
          Math.min(MAX_OPEN_REVIEWS_FOR_LOAD, externalActivityStore.rowsFor(id).size());
      if (activityLoad > 0) {
        candidateScores.get(id).add(-weight * Math.log1p(activityLoad));
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
      if (!"_other".equals(cluster)) {
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

  /**
   * Returns whether external activity contributes a positive score for {@code candidate} for this
   * request context. Used to annotate reviewer suggestions with explainability metadata.
   */
  boolean externalActivityHelpsCandidate(
      Account.Id candidate,
      @Nullable ChangeNotes changeNotes,
      ProjectState projectState,
      @Nullable Double wRecent,
      @Nullable Double wContrib) {
    return !externalActivityReasonsForCandidate(
            candidate,
            changeNotes,
            projectState,
            null,
            null,
            null,
            null,
            null,
            wRecent,
            wContrib)
        .isEmpty();
  }

  @Nullable
  String externalActivityReasonForCandidate(
      Account.Id candidate,
      @Nullable ChangeNotes changeNotes,
      ProjectState projectState,
      @Nullable Double wOwnership,
      @Nullable Double wFileFamiliarity,
      @Nullable Double wEngagement,
      @Nullable Double wCrossRepo,
      @Nullable Double wAvailability,
      @Nullable Double wRecent,
      @Nullable Double wContrib) {
    Set<String> reasons =
        externalActivityReasonsForCandidate(
            candidate,
            changeNotes,
            projectState,
            wOwnership,
            wFileFamiliarity,
            wEngagement,
            wCrossRepo,
            wAvailability,
            wRecent,
            wContrib);
    if (reasons.isEmpty()) {
      return null;
    }
    return String.join(", ", reasons);
  }

  private Set<String> externalActivityReasonsForCandidate(
      Account.Id candidate,
      @Nullable ChangeNotes changeNotes,
      ProjectState projectState,
      @Nullable Double wOwnership,
      @Nullable Double wFileFamiliarity,
      @Nullable Double wEngagement,
      @Nullable Double wCrossRepo,
      @Nullable Double wAvailability,
      @Nullable Double wRecent,
      @Nullable Double wContrib) {
    Set<String> reasons = new LinkedHashSet<>();
    if (externalActivityStore.isEmpty()) {
      return reasons;
    }
    double w1 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w1"), 0.35);
    double w2 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w2"), 0.30);
    double w3 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w3"), 0.20);
    double w4 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w4"), 0.10);
    double w5 = parseConfigDouble(config.getString("algorithmicReviewer", null, "w5"), 0.05);
    if (wOwnership != null) w1 = wOwnership;
    if (wFileFamiliarity != null) w2 = wFileFamiliarity;
    if (wEngagement != null) w3 = wEngagement;
    if (wCrossRepo != null) w4 = wCrossRepo;
    if (wAvailability != null) w5 = wAvailability;
    double total = w1 + w2 + w3 + w4 + w5;
    if (total > 0) {
      w1 /= total;
      w2 /= total;
      w3 /= total;
      w4 /= total;
    }
    if (wContrib != null) {
      w1 *= wContrib;
    }
    if (wRecent != null) {
      w2 *= wRecent;
      w3 *= wRecent;
    }
    if (wContrib != null) {
      w4 *= wContrib;
    }

    ImmutableSet<String> targetFiles = ImmutableSet.of();
    if (changeNotes != null) {
      ChangeData targetCd = changeDataFactory.create(changeNotes.load());
      targetFiles = ImmutableSet.copyOf(targetCd.currentFilePaths());
    }
    final ImmutableSet<String> files = targetFiles;
    String targetProjectName = projectState.getName();
    ImmutableListMultimap<String, ExternalActivityStore.Row> byFile =
        externalActivityStore.rowsByFileFor(candidate);
    ImmutableList<ExternalActivityStore.Row> rows = externalActivityStore.rowsFor(candidate);

    Map<String, Double> matchedReasonsByWeight = new LinkedHashMap<>();
    if (w1 > 0
        && rows.stream().anyMatch(r -> r.project != null && r.project.equals(targetProjectName))) {
      matchedReasonsByWeight.put("project ownership context", w1);
    }
    if (w2 > 0 && !files.isEmpty() && files.stream().anyMatch(byFile::containsKey)) {
      matchedReasonsByWeight.put("exact file-path overlap", w2);
    } else if (w2 > 0
        && !files.isEmpty()
        && extensionOverlapCount(extensionCounts(files), byFile.keySet()) > 0) {
      matchedReasonsByWeight.put("similar file type/extension overlap", w2);
    }
    if (w3 > 0 && rows.stream().anyMatch(r -> r.vote != 0)) {
      matchedReasonsByWeight.put("strong review engagement", w3);
    }
    boolean crossRepo =
        w4 > 0
            && !files.isEmpty()
            && rows.stream()
                .anyMatch(
                    r ->
                        r.project != null
                            && !r.project.equals(targetProjectName)
                            && files.contains(r.filePath));
    if (crossRepo) {
      matchedReasonsByWeight.put("cross-repo overlap", w4);
    }
    if (matchedReasonsByWeight.isEmpty()) {
      return reasons;
    }
    matchedReasonsByWeight.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                .reversed())
        .map(Map.Entry::getKey)
        .forEach(reasons::add);
    return reasons;
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
