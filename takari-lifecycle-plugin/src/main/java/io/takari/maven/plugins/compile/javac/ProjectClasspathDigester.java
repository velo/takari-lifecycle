/**
 * Copyright (c) 2014 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package io.takari.maven.plugins.compile.javac;

import io.takari.incrementalbuild.BuildContext.InputMetadata;
import io.takari.incrementalbuild.spi.DefaultBuildContext;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.scope.MojoExecutionScoped;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

@Named
@MojoExecutionScoped
public class ProjectClasspathDigester {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private static final Map<File, ArtifactFile> CACHE = new ConcurrentHashMap<File, ArtifactFile>();

  private final DefaultBuildContext<?> context;

  @Inject
  public ProjectClasspathDigester(DefaultBuildContext<?> context, MavenProject project, MavenSession session) {
    this.context = context;

    // this is only needed for unit tests, but won't hurt in general
    CACHE.remove(new File(project.getBuild().getOutputDirectory()));
    CACHE.remove(new File(project.getBuild().getTestOutputDirectory()));
  }

  /**
   * Detects if classpath dependencies changed compared to the previous build or not.
   */
  public boolean digestDependencies(List<File> dependencies) throws IOException {
    Stopwatch stopwatch = Stopwatch.createStarted();

    boolean changed = false;

    Map<File, ArtifactFile> previousArtifacts = getPreviousDependencies();

    for (File dependency : dependencies) {
      ArtifactFile previousArtifact = previousArtifacts.get(dependency);
      ArtifactFile artifact = CACHE.get(dependency);
      if (artifact == null) {
        if (dependency.isFile()) {
          artifact = newFileArtifact(dependency, previousArtifact);
        } else if (dependency.isDirectory()) {
          artifact = newDirectoryArtifact(dependency, previousArtifact);
        } else {
          // happens with reactor dependencies with empty source folders
          continue;
        }
        CACHE.put(dependency, artifact);
      }

      context.registerInput(new ArtifactFileHolder(artifact));

      if (hasChanged(artifact, previousArtifact)) {
        changed = true;
        log.debug("New or changed classpath entry {}", dependency);
      }
    }

    for (InputMetadata<ArtifactFile> removedArtifact : context.getRemovedInputs(ArtifactFile.class)) {
      changed = true;
      log.debug("Removed classpath entry {}", removedArtifact.getResource().file);
    }

    log.debug("Analyzed {} classpath dependencies ({} ms)", dependencies.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS));

    return changed;
  }

  private boolean hasChanged(ArtifactFile artifact, ArtifactFile previousArtifact) {
    if (previousArtifact == null) {
      return true;
    }
    return artifact.lastModified != previousArtifact.lastModified || artifact.length != previousArtifact.length;
  }

  private ArtifactFile newDirectoryArtifact(File directory, ArtifactFile previousArtifact) {
    StringBuilder msg = new StringBuilder();
    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(directory);
    scanner.setIncludes(new String[] {"**/*.class"});
    scanner.scan();
    long maxLastModified = 0, fileCount = 0;
    for (String path : scanner.getIncludedFiles()) {
      File file = new File(directory, path);
      long lastModified = file.lastModified();
      maxLastModified = Math.max(maxLastModified, lastModified);
      fileCount++;
      if (previousArtifact != null && previousArtifact.lastModified < lastModified) {
        msg.append("\n   new or modfied class folder member ").append(file);
      }
    }

    if (previousArtifact != null && previousArtifact.length != fileCount) {
      msg.append("\n   classfolder member count changed (new ").append(fileCount).append(" previous ").append(previousArtifact.length).append(')');
    }

    if (msg.length() > 0) {
      log.debug("Changed dependency class folder {}: {}", directory, msg.toString());
    }

    return new ArtifactFile(directory, false, fileCount, maxLastModified);
  }

  private ArtifactFile newFileArtifact(File file, ArtifactFile previousArtifact) {
    return new ArtifactFile(file, true, file.length(), file.lastModified());
  }

  private Map<File, ArtifactFile> getPreviousDependencies() {
    Map<File, ArtifactFile> result = new HashMap<File, ArtifactFile>();

    for (InputMetadata<ArtifactFile> metadata : context.getRegisteredInputs(ArtifactFile.class)) {
      ArtifactFile artifact = metadata.getResource();
      result.put(artifact.file, artifact);
    }

    return result;
  }
}
