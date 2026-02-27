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
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
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
import com.google.gerrit.server.query.change.ChangePredicates;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

  private final Config config;
  private final PluginMapContext<ReviewerSuggestion> reviewerSuggestionPluginMap;
  private final Provider<InternalChangeQuery> queryProvider;
  private final Provider<IdentifiedUser> identifiedUser;
  private final ExecutorService executor;
  private final ApprovalsUtil approvalsUtil;
  private final AccountCache accountCache;
  private final GroupMembers groupMembers;

  @Inject
  ReviewerRecommender(
      PluginMapContext<ReviewerSuggestion> reviewerSuggestionPluginMap,
      Provider<InternalChangeQuery> queryProvider,
      Provider<IdentifiedUser> identifiedUser,
      @FanOutExecutor ExecutorService executor,
      ApprovalsUtil approvalsUtil,
      @GerritServerConfig Config config,
      AccountCache accountCache,
      GroupMembers groupMembers) {
    this.config = config;
    this.queryProvider = queryProvider;
    this.identifiedUser = identifiedUser;
    this.reviewerSuggestionPluginMap = reviewerSuggestionPluginMap;
    this.executor = executor;
    this.approvalsUtil = approvalsUtil;
    this.accountCache = accountCache;
    this.groupMembers = groupMembers;
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

    // Algorithmic scoring: score = w1·ownership + w2·familiarity + w3·engagement
    //                                + w4·crossRepo - w5·loadPenalty
    // All weights are tunable in gerrit.config under [algorithmicReviewer].
    // Each apply* method is currently a no-op; base scoring remains the fallback.
    double w1 = config.getDouble("algorithmicReviewer", null, "w1", 0.35); // ownership
    double w2 = config.getDouble("algorithmicReviewer", null, "w2", 0.30); // file familiarity
    double w3 = config.getDouble("algorithmicReviewer", null, "w3", 0.20); // engagement
    double w4 = config.getDouble("algorithmicReviewer", null, "w4", 0.10); // cross-repo
    double w5 = config.getDouble("algorithmicReviewer", null, "w5", 0.05); // load penalty
    int diversityCap = config.getInt("algorithmicReviewer", "diversityCap", 2);
    logger.atFine().log(
        "algorithmicReviewer weights — w1=%s w2=%s w3=%s w4=%s w5=%s diversityCap=%s",
        w1, w2, w3, w4, w5, diversityCap);

    applyOwnershipScores(changeNotes, projectState, candidateScores, w1);
    applyFileFamiliarityScores(changeNotes, candidateScores, w2);
    applyEngagementScores(changes, candidateScores, w3);
    applyCrossRepoScores(changeNotes, projectState, candidateScores, w4);
    applyLoadPenalties(candidateScores, w5);
    applyDiversityCap(candidateScores, diversityCap);


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

  // TODO: replace with OwnershipResolver.score(id, files).
  // ownership(user, path) = 1.0 if direct CODEOWNER, 0.5 if parent-dir owner.
  private void applyOwnershipScores(
      @Nullable ChangeNotes changeNotes,
      ProjectState projectState,
      Map<Account.Id, MutableDouble> candidateScores,
      double weight) {
    if (changeNotes == null) {
      return;
    }
    logger.atFine().log("applyOwnershipScores: placeholder (no-op), weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      candidateScores.get(id).add(0.0); // TODO: weight * ownershipResolver.score(id, files)
    }
  }

  // TODO: replace with ExpertiseProfileStore.familiarity(id, files).
  // familiarity(user, repo, path) = Σ_authored e^(-λ·Δt) + α · Σ_reviewed e^(-λ·Δt)
  private void applyFileFamiliarityScores(
      @Nullable ChangeNotes changeNotes,
      Map<Account.Id, MutableDouble> candidateScores,
      double weight) {
    if (changeNotes == null) {
      return;
    }
    logger.atFine().log("applyFileFamiliarityScores: placeholder (no-op), weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      candidateScores.get(id).add(0.0); // TODO: weight * profileStore.familiarity(id, files)
    }
  }

  // TODO: replace with event store lookup per reviewer.
  // engagement = normalize(comment_count + |label_vote|) * e^(-λ·Δt), summed over recentChanges.
  private void applyEngagementScores(
      ImmutableList<ChangeData> recentChanges,
      Map<Account.Id, MutableDouble> candidateScores,
      double weight) {
    logger.atFine().log("applyEngagementScores: placeholder (no-op), weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      candidateScores.get(id).add(0.0); // TODO: weight * engagementScore(id, recentChanges)
    }
  }

  // TODO: replace with CrossRepoLinker.score(id, project).
  // cross_repo_score(user, path) = β · Σ_other_repos familiarity(user, other_repo, path)
  private void applyCrossRepoScores(
      @Nullable ChangeNotes changeNotes,
      ProjectState projectState,
      Map<Account.Id, MutableDouble> candidateScores,
      double weight) {
    if (changeNotes == null) {
      return;
    }
    logger.atFine().log("applyCrossRepoScores: placeholder (no-op), weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      candidateScores.get(id).add(0.0); // TODO: weight * crossRepoLinker.score(id, project)
    }
  }

  // TODO: replace with a real open-review-count lookup.
  // loadPenalty = f(openReviewCount); subtracted to avoid over-assigning the same reviewers.
  private void applyLoadPenalties(
      Map<Account.Id, MutableDouble> candidateScores, double weight) {
    logger.atFine().log("applyLoadPenalties: placeholder (no-op), weight=%s", weight);
    for (Account.Id id : candidateScores.keySet()) {
      candidateScores.get(id).add(0.0); // TODO: add(-weight * loadPenalty(id))
    }
  }

  // TODO: group candidates by CODEOWNERS cluster (via OwnershipResolver) and keep only the
  // top-cap entries per cluster to prevent any one cluster from dominating results.
  private void applyDiversityCap(
      Map<Account.Id, MutableDouble> candidateScores, int cap) {
    logger.atFine().log("applyDiversityCap: placeholder (no-op), cap=%s", cap);
    // TODO: remove lower-ranked candidates that exceed cap per ownership cluster
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
