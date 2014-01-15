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

package org.apache.hadoop.hdfs.server.namenode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.LayoutVersion;
import org.apache.hadoop.hdfs.protocol.LayoutVersion.Feature;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FileSummary;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.NameSystemSection;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.compress.CompressionCodec;

import com.google.common.io.LimitInputStream;
import com.google.protobuf.CodedOutputStream;

/**
 * Utility class to read / write fsimage in protobuf format.
 */
final class FSImageFormatProtobuf {
  private static final Log LOG = LogFactory.getLog(FSImageFormatProtobuf.class);

  static final byte[] MAGIC_HEADER = "HDFSIMG1".getBytes();
  private static final int FILE_VERSION = 1;

  static final class Loader implements FSImageFormat.AbstractLoader {
    private static final int MINIMUM_FILE_LENGTH = 8;
    private final Configuration conf;
    private final FSNamesystem fsn;

    /** The MD5 sum of the loaded file */
    private MD5Hash imgDigest;
    /** The transaction ID of the last edit represented by the loaded file */
    private long imgTxId;

    Loader(Configuration conf, FSNamesystem fsn) {
      this.conf = conf;
      this.fsn = fsn;
    }

    @Override
    public MD5Hash getLoadedImageMd5() {
      return imgDigest;
    }

    @Override
    public long getLoadedImageTxId() {
      return imgTxId;
    }

    void load(File file) throws IOException {
      RandomAccessFile raFile = new RandomAccessFile(file, "r");
      FileInputStream fin = new FileInputStream(file);
      try {
        loadInternal(raFile, fin);
      } finally {
        fin.close();
        raFile.close();
      }
    }

    private boolean checkFileFormat(RandomAccessFile file) throws IOException {
      if (file.length() < MINIMUM_FILE_LENGTH)
        return false;

      byte[] magic = new byte[MAGIC_HEADER.length];
      file.readFully(magic);
      if (!Arrays.equals(MAGIC_HEADER, magic))
        return false;

      return true;
    }

    private FileSummary loadSummary(RandomAccessFile file) throws IOException {
      final int FILE_LENGTH_FIELD_SIZE = 4;
      long fileLength = file.length();
      file.seek(fileLength - FILE_LENGTH_FIELD_SIZE);
      int summaryLength = file.readInt();
      file.seek(fileLength - FILE_LENGTH_FIELD_SIZE - summaryLength);

      byte[] summaryBytes = new byte[summaryLength];
      file.readFully(summaryBytes);

      FileSummary summary = FileSummary
          .parseDelimitedFrom(new ByteArrayInputStream(summaryBytes));
      if (summary.getOndiskVersion() != FILE_VERSION) {
        throw new IOException("Unsupported file version "
            + summary.getOndiskVersion());
      }

      if (!LayoutVersion.supports(Feature.PROTOBUF_FORMAT,
          summary.getLayoutVersion())) {
        throw new IOException("Unsupported layout version "
            + summary.getLayoutVersion());
      }
      return summary;
    }

    @SuppressWarnings("resource")
    private void loadInternal(RandomAccessFile raFile, FileInputStream fin)
        throws IOException {
      if (!checkFileFormat(raFile)) {
        throw new IOException("Unrecognized file format");
      }
      FileSummary summary = loadSummary(raFile);

      MessageDigest digester = MD5Hash.getDigester();
      FileChannel channel = fin.getChannel();

      FSImageFormatPBINode.Loader inodeLoader = new FSImageFormatPBINode.Loader(
          fsn);

      for (FileSummary.Section s : summary.getSectionsList()) {
        channel.position(s.getOffset());
        InputStream in = new DigestInputStream(new BufferedInputStream(
            new LimitInputStream(fin, s.getLength())), digester);

        if (summary.hasCodec()) {
          // read compression related info
          FSImageCompression compression = FSImageCompression
              .createCompression(conf, summary.getCodec());
          CompressionCodec imageCodec = compression.getImageCodec();
          if (summary.getCodec() != null) {
            in = imageCodec.createInputStream(in);
          }
        }

        String n = s.getName();
        switch (SectionName.fromString(n)) {
        case NS_INFO:
          loadNameSystemSection(in, s);
          break;
        case INODE:
          inodeLoader.loadINodeSection(in);
          break;
        case INODE_DIR:
          inodeLoader.loadINodeDirectorySection(in);
          break;
        case FILES_UNDERCONSTRUCTION:
          inodeLoader.loadFilesUnderConstructionSection(in);
          break;
        default:
          LOG.warn("Unregconized section " + n);
          break;
        }
      }

      updateDigestForFileSummary(summary, digester);

      imgDigest = new MD5Hash(digester.digest());
    }

    private void loadNameSystemSection(InputStream in, FileSummary.Section sections)
        throws IOException {
      NameSystemSection s = NameSystemSection.parseDelimitedFrom(in);
      fsn.setGenerationStampV1(s.getGenstampV1());
      fsn.setGenerationStampV2(s.getGenstampV2());
      fsn.setGenerationStampV1Limit(s.getGenstampV1Limit());
      fsn.setLastAllocatedBlockId(s.getLastAllocatedBlockId());
      imgTxId = s.getTransactionId();
    }
  }

