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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Config;

/**
 * Loads the external-activity JSON snapshot produced by the Python ingester and exposes its rows
 * to the algorithmic reviewer scorer.
 *
 * <p>The store is intentionally read-mostly and dirt simple: parse the file once on first use,
 * cache the rows in memory, and reload only if the file's last-modified time changes. There is no
 * background poller; staleness is bounded by however often the cron regenerates the file plus the
 * lifetime of the JVM's first-touched cache entry.
 *
 * <p>Configuration in {@code gerrit.config}:
 *
 * <pre>{@code
 * [algorithmicReviewer]
 *   externalActivityFile = /var/gerrit/data/external-activity.json
 * }</pre>
 *
 * If the key is unset or the file is absent/unreadable, the store returns an empty view and the
 * scorer behaves exactly as it did before this class existed.
 */
@Singleton
public class ExternalActivityStore {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Latest schema version we know how to parse. Newer files are ignored with a warning. */
  static final int SUPPORTED_VERSION = 1;

  private static final Gson GSON = new Gson();
  private static final Type SNAPSHOT_TYPE = new TypeToken<Snapshot>() {}.getType();

  private final Config config;

  /** Cached snapshot. Volatile so reload() doesn't tear under concurrent reads. */
  private volatile Snapshot cached = Snapshot.empty();

  /** Last-modified time of the file when {@link #cached} was loaded. */
  private volatile long cachedMtimeMillis = -1L;

  /**
   * When true, {@link #load()} short-circuits to {@link #cached} and never consults the config or
   * filesystem. Used exclusively by {@link #forTesting(List)}.
   */
  private volatile boolean frozen = false;

  @Inject
  ExternalActivityStore(@GerritServerConfig Config config) {
    this.config = config;
  }

  /**
   * Constructs a store backed by a fixed in-memory snapshot. Only intended for tests and the
   * empty-default Guice binding.
   */
  static ExternalActivityStore forTesting(List<Row> rows) {
    ExternalActivityStore s = new ExternalActivityStore(new Config());
    Snapshot snap = new Snapshot();
    snap.version = SUPPORTED_VERSION;
    snap.rows = ImmutableList.copyOf(rows);
    snap.index();
    s.cached = snap;
    s.cachedMtimeMillis = Long.MAX_VALUE;
    s.frozen = true;
    return s;
  }

  /** Returns true if no snapshot is configured or the file is empty/unreadable. */
  public boolean isEmpty() {
    return load().rows.isEmpty();
  }

  /**
   * All rows for {@code accountId}, indexed by file path. Returns an empty multimap if the
   * account has no external activity recorded.
   */
  public ImmutableListMultimap<String, Row> rowsByFileFor(Account.Id accountId) {
    Snapshot snap = load();
    if (snap.rowsByAccountAndFile == null) {
      return ImmutableListMultimap.of();
    }
    return snap.rowsByAccountAndFile.getOrDefault(accountId, ImmutableListMultimap.of());
  }

  /** All rows for {@code accountId} (any file path / project). */
  public ImmutableList<Row> rowsFor(Account.Id accountId) {
    Snapshot snap = load();
    if (snap.rowsByAccount == null) {
      return ImmutableList.of();
    }
    // ImmutableListMultimap.get() returns an empty ImmutableList for missing keys.
    return snap.rowsByAccount.get(accountId);
  }

