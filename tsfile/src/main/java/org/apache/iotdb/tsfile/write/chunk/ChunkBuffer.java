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
package org.apache.iotdb.tsfile.write.chunk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import org.apache.iotdb.tsfile.compress.ICompressor;
import org.apache.iotdb.tsfile.exception.write.PageException;
import org.apache.iotdb.tsfile.file.header.PageHeader;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.utils.PublicBAOS;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.writer.TsFileIOWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Store current pages in this chunk.
 */
public class ChunkBuffer {

  private static final Logger LOG = LoggerFactory.getLogger(ChunkBuffer.class);
  private ICompressor compressor;
  private MeasurementSchema schema;

  private int numOfPages;

  /**
   * all pages of this column.
   */
  private PublicBAOS pageBuffer;

  private long totalValueCount;
  private long maxTimestamp;
  private long minTimestamp = Long.MIN_VALUE;
  private ByteBuffer compressedData;// DirectByteBuffer

  /**
   * constructor of ChunkBuffer.
   *
   * @param schema measurement schema
   */
  public ChunkBuffer(MeasurementSchema schema) {
    this.schema = schema;
    this.compressor = ICompressor.getCompressor(schema.getCompressor());
    this.pageBuffer = new PublicBAOS();
  }

  public int getNumOfPages() {
    return numOfPages;
  }

  /**
   * write the page header and data into the PageWriter's output stream.
   *
   * @param data the data of the page
   * @param valueCount - the amount of values in that page
   * @param statistics - page statistics
   * @param maxTimestamp - timestamp maximum in given data
   * @param minTimestamp - timestamp minimum in given data
   * @return byte size of the page header and uncompressed data in the page body.
   */
  public int writePageHeaderAndDataIntoBuff(ByteBuffer data, int valueCount,
      Statistics<?> statistics, long maxTimestamp, long minTimestamp, int compressRatio,
      long timeIntervalSum, long timeIntervalSquareSum) throws PageException {
    numOfPages++;

    // 1. update time statistics
    if (this.minTimestamp == Long.MIN_VALUE) {
      this.minTimestamp = minTimestamp;
    }
    if (this.minTimestamp == Long.MIN_VALUE) {
      throw new PageException("No valid data point in this page");
    }
    this.maxTimestamp = maxTimestamp;
    int uncompressedSize = data.remaining();
    int compressedSize;
    int compressedPosition = 0;
    byte[] compressedBytes = null;

    if (compressor.getType().equals(CompressionType.UNCOMPRESSED)) {
      compressedSize = data.remaining();
    } else {
      compressedBytes = new byte[compressor.getMaxBytesForCompression(uncompressedSize)];
      try {
        compressedPosition = 0;
        // data is never a directByteBuffer now, so we can use data.array()
        compressedSize = compressor
            .compress(data.array(), data.position(), data.remaining(), compressedBytes);
      } catch (IOException e) {
        throw new PageException(e);
      }
    }

    int headerSize;

    // write the page header to IOWriter
    try {
      PageHeader header = new PageHeader(uncompressedSize, compressedSize, valueCount, statistics,
          maxTimestamp, minTimestamp, compressRatio, timeIntervalSum, timeIntervalSquareSum);
      headerSize = header.getSerializedSize();
      LOG.debug("start to flush a page header into buffer, buffer position {} ", pageBuffer.size());
      header.serializeTo(pageBuffer);
      LOG.debug("finish to flush a page header {} of {} into buffer, buffer position {} ", header,
          schema.getMeasurementId(), pageBuffer.size());

    } catch (IOException e) {
      resetTimeStamp();
      throw new PageException(
          "IO Exception in writeDataPageHeader,ignore this page", e);
    }

    // update data point num
    this.totalValueCount += valueCount;

    // write page content to temp PBAOS
    try (WritableByteChannel channel = Channels.newChannel(pageBuffer)) {
      LOG.debug("start to flush a page data into buffer, buffer position {} ", pageBuffer.size());
      if (compressor.getType().equals(CompressionType.UNCOMPRESSED)) {
        channel.write(data);
      } else {
        if (data.isDirect()) {
          channel.write(compressedData);
        } else {
          pageBuffer.write(compressedBytes, compressedPosition, compressedSize);
        }
      }
      LOG.debug("finish to flush a page data into buffer, buffer position {} ", pageBuffer.size());
    } catch (IOException e) {
      throw new PageException(e);
    }
    return headerSize + uncompressedSize;
  }

