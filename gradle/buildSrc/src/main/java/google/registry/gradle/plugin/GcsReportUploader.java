// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.gradle.plugin;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.gradle.plugin.GcsPluginUtils.createReportFiles;
import static google.registry.gradle.plugin.GcsPluginUtils.toByteArraySupplier;
import static google.registry.gradle.plugin.GcsPluginUtils.toNormalizedPath;
import static google.registry.gradle.plugin.GcsPluginUtils.uploadFileToGcs;
import static google.registry.gradle.plugin.GcsPluginUtils.uploadFilesToGcsMultithread;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.collect.ImmutableMap;
import google.registry.gradle.plugin.ProjectData.TaskData;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Supplier;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.reporting.DirectoryReport;
import org.gradle.api.reporting.Report;
import org.gradle.api.reporting.ReportContainer;
import org.gradle.api.reporting.Reporting;
import org.gradle.api.tasks.TaskAction;

/**
 * A task that uploads the Reports generated by other tasks to GCS.
 */
public class GcsReportUploader extends DefaultTask {

  private static final SecureRandom secureRandom = new SecureRandom();

  private final ArrayList<Task> tasks = new ArrayList<>();
  private final HashMap<String, StringBuilder> logs = new HashMap<>();
  private Project project;

  private String bucket = null;
  private String credentialsFile = null;
  private String multithreadedUpload = null;

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public void setCredentialsFile(String credentialsFile) {
    this.credentialsFile = credentialsFile;
  }

  public void setMultithreadedUpload(String multithreadedUpload) {
    this.multithreadedUpload = multithreadedUpload;
  }

  /** Converts the given Gradle Project into a ProjectData. */
  private ProjectData createProjectData() {
    ProjectData.Builder builder =
        ProjectData.builder()
            .setName(project.getPath() + project.getName())
            .setDescription(
                Optional.ofNullable(project.getDescription()).orElse("[No description available]"))
            .setGradleVersion(project.getGradle().getGradleVersion())
            .setProjectProperties(project.getGradle().getStartParameter().getProjectProperties())
            .setSystemProperties(project.getGradle().getStartParameter().getSystemPropertiesArgs())
            .setTasksRequested(project.getGradle().getStartParameter().getTaskNames());

    Path rootDir = toNormalizedPath(project.getRootDir());
    tasks.stream()
        .filter(task -> task.getState().getExecuted() || task.getState().getUpToDate())
        .map(task -> createTaskData(task, rootDir))
        .forEach(builder.tasksBuilder()::add);
    return builder.build();
  }

  /**
   * Converts a Gradle Task into a TaskData.
   *
   * @param rootDir the root directory of the main Project - used to get the relative path of any
   *     Task files.
   */
  private TaskData createTaskData(Task task, Path rootDir) {
    TaskData.State state =
        task.getState().getFailure() != null
            ? TaskData.State.FAILURE
            : task.getState().getUpToDate() ? TaskData.State.UP_TO_DATE : TaskData.State.SUCCESS;
    String log = logs.get(task.getPath()).toString();

    TaskData.Builder builder =
        TaskData.builder()
            .setState(state)
            .setUniqueName(task.getPath())
            .setDescription(
                Optional.ofNullable(task.getDescription()).orElse("[No description available]"));
    if (!log.isEmpty()) {
      builder.setLog(toByteArraySupplier(log));
    }

    Reporting<? extends ReportContainer<? extends Report>> reporting = asReporting(task);

    if (reporting != null) {
      // This Task is also a Reporting task! It has a destination file/directory for every supported
      // format.
      // Add the files for each of the formats into the ReportData.
      reporting
          .getReports()
          .getAsMap()
          .forEach(
              (type, report) -> {
                File destination = report.getDestination();
                // The destination could be a file, or a directory. If it's a directory - the Report
                // could have created multiple files - and we need to know to which one of those to
                // link.
                //
                // If we're lucky, whoever implemented the Report made sure to extend
                // DirectoryReport, which gives us the entry point to all the files.
                //
                // This isn't guaranteed though, as it depends on the implementer.
                Optional<File> entryPointHint =
                    destination.isDirectory() && (report instanceof DirectoryReport)
                        ? Optional.ofNullable(((DirectoryReport) report).getEntryPoint())
                        : Optional.empty();
                builder
                    .reportsBuilder()
                    .put(type, createReportFiles(destination, entryPointHint, rootDir));
              });
    }
    return builder.build();
  }

