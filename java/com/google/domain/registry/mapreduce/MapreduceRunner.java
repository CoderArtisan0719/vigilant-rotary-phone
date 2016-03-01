// Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.domain.registry.mapreduce;

import static com.google.appengine.api.search.checkers.Preconditions.checkNotNull;
import static com.google.appengine.tools.pipeline.PipelineServiceFactory.newPipelineService;
import static com.google.domain.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.MapJob;
import com.google.appengine.tools.mapreduce.MapReduceJob;
import com.google.appengine.tools.mapreduce.MapReduceSettings;
import com.google.appengine.tools.mapreduce.MapReduceSpecification;
import com.google.appengine.tools.mapreduce.MapSettings;
import com.google.appengine.tools.mapreduce.MapSpecification;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Marshallers;
import com.google.appengine.tools.mapreduce.Output;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.outputs.NoOutput;
import com.google.appengine.tools.pipeline.Job0;
import com.google.appengine.tools.pipeline.JobSetting;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.domain.registry.request.Parameter;
import com.google.domain.registry.util.FormattingLogger;
import com.google.domain.registry.util.PipelineUtils;

import org.joda.time.Duration;

import java.io.Serializable;

import javax.inject.Inject;

/**
 * Runner for map-only or full map and reduce mapreduces.
 *
 * <p>We use hardcoded serialization marshallers for moving data between steps, so all types used as
 * keys or values must implement {@link Serializable}.
 */
public class MapreduceRunner {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  public static final String PARAM_DRY_RUN = "dryRun";
  public static final String PARAM_MAP_SHARDS = "mapShards";
  public static final String PARAM_REDUCE_SHARDS = "reduceShards";

  private static final String BASE_URL = "/_dr/mapreduce/";
  private static final String QUEUE_NAME = "mapreduce";

  private final Optional<Integer> httpParamMapShards;
  private final Optional<Integer> httpParamReduceShards;

  // Default to 3 minutes since many slices will contain datastore queries that time out at 4:30.
  private Duration sliceDuration = Duration.standardMinutes(3);
  private String jobName;
  private String moduleName;

  // If no reduce shards are set via http params, use this many shards.
  private int defaultReduceShards = 1;

  /**
   * @param mapShards number of map shards; if omitted, the {@link Input} objects will choose
   * @param reduceShards number of reduce shards; if omitted, uses {@link #defaultReduceShards}
   */
  @Inject
  @VisibleForTesting
  public MapreduceRunner(
      @Parameter(PARAM_MAP_SHARDS) Optional<Integer> mapShards,
      @Parameter(PARAM_REDUCE_SHARDS) Optional<Integer> reduceShards) {
    this.httpParamMapShards = mapShards;
    this.httpParamReduceShards = reduceShards;
  }

  /** Set the max time to run a slice before serializing; defaults to 3 minutes. */
  public MapreduceRunner setSliceDuration(Duration sliceDuration) {
    this.sliceDuration = checkArgumentNotNull(sliceDuration, "sliceDuration");
    return this;
  }

  /** Set the human readable job name for display purposes. */
  public MapreduceRunner setJobName(String jobName) {
    this.jobName = checkArgumentNotNull(jobName, "jobName");
    return this;
  }

  /** Set the module to run in. */
  public MapreduceRunner setModuleName(String moduleName) {
    this.moduleName = checkArgumentNotNull(moduleName, "moduleName");
    return this;
  }

  /** Set the default number of reducers, if not overriden by the http param. */
  public MapreduceRunner setDefaultReduceShards(int defaultReduceShards) {
    this.defaultReduceShards = checkNotNull(defaultReduceShards, "defaultReduceShards");
    return this;
  }

  /**
   * Create a map-only mapreduce to be run as part of a pipeline.
   *
   * @see #runMapOnly for creating and running an independent map-only mapreduce
   *
   * @param mapper instance of a mapper class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <O> individual output record type sent to the {@link Output}
   * @param <R> overall output result type
   */
  public <I, O, R> MapJob<I, O, R> createMapOnlyJob(
      Mapper<I, Void, O> mapper,
      Output<O, R> output,
      Iterable<? extends Input<? extends I>> inputs) {
    checkCommonRequiredFields(inputs, mapper);
    return new MapJob<>(
        new MapSpecification.Builder<I, O, R>()
            .setJobName(jobName)
            .setInput(new ConcatenatingInput<>(inputs, httpParamMapShards.or(Integer.MAX_VALUE)))
            .setMapper(mapper)
            .setOutput(output)
            .build(),
        new MapSettings.Builder()
            .setWorkerQueueName(QUEUE_NAME)
            .setBaseUrl(BASE_URL)
            .setModule(moduleName)
            .setMillisPerSlice((int) sliceDuration.getMillis())
            .build());
  }

