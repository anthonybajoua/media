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
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Compares data loss between {@link Mp4Muxer} and {@link HybridMp4Muxer} when abandoned without
 * calling {@code close()}.
 *
 * <p>Writes identical recordings with both muxers, crashes each, and measures how much recording
 * time is recoverable. Mp4Muxer's data loss grows with file size (moov rewrite gap grows).
 * HybridMp4Muxer's data loss stays constant (~1 fragment duration).
 */
@RunWith(RobolectricTestRunner.class)
public class Mp4MuxerDataLossTest {
  private static final File OUTPUT_DIR =
      new File(System.getProperty("user.home"), "media/test-output/data-loss");

  private static final long VIDEO_FRAME_DURATION_US = 33_333; // 30fps
  private static final long AUDIO_FRAME_DURATION_US = 23_220; // ~43fps AAC
  private static final int RESERVED_MOOV_SIZE_BYTES = 10_000;

  @Before
  public void setUp() {
    OUTPUT_DIR.mkdirs();
  }

  @Test
  public void dataLossComparison_mp4VsHybrid() throws Exception {
    long[] recordingDurationsUs = {
      30 * 1_000_000L,   // 30s
      60 * 1_000_000L,   // 1 min
      120 * 1_000_000L,  // 2 min
      300 * 1_000_000L,  // 5 min
      600 * 1_000_000L,  // 10 min
    };

    StringBuilder report = new StringBuilder();
    report.append("\nData Loss Comparison: Mp4Muxer vs HybridMp4Muxer (crash without close)\n");
    report.append(
        String.format(
            "%-10s %-12s %-12s %-15s %-15s %-15s %-15s%n",
            "Duration",
            "Mp4 Size",
            "Hybrid Size",
            "Mp4 Recovered",
            "Mp4 Loss",
            "Hybrid Recov.",
            "Hybrid Loss"));
    report.append("-".repeat(104)).append("\n");

    long lastMp4LossUs = 0;
    long lastHybridLossUs = 0;

    for (long durationUs : recordingDurationsUs) {
      // --- Mp4Muxer ---
      String mp4Path = new File(OUTPUT_DIR, "mp4_" + durationUs + ".mp4").getPath();
      SeekableMuxerOutput mp4Output = SeekableMuxerOutput.of(new FileOutputStream(mp4Path));
      long lastWrittenUs = writeSamples(
          new Mp4Muxer.Builder(mp4Output)
              .experimentalSetFreeSpaceAfterFileTypeBox(RESERVED_MOOV_SIZE_BYTES)
              .build(),
          durationUs);
      long mp4FileSize = mp4Output.getSize();
      mp4Output.close();

      long mp4RecoveredUs = recoverWithMp4Extractor(mp4Path);
      long mp4LossUs = lastWrittenUs - mp4RecoveredUs;

      // --- HybridMp4Muxer ---
      String hybridPath = new File(OUTPUT_DIR, "hybrid_" + durationUs + ".mp4").getPath();
      SeekableMuxerOutput hybridOutput =
          SeekableMuxerOutput.of(new FileOutputStream(hybridPath));
      long hybridLastWrittenUs = writeSamples(
          new HybridMp4Muxer.Builder(hybridOutput).build(),
          durationUs);
      long hybridFileSize = hybridOutput.getSize();
      hybridOutput.close();

      long hybridRecoveredUs = recoverWithFragmentedMp4Extractor(hybridPath);
      long hybridLossUs = hybridLastWrittenUs - hybridRecoveredUs;

      lastMp4LossUs = mp4LossUs;
      lastHybridLossUs = hybridLossUs;

      report.append(
          String.format(
              "%-10s %-12s %-12s %-15s %-15s %-15s %-15s%n",
              String.format("%.0fs", durationUs / 1e6),
              String.format("%.1f KB", mp4FileSize / 1024.0),
              String.format("%.1f KB", hybridFileSize / 1024.0),
              String.format("%.1fs", mp4RecoveredUs / 1e6),
              String.format("%.1fs", mp4LossUs / 1e6),
              String.format("%.1fs", hybridRecoveredUs / 1e6),
              String.format("%.1fs", hybridLossUs / 1e6)));
      report.append(String.format("  Mp4:    %s%n", mp4Path));
      report.append(String.format("  Hybrid: %s%n", hybridPath));
    }

    System.out.println(report);

    // At 10 minutes, Mp4Muxer should lose more data than HybridMp4Muxer.
    assertWithMessage(
            "At 10 min recording, Mp4Muxer data loss should exceed HybridMp4Muxer data loss.\n"
                + report)
        .that(lastMp4LossUs)
        .isGreaterThan(lastHybridLossUs);
  }

  /** Writes interleaved video+audio samples up to the given duration. Returns last written PTS. */
  private long writeSamples(Muxer muxer, long durationUs) throws MuxerException {
    muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));

    int videoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
    int audioTrackId = muxer.addTrack(FAKE_AUDIO_FORMAT);

    long videoTimestampUs = 0;
    long audioTimestampUs = 0;
    long lastWrittenVideoTimestampUs = 0;

    while (videoTimestampUs < durationUs || audioTimestampUs < durationUs) {
      if (videoTimestampUs <= audioTimestampUs) {
        Pair<ByteBuffer, BufferInfo> sample =
            getFakeSampleAndSampleInfo(videoTimestampUs, /* isVideo= */ true);
        muxer.writeSampleData(videoTrackId, sample.first, sample.second);
        lastWrittenVideoTimestampUs = videoTimestampUs;
        videoTimestampUs += VIDEO_FRAME_DURATION_US;
      } else {
        Pair<ByteBuffer, BufferInfo> sample =
            getFakeSampleAndSampleInfo(audioTimestampUs, /* isVideo= */ false);
        muxer.writeSampleData(audioTrackId, sample.first, sample.second);
        audioTimestampUs += AUDIO_FRAME_DURATION_US;
      }
    }

    // Do NOT call muxer.close() — simulating a crash.
    return lastWrittenVideoTimestampUs;
  }

  /** Recovers a crashed Mp4Muxer file and returns the last sample timestamp. */
  private long recoverWithMp4Extractor(String path) {
    try {
      FakeExtractorOutput output =
          TestUtil.extractAllSamplesFromFilePath(new Mp4Extractor(), path);
      return getLastTimestamp(output);
    } catch (Exception e) {
      return 0;
    }
  }

  /** Recovers a crashed HybridMp4Muxer file (fragmented during recording) and returns the last
   *  sample timestamp. */
  private long recoverWithFragmentedMp4Extractor(String path) {
    try {
      FakeExtractorOutput output =
          TestUtil.extractAllSamplesFromFilePath(new FragmentedMp4Extractor(), path);
      return getLastTimestamp(output);
    } catch (Exception e) {
      return 0;
    }
  }

  private static long getLastTimestamp(FakeExtractorOutput output) {
    long lastTimestampUs = 0;
    for (int i = 0; i < output.trackOutputs.size(); i++) {
      FakeTrackOutput trackOutput = output.trackOutputs.valueAt(i);
      if (trackOutput.getSampleCount() > 0) {
        long ts = trackOutput.getSampleTimeUs(trackOutput.getSampleCount() - 1);
        lastTimestampUs = Math.max(lastTimestampUs, ts);
      }
    }
    return lastTimestampUs;
  }
}
