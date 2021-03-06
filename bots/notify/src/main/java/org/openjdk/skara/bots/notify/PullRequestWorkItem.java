/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.*;

public class PullRequestWorkItem implements WorkItem {
    private final PullRequest pr;
    private final StorageBuilder<PullRequestState> prStateStorageBuilder;
    private final List<PullRequestListener> listeners;
    private final Consumer<RuntimeException> errorHandler;
    private final String integratorId;

    PullRequestWorkItem(PullRequest pr, StorageBuilder<PullRequestState> prStateStorageBuilder, List<PullRequestListener> listeners, Consumer<RuntimeException> errorHandler, String integratorId) {
        this.pr = pr;
        this.prStateStorageBuilder = prStateStorageBuilder;
        this.listeners = listeners;
        this.errorHandler = errorHandler;
        this.integratorId = integratorId;
    }

    private final static Pattern pushedPattern = Pattern.compile("Pushed as commit ([a-f0-9]{40})\\.");

    private Hash resultingCommitHash() {
        if (pr.labels().contains("integrated")) {
            return pr.comments().stream()
                     .filter(comment -> comment.author().id().equals(integratorId))
                     .map(Comment::body)
                     .map(pushedPattern::matcher)
                     .filter(Matcher::find)
                     .map(m -> m.group(1))
                     .map(Hash::new)
                     .findAny()
                     .orElse(null);
        }
        return null;
    }

    private Set<PullRequestState> deserializePrState(String current) {
        if (current.isBlank()) {
            return Set.of();
        }
        var data = JSON.parse(current);
        return data.stream()
                   .map(JSONValue::asObject)
                   .map(obj -> {
                       var id = obj.get("pr").asString();
                       var issues = obj.get("issues").stream()
                                                     .map(JSONValue::asString)
                                                     .collect(Collectors.toSet());

                       // Storage might be missing commit information
                       if (!obj.contains("commit")) {
                           obj.put("commit", Hash.zero().hex());
                       }

                       var commit = obj.get("commit").isNull() ?
                           null : new Hash(obj.get("commit").asString());

                       return new PullRequestState(id, issues, commit);
                   })
                   .collect(Collectors.toSet());
    }

    private String serializePrState(Collection<PullRequestState> added, Set<PullRequestState> existing) {
        var addedPrs = added.stream()
                            .map(PullRequestState::prId)
                            .collect(Collectors.toSet());
        var nonReplaced = existing.stream()
                                  .filter(item -> !addedPrs.contains(item.prId()))
                                  .collect(Collectors.toSet());

        var entries = Stream.concat(nonReplaced.stream(),
                                    added.stream())
                            .sorted(Comparator.comparing(PullRequestState::prId))
                            .map(pr -> {
                                var issues = new JSONArray(pr.issueIds()
                                                             .stream()
                                                             .map(JSON::of)
                                                             .collect(Collectors.toList()));
                                var ret = JSON.object().put("pr", pr.prId())
                                              .put("issues",issues);
                                if (pr.commitId().isPresent()) {
                                    if (!pr.commitId().get().equals(Hash.zero())) {
                                        ret.put("commit", JSON.of(pr.commitId().get().hex()));
                                    }
                                } else {
                                    ret.putNull("commit");
                                }
                                return ret;
                            })
                            .map(JSONObject::toString)
                            .collect(Collectors.toList());
        return "[\n" + String.join(",\n", entries) + "\n]";
    }

    private final Pattern issuesBlockPattern = Pattern.compile("\\n\\n###? Issues?((?:\\n(?: \\* )?\\[.*)+)", Pattern.MULTILINE);
    private final Pattern issuePattern = Pattern.compile("^(?: \\* )?\\[(\\S+)]\\(.*\\): .*$", Pattern.MULTILINE);

    private Set<String> parseIssues() {
        var issuesBlockMatcher = issuesBlockPattern.matcher(pr.body());
        if (!issuesBlockMatcher.find()) {
            return Set.of();
        }
        var issueMatcher = issuePattern.matcher(issuesBlockMatcher.group(1));
        return issueMatcher.results()
                           .map(mo -> mo.group(1))
                           .collect(Collectors.toSet());
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem)) {
            return true;
        }
        PullRequestWorkItem otherItem = (PullRequestWorkItem)other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!pr.repository().name().equals(otherItem.pr.repository().name())) {
            return true;
        }
        return false;
    }

    private void notifyNewIssue(String issueId) {
        listeners.forEach(c -> c.onNewIssue(pr, new Issue(issueId, "")));
    }

    private void notifyRemovedIssue(String issueId) {
        listeners.forEach(c -> c.onRemovedIssue(pr, new Issue(issueId, "")));
    }

    private void notifyNewPr(PullRequest pr) {
        listeners.forEach(c -> c.onNewPullRequest(pr));
    }

    private void notifyIntegratedPr(PullRequest pr, Hash hash) {
        listeners.forEach(c -> c.onIntegratedPullRequest(pr, hash));
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var historyPath = scratchPath.resolve("notify").resolve("history");
        var storage = prStateStorageBuilder
                .serializer(this::serializePrState)
                .deserializer(this::deserializePrState)
                .materialize(historyPath);

        var issues = parseIssues();
        var commit = resultingCommitHash();
        var state = new PullRequestState(pr, issues, commit);
        var stored = storage.current();
        if (stored.contains(state)) {
            // Already up to date
            return List.of();
        }

        // Search for an existing
        var storedState = stored.stream()
                .filter(ss -> ss.prId().equals(state.prId()))
                .findAny();
        // The stored entry could be old and be missing commit information - if so, upgrade it
        if (storedState.isPresent() && storedState.get().commitId().equals(Optional.of(Hash.zero()))) {
            var hash = resultingCommitHash();
            storedState = Optional.of(new PullRequestState(pr, storedState.get().issueIds(), hash));
            storage.put(storedState.get());
        }

        if (storedState.isPresent()) {
            var storedIssues = storedState.get().issueIds();
            storedIssues.stream()
                        .filter(issue -> !issues.contains(issue))
                        .forEach(this::notifyRemovedIssue);
            issues.stream()
                  .filter(issue -> !storedIssues.contains(issue))
                  .forEach(this::notifyNewIssue);

            var storedCommit = storedState.get().commitId();
            if (!storedCommit.isPresent() && state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get());
            }
        } else {
            notifyNewPr(pr);
            issues.forEach(this::notifyNewIssue);
            if (state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get());
            }
        }

        storage.put(state);
        return List.of();
    }

    @Override
    public String toString() {
        return "Notify.PR@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }
}