  static final class Saver {
    final SaveNamespaceContext context;
    private long currentOffset = MAGIC_HEADER.length;
    private MD5Hash savedDigest;

    private FileChannel fileChannel;
    // OutputStream for the section data
    private OutputStream sectionOutputStream;

    Saver(SaveNamespaceContext context) {
      this.context = context;
    }

    public MD5Hash getSavedDigest() {
      return savedDigest;
    }

    void commitSection(FileSummary.Builder summary, SectionName name)
        throws IOException {
      long oldOffset = currentOffset;
      sectionOutputStream.flush();
      long length = fileChannel.position() - oldOffset;
      summary.addSections(FileSummary.Section.newBuilder().setName(name.name)
          .setLength(length).setOffset(currentOffset));
      currentOffset += length;
    }

    void save(File file, FSImageCompression compression) throws IOException {
      FileOutputStream fout = new FileOutputStream(file);
      try {
        saveInternal(fout, compression);
      } finally {
        fout.close();
      }
    }

    private void saveFileSummary(FileOutputStream fout, FileSummary summary)
        throws IOException {
      summary.writeDelimitedTo(fout);
      int length = getOndiskTrunkSize(summary);
      byte[] lengthBytes = new byte[4];
      ByteBuffer.wrap(lengthBytes).asIntBuffer().put(length);
      fout.write(lengthBytes);
    }

    private void saveInodes(OutputStream out, FileSummary.Builder summary)
        throws IOException {
      FSImageFormatPBINode.Saver saver = new FSImageFormatPBINode.Saver(this,
          out, summary);
      saver.serializeINodeSection();
      saver.serializeINodeDirectorySection();
      saver.serializeFilesUCSection();
    }

    private void saveInternal(FileOutputStream fout,
        FSImageCompression compression) throws IOException {
      fout.write(MAGIC_HEADER);
      fileChannel = fout.getChannel();

      MessageDigest digester = MD5Hash.getDigester();
      OutputStream out = new DigestOutputStream(new BufferedOutputStream(fout),
          digester);

      FileSummary.Builder b = FileSummary.newBuilder()
          .setOndiskVersion(FILE_VERSION)
          .setLayoutVersion(LayoutVersion.getCurrentLayoutVersion());

      CompressionCodec codec = compression.getImageCodec();
      if (codec != null) {
        b.setCodec(codec.getClass().getCanonicalName());
        sectionOutputStream = codec.createOutputStream(out);
      } else {
        sectionOutputStream = out;
      }

      saveNameSystemSection(sectionOutputStream, b);
      saveInodes(sectionOutputStream, b);

      // Flush the buffered data into the file before appending the header
      out.flush();

      FileSummary summary = b.build();
      saveFileSummary(fout, summary);
      updateDigestForFileSummary(summary, digester);
      savedDigest = new MD5Hash(digester.digest());
    }

    private void saveNameSystemSection(OutputStream out,
        FileSummary.Builder summary) throws IOException {
      final FSNamesystem fsn = context.getSourceNamesystem();
      NameSystemSection.Builder b = NameSystemSection.newBuilder()
          .setGenstampV1(fsn.getGenerationStampV1())
          .setGenstampV1Limit(fsn.getGenerationStampV1Limit())
          .setGenstampV2(fsn.getGenerationStampV2())
          .setLastAllocatedBlockId(fsn.getLastAllocatedBlockId())
          .setTransactionId(context.getTxId());

      // We use the non-locked version of getNamespaceInfo here since
      // the coordinating thread of saveNamespace already has read-locked
      // the namespace for us. If we attempt to take another readlock
      // from the actual saver thread, there's a potential of a
      // fairness-related deadlock. See the comments on HDFS-2223.
      b.setNamespaceId(fsn.unprotectedGetNamespaceInfo().getNamespaceID());
      NameSystemSection s = b.build();
      s.writeDelimitedTo(out);

      commitSection(summary, SectionName.NS_INFO);
    }
  }

  /**
   * Supported section name
   */
  enum SectionName {
    INODE("INODE"), INODE_DIR("INODE_DIR"), NS_INFO("NS_INFO"),
    FILES_UNDERCONSTRUCTION("FILES_UNDERCONSTRUCTION");

    private static final SectionName[] values = SectionName.values();

    private static SectionName fromString(String name) {
      for (SectionName n : values) {
        if (n.name.equals(name))
          return n;
      }
      return null;
    }

    private final String name;

    private SectionName(String name) {
      this.name = name;
    }
  }

  private static int getOndiskTrunkSize(com.google.protobuf.GeneratedMessage s) {
    return CodedOutputStream.computeRawVarint32Size(s.getSerializedSize())
        + s.getSerializedSize();
  }

  /**
   * Include the FileSummary when calculating the digest. This is required as the
   * code does not access the FSImage strictly in sequential order.
   */
  private static void updateDigestForFileSummary(FileSummary summary,
      MessageDigest digester) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    o.write(MAGIC_HEADER);
    summary.writeDelimitedTo(o);
    digester.update(o.toByteArray());
  }

  private FSImageFormatProtobuf() {
  }

}
