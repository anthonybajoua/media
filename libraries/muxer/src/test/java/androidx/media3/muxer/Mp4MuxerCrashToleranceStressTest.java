/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.muxer;

import static androidx.media3.muxer.MuxerTestUtil.FAKE_AUDIO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.truth.Truth.assertWithMessage;

import android.util.Pair;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Stress test for {@link Mp4Muxer} crash tolerance.
 *
 * <p>Uses a {@link CrashAfterNWritesOutput} that lets the first N write calls complete
 * successfully, then throws on write N+1. This simulates a process kill landing between two
 * sequential writes inside {@link Mp4Writer} — for example between writing a new moov and updating
 * the mdat size in {@code safelyReplaceMoovAtEnd}.
 */
@RunWith(RobolectricTestRunner.class)
public class Mp4MuxerCrashToleranceStressTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final long VIDEO_FRAME_DURATION_US = 33_333; // 30fps
  private static final long AUDIO_FRAME_DURATION_US = 23_220; // ~43fps AAC
  private static final int RESERVED_MOOV_SIZE_BYTES = 10_000;
  private static final long RECORDING_DURATION_US = 45 * 1_000_000L;
  private static final int MAX_TRIALS = 1000;

  @Test
  public void mp4Muxer_crashAfterNthWrite_runsUntilFailure() throws Exception {
    // First, do a clean run to count total write calls.
    String referenceOutputPath = temporaryFolder.newFile("reference.mp4").getPath();
    CrashAfterNWritesOutput countingOutput =
        new CrashAfterNWritesOutput(
            SeekableMuxerOutput.of(new FileOutputStream(referenceOutputPath)),
            /* crashAfterWrite= */ Integer.MAX_VALUE);
    writeFullRecording(countingOutput);
    countingOutput.closeUnderlying();
    int totalWriteCalls = countingOutput.getWriteCount();

    // Skip early writes (ftyp, reserved space, initial mdat header) — start crashing after
    // at least 50% of writes to focus on the post-overflow region.
    int minCrashWrite = totalWriteCalls / 2;

    int successes = 0;
    String failureMessage = null;

    for (int trial = 0; trial < MAX_TRIALS; trial++) {
      // Evenly space crash points across the second half of the write sequence.
      int crashAfterWrite =
          minCrashWrite + ((totalWriteCalls - minCrashWrite) * trial) / MAX_TRIALS;

      String outputPath =
          temporaryFolder.newFile("crash_test_" + trial + ".mp4").getPath();

      CrashAfterNWritesOutput crashingOutput =
          new CrashAfterNWritesOutput(
              SeekableMuxerOutput.of(new FileOutputStream(outputPath)), crashAfterWrite);

      try {
        writeFullRecording(crashingOutput);
      } catch (MuxerException e) {
        // Expected — the output threw IOException after the Nth write.
      }

      try {
        crashingOutput.closeUnderlying();
      } catch (IOException e) {
        // Ignore.
      }

      // Verify the abandoned file is recoverable.
      try {
        FakeExtractorOutput extractorOutput =
            TestUtil.extractAllSamplesFromFilePath(new Mp4Extractor(), outputPath);
        if (extractorOutput.numberOfTracks > 0) {
          successes++;
        } else {
          failureMessage =
              String.format(
                  "Trial %d: crashed after write %d/%d - extracted 0 tracks",
                  trial, crashAfterWrite, totalWriteCalls);
          break;
        }
      } catch (Exception e) {
        failureMessage =
            String.format(
                "Trial %d: crashed after write %d/%d (%.1f%%) - %s: %s",
                trial,
                crashAfterWrite,
                totalWriteCalls,
                100.0 * crashAfterWrite / totalWriteCalls,
                e.getClass().getSimpleName(),
                e.getMessage());
        break;
      }
    }

    assertWithMessage(
            String.format(
                "Mp4Muxer crash tolerance: %d/%d succeeded before failure. "
                    + "Total write calls in clean run: %d.%s",
                successes,
                MAX_TRIALS,
                totalWriteCalls,
                failureMessage != null ? "\nFirst failure: " + failureMessage : ""))
        .that(successes)
        .isEqualTo(MAX_TRIALS);
  }

  private void writeFullRecording(CrashAfterNWritesOutput muxerOutput)
      throws MuxerException {
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(muxerOutput)
            .experimentalSetFreeSpaceAfterFileTypeBox(RESERVED_MOOV_SIZE_BYTES)
            .build();
    muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));

    int videoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
    int audioTrackId = muxer.addTrack(FAKE_AUDIO_FORMAT);

    long videoTimestampUs = 0;
    long audioTimestampUs = 0;

    while (videoTimestampUs < RECORDING_DURATION_US || audioTimestampUs < RECORDING_DURATION_US) {
      if (videoTimestampUs <= audioTimestampUs) {
        Pair<ByteBuffer, BufferInfo> sample =
            getFakeSampleAndSampleInfo(videoTimestampUs, /* isVideo= */ true);
        muxer.writeSampleData(videoTrackId, sample.first, sample.second);
        videoTimestampUs += VIDEO_FRAME_DURATION_US;
      } else {
        Pair<ByteBuffer, BufferInfo> sample =
            getFakeSampleAndSampleInfo(audioTimestampUs, /* isVideo= */ false);
        muxer.writeSampleData(audioTrackId, sample.first, sample.second);
        audioTimestampUs += AUDIO_FRAME_DURATION_US;
      }
    }

    muxer.close();
  }

  /**
   * A {@link SeekableMuxerOutput} that lets the first N {@code write()} calls succeed, then throws
   * {@link IOException} on subsequent calls. All completed writes are fully flushed to disk,
   * simulating a process kill between two write syscalls.
   */
  private static final class CrashAfterNWritesOutput implements SeekableMuxerOutput {
    private final SeekableMuxerOutput delegate;
    private final int crashAfterWrite;
    private int writeCount;
    private boolean crashed;

    CrashAfterNWritesOutput(SeekableMuxerOutput delegate, int crashAfterWrite) {
      this.delegate = delegate;
      this.crashAfterWrite = crashAfterWrite;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      if (crashed) {
        throw new IOException("Simulated crash: process killed");
      }
      writeCount++;
      if (writeCount > crashAfterWrite) {
        crashed = true;
        throw new IOException(
            String.format("Simulated crash after write %d", crashAfterWrite));
      }
      return delegate.write(src);
    }

    @Override
    public long getPosition() throws IOException {
      return delegate.getPosition();
    }

    @Override
    public void setPosition(long position) throws IOException {
      if (crashed) {
        throw new IOException("Simulated crash: process killed");
      }
      delegate.setPosition(position);
    }

    @Override
    public long getSize() throws IOException {
      return delegate.getSize();
    }

    @Override
    public void truncate(long size) throws IOException {
      if (crashed) {
        throw new IOException("Simulated crash: process killed");
      }
      delegate.truncate(size);
    }

    @Override
    public boolean isOpen() {
      return !crashed && delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
      crashed = true;
      delegate.close();
    }

    void closeUnderlying() throws IOException {
      delegate.close();
    }

    int getWriteCount() {
      return writeCount;
    }
  }
}
