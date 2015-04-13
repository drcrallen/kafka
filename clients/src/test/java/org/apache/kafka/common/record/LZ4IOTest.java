/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.record;

import junit.framework.Assert;
import org.apache.log4j.lf5.util.StreamUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 *
 */
@RunWith(Parameterized.class)
public class LZ4IOTest
{
  @Parameterized.Parameters
  public static Iterable<Object[]> parameters(){
    final List<Object[]> retval = new LinkedList<>(Arrays.asList(new Object[]{0}, new Object[]{1}, new Object[]{1<<10}, new Object[]{(1<<10) + 1}, new Object[]{1<<16}, new Object[]{1<<17}, new Object[]{1<<20}));
    final Random rnd = new Random(78370789134L); // Chosen by lightly  smashing my keyboard a few times.
    for(int i = 0; i < 10; ++i){
      retval.add(new Object[]{Math.abs(rnd.nextInt()) % (1<<22)});
    }
    return retval;
  }
  private final int testSize;
  public LZ4IOTest(int testSize){
    this.testSize = testSize;
  }
  File tmpFile = null;

  @Before
  public void setUp() throws IOException
  {
    final int fill = 0xDEADBEEF;
    tmpFile = Files.createTempFile("lz4ioTest", ".dat").toFile();
    final Random rnd = new Random(5378L);
    int sizeRemaining = testSize;
    try (OutputStream os = Files.newOutputStream(tmpFile.toPath())) {
      while (sizeRemaining > 0) {
        final byte[] buff = new byte[Math.min(sizeRemaining, 1 << 10)];
        final IntBuffer intBuffer = ByteBuffer.wrap(buff).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        rnd.nextBytes(buff);
        while(intBuffer.hasRemaining()){
          intBuffer.put(fill);
        }
        os.write(buff);
        sizeRemaining -= buff.length;
      }
    }
    Assert.assertEquals(testSize, tmpFile.length());
  }

  @After
  public void tearDown(){
    if(tmpFile != null && tmpFile.exists() && !tmpFile.delete()){
      Assert.fail(String.format("Could not delete file [%s]", tmpFile.getAbsolutePath()));
    }
  }

  private void fillBuffer(final byte[] buffer, final InputStream is) throws IOException
  {
    int offset = 0;
    while(offset < buffer.length){
      final int myLength = is.read(buffer, offset, buffer.length - offset);
      if(myLength < 0){
        throw new EOFException("End of stream");
      }
      offset += myLength;
    }
  }

  private void validateStreamEquals(InputStream is, File file) throws IOException
  {
    int size = (int) file.length();
    try(InputStream fis = new FileInputStream(file)) {
      while (size > 0) {
        final byte[] buffer0 = new byte[Math.min(size, 1 << 10)];
        final byte[] buffer1 = new byte[Math.min(size, 1 << 10)];
        fillBuffer(buffer1, fis);
        fillBuffer(buffer0, is);
        for(int i = 0; i < buffer0.length; ++i){
          Assert.assertEquals(buffer0[i], buffer1[i]);
        }
        size -= buffer0.length;
      }
    }
  }

  @Test
  public void testValidator() throws IOException
  {
    try (InputStream is = new FileInputStream(tmpFile)) {
     validateStreamEquals(is, tmpFile);
    }
  }

