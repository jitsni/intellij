/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.dependencies;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.bazel.BuildSystemProvider;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeInvocationContext.ContextType;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.query.BlazeQueryLabelKindParser;
import com.google.idea.blaze.base.query.BlazeQueryOutputBaseProvider;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.scopes.IdeaLogScope;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.sync.workspace.WorkspaceHelper;
import com.google.idea.blaze.base.sync.workspace.WorkspacePathResolver;
import com.google.idea.blaze.base.sync.workspace.WorkspacePathResolverProvider;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import javax.annotation.Nullable;
import org.jetbrains.ide.PooledThreadExecutor;

/**
 * Given a source file, runs a blaze query invocation to find the direct rdeps of that file.
 *
 * <p>This is expected to be slow, so should be asynchronous and/or cancellable.
 */
public class BlazeQuerySourceToTargetProvider implements SourceToTargetProvider {

  private static final Logger logger = Logger.getInstance(BlazeQuerySourceToTargetProvider.class);

  /**
   * Currently disabled for performance reasons. SourceToTargetProvider is called often, in the
   * background, and we don't want to monopolize the local blaze server.
   */
  private static final BoolExperiment enabled =
      new BoolExperiment("use.blaze.query.for.background.rdeps", false);

  @Override
  public Future<List<TargetInfo>> getTargetsBuildingSourceFile(
      Project project, String workspaceRelativePath) {
    if (!enabled.getValue()) {
      return Futures.immediateFuture(null);
    }
    Label label = getSourceLabel(project, workspaceRelativePath);
    if (label == null) {
      return Futures.immediateFuture(null);
    }
    return PooledThreadExecutor.INSTANCE.submit(
        () ->
            Scope.root(
                context -> {
                  context.push(new IdeaLogScope());
                  return runDirectRdepsQuery(
                      project, ImmutableList.of(label), context, ContextType.Other);
                }));
  }

  /** Synchronously runs a blaze query to find the direct rdeps of the given source files. */
  @Nullable
  public static ImmutableList<TargetInfo> getTargetsBuildingSourceFiles(
      Project project, Collection<WorkspacePath> sources, BlazeContext context, ContextType type) {
    ImmutableList<Label> labels =
        sources.stream()
            .map(s -> getSourceLabel(project, s.relativePath()))
            .filter(Objects::nonNull)
            .collect(toImmutableList());
    return runDirectRdepsQuery(project, labels, context, type);
  }

  @Nullable
  private static ImmutableList<TargetInfo> runDirectRdepsQuery(
      Project project, Collection<Label> sources, BlazeContext context, ContextType type) {
    if (sources.isEmpty()) {
      return ImmutableList.of();
    }
    String expr = Joiner.on('+').join(sources);
    String query = String.format("same_pkg_direct_rdeps(%s)", expr);

    // never use a custom output base for queries during sync
    String outputBaseFlag =
        type == ContextType.Sync
            ? null
            : BlazeQueryOutputBaseProvider.getInstance(project).getOutputBaseFlag();

    BlazeCommand command =
        BlazeCommand.builder(getBinaryPath(project), BlazeCommandName.QUERY)
            .addBlazeFlags("--output=label_kind")
            .addBlazeFlags("--keep_going")
            .addBlazeFlags(query)
            .addBlazeStartupFlags(
                outputBaseFlag == null ? ImmutableList.of() : ImmutableList.of(outputBaseFlag))
            .build();

    BlazeQueryLabelKindParser outputProcessor = new BlazeQueryLabelKindParser(t -> true);
    int retVal =
        ExternalTask.builder(WorkspaceRoot.fromProject(project))
            .addBlazeCommand(command)
            .context(context)
            .stdout(LineProcessingOutputStream.of(outputProcessor))
            .stderr(
                LineProcessingOutputStream.of(
                    line -> {
                      // errors are expected, so limit logging to info level
                      logger.info(line);
                      return true;
                    }))
            .build()
            .run();
    if (retVal != 0 && retVal != 3) {
      // exit code of 3 represents a potentially expected, non-fatal error
      return null;
    }
    return outputProcessor.getTargets();
  }

  /**
   * Derives the blaze target label corresponding to a source file, or null if it can't be
   * determined.
   */
  @Nullable
  private static Label getSourceLabel(Project project, String workspaceRelativePath) {
    WorkspacePathResolver resolver =
        WorkspacePathResolverProvider.getInstance(project).getPathResolver();
    if (resolver == null) {
      return null;
    }
    File file = resolver.resolveToFile(workspaceRelativePath);
    return WorkspaceHelper.getBuildLabel(project, file);
  }

  private static String getBinaryPath(Project project) {
    BuildSystemProvider buildSystemProvider = Blaze.getBuildSystemProvider(project);
    return buildSystemProvider.getSyncBinaryPath(project);
  }
}
