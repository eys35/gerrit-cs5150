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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.ReviewerSet;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupMembers;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.change.ReviewerSuggestion;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.ReviewerStateInternal;
import com.google.gerrit.server.plugincontext.PluginMapContext;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.inject.Provider;
import java.time.Instant;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class ReviewerRecommenderTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Rule public final TestName testName = new TestName();

  @Mock private PluginMapContext<ReviewerSuggestion> pluginMap;
  @Mock private Provider<InternalChangeQuery> queryProvider;
  @Mock private Provider<IdentifiedUser> identifiedUserProvider;
  @Mock private InternalChangeQuery query;
  @Mock private ApprovalsUtil approvalsUtil;
  @Mock private AccountCache accountCache;
  @Mock private GroupMembers groupMembers;
  @Mock private ChangeData.Factory changeDataFactory;
  @Mock private ProjectState projectState;
  @Mock private IdentifiedUser identifiedUser;

  private final Config config = new Config();
  private final Project.NameKey projectName = Project.nameKey("test-project");

  private ReviewerRecommender recommender;
  private ExternalActivityStore externalActivityStore;

  @Before
  public void setUp() throws Exception {
    logScenario("setting up shared ReviewerRecommender test fixtures");
    externalActivityStore = ExternalActivityStore.forTesting(ImmutableList.of());
    recommender =
        new ReviewerRecommender(
            pluginMap,
            queryProvider,
            identifiedUserProvider,
            MoreExecutors.newDirectExecutorService(),
            approvalsUtil,
            config,
            accountCache,
            groupMembers,
            changeDataFactory,
            externalActivityStore);

    when(queryProvider.get()).thenReturn(query);
    when(query.setLimit(anyInt())).thenReturn(query);
    when(query.setRequestedFields(any())).thenReturn(query);
    when(query.noFields()).thenReturn(query);

    when(pluginMap.plugins()).thenReturn(ImmutableSortedSet.of());
    when(identifiedUserProvider.get()).thenReturn(identifiedUser);
    when(identifiedUser.getAccountId()).thenReturn(Account.id(1000));
    when(projectState.getNameKey()).thenReturn(projectName);

    when(accountCache.get(org.mockito.ArgumentMatchers.<java.util.Set<Account.Id>>any()))
        .thenReturn(ImmutableMap.of());
    when(groupMembers.listAccounts(any(), eq(projectName))).thenReturn(ImmutableSet.of());
  }

  @Test
  public void returnsRecentMatchingReviewersForQuery() throws Exception {
    logScenario("matches reviewers from recent history by query prefix");
    Account.Id aliceId = Account.id(1);
    Account.Id bobId = Account.id(2);
    ChangeData recentChange = changeDataWithReviewers(aliceId, bobId);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(recentChange));
    when(accountCache.get(org.mockito.ArgumentMatchers.<java.util.Set<Account.Id>>any()))
        .thenReturn(
            ImmutableMap.of(
                aliceId, accountState(aliceId, "Alice", "alice@example.com", true),
                bobId, accountState(bobId, "Bob", "bob@example.com", true)));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "Al",
                projectState,
                ImmutableList.of()));

    assertThat(result).containsExactly(aliceId);
  }

  @Test
  public void fallsBackToProjectOwnersWhenNoHistoryExists() throws Exception {
    logScenario("falls back to project owners when no history exists for an empty query");
    Account.Id owner1 = Account.id(11);
    Account.Id owner2 = Account.id(12);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of());
    when(groupMembers.listAccounts(any(), any()))
        .thenReturn(
            ImmutableSet.of(
                account(owner1, "Owner One", "owner1@example.com", true),
                account(owner2, "Owner Two", "owner2@example.com", true)));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of()));

    assertThat(result).containsExactly(owner1, owner2).inOrder();
  }

  @Test
  public void ignoresInactiveAccountsFromRecentReviewHistory() throws Exception {
    logScenario("filters inactive accounts out of recent reviewer history");
    Account.Id activeId = Account.id(21);
    Account.Id inactiveId = Account.id(22);
    ChangeData recentChange = changeDataWithReviewers(activeId, inactiveId);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(recentChange));
    when(accountCache.get(org.mockito.ArgumentMatchers.<java.util.Set<Account.Id>>any()))
        .thenReturn(
            ImmutableMap.of(
                activeId, accountState(activeId, "Active User", "active@example.com", true),
                inactiveId,
                    accountState(inactiveId, "Inactive User", "inactive@example.com", false)));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of()));

    assertThat(result).containsExactly(activeId);
  }

  @Test
  public void doesNotFallbackToProjectOwnersForNonEmptyQuery() throws Exception {
    logScenario("does not use project-owner fallback when the query is non-empty");
    Account.Id ownerId = Account.id(30);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of());
    when(groupMembers.listAccounts(any(), any()))
        .thenReturn(ImmutableSet.of(account(ownerId, "Owner", "owner@example.com", true)));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "Ow",
                projectState,
                ImmutableList.of()));

    assertThat(result).isEmpty();
  }

  @Test
  public void preservesExplicitCandidateListWhenNoHistoryMatches() throws Exception {
    logScenario("keeps the explicit candidate list when recent history adds nothing");
    Account.Id candidate1 = Account.id(41);
    Account.Id candidate2 = Account.id(42);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of());

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "missing",
                projectState,
                ImmutableList.of(candidate1, candidate2)));

    assertThat(result).containsExactly(candidate1, candidate2).inOrder();
  }

  @Test
  public void filtersOutChangeOwnerAndExistingReviewers() throws Exception {
    logScenario("removes the change owner and existing reviewers from the final suggestions");
    Account.Id ownerId = Account.id(31);
    Account.Id existingReviewerId = Account.id(32);
    Account.Id remainingCandidateId = Account.id(33);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(ownerId);
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of());
    when(targetChangeData.getId()).thenReturn(Change.id(100));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet(existingReviewerId));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(ownerId, existingReviewerId, remainingCandidateId)));

    assertThat(result).containsExactly(remainingCandidateId);
  }

  @Test
  public void doesNotFilterExistingCcWhenSuggestingReviewers() throws Exception {
    logScenario("keeps existing CCs when filtering only REVIEWER suggestions");
    Account.Id ccId = Account.id(51);
    Account.Id reviewerId = Account.id(52);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of());
    when(targetChangeData.getId()).thenReturn(Change.id(101));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet(ReviewerStateInternal.CC, ccId));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(ccId, reviewerId)));

    assertThat(result).containsExactly(ccId, reviewerId).inOrder();
  }

  @Test
  public void wContribBoostsProjectOwnerAboveNonOwnerCandidate() throws Exception {
    logScenario("wContrib=10 scales ownership weight enough to reorder candidates");
    Account.Id ownerId = Account.id(71);
    Account.Id nonOwnerId = Account.id(72);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of());
    when(groupMembers.listAccounts(any(), eq(projectName)))
        .thenReturn(ImmutableSet.of(account(ownerId, "Owner", "owner@example.com", true)));

    // wRecent=1 (no-op), wContrib=10 (ownership bonus amplified 10x).
    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of(nonOwnerId, ownerId),
                /* wRecent= */ 1.0,
                /* wContrib= */ 10.0));

    assertThat(result).containsExactly(ownerId, nonOwnerId).inOrder();
  }

  @Test
  public void wContribZeroPreservesInsertionOrderForEqualBaselines() throws Exception {
    logScenario("wContrib=0 disables ownership so insertion order is preserved");
    Account.Id ownerId = Account.id(81);
    Account.Id nonOwnerId = Account.id(82);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of());
    when(groupMembers.listAccounts(any(), eq(projectName)))
        .thenReturn(ImmutableSet.of(account(ownerId, "Owner", "owner@example.com", true)));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of(nonOwnerId, ownerId),
                /* wRecent= */ 1.0,
                /* wContrib= */ 0.0));

    assertThat(result).containsExactly(nonOwnerId, ownerId).inOrder();
  }

  @Test
  public void externalActivityRowsBoostFileFamiliarityForCandidate() throws Exception {
    logScenario("external (GitHub) activity rows raise a candidate's file-familiarity score");
    Account.Id externalReviewerId = Account.id(91);
    Account.Id baselineId = Account.id(92);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths())
        .thenReturn(ImmutableList.of("src/foo.ts", "src/bar.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(200));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    // Replace the empty store with one that has externalReviewerId touching one of the target
    // files (and another, unrelated file - which should NOT add overlap).
    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    externalReviewerId.get(), "acme/widgets", "src/foo.ts", 1, "github"),
                new ExternalActivityStore.Row(
                    externalReviewerId.get(),
                    "acme/widgets",
                    "src/unrelated.ts",
                    1,
                    "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(baselineId, externalReviewerId)));

    assertThat(result).containsExactly(externalReviewerId, baselineId).inOrder();
  }

  @Test
  public void externalActivityExtensionSimilarityBoostsFileFamiliarity() throws Exception {
    logScenario("external familiarity uses file-extension similarity when exact path overlap is absent");
    Account.Id extensionSimilarId = Account.id(913);
    Account.Id baselineId = Account.id(914);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of("src/feature/foo.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(205));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    extensionSimilarId.get(), "acme/widgets", "lib/utils/bar.ts", 0, "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(baselineId, extensionSimilarId),
                /* wOwnership= */ 0.0,
                /* wFileFamiliarity= */ 1.0,
                /* wEngagement= */ 0.0,
                /* wCrossRepo= */ 0.0,
                /* wAvailability= */ 0.0));

    assertThat(result).containsExactly(extensionSimilarId, baselineId).inOrder();
  }

  @Test
  public void externalOwnershipUsesWOwnershipWeight() throws Exception {
    logScenario("wOwnership boosts candidates with external activity in the target project");
    Account.Id ownsTargetProjectId = Account.id(901);
    Account.Id otherProjectOnlyId = Account.id(902);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of("src/foo.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(203));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    ownsTargetProjectId.get(), projectName.get(), "src/unrelated.ts", 0, "github"),
                new ExternalActivityStore.Row(
                    otherProjectOnlyId.get(), "acme/other-repo", "src/unrelated.ts", 0, "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(otherProjectOnlyId, ownsTargetProjectId),
                /* wOwnership= */ 1.0,
                /* wFileFamiliarity= */ 0.0,
                /* wEngagement= */ 0.0,
                /* wCrossRepo= */ 0.0,
                /* wAvailability= */ 0.0));

    assertThat(result).containsExactly(ownsTargetProjectId, otherProjectOnlyId).inOrder();
  }

  @Test
  public void externalAvailabilityUsesWAvailabilityWeightAsPenalty() throws Exception {
    logScenario("wAvailability penalizes candidates with heavier external activity load");
    Account.Id heavilyLoadedId = Account.id(911);
    Account.Id lightlyLoadedId = Account.id(912);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of("src/foo.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(204));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    heavilyLoadedId.get(), "acme/repo", "src/a.ts", 0, "github"),
                new ExternalActivityStore.Row(
                    heavilyLoadedId.get(), "acme/repo", "src/b.ts", 0, "github"),
                new ExternalActivityStore.Row(
                    heavilyLoadedId.get(), "acme/repo", "src/c.ts", 0, "github"),
                new ExternalActivityStore.Row(
                    lightlyLoadedId.get(), "acme/repo", "src/a.ts", 0, "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(heavilyLoadedId, lightlyLoadedId),
                /* wOwnership= */ 0.0,
                /* wFileFamiliarity= */ 0.0,
                /* wEngagement= */ 0.0,
                /* wCrossRepo= */ 0.0,
                /* wAvailability= */ 1.0));

    assertThat(result).containsExactly(lightlyLoadedId, heavilyLoadedId).inOrder();
  }

  @Test
  public void wRecentZeroSilencesExternalFileFamiliarity() throws Exception {
    logScenario("wRecent=0 disables the external-activity contribution to file familiarity");
    Account.Id externalReviewerId = Account.id(93);
    Account.Id baselineId = Account.id(94);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of("src/foo.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(201));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    // Put the row in another project and set wContrib=0 below so both ownership/cross-repo are
    // disabled. This isolates wRecent's effect on file familiarity + engagement.
    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    externalReviewerId.get(),
                    "acme/other-repo",
                    "src/foo.ts",
                    1,
                    "github"))));

    // wRecent = 0 zeroes both w2 (file familiarity) and w3 (engagement), so the external-activity
    // contributions should also vanish. With no other signal, the candidate list keeps its
    // insertion order and the external reviewer does NOT get a boost.
    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(baselineId, externalReviewerId),
                /* wRecent= */ 0.0,
                /* wContrib= */ 0.0));

    assertThat(result).containsExactly(baselineId, externalReviewerId).inOrder();
  }

  @Test
  public void externalCrossRepoIgnoresRowsInTargetProject() throws Exception {
    logScenario("external cross-repo signal does not double-count rows in the target project");
    Account.Id sameProjectReviewer = Account.id(95);
    Account.Id otherProjectReviewer = Account.id(96);

    ChangeNotes changeNotes = mock(ChangeNotes.class);
    ChangeNotes loadedNotes = mock(ChangeNotes.class);
    Change change = mock(Change.class);
    ChangeData targetChangeData = mock(ChangeData.class);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    when(changeNotes.load()).thenReturn(loadedNotes);
    when(changeNotes.getChange()).thenReturn(change);
    when(change.getOwner()).thenReturn(Account.id(999));
    when(changeDataFactory.create(loadedNotes)).thenReturn(targetChangeData);
    when(targetChangeData.currentFilePaths()).thenReturn(ImmutableList.of("src/foo.ts"));
    when(targetChangeData.getId()).thenReturn(Change.id(202));
    when(approvalsUtil.getReviewers(changeNotes)).thenReturn(reviewerSet());

    // Both candidates have file-familiarity overlap (so they tie on w2). The cross-repo signal
    // (w4) should fire ONLY for otherProjectReviewer, breaking the tie in their favour.
    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    sameProjectReviewer.get(),
                    projectName.get(),
                    "src/foo.ts",
                    1,
                    "github"),
                new ExternalActivityStore.Row(
                    otherProjectReviewer.get(),
                    "acme/other-repo",
                    "src/foo.ts",
                    1,
                    "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                changeNotes,
                "",
                projectState,
                ImmutableList.of(sameProjectReviewer, otherProjectReviewer),
                /* wOwnership= */ 0.0,
                /* wFileFamiliarity= */ 0.0,
                /* wEngagement= */ 0.0,
                /* wCrossRepo= */ 1.0,
                /* wAvailability= */ 0.0));

    assertThat(result).containsExactly(otherProjectReviewer, sameProjectReviewer).inOrder();
  }

  @Test
  public void emptyQuerySeedsCandidatesFromExternalActivity() throws Exception {
    logScenario("empty-query suggestions can seed from external activity when NoteDb has no candidates");
    Account.Id externalId = Account.id(97);

    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of());
    when(groupMembers.listAccounts(any(), eq(projectName))).thenReturn(ImmutableSet.of());

    rebuildRecommenderWith(
        ExternalActivityStore.forTesting(
            ImmutableList.of(
                new ExternalActivityStore.Row(
                    externalId.get(), "facebook/react", "src/foo.ts", 1, "github"))));

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of()));

    assertThat(result).containsExactly(externalId);
  }

  @Test
  public void returnsEmptyWhenNoHistoryNoOwnersAndNoCandidates() throws Exception {
    logScenario("returns an empty result when there are no candidates from any source");
    when(query.query(org.mockito.ArgumentMatchers.<Predicate<ChangeData>>any()))
        .thenReturn(ImmutableList.of(), ImmutableList.of());
    when(groupMembers.listAccounts(any(), any())).thenReturn(ImmutableSet.of());

    ImmutableList<Account.Id> result =
        ImmutableList.copyOf(
            recommender.suggestReviewers(
                ReviewerState.REVIEWER,
                null,
                "",
                projectState,
                ImmutableList.of()));

    assertThat(result).isEmpty();
  }

  /** Rebuilds the recommender with a different external store, keeping all other mocks. */
  private void rebuildRecommenderWith(ExternalActivityStore store) {
    this.externalActivityStore = store;
    this.recommender =
        new ReviewerRecommender(
            pluginMap,
            queryProvider,
            identifiedUserProvider,
            MoreExecutors.newDirectExecutorService(),
            approvalsUtil,
            config,
            accountCache,
            groupMembers,
            changeDataFactory,
            store);
  }

  private static ChangeData changeDataWithReviewers(Account.Id... reviewerIds) {
    ChangeData changeData = mock(ChangeData.class);
    when(changeData.reviewers()).thenReturn(reviewerSet(reviewerIds));
    return changeData;
  }

  private static ReviewerSet reviewerSet(Account.Id... reviewerIds) {
    return reviewerSet(ReviewerStateInternal.REVIEWER, reviewerIds);
  }

  private static ReviewerSet reviewerSet(
      ReviewerStateInternal reviewerState, Account.Id... reviewerIds) {
    HashBasedTable<ReviewerStateInternal, Account.Id, Instant> table = HashBasedTable.create();
    Instant now = Instant.now();
    for (Account.Id reviewerId : reviewerIds) {
      table.put(reviewerState, reviewerId, now);
    }
    return ReviewerSet.fromTable(table);
  }

  private static AccountState accountState(
      Account.Id id, String fullName, String email, boolean active) {
    return AccountState.forAccount(account(id, fullName, email, active));
  }

  private static Account account(Account.Id id, String fullName, String email, boolean active) {
    return Account.builder(id, Instant.EPOCH)
        .setFullName(fullName)
        .setPreferredEmail(email)
        .setActive(active)
        .build();
  }

  private void logScenario(String scenario) {
    System.out.println();
    System.out.println("Running test: " + testName.getMethodName());
    System.out.println("Scenario: " + scenario);
  }
}