  /**
   * write the page header and data into the PageWriter's output stream.
   */
  public void writePageHeaderAndDataIntoBuff(ByteBuffer data, PageHeader header)
      throws PageException {
    numOfPages++;

    // 1. update time statistics
    if (this.minTimestamp == Long.MIN_VALUE) {
      this.minTimestamp = header.getMinTimestamp();
    }
    if (this.minTimestamp == Long.MIN_VALUE) {
      throw new PageException("No valid data point in this page");
    }
    this.maxTimestamp = header.getMaxTimestamp();

    // write the page header to pageBuffer
    try {
      LOG.debug("start to flush a page header into buffer, buffer position {} ", pageBuffer.size());
      header.serializeTo(pageBuffer);
      LOG.debug("finish to flush a page header {} of {} into buffer, buffer position {} ", header,
          schema.getMeasurementId(), pageBuffer.size());

    } catch (IOException e) {
      resetTimeStamp();
      throw new PageException(
          "IO Exception in writeDataPageHeader,ignore this page", e);
    }

    // update data point num
    this.totalValueCount += header.getNumOfValues();

    // write page content to temp PBAOS
    try (WritableByteChannel channel = Channels.newChannel(pageBuffer)) {
      channel.write(data);
    } catch (IOException e) {
      throw new PageException(e);
    }
  }

  private void resetTimeStamp() {
    if (totalValueCount == 0) {
      minTimestamp = Long.MIN_VALUE;
    }
  }

  /**
   * write the page to specified IOWriter.
   *
   * @param writer the specified IOWriter
   * @param statistics the statistic information provided by series writer
   * @return the data size of this chunk
   * @throws IOException exception in IO
   */
  public long writeAllPagesOfSeriesToTsFile(TsFileIOWriter writer, Statistics<?> statistics, int virtualPageNum)
      throws IOException {
    if (totalValueCount == 0) {
      return 0;
    }

    // start to write this column chunk
    int headerSize = writer.startFlushChunk(schema, compressor.getType(), schema.getType(),
        schema.getEncodingType(), statistics, maxTimestamp, minTimestamp, pageBuffer.size(),
        numOfPages);

    long dataOffset = writer.getPos();
    LOG.debug("start writing pages of {} into file, position {}", schema.getMeasurementId(),
        writer.getPos());

    // write all pages of this column
    writer.writeBytesToStream(pageBuffer);
    LOG.debug("finish writing pages of {} into file, position {}", schema.getMeasurementId(),
        writer.getPos());

    long dataSize = writer.getPos() - dataOffset;
    if (dataSize != pageBuffer.size()) {
      throw new IOException(
          "Bytes written is inconsistent with the size of data: " + dataSize + " !="
              + " " + pageBuffer.size());
    }

    LOG.debug("{} 写入actualPage={}, virtualPage={}",writer.getFile().getAbsolutePath() ,numOfPages, virtualPageNum);
    writer.endChunk(totalValueCount, numOfPages, virtualPageNum);

    return headerSize + dataSize;
  }

  /**
   * reset exist data in page for next stage.
   */
  public void reset() {
    minTimestamp = Long.MIN_VALUE;
    pageBuffer.reset();
    totalValueCount = 0;
    numOfPages = 0;
  }

  /**
   * reset exist data in page for next stage.
   */
  public void reInit(MeasurementSchema schema) {
    reset();
    this.schema = schema;
    this.compressor = ICompressor.getCompressor(schema.getCompressor());
    numOfPages = 0;
    maxTimestamp = 0;
  }

  /**
   * estimate max page memory size.
   *
   * @return the max possible allocated size currently
   */
  public long estimateMaxPageMemSize() {
    // return the sum of size of buffer and page max size
    return (long) (pageBuffer.size() + estimateMaxPageHeaderSize());
  }

  private int estimateMaxPageHeaderSize() {
    return PageHeader.calculatePageHeaderSize(schema.getType());
  }

  /**
   * get current data size.
   *
   * @return current data size that the writer has serialized.
   */
  public long getCurrentDataSize() {
    return pageBuffer.size();
  }

  public long getTotalValueCount() {
    return totalValueCount;
  }

  public void setSchema(MeasurementSchema schema) {
    this.schema = schema;
  }

  public MeasurementSchema getSchema() {
    return schema;
  }
}