  /**
   * Kick off a map-only mapreduce.
   *
   * <p>For simplicity, the mapreduce is hard-coded with {@link NoOutput}, on the assumption that
   * all work will be accomplished via side effects during the map phase.
   *
   * @see #createMapOnlyJob for creating and running a map-only mapreduce as part of a pipeline
   *
   * @param mapper instance of a mapper class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @return the job id
   */
  public <I> String runMapOnly(
      Mapper<I, Void, Void> mapper,
      Iterable<? extends Input<? extends I>> inputs) {
    return runAsPipeline(createMapOnlyJob(mapper, new NoOutput<Void, Void>(), inputs));
  }

  /**
   * Create a mapreduce job to be run as part of a pipeline.
   *
   * @see #runMapreduce for creating and running an independent mapreduce
   *
   * @param mapper instance of a mapper class
   * @param reducer instance of a reducer class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <K> emitted key type
   * @param <V> emitted value type
   * @param <O> individual output record type sent to the {@link Output}
   * @param <R> overall output result type
   */
  public final <I, K extends Serializable, V extends Serializable, O, R> MapReduceJob<I, K, V, O, R>
      createMapreduceJob(
          Mapper<I, K, V> mapper,
          Reducer<K, V, O> reducer,
          Output<O, R> output,
          Iterable<? extends Input<? extends I>> inputs) {
    checkCommonRequiredFields(inputs, mapper);
    checkArgumentNotNull(reducer, "reducer");
    return new MapReduceJob<>(
        new MapReduceSpecification.Builder<I, K, V, O, R>()
            .setJobName(jobName)
            .setInput(new ConcatenatingInput<>(inputs, httpParamMapShards.or(Integer.MAX_VALUE)))
            .setMapper(mapper)
            .setReducer(reducer)
            .setOutput(output)
            .setKeyMarshaller(Marshallers.<K>getSerializationMarshaller())
            .setValueMarshaller(Marshallers.<V>getSerializationMarshaller())
            .setNumReducers(httpParamReduceShards.or(defaultReduceShards))
            .build(),
        new MapReduceSettings.Builder()
            .setWorkerQueueName(QUEUE_NAME)
            .setBaseUrl(BASE_URL)
            .setModule(moduleName)
            .setMillisPerSlice((int) sliceDuration.getMillis())
            .build());
  }

  /**
   * Kick off a mapreduce job.
   *
   * <p>For simplicity, the mapreduce is hard-coded with {@link NoOutput}, on the assumption that
   * all work will be accomplished via side effects during the map or reduce phases.
   *
   * @see #createMapreduceJob for creating and running a mapreduce as part of a pipeline
   *
   * @param mapper instance of a mapper class
   * @param reducer instance of a reducer class
   * @param inputs input sources for the mapper
   * @param <I> mapper input type
   * @param <K> emitted key type
   * @param <V> emitted value type
   * @return the job id
   */
  public final <I, K extends Serializable, V extends Serializable> String runMapreduce(
      Mapper<I, K, V> mapper,
      Reducer<K, V, Void> reducer,
      Iterable<? extends Input<? extends I>> inputs) {
    return runAsPipeline(
        createMapreduceJob(mapper, reducer, new NoOutput<Void, Void>(), inputs));
  }

  private void checkCommonRequiredFields(Iterable<?> inputs, Mapper<?, ?, ?> mapper) {
    checkNotNull(jobName, "jobName");
    checkNotNull(moduleName, "moduleName");
    checkArgumentNotNull(inputs, "inputs");
    checkArgumentNotNull(mapper, "mapper");
  }

  private String runAsPipeline(Job0<?> job) {
    String jobId = newPipelineService().startNewPipeline(
        job,
        new JobSetting.OnModule(moduleName),
        new JobSetting.OnQueue(QUEUE_NAME));
    logger.infofmt(
        "Started '%s' %s job: %s",
        jobName,
        job instanceof MapJob ? "map" : "mapreduce",
        PipelineUtils.createJobPath(jobId));
    return jobId;
  }
}
