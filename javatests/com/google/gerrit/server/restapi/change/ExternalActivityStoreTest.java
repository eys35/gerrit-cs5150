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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.restapi.change.ExternalActivityStore.Row;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ExternalActivityStoreTest {
  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void emptyByDefaultWhenConfigKeyMissing() throws Exception {
    ExternalActivityStore store = newStoreFromConfig(new Config());
    assertThat(store.isEmpty()).isTrue();
    assertThat(store.rowsFor(Account.id(1))).isEmpty();
  }

  @Test
  public void emptyWhenConfiguredFileDoesNotExist() throws Exception {
    Config c = new Config();
    c.setString(
        "algorithmicReviewer", null, "externalActivityFile",
        tmp.getRoot().toPath().resolve("nope.json").toString());
    ExternalActivityStore store = newStoreFromConfig(c);
    assertThat(store.isEmpty()).isTrue();
  }

  @Test
  public void parsesValidSnapshotAndIndexesByAccountAndFile() throws Exception {
    Path file = writeSnapshot(
        "{\"version\":1,\"generated_at\":\"2025-04-26T19:00:00Z\",\"rows\":["
            + "{\"account_id\":1001,\"project\":\"acme/widgets\",\"file_path\":\"src/a.ts\","
            + "\"label_name\":\"Code-Review\",\"vote\":1,\"source\":\"github\"},"
            + "{\"account_id\":1001,\"project\":\"acme/widgets\",\"file_path\":\"src/b.ts\","
            + "\"label_name\":\"Code-Review\",\"vote\":-1,\"source\":\"github\"},"
            + "{\"account_id\":1002,\"project\":\"acme/widgets\",\"file_path\":\"src/a.ts\","
            + "\"label_name\":\"\",\"vote\":0,\"source\":\"github\"}"
            + "]}");
    ExternalActivityStore store = newStoreFromConfig(configFor(file));

    assertThat(store.isEmpty()).isFalse();

    ImmutableList<Row> aliceRows = store.rowsFor(Account.id(1001));
    assertThat(aliceRows).hasSize(2);

    ImmutableListMultimap<String, Row> byFile = store.rowsByFileFor(Account.id(1001));
    assertThat(byFile.keySet()).containsExactly("src/a.ts", "src/b.ts");
    assertThat(byFile.get("src/a.ts").get(0).vote).isEqualTo(1);

    assertThat(store.rowsFor(Account.id(1002))).hasSize(1);
    assertThat(store.rowsFor(Account.id(9999))).isEmpty();
  }

  @Test
  public void rejectsFutureSchemaVersionsGracefully() throws Exception {
    Path file = writeSnapshot(
        "{\"version\":99,\"rows\":["
            + "{\"account_id\":1,\"project\":\"p\",\"file_path\":\"f\",\"vote\":1,"
            + "\"source\":\"github\"}]}");
    ExternalActivityStore store = newStoreFromConfig(configFor(file));
    assertThat(store.isEmpty()).isTrue();
  }

  @Test
  public void malformedJsonKeepsLastGoodSnapshot() throws Exception {
    Path file = writeSnapshot(
        "{\"version\":1,\"rows\":["
            + "{\"account_id\":1,\"project\":\"p\",\"file_path\":\"f\",\"vote\":1,"
            + "\"source\":\"github\"}]}");
    ExternalActivityStore store = newStoreFromConfig(configFor(file));
    assertThat(store.isEmpty()).isFalse();

    // Corrupt the file.
    Files.writeString(file, "not json {{{");
    // Touch mtime so the loader sees a "change" and tries to re-parse.
    Files.setLastModifiedTime(
        file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

    // The current behaviour is to keep returning the last good snapshot when parsing fails;
    // verify that property holds.
    assertThat(store.isEmpty()).isFalse();
  }

  @Test
  public void missingFileAfterPreviousLoadResetsCacheToEmpty() throws Exception {
    Path file = writeSnapshot(
        "{\"version\":1,\"rows\":["
            + "{\"account_id\":1,\"project\":\"p\",\"file_path\":\"f\",\"vote\":1,"
            + "\"source\":\"github\"}]}");
    ExternalActivityStore store = newStoreFromConfig(configFor(file));
    assertThat(store.isEmpty()).isFalse();

    Files.delete(file);
    assertThat(store.isEmpty()).isTrue();
  }

  // --- helpers --------------------------------------------------------

  private Path writeSnapshot(String json) throws IOException {
    Path p = tmp.newFile("snapshot.json").toPath();
    Files.writeString(p, json);
    return p;
  }

  private Config configFor(Path snapshot) {
    Config c = new Config();
    c.setString("algorithmicReviewer", null, "externalActivityFile", snapshot.toString());
    return c;
  }

  /**
   * The package-private constructor takes {@code @GerritServerConfig Config}; reflectively
   * construct it so the test doesn't need a Guice injector.
   */
  private static ExternalActivityStore newStoreFromConfig(Config config) throws Exception {
    Constructor<ExternalActivityStore> ctor =
        ExternalActivityStore.class.getDeclaredConstructor(Config.class);
    ctor.setAccessible(true);
    ExternalActivityStore store = ctor.newInstance(config);
    // Reset the volatile mtime to a fresh state to make sure the first load() actually parses.
    Field mtime = ExternalActivityStore.class.getDeclaredField("cachedMtimeMillis");
    mtime.setAccessible(true);
    mtime.setLong(store, -1L);
    return store;
  }
}