  @TaskAction
  void uploadResults() {
    System.out.format("GcsReportUploader: bucket= '%s'\n", bucket);
    if (isNullOrEmpty(bucket)) {
      System.out.format("GcsReportUploader: no bucket defined. Skipping upload\n");
      return;
    }

    try {
      uploadResultsToGcs();
    } catch (Throwable e) {
      System.out.format("GcsReportUploader: Encountered error %s\n", e);
      e.printStackTrace(System.out);
      System.out.format("GcsReportUploader: skipping upload\n");
    }
  }

  void uploadResultsToGcs() {
    checkNotNull(bucket);
    ProjectData projectData = createProjectData();

    Path folder = Paths.get(createUniqueFolderName());

    StorageOptions.Builder storageOptions = StorageOptions.newBuilder();
    if (!isNullOrEmpty(credentialsFile)) {
      try {
        storageOptions.setCredentials(
            GoogleCredentials.fromStream(new FileInputStream(credentialsFile)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    Storage storage = storageOptions.build().getService();

    CoverPageGenerator coverPageGenerator = new CoverPageGenerator(projectData);
    ImmutableMap<Path, Supplier<byte[]>> filesToUpload = coverPageGenerator.getFilesToUpload();

    System.out.format(
        "GcsReportUploader: going to upload %s files to %s/%s\n",
        filesToUpload.size(), bucket, folder);
    if ("yes".equals(multithreadedUpload)) {
      System.out.format("GcsReportUploader: multi-threaded upload\n");
      uploadFilesToGcsMultithread(storage, bucket, folder, filesToUpload);
    } else {
      System.out.format("GcsReportUploader: single threaded upload\n");
      filesToUpload.forEach(
          (path, dataSupplier) -> {
            System.out.format("GcsReportUploader: Uploading %s\n", path);
            uploadFileToGcs(storage, bucket, folder.resolve(path), dataSupplier);
          });
    }
    System.out.format(
        "GcsReportUploader: report uploaded to https://storage.googleapis.com/%s/%s\n",
        bucket, folder.resolve(coverPageGenerator.getEntryPoint()));
  }

  void setProject(Project project) {
    this.project = project;

    for (Project subProject : project.getAllprojects()) {
      subProject.getTasks().all(this::addTask);
    }
  }

  private void addTask(Task task) {
    if (task instanceof GcsReportUploader) {
      return;
    }
    tasks.add(task);
    StringBuilder log = new StringBuilder();
    checkArgument(
        !logs.containsKey(task.getPath()),
        "Multiple tasks with the same .getPath()=%s",
        task.getPath());
    logs.put(task.getPath(), log);
    task.getLogging().addStandardOutputListener(output -> log.append(output));
    task.getLogging().addStandardErrorListener(output -> log.append(output));
    task.finalizedBy(this);
  }

  @SuppressWarnings("unchecked")
  private static Reporting<? extends ReportContainer<? extends Report>> asReporting(Task task) {
    if (task instanceof Reporting) {
      return (Reporting<? extends ReportContainer<? extends Report>>) task;
    }
    return null;
  }

  private String createUniqueFolderName() {
    return String.format(
        "%h-%h-%h-%h",
        secureRandom.nextInt(),
        secureRandom.nextInt(),
        secureRandom.nextInt(),
        secureRandom.nextInt());
  }
}
