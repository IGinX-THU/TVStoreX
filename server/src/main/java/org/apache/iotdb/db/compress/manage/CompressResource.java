/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.compress.manage;

import static org.apache.iotdb.db.compress.task.CompressTask.COMPRESS_SUFFIX;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.query.reader.IPointReader;
import org.apache.iotdb.db.query.reader.resourceRelated.CachedUnseqResourceMergeReader;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetaData;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Chunk;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.write.chunk.ChunkWriterImpl;
import org.apache.iotdb.tsfile.write.chunk.IChunkWriter;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.writer.RestorableTsFileIOWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompressResource manages files and caches of readers, writers, MeasurementSchemas and
 * modifications to avoid unnecessary object creations and file openings.
 */
public class CompressResource {
  private static final Logger logger = LoggerFactory.getLogger(
      CompressResource.class);

  private List<TsFileResource> seqFiles;

  private Map<TsFileResource, TsFileSequenceReader> fileReaderCache = new HashMap<>();
  private Map<TsFileResource, RestorableTsFileIOWriter> fileWriterCache = new HashMap<>();
  private Map<TsFileResource, List<Modification>> modificationCache = new HashMap<>();
  private Map<String, MeasurementSchema> measurementSchemaMap = new HashMap<>();
  private Map<MeasurementSchema, ChunkWriterImpl> chunkWriterCache = new ConcurrentHashMap<>();
  private boolean cacheDeviceMeta = false;
  private long timeHigherBound;


  public CompressResource(List<TsFileResource> seqFiles, long timeHigherBound) {
    this.timeHigherBound = timeHigherBound;
    this.seqFiles = seqFiles.stream().filter(this::filterResource)
        .collect(Collectors.toList());
    logger.info("seqFiles的个数为{}, 检测过后CompressResource file 的个数为={}，",seqFiles.size(),  this.seqFiles.size());
  }

  private boolean filterResource(TsFileResource res) {
    logger.info("file {}:res.isClosed() ={},res.isDeleted()={},res.stillLives(timeHigherBound)={}.",
        res.getFile().getAbsolutePath(),res.isClosed() ,res.isDeleted() ,res.stillLives(timeHigherBound));
    return res.isClosed() && !res.isDeleted() && !res.stillLives(timeHigherBound);
  }


  public List<Integer> handle(int compressTime, int targetPageNum){
    if(compressTime<=0){
      return null;
    }

    int i = 0;
    int cnt = 0;
    List<Integer> integerList = new ArrayList<>();
    while (cnt<compressTime){
      i++;
      cnt+=fun(i);
    }

    if(i<=targetPageNum){
      integerList.add(fun(i));
      integerList.addAll(handle(compressTime-fun(i), targetPageNum-1));

    }
    else {
      int temp = 0;
      for (int j = 1; j <= targetPageNum; j++) {
        temp +=fun(j);
      }

      while (temp<compressTime){
        i++;
        temp-=fun(i-targetPageNum);
        temp+=fun(i);
      }
      for (int j = 0; j <targetPageNum ; j++) {
        integerList.add(fun(i));
        i--;
      }
    }
    return integerList;
  }


  public int fun(int x){
    return 2^x;
  }

  public void clear() throws IOException {
    logger.error("Before clear, seqFile size ={}.", seqFiles.size());
    for (TsFileSequenceReader sequenceReader : fileReaderCache.values()) {
      sequenceReader.close();
    }

    fileReaderCache.clear();
    fileWriterCache.clear();
    modificationCache.clear();
    measurementSchemaMap.clear();
    chunkWriterCache.clear();

    seqFiles.stream().forEach(f->logger.info("file {} is waiting for compress", f.getFile().getName()));
    if(seqFiles.size()==0){
      logger.error("After clear, seqFile size =0.");
    }
  }

  public MeasurementSchema getSchema(String measurementId) {
    return measurementSchemaMap.get(measurementId);
  }

  /**
   * Construct a new or get an existing RestorableTsFileIOWriter of a merge temp file for a
   * SeqFile. The path of the merge temp file will be the seqFile's + ".merge".
   * @param resource
   * @return A RestorableTsFileIOWriter of a merge temp file for a SeqFile.
   * @throws IOException
   */
  public RestorableTsFileIOWriter getMergeFileWriter(TsFileResource resource) throws IOException {
    RestorableTsFileIOWriter writer = fileWriterCache.get(resource);
    if (writer == null) {
      writer = new RestorableTsFileIOWriter(FSFactoryProducer.getFSFactory()
          .getFile(resource.getFile().getPath() + COMPRESS_SUFFIX));
      fileWriterCache.put(resource, writer);
      logger.info("create compress file:{}", resource.getFile().getPath() + COMPRESS_SUFFIX);
    }
    return writer;
  }

  /**
   * Query ChunkMetadata of a timeseries from the given TsFile (seq or unseq). The ChunkMetadata
   * is not cached since it is usually huge.
   * @param path name of the time series
   * @param seqFile
   * @return
   * @throws IOException
   */
  public List<ChunkMetaData> queryChunkMetadata(Path path, TsFileResource seqFile)
      throws IOException {
    TsFileSequenceReader sequenceReader = getFileReader(seqFile);
    return sequenceReader.getChunkMetadataList(path);
  }