  /**
   * Returns account ids with external activity, sorted by descending activity volume (row count).
   *
   * <p>This is used as a candidate-seeding fallback for empty-query suggestions so the UI can show
   * suggestions even when NoteDb has no useful reviewer history in a fresh demo.
   */
  public ImmutableList<Account.Id> topAccountsByActivity(int limit) {
    if (limit <= 0) {
      return ImmutableList.of();
    }
    Snapshot snap = load();
    if (snap.rowsByAccount == null || snap.rowsByAccount.isEmpty()) {
      return ImmutableList.of();
    }
    return snap.rowsByAccount.asMap().entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<Account.Id, java.util.Collection<Row>>>comparingInt(
                    e -> e.getValue().size())
                .reversed())
        .limit(limit)
        .map(Map.Entry::getKey)
        .collect(ImmutableList.toImmutableList());
  }

  // --- internal -------------------------------------------------------

  private Snapshot load() {
    if (frozen) {
      return cached;
    }
    String path = config.getString("algorithmicReviewer", null, "externalActivityFile");
    if (path == null || path.isEmpty()) {
      return Snapshot.empty();
    }
    Path p = Paths.get(path);
    long mtime;
    try {
      if (!Files.isRegularFile(p)) {
        if (cachedMtimeMillis != -1L) {
          // File disappeared between calls - drop the cache so we don't keep
          // returning stale data after an admin removes the file.
          cached = Snapshot.empty();
          cachedMtimeMillis = -1L;
        }
        return Snapshot.empty();
      }
      mtime = Files.getLastModifiedTime(p).toMillis();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot stat external activity file %s", path);
      return cached;
    }
    if (mtime == cachedMtimeMillis) {
      return cached;
    }
    Snapshot fresh = parse(p);
    if (fresh != null) {
      cached = fresh;
      cachedMtimeMillis = mtime;
    }
    return cached;
  }

  @Nullable
  private static Snapshot parse(Path p) {
    Snapshot raw;
    try (BufferedReader reader = Files.newBufferedReader(p)) {
      raw = GSON.fromJson(reader, SNAPSHOT_TYPE);
    } catch (IOException | JsonSyntaxException e) {
      logger.atWarning().withCause(e).log("Cannot parse external activity file %s", p);
      return null;
    }
    if (raw == null) {
      return Snapshot.empty();
    }
    if (raw.version > SUPPORTED_VERSION) {
      logger.atWarning().log(
          "external activity file %s has version %d > supported %d; ignoring",
          p, raw.version, SUPPORTED_VERSION);
      return Snapshot.empty();
    }
    raw.index();
    logger.atFine().log(
        "Loaded %d external activity rows from %s", raw.rows.size(), p);
    return raw;
  }

  // --- DTOs -----------------------------------------------------------

  /** A flat row: an account's recorded reviewer activity on one file in one project. */
  public static final class Row {
    @SerializedName("account_id")
    public int accountId;

    @SerializedName("project")
    public String project;

    @SerializedName("file_path")
    public String filePath;

    @SerializedName("label_name")
    public String labelName;

    @SerializedName("vote")
    public int vote;

    @SerializedName("source")
    public String source;

    public Row() {}

    public Row(int accountId, String project, String filePath, int vote, String source) {
      this.accountId = accountId;
      this.project = project;
      this.filePath = filePath;
      this.vote = vote;
      this.source = source;
    }
  }

  /** On-disk shape of the snapshot file. */
  static final class Snapshot {
    int version;

    @SerializedName("generated_at")
    String generatedAt;

    List<Row> rows;

    transient ImmutableListMultimap<Account.Id, Row> rowsByAccount;

    /** account -> (file_path -> rows). */
    transient java.util.Map<Account.Id, ImmutableListMultimap<String, Row>> rowsByAccountAndFile;

    static Snapshot empty() {
      Snapshot s = new Snapshot();
      s.version = SUPPORTED_VERSION;
      s.rows = ImmutableList.of();
      s.rowsByAccount = ImmutableListMultimap.of();
      s.rowsByAccountAndFile = ImmutableMap.of();
      return s;
    }

    void index() {
      if (rows == null) {
        rows = ImmutableList.of();
      }
      ImmutableListMultimap.Builder<Account.Id, Row> byAccount = ImmutableListMultimap.builder();
      Map<Account.Id, ImmutableListMultimap.Builder<String, Row>> perAccountFile = new HashMap<>();
      for (Row r : rows) {
        if (r == null || r.filePath == null) {
          continue;
        }
        Account.Id id = Account.id(r.accountId);
        byAccount.put(id, r);
        perAccountFile
            .computeIfAbsent(id, ignored -> ImmutableListMultimap.builder())
            .put(r.filePath, r);
      }
      this.rowsByAccount = byAccount.build();
      ImmutableMap.Builder<Account.Id, ImmutableListMultimap<String, Row>> perAccountBuilt =
          ImmutableMap.builder();
      for (Map.Entry<Account.Id, ImmutableListMultimap.Builder<String, Row>> e :
          perAccountFile.entrySet()) {
        perAccountBuilt.put(e.getKey(), e.getValue().build());
      }
      this.rowsByAccountAndFile = perAccountBuilt.buildOrThrow();
    }
  }
}