  @Test
  public void testOutputSimple() throws IOException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    try {
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File))) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      final FileChannel channel = FileChannel.open(lz4File.toPath());
      final ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
      channel.read(buffer);
      buffer.rewind();
      Assert.assertEquals(KafkaLZ4BlockOutputStream.MAGIC, buffer.getInt());
      final BitSet b = BitSet.valueOf(new byte[]{buffer.get()});
      Assert.assertFalse(b.get(0));
      Assert.assertFalse(b.get(1));
      Assert.assertTrue(b.get(2));
      Assert.assertFalse(b.get(3));
      Assert.assertFalse(b.get(4));
      Assert.assertTrue(b.get(5));
      KafkaLZ4BlockOutputStream.BD bd = KafkaLZ4BlockOutputStream.BD.fromByte(buffer.get());
      Assert.assertEquals(KafkaLZ4BlockOutputStream.BLOCKSIZE.SIZE_4MB.getIndicator() << 4, bd.toByte());
    }
    finally {
      lz4File.delete();
    }
  }

  @Test
  public void testInputOutputSimple() throws IOException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    try {
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File))) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      try(InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))){
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
    }
  }


  @Test
  public void testInputOutputSkipped() throws IOException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    try {
      try(FileOutputStream fos = new FileOutputStream(lz4File)) {
        final int skipSize = 1<<10;
        final ByteBuffer skipBuffer = ByteBuffer.allocate(skipSize + 8).order(ByteOrder.LITTLE_ENDIAN);
        skipBuffer.putInt(KafkaLZ4BlockInputStream.MAGIC_SKIPPABLE_BASE | 0x00000007); // anything 00 through FF should work
        skipBuffer.putInt(skipSize);
        final byte[] skipRandom = new byte[skipSize];
        new Random(478278L).nextBytes(skipRandom);
        skipBuffer.put(skipRandom);
        fos.write(skipBuffer.array());
        try (OutputStream os = new KafkaLZ4BlockOutputStream(fos)) {
          try (InputStream is = new FileInputStream(tmpFile)) {
            StreamUtils.copy(is, os);
          }
        }
      }
      try(InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))){
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
    }
  }


  @Test
  public void testStreamWithContentSize() throws IOException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    try {
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File),
                                                           KafkaLZ4BlockOutputStream.BLOCKSIZE.SIZE_4MB,
                                                           tmpFile.length(),
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.CONTENT_CHECKSUM,
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.CONTENT_SIZE)) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      try(InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))){
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
    }
  }


  @Test
  public void testInputOutputMultipleFiles() throws IOException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    try {
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File))) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      final long oneLength = lz4File.length();
      try(OutputStream os = new FileOutputStream(lz4File, true)){
        for(int i = 0; i < 3; ++i) {
          try(InputStream is = new FileInputStream(lz4File)) {
            long size = oneLength;
            while(size > 0) {
              final byte[] buff = new byte[Math.min((int) size, 1 << 10)];
              fillBuffer(buff, is);
              os.write(buff);
              size -= buff.length;
            }
          }
        }
      }
      try(InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))){
        validateStreamEquals(is, tmpFile);
        validateStreamEquals(is, tmpFile);
        validateStreamEquals(is, tmpFile);
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
    }
  }

  @Test
  public void testNativeCompressIfAvailable() throws IOException, InterruptedException
  {
    Assume.assumeTrue(hasNativeLz4CLI());
    nativeCompress();
    nativeCompress("-Sx");
  }

  private void nativeCompress(String... args) throws IOException, InterruptedException
  {
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    lz4File.delete();
    try {
      final ProcessBuilder builder = new ProcessBuilder();
      final ArrayList<String> cmd = new ArrayList<>();
      cmd.add("lz4");
      if(args != null) {
        cmd.addAll(Arrays.asList(args));
      }
      cmd.add(tmpFile.getAbsolutePath());
      cmd.add(lz4File.getAbsolutePath());
      builder.command(cmd.toArray(new String[cmd.size()]));
      builder.inheritIO();
      Process process = builder.start();
      int retval = process.waitFor();
      Assert.assertEquals(0, retval);
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File))) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      try(InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))){
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
    }
  }


  private static boolean hasNativeLz4CLI() throws IOException, InterruptedException
  {
    ProcessBuilder checkBuilder = new ProcessBuilder().command("lz4", "-V").inheritIO();
    Process checkProcess = checkBuilder.start();
    return checkProcess.waitFor() == 0;
  }
  @Test
  public void testNativeDecompresIfAvailable() throws IOException, InterruptedException
  {
    Assume.assumeTrue(hasNativeLz4CLI());
    final File lz4File = Files.createTempFile("lz4test", ".lz4").toFile();
    final File unCompressedFile = Files.createTempFile("lz4raw", ".dat").toFile();
    unCompressedFile.delete();
    lz4File.delete();
    try {
      try (OutputStream os = new KafkaLZ4BlockOutputStream(new FileOutputStream(lz4File),
                                                           KafkaLZ4BlockOutputStream.BLOCKSIZE.SIZE_4MB,
                                                           tmpFile.length(),
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.BLOCK_INDEPENDENCE,
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.CONTENT_SIZE,
                                                           KafkaLZ4BlockOutputStream.FLG.Bits.CONTENT_CHECKSUM)) {
        try (InputStream is = new FileInputStream(tmpFile)) {
          StreamUtils.copy(is, os);
        }
      }
      try (InputStream is = new KafkaLZ4BlockInputStream(new FileInputStream(lz4File))) {
        validateStreamEquals(is, tmpFile);
      }

      final ProcessBuilder builder = new ProcessBuilder();
      builder.command("/Users/charlesallen/src/lz4/programs/lz4", "-d", "-vvvvvvv", lz4File.getAbsolutePath(), unCompressedFile.getAbsolutePath()).inheritIO();
      Process process = builder.start();
      int retval = process.waitFor();
      Assert.assertEquals(0, retval);
      try (InputStream is = new FileInputStream(unCompressedFile)) {
        validateStreamEquals(is, tmpFile);
      }
    }
    finally {
      lz4File.delete();
      unCompressedFile.delete();
    }
  }
}