  /**
   * Construct the a new or get an existing TsFileSequenceReader of a TsFile.
   * @param tsFileResource
   * @return a TsFileSequenceReader
   */
  public TsFileSequenceReader getFileReader(TsFileResource tsFileResource) throws IOException {
    TsFileSequenceReader reader = fileReaderCache.get(tsFileResource);
    if (reader == null) {
      reader = new TsFileSequenceReader(tsFileResource.getFile().getPath(), true, cacheDeviceMeta);
      fileReaderCache.put(tsFileResource, reader);
    }
    return reader;
  }

  /**
   * Construct UnseqResourceMergeReaders of for each timeseries over all seqFiles. The readers are
   * not cached since the method is only called once for each timeseries.
   * @param paths names of the timeseries
   * @return an array of UnseqResourceMergeReaders each corresponding to a timeseries in paths
   * @throws IOException
   */
  public IPointReader[] getUnseqReaders(List<Path> paths) throws IOException {
    List<Chunk>[] pathChunks = null;//MergeUtils.collectUnseqChunks(paths, unseqFiles);
    IPointReader[] ret = new IPointReader[paths.size()];
    for (int i = 0; i < paths.size(); i++) {
      TSDataType dataType = getSchema(paths.get(i).getMeasurement()).getType();
      ret[i] = new CachedUnseqResourceMergeReader(pathChunks[i], dataType);
    }
    return ret;
  }

  /**
   * Construct the a new or get an existing ChunkWriter of a measurement. Different timeseries of
   * the same measurement shares the same instance.
   */
  public ChunkWriterImpl getChunkWriter(MeasurementSchema measurementSchema) {
    return chunkWriterCache.computeIfAbsent(measurementSchema, ChunkWriterImpl::new);
  }

  /**
   * Get the modifications of a timeseries in the ModificationFile of a TsFile. Once the
   * modifications of the timeseries are found out, they will be removed from the list to boost
   * the next query, so two calls of the same file and timeseries are forbidden.
   * @param path name of the time series
   */
  public List<Modification> getModifications(TsFileResource tsFileResource, Path path) {
    // copy from TsFileResource so queries are not affected
    List<Modification> modifications = modificationCache.computeIfAbsent(tsFileResource,
        resource -> new LinkedList<>(resource.getModFile().getModifications()));
    List<Modification> pathModifications = new ArrayList<>();
    Iterator<Modification> modificationIterator = modifications.iterator();
    // each path is visited only once in a merge, so the modifications can be removed after visiting
    while (modificationIterator.hasNext()) {
      Modification modification = modificationIterator.next();
      if (modification.getPath().equals(path)) {
        pathModifications.add(modification);
        modificationIterator.remove();
      }
    }
    return pathModifications;
  }

  /**
   * Remove and close the writer of the merge temp file of a SeqFile. The merge temp file is also
   * deleted.
   * @param tsFileResource the SeqFile
   * @throws IOException
   */
  public void removeFileAndWriter(TsFileResource tsFileResource) throws IOException {
    RestorableTsFileIOWriter newFileWriter = fileWriterCache.remove(tsFileResource);
    if (newFileWriter != null) {
      newFileWriter.close();
      newFileWriter.getFile().delete();
    }
  }

  /**
   * Remove and close the reader of the TsFile. The TsFile is NOT deleted.
   * @param resource the SeqFile
   * @throws IOException
   */
  public void removeFileReader(TsFileResource resource) throws IOException {
    TsFileSequenceReader sequenceReader = fileReaderCache.remove(resource);
    if (sequenceReader != null) {
      sequenceReader.close();
    }
  }

  public List<TsFileResource> getSeqFiles() {
    return seqFiles;
  }

  public void setSeqFiles(List<TsFileResource> seqFiles) {
    this.seqFiles = seqFiles;
  }

  public void removeOutdatedSeqReaders() throws IOException {
    Iterator<Entry<TsFileResource, TsFileSequenceReader>> entryIterator =
        fileReaderCache.entrySet().iterator();
    while (entryIterator.hasNext()) {
      Entry<TsFileResource, TsFileSequenceReader> entry = entryIterator.next();
      TsFileResource tsFile = entry.getKey();
      if (!seqFiles.contains(tsFile)) {
        TsFileSequenceReader reader = entry.getValue();
        reader.close();
        entryIterator.remove();
      }
    }
  }

  public void setCacheDeviceMeta(boolean cacheDeviceMeta) {
    this.cacheDeviceMeta = cacheDeviceMeta;
  }

  public void addMeasurements(List<MeasurementSchema> measurementSchemas) {
    for (MeasurementSchema measurementSchema : measurementSchemas) {
      measurementSchemaMap.put(measurementSchema.getMeasurementId(), measurementSchema);
    }
  }


  public static void main(String[] args) throws IOException {
    List<TsFileResource> seq = new ArrayList<>();
    TsFileResource resource = new TsFileResource(new File("/Users/suyue/lab/code/1.txt"));
    resource.updateEndTime("d2",345);
    resource.close();
    seq.add(resource);
    seq.add(new TsFileResource(new File("/Users/suyue/lab/code/2.txt")));

    CompressResource compressResource = new CompressResource(seq, 300);
    compressResource.clear();
  }
}
