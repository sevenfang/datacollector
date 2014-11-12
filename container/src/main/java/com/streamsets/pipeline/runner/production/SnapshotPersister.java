/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.runner.production;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.container.Utils;
import com.streamsets.pipeline.record.RecordImplDeserializer;
import com.streamsets.pipeline.runner.ErrorRecords;
import com.streamsets.pipeline.runner.StageOutput;
import com.streamsets.pipeline.util.NullDeserializer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SnapshotPersister {

  static final String DEFAULT_PIPELINE_NAME = "xyz";
  private static final String SNAPSHOT_FILE = "snapshot.json";
  private static final String SNAPSHOT_DIR = "runInfo";
  private static final String STAGE_FILE_SUFFIX = ".tmp";

  private File snapshotDir;
  private File snapshotFile;
  private File snapshotStageFile;
  private ObjectMapper json;

  public SnapshotPersister(RuntimeInfo runtimeInfo) {
    this.snapshotDir = new File(runtimeInfo.getDataDir(),
        SNAPSHOT_DIR + File.separator + DEFAULT_PIPELINE_NAME);
    this.snapshotFile = new File(snapshotDir, SNAPSHOT_FILE);
    this.snapshotStageFile = new File(snapshotFile.getAbsolutePath() + STAGE_FILE_SUFFIX);
    initializeObjectMapper();
  }

  private void initializeObjectMapper() {
    json = new ObjectMapper();
    json.enable(SerializationFeature.INDENT_OUTPUT);
    final SimpleModule module = new SimpleModule("", Version.unknownVersion());
    module.addDeserializer(Record.class, new RecordImplDeserializer());
    module.addDeserializer(ErrorRecords.class, new NullDeserializer());
    json.registerModule(module);
  }

  public void persistSnapshot(List<StageOutput> snapshot) {
    if(!snapshotDir.exists()) {
      if(!snapshotDir.mkdirs()) {
        throw new RuntimeException(Utils.format("Could not create directory '{}'", snapshotDir.getAbsolutePath()));
      }
    }
    try {
      json.writeValue(snapshotStageFile, snapshot);
      Files.copy(snapshotStageFile, snapshotFile);
      snapshotStageFile.delete();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  public List<StageOutput> retrieveSnapshot() {
    if(!snapshotDir.exists() || !snapshotFile.exists()) {
      return Collections.EMPTY_LIST;
    }
    try {
      return json.readValue(snapshotFile, json.getTypeFactory().constructCollectionType(
          ArrayList.class, StageOutput.class));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public File getSnapshotFile() {
    return snapshotFile;
  }

  public File getSnapshotStageFile() {
    return snapshotStageFile;
  }

}
