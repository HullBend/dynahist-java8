/*
 * Copyright 2020-2021 Dynatrace LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dynatrace.dynahist.layout;

import static com.dynatrace.dynahist.serialization.SerializationUtil.checkSerialVersion;
import static com.dynatrace.dynahist.util.Preconditions.checkArgument;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A tentative histogram bin layout that implements the proposal as discussed in
 * https://github.com/open-telemetry/oteps/pull/149.
 *
 * <p>BETA: This class is still subject to incompatible changes, or even removal, in a future
 * release.
 *
 * <p>This class is immutable.
 */
public final class OpenTelemetryExponentialBucketsLayout extends AbstractLayout {

  private static final byte SERIAL_VERSION_V0 = 0;

  static final int MAX_PRECISION = 10;

  static long getBoundaryConstant(int idx) {
    return BOUNDARY_CONSTANTS[idx];
  }

  private static final AtomicReferenceArray<OpenTelemetryExponentialBucketsLayout> INSTANCES =
      new AtomicReferenceArray<>(MAX_PRECISION + 1);

  private final int precision;

  private final transient int underflowBinIndex;
  private final transient int overflowBinIndex;
  private final transient long[] boundaries;
  private final transient int[] indices;
  private final transient long firstNormalValueBits;
  private final transient int indexOffset;

  /**
   * Creates a histogram bin layout with exponential buckets with given precision.
   *
   * @param precision the precision
   * @return a new {@link OpenTelemetryExponentialBucketsLayout} instance
   */
  public static OpenTelemetryExponentialBucketsLayout create(int precision) {
    checkArgument(precision >= 0);
    checkArgument(precision <= MAX_PRECISION);

    return INSTANCES.updateAndGet(
        precision,
        x -> {
          if (x != null) {
            return x;
          } else {
            return new OpenTelemetryExponentialBucketsLayout(precision);
          }
        });
  }

  static long[] calculateBoundaries(int precision) {
    int len = 1 << precision;
    long[] boundaries = new long[len + 1];
    for (int i = 0; i < len - 1; ++i) {
      boundaries[i] = getBoundaryConstant((i + 1) << (MAX_PRECISION - precision));
    }
    boundaries[len - 1] = 0x0010000000000000L;
    boundaries[len] = 0x0010000000000000L;
    return boundaries;
  }

  private static int[] calculateIndices(long[] boundaries, int precision) {
    int len = 1 << precision;
    int[] indices = new int[len];
    int c = 0;
    for (int i = 0; i < len; ++i) {
      long mantissaLowerBound = ((long) i) << (52 - precision);
      while (boundaries[c] <= mantissaLowerBound) {
        c += 1;
      }
      indices[i] = c;
    }
    return indices;
  }

  OpenTelemetryExponentialBucketsLayout(int precision) {
    this.precision = precision;
    this.boundaries = calculateBoundaries(precision);
    this.indices = calculateIndices(boundaries, precision);

    int valueBits = 0;
    int index = Integer.MIN_VALUE;
    while (true) {
      int nextValueBits = valueBits + 1;
      int nextIndex = mapToBinIndexHelper(nextValueBits, indices, boundaries, precision, 0L, 0);
      if (index == nextIndex) {
        break;
      }
      valueBits = nextValueBits;
      index = nextIndex;
    }
    this.firstNormalValueBits = valueBits;
    this.indexOffset = valueBits - index;
    this.overflowBinIndex = mapToBinIndex(Double.MAX_VALUE) + 1;
    this.underflowBinIndex = -overflowBinIndex;
  }

  private static int mapToBinIndexHelper(
      long valueBits,
      int[] indices,
      long[] boundaries,
      int precision,
      long firstNormalValueBits,
      int indexOffset) {
    long mantissa = 0xfffffffffffffL & valueBits;
    int exponent = (int) ((0x7ff0000000000000L & valueBits) >> 52);
    if (exponent == 0) {
      if (mantissa < firstNormalValueBits) return (int) mantissa;
      int nlz = Long.numberOfLeadingZeros(mantissa) - 12;
      exponent -= nlz;
      mantissa <<= (nlz + 1);
      mantissa &= 0x000fffffffffffffL;
    }
    int i = indices[(int) (mantissa >>> (52 - precision))];
    int k = i + ((mantissa >= boundaries[i]) ? 1 : 0) + ((mantissa >= boundaries[i + 1]) ? 1 : 0);
    return (exponent << precision) + k + indexOffset;
  }

  @Override
  public int mapToBinIndex(double value) {
    long valueBits = Double.doubleToRawLongBits(value);
    int index =
        mapToBinIndexHelper(
            valueBits, indices, boundaries, precision, firstNormalValueBits, indexOffset);
    return (valueBits >= 0) ? index : -index;
  }

  @Override
  public int getUnderflowBinIndex() {
    return underflowBinIndex;
  }

  @Override
  public int getOverflowBinIndex() {
    return overflowBinIndex;
  }

  private double getBinLowerBoundApproximationHelper(int absBinIndex) {
    if (absBinIndex < firstNormalValueBits) {
      return Double.longBitsToDouble((long) absBinIndex);
    } else {
      int k = (absBinIndex - indexOffset) & (~(0xFFFFFFFF << precision));
      int exponent = (absBinIndex - indexOffset) >> precision;
      long mantissa = (k > 0) ? boundaries[k - 1] : 0;
      if (exponent <= 0) {
        int shift = 1 - exponent;
        mantissa += (~(0xffffffffffffffffL << shift));
        mantissa |= 0x0010000000000000L;
        mantissa >>>= shift;
        exponent = 0;
      }
      return Double.longBitsToDouble(mantissa | (((long) exponent) << 52));
    }
  }

  @Override
  protected double getBinLowerBoundApproximation(int binIndex) {
    if (binIndex == 0) {
      return -0.;
    } else if (binIndex > 0) {
      return getBinLowerBoundApproximationHelper(binIndex);
    }
    {
      return Math.nextUp(-getBinLowerBoundApproximationHelper(-binIndex + 1));
    }
  }

  @Override
  public String toString() {
    return "OpenTelemetryExponentialBucketsLayout [" + "precision=" + precision + ']';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    OpenTelemetryExponentialBucketsLayout that = (OpenTelemetryExponentialBucketsLayout) o;
    return precision == that.precision;
  }

  @Override
  public int hashCode() {
    return 31 * precision;
  }

  public void write(DataOutput dataOutput) throws IOException {
    dataOutput.writeByte(SERIAL_VERSION_V0);
    dataOutput.writeByte(precision);
  }

  public static OpenTelemetryExponentialBucketsLayout read(DataInput dataInput) throws IOException {
    checkSerialVersion(SERIAL_VERSION_V0, dataInput.readUnsignedByte());
    int tmpPrecision = dataInput.readUnsignedByte();
    return OpenTelemetryExponentialBucketsLayout.create(tmpPrecision);
  }

  private static final long[] BOUNDARY_CONSTANTS = {
    0x0000000000000L,
    0x002c605e2e8cfL,
    0x0058c86da1c0aL,
    0x0085382faef84L,
    0x00b1afa5abcbfL,
    0x00de2ed0ee0f5L,
    0x010ab5b2cbd12L,
    0x0137444c9b5b5L,
    0x0163da9fb3336L,
    0x019078ad6a19fL,
    0x01bd1e77170b5L,
    0x01e9cbfe113efL,
    0x02168143b0281L,
    0x02433e494b755L,
    0x027003103b10eL,
    0x029ccf99d720bL,
    0x02c9a3e778061L,
    0x02f67ffa765e6L,
    0x032363d42b028L,
    0x03504f75ef072L,
    0x037d42e11bbcdL,
    0x03aa3e170aafeL,
    0x03d7411915a8bL,
    0x04044be896ab7L,
    0x04315e86e7f85L,
    0x045e78f5640baL,
    0x048b9b35659d9L,
    0x04b8c54847a28L,
    0x04e5f72f654b2L,
    0x051330ec1a040L,
    0x0540727fc1762L,
    0x056dbbebb786cL,
    0x059b0d3158575L,
    0x05c866520045bL,
    0x05f5c74f0bec3L,
    0x06233029d8217L,
    0x0650a0e3c1f89L,
    0x067e197e26c15L,
    0x06ab99fa6407cL,
    0x06d92259d794dL,
    0x0706b29ddf6deL,
    0x07344ac7d9d51L,
    0x0761ead925493L,
    0x078f92d32085eL,
    0x07bd42b72a837L,
    0x07eafa86a2772L,
    0x0818ba42e7d30L,
    0x084681ed5a462L,
    0x0874518759bc9L,
    0x08a22912465f2L,
    0x08d0088f80940L,
    0x08fdf00068fe3L,
    0x092bdf66607e0L,
    0x0959d6c2c830eL,
    0x0987d61701716L,
    0x09b5dd646dd77L,
    0x09e3ecac6f384L,
    0x0a1203f067a64L,
    0x0a402331b9716L,
    0x0a6e4a71c726eL,
    0x0a9c79b1f391aL,
    0x0acab0f3a1b9cL,
    0x0af8f03834e53L,
    0x0b27378110974L,
    0x0b5586cf98910L,
    0x0b83de2530d12L,
    0x0bb23d833d93fL,
    0x0be0a4eb2353cL,
    0x0c0f145e46c86L,
    0x0c3d8bde0ce7aL,
    0x0c6c0b6bdae53L,
    0x0c9a93091632aL,
    0x0cc922b7247f8L,
    0x0cf7ba776bb95L,
    0x0d265a4b520baL,
    0x0d5502343de03L,
    0x0d83b23395decL,
    0x0db26a4ac0ed5L,
    0x0de12a7b26301L,
    0x0e0ff2c62d097L,
    0x0e3ec32d3d1a3L,
    0x0e6d9bb1be415L,
    0x0e9c7c55189c7L,
    0x0ecb6518b4875L,
    0x0efa55fdfa9c5L,
    0x0f294f0653b46L,
    0x0f58503328e6dL,
    0x0f875985e389cL,
    0x0fb66affed31bL,
    0x0fe584a2afb22L,
    0x1014a66f951cfL,
    0x1043d06807c30L,
    0x1073028d7233fL,
    0x10a23ce13f3e3L,
    0x10d17f64d9ef2L,
    0x1100ca19ad930L,
    0x11301d0125b51L,
    0x115f781cae1fbL,
    0x118edb6db2dc1L,
    0x11be46f5a032dL,
    0x11edbab5e2ab6L,
    0x121d36afe70caL,
    0x124cbae51a5c8L,
    0x127c4756e9e06L,
    0x12abdc06c31ccL,
    0x12db78f613d5cL,
    0x130b1e264a0e9L,
    0x133acb98d40a2L,
    0x136a814f204abL,
    0x139a3f4a9d923L,
    0x13ca058cbae1eL,
    0x13f9d416e77afL,
    0x1429aaea92de0L,
    0x14598a092ccb8L,
    0x1489717425438L,
    0x14b9612cec861L,
    0x14e95934f312eL,
    0x1519598da9a9aL,
    0x154962388149fL,
    0x15797336eb333L,
    0x15a98c8a58e52L,
    0x15d9ae343c1f3L,
    0x1609d83606e12L,
    0x163a0a912b6adL,
    0x166a45471c3c3L,
    0x169a88594c158L,
    0x16cad3c92df74L,
    0x16fb279835224L,
    0x172b83c7d517bL,
    0x175be85981993L,
    0x178c554eaea8aL,
    0x17bccaa8d0889L,
    0x17ed48695bbc1L,
    0x181dce91c506aL,
    0x184e5d23816c9L,
    0x187ef4200632bL,
    0x18af9388c8deaL,
    0x18e03b5f3f36bL,
    0x1910eba4df420L,
    0x1941a45b1f488L,
    0x1972658375d30L,
    0x19a32f1f59ab5L,
    0x19d4013041dc2L,
    0x1a04dbb7a5b13L,
    0x1a35beb6fcb76L,
    0x1a66aa2fbebc7L,
    0x1a979e2363cf9L,
    0x1ac89a936440dL,
    0x1af99f8138a1dL,
    0x1b2aacee59c53L,
    0x1b5bc2dc40bf1L,
    0x1b8ce14c66e4dL,
    0x1bbe084045cd4L,
    0x1bef37b95750cL,
    0x1c206fb915890L,
    0x1c51b040fad16L,
    0x1c82f95281c6cL,
    0x1cb44aef2547bL,
    0x1ce5a51860746L,
    0x1d1707cfaeaedL,
    0x1d4873168b9abL,
    0x1d79e6ee731d7L,
    0x1dab6358e15e8L,
    0x1ddce85752c72L,
    0x1e0e75eb44027L,
    0x1e400c1631fdcL,
    0x1e71aad999e83L,
    0x1ea35236f9331L,
    0x1ed5022fcd91dL,
    0x1f06bac594fa1L,
    0x1f387bf9cda39L,
    0x1f6a45cdf6086L,
    0x1f9c18438ce4dL,
    0x1fcdf35c1137aL,
    0x1fffd7190241fL,
    0x2031c37bdf873L,
    0x2063b88628cd7L,
    0x2095b6395e1d3L,
    0x20c7bc96ffc18L,
    0x20f9cba08e484L,
    0x212be3578a81aL,
    0x215e03bd7580dL,
    0x21902cd3d09b9L,
    0x21c25e9c1d6aaL,
    0x21f49917ddc97L,
    0x2226dc4893d64L,
    0x2259282fc1f28L,
    0x228b7cceeac25L,
    0x22bdda27912d2L,
    0x22f0403b385d3L,
    0x2322af0b63c00L,
    0x2355269997062L,
    0x2387a6e756239L,
    0x23ba2ff6254f4L,
    0x23ecc1c78903aL,
    0x241f5c5d05fe6L,
    0x2451ffb82140bL,
    0x2484abda600f0L,
    0x24b760c547f16L,
    0x24ea1e7a5eb35L,
    0x251ce4fb2a640L,
    0x254fb44931561L,
    0x25828c65fa200L,
    0x25b56d530b9bdL,
    0x25e85711ece76L,
    0x261b49a425645L,
    0x264e450b3cb82L,
    0x26814948bacc3L,
    0x26b4565e27cdeL,
    0x26e76c4d0c2e6L,
    0x271a8b16f0a30L,
    0x274db2bd5e254L,
    0x2780e341ddf2aL,
    0x27b41ca5f98ccL,
    0x27e75eeb3ab99L,
    0x281aaa132b833L,
    0x284dfe1f56381L,
    0x28815b11456b1L,
    0x28b4c0ea83f36L,
    0x28e82fac9cecaL,
    0x291ba7591bb70L,
    0x294f27f18bf73L,
    0x2982b17779966L,
    0x29b643ec70c28L,
    0x29e9df51fdee2L,
    0x2a1d83a9add08L,
    0x2a5130f50d65cL,
    0x2a84e735a9eecL,
    0x2ab8a66d10f13L,
    0x2aec6e9cd037cL,
    0x2b203fc675d20L,
    0x2b5419eb90148L,
    0x2b87fd0dad990L,
    0x2bbbe92e5d3e4L,
    0x2befde4f2e281L,
    0x2c23dc71afbf8L,
    0x2c57e39771b2fL,
    0x2c8bf3c203f60L,
    0x2cc00cf2f6c18L,
    0x2cf42f2bda93eL,
    0x2d285a6e4030cL,
    0x2d5c8ebbb8a16L,
    0x2d90cc15d5347L,
    0x2dc5127e277e3L,
    0x2df961f64158aL,
    0x2e2dba7fb4e33L,
    0x2e621c1c14834L,
    0x2e9686ccf2e3bL,
    0x2ecafa93e2f57L,
    0x2eff777277ef1L,
    0x2f33fd6a454d2L,
    0x2f688c7cded23L,
    0x2f9d24abd886bL,
    0x2fd1c5f8c6b93L,
    0x300670653dfe5L,
    0x303b23f2d330bL,
    0x306fe0a31b716L,
    0x30a4a677ac277L,
    0x30d975721b005L,
    0x310e4d93fdefcL,
    0x31432edeeb2feL,
    0x3178195479413L,
    0x31ad0cf63eeacL,
    0x31e209c5d33a0L,
    0x32170fc4cd832L,
    0x324c1ef4c560bL,
    0x3281375752b40L,
    0x32b658ee0da54L,
    0x32eb83ba8ea32L,
    0x3320b7be6e634L,
    0x3355f4fb45e21L,
    0x338b3b72ae62eL,
    0x33c08b2641700L,
    0x33f5e41798dabL,
    0x342b46484ebb4L,
    0x3460b1b9fd712L,
    0x3496266e3fa2dL,
    0x34cba466b03e1L,
    0x35012ba4ea77dL,
    0x3536bc2a89cc5L,
    0x356c55f929ff1L,
    0x35a1f912671b2L,
    0x35d7a577dd72cL,
    0x360d5b2b299fdL,
    0x36431a2de883bL,
    0x3678e281b7476L,
    0x36aeb428335b5L,
    0x36e48f22fa77cL,
    0x371a7373aa9cbL,
    0x3750611be211dL,
    0x3786581d3f669L,
    0x37bc587961727L,
    0x37f26231e754aL,
    0x3828754870747L,
    0x385e91be9c812L,
    0x3894b7960b71fL,
    0x38cae6d05d866L,
    0x39011f6f33460L,
    0x393761742d809L,
    0x396dace0ed4e1L,
    0x39a401b7140efL,
    0x39da5ff8436bdL,
    0x3a10c7a61d55cL,
    0x3a4738c244064L,
    0x3a7db34e59ff7L,
    0x3ab4374c020beL,
    0x3aeac4bcdf3eaL,
    0x3b215ba294f3aL,
    0x3b57fbfec6cf5L,
    0x3b8ea5d318befL,
    0x3bc559212ef89L,
    0x3bfc15eaadfb2L,
    0x3c32dc313a8e5L,
    0x3c69abf679c2eL,
    0x3ca0853c10f29L,
    0x3cd76803a5c01L,
    0x3d0e544ede174L,
    0x3d454a1f602d1L,
    0x3d7c4976d27faL,
    0x3db35256dbd68L,
    0x3dea64c123423L,
    0x3e2180b7501ccL,
    0x3e58a63b0a09bL,
    0x3e8fd54df8f5cL,
    0x3ec70df1c5175L,
    0x3efe502816ee4L,
    0x3f359bf29743fL,
    0x3f6cf152ef2b8L,
    0x3fa4504ac801cL,
    0x3fdbb8dbcb6d2L,
    0x40132b07a35dfL,
    0x404aa6cffa0e6L,
    0x40822c367a025L,
    0x40b9bb3cce07cL,
    0x40f153e4a136aL,
    0x4128f62f9ef0fL,
    0x4160a21f72e2aL,
    0x419857b5c9020L,
    0x41d016f44d8f5L,
    0x4207dfdcad154L,
    0x423fb2709468aL,
    0x42778eb1b0a8bL,
    0x42af74a1af3f2L,
    0x42e764423ddfdL,
    0x431f5d950a897L,
    0x4357609bc3851L,
    0x438f6d5817663L,
    0x43c783cbb50b5L,
    0x43ffa3f84b9d5L,
    0x4437cddf8a8feL,
    0x4470018321a1aL,
    0x44a83ee4c0dbeL,
    0x44e086061892eL,
    0x4518d6e8d965cL,
    0x4551318eb43ecL,
    0x458995f95a532L,
    0x45c2042a7d232L,
    0x45fa7c23ce7a5L,
    0x4632fde7006f4L,
    0x466b8975c563fL,
    0x46a41ed1d0058L,
    0x46dcbdfcd34c9L,
    0x471566f8827d0L,
    0x474e19c691266L,
    0x4786d668b3237L,
    0x47bf9ce09c9acL,
    0x47f86d3001fe6L,
    0x48314758980bfL,
    0x486a2b5c13cd1L,
    0x48a3193c2a96cL,
    0x48dc10fa920a2L,
    0x4915129900140L,
    0x494e1e192aed2L,
    0x4987337cc91a5L,
    0x49c052c5916c5L,
    0x49f97bf53affdL,
    0x4a32af0d7d3dfL,
    0x4a6bec100fdbbL,
    0x4aa532feaada6L,
    0x4ade83db0687bL,
    0x4b17dea6db7d7L,
    0x4b514363e2a21L,
    0x4b8ab213d5283L,
    0x4bc42ab86c8f1L,
    0x4bfdad5362a28L,
    0x4c3739e6717abL,
    0x4c70d073537cbL,
    0x4caa70fbc35a1L,
    0x4ce41b817c115L,
    0x4d1dd00638ed8L,
    0x4d578e8bb586cL,
    0x4d915713adc1fL,
    0x4dcb299fddd0eL,
    0x4e05063202328L,
    0x4e3eeccbd7b2bL,
    0x4e78dd6f1b6a7L,
    0x4eb2d81d8abffL,
    0x4eecdcd8e366aL,
    0x4f26eba2e35f1L,
    0x4f61047d48f74L,
    0x4f9b2769d2ca7L,
    0x4fd5546a3fc17L,
    0x500f8b804f127L,
    0x5049ccadc0413L,
    0x508417f4531efL,
    0x50be6d55c7caaL,
    0x50f8ccd3deb0dL,
    0x51333670588c0L,
    0x516daa2cf6642L,
    0x51a8280b798f5L,
    0x51e2b00da3b14L,
    0x521d423536bbeL,
    0x5257de83f4eefL,
    0x529284fba0d85L,
    0x52cd359dfd53dL,
    0x5307f06ccd8bbL,
    0x5342b569d4f82L,
    0x537d8496d75fdL,
    0x53b85df598d78L,
    0x53f34187ddc28L,
    0x542e2f4f6ad28L,
    0x5469274e05079L,
    0x54a4298571b06L,
    0x54df35f7766a4L,
    0x551a4ca5d920fL,
    0x55556d92600f2L,
    0x559098bed1be0L,
    0x55cbce2cf505bL,
    0x56070dde910d2L,
    0x564257d56d4a3L,
    0x567dac1351819L,
    0x56b90a9a05c72L,
    0x56f4736b527dbL,
    0x572fe68900573L,
    0x576b63f4d854dL,
    0x57a6ebb0a3c6eL,
    0x57e27dbe2c4cfL,
    0x581e1a1f3bd61L,
    0x5859c0d59ca08L,
    0x589571e3193a0L,
    0x58d12d497c7feL,
    0x590cf30a919edL,
    0x5948c32824135L,
    0x59849da3ffa96L,
    0x59c0827ff07ccL,
    0x59fc71bdc2f8fL,
    0x5a386b5f43d93L,
    0x5a746f664028bL,
    0x5ab07dd48542aL,
    0x5aec96abe0d20L,
    0x5b28b9ee20d1eL,
    0x5b64e79d138d8L,
    0x5ba11fba87a03L,
    0x5bdd62484bf57L,
    0x5c19af482fc8fL,
    0x5c5606bc02a6dL,
    0x5c9268a5946b8L,
    0x5cced506b543bL,
    0x5d0b4be135accL,
    0x5d47cd36e6747L,
    0x5d84590998b93L,
    0x5dc0ef5b1de9fL,
    0x5dfd902d47c65L,
    0x5e3a3b81e85edL,
    0x5e76f15ad2149L,
    0x5eb3b1b9d799aL,
    0x5ef07ca0cbf10L,
    0x5f2d5211826e8L,
    0x5f6a320dceb71L,
    0x5fa71c9784c0bL,
    0x5fe411b078d27L,
    0x6021115a7f849L,
    0x605e1b976dc09L,
    0x609b306918c14L,
    0x60d84fd15612bL,
    0x611579d1fb926L,
    0x6152ae6cdf6f5L,
    0x618feda3d829fL,
    0x61cd3778bc945L,
    0x620a8bed63d20L,
    0x6247eb03a5585L,
    0x628554bd58ee6L,
    0x62c2c91c56aceL,
    0x6300482276fe9L,
    0x633dd1d1929feL,
    0x637b662b829f6L,
    0x63b90532205d8L,
    0x63f6aee7458cdL,
    0x6434634ccc320L,
    0x647222648ea3eL,
    0x64afec30678b7L,
    0x64edc0b231e41L,
    0x652b9febc8fb7L,
    0x656989df08719L,
    0x65a77e8dcc390L,
    0x65e57df9f096cL,
    0x6623882552225L,
    0x66619d11cdc5fL,
    0x669fbcc140be8L,
    0x66dde735889b8L,
    0x671c1c70833f6L,
    0x675a5c740edf5L,
    0x6798a7420a036L,
    0x67d6fcdc5386bL,
    0x68155d44ca974L,
    0x6853c87d4eb62L,
    0x68923e87bfb7bL,
    0x68d0bf65fdc34L,
    0x690f4b19e9539L,
    0x694de1a563367L,
    0x698c830a4c8d4L,
    0x69cb2f4a86ccbL,
    0x6a09e667f3bcdL,
    0x6a48a86475796L,
    0x6a877541ee719L,
    0x6ac64d0241683L,
    0x6b052fa75173fL,
    0x6b441d3301fefL,
    0x6b8315a736c75L,
    0x6bc21905d3df1L,
    0x6c012750bdabfL,
    0x6c404089d8e7eL,
    0x6c7f64b30aa09L,
    0x6cbe93ce38381L,
    0x6cfdcddd47646L,
    0x6d3d12e21e2fcL,
    0x6d7c62dea2f8bL,
    0x6dbbbdd4bc721L,
    0x6dfb23c651a2fL,
    0x6e3a94b549e72L,
    0x6e7a10a38cee8L,
    0x6eb9979302bdeL,
    0x6ef9298593ae5L,
    0x6f38c67d286ddL,
    0x6f786e7ba9fefL,
    0x6fb8218301b91L,
    0x6ff7df9519484L,
    0x7037a8b3daadcL,
    0x70777ce1303f6L,
    0x70b75c1f04a85L,
    0x70f7466f42e88L,
    0x71373bd3d6552L,
    0x71773c4eaa988L,
    0x71b747e1abb25L,
    0x71f75e8ec5f74L,
    0x72378057e611bL,
    0x7277ad3ef9011L,
    0x72b7e545ec1a9L,
    0x72f8286ead08aL,
    0x733876bb29cb8L,
    0x7378d02d50b90L,
    0x73b934c7107c8L,
    0x73f9a48a58174L,
    0x743a1f7916e05L,
    0x747aa5953c849L,
    0x74bb36e0b906dL,
    0x74fbd35d7cbfeL,
    0x753c7b0d785e9L,
    0x757d2df29ce7dL,
    0x75bdec0edbb6bL,
    0x75feb564267c9L,
    0x763f89f46f410L,
    0x768069c1a861eL,
    0x76c154cdc4938L,
    0x77024b1ab6e0aL,
    0x77434caa72aa8L,
    0x7784597eeba8fL,
    0x77c5719a15ea6L,
    0x780694fde5d40L,
    0x7847c3ac50219L,
    0x7888fda749e5eL,
    0x78ca42f0c88a5L,
    0x790b938ac1cf7L,
    0x794cef772bcc9L,
    0x798e56b7fcf04L,
    0x79cfc94f2c000L,
    0x7a11473eb0187L,
    0x7a52d08880adaL,
    0x7a94652e958aaL,
    0x7ad60532e6d20L,
    0x7b17b0976cfdbL,
    0x7b59675e20df0L,
    0x7b9b2988fb9edL,
    0x7bdcf719f6bd8L,
    0x7c1ed0130c133L,
    0x7c60b47635cf9L,
    0x7ca2a4456e7a3L,
    0x7ce49f82b0f25L,
    0x7d26a62ff86f1L,
    0x7d68b84f407f8L,
    0x7daad5e2850acL,
    0x7decfeebc24ffL,
    0x7e2f336cf4e63L,
    0x7e71736819bceL,
    0x7eb3bedf2e1baL,
    0x7ef615d42fa25L,
    0x7f3878491c491L,
    0x7f7ae63ff260aL,
    0x7fbd5fbab0920L,
    0x7fffe4bb55decL,
    0x80427543e1a12L,
    0x80851156538beL,
    0x80c7b8f4abaa9L,
    0x810a6c20ea617L,
    0x814d2add106daL,
    0x818ff52b1ee51L,
    0x81d2cb0d1736bL,
    0x8215ac84fb2a6L,
    0x82589994cce13L,
    0x829b923e8ed53L,
    0x82de968443d9bL,
    0x8321a667ef1b3L,
    0x8364c1eb941f8L,
    0x83a7e91136c5eL,
    0x83eb1bdadb46eL,
    0x842e5a4a8634aL,
    0x8471a4623c7adL,
    0x84b4fa24035ebL,
    0x84f85b91e07f2L,
    0x853bc8add9d4cL,
    0x857f4179f5b21L,
    0x85c2c5f83ac36L,
    0x8606562ab00edL,
    0x8649f2135cf49L,
    0x868d99b4492edL,
    0x86d14d0f7cd1eL,
    0x87150c27004c3L,
    0x8758d6fcdc666L,
    0x879cad931a437L,
    0x87e08febc3609L,
    0x88247e08e1957L,
    0x886877ec7f144L,
    0x88ac7d98a669aL,
    0x88f08f0f627ccL,
    0x8934ac52be8f8L,
    0x8978d564c63e7L,
    0x89bd0a4785810L,
    0x8a014afd08a94L,
    0x8a4597875c645L,
    0x8a89efe88dba2L,
    0x8ace5422aa0dcL,
    0x8b12c437bf1d4L,
    0x8b574029db01fL,
    0x8b9bc7fb0c302L,
    0x8be05bad61779L,
    0x8c24fb42ea034L,
    0x8c69a6bdb5598L,
    0x8cae5e1fd35c4L,
    0x8cf3216b5448cL,
    0x8d37f0a248b80L,
    0x8d7ccbc6c19e7L,
    0x8dc1b2dad04c4L,
    0x8e06a5e0866d9L,
    0x8e4ba4d9f60a1L,
    0x8e90afc931858L,
    0x8ed5c6b04b9f6L,
    0x8f1ae99157737L,
    0x8f60186e68794L,
    0x8fa553499284bL,
    0x8fea9a24e9c5cL,
    0x902fed0282c8bL,
    0x90754be472761L,
    0x90bab6ccce12cL,
    0x91002dbdab404L,
    0x9145b0b91ffc6L,
    0x918b3fc142a1aL,
    0x91d0dad829e70L,
    0x921681ffece05L,
    0x925c353aa2fe2L,
    0x92a1f48a640dcL,
    0x92e7bff148396L,
    0x932d977168083L,
    0x93737b0cdc5e5L,
    0x93b96ac5be7d1L,
    0x93ff669e2802cL,
    0x94456e9832eaeL,
    0x948b82b5f98e5L,
    0x94d1a2f996a34L,
    0x9517cf65253d1L,
    0x955e07fac0ccdL,
    0x95a44cbc8520fL,
    0x95ea9dac8e659L,
    0x9630faccf9244L,
    0x9677641fe2446L,
    0x96bdd9a7670b3L,
    0x97045b65a51baL,
    0x974ae95cba769L,
    0x9791838ec57abL,
    0x97d829fde4e50L,
    0x981edcac37d05L,
    0x98659b9bddb5cL,
    0x98ac66cef66c8L,
    0x98f33e47a22a3L,
    0x993a220801829L,
    0x9981121235681L,
    0x99c80e685f2b5L,
    0x9a0f170ca07baL,
    0x9a562c011b66eL,
    0x9a9d4d47f2598L,
    0x9ae47ae3481edL,
    0x9b2bb4d53fe0dL,
    0x9b72fb1ffd286L,
    0x9bba4dc5a3dd4L,
    0x9c01acc858463L,
    0x9c49182a3f091L,
    0x9c908fed7d2abL,
    0x9cd81414380f3L,
    0x9d1fa4a09579eL,
    0x9d674194bb8d5L,
    0x9daeeaf2d0cb9L,
    0x9df6a0bcfc15fL,
    0x9e3e62f564ad5L,
    0x9e86319e32324L,
    0x9ece0cb98ca4bL,
    0x9f15f4499c648L,
    0x9f5de8508a312L,
    0x9fa5e8d07f29eL,
    0x9fedf5cba4ce1L,
    0xa0360f4424fcbL,
    0xa07e353c29f51L,
    0xa0c667b5de565L,
    0xa10ea6b36d1feL,
    0xa156f23701b16L,
    0xa19f4a42c7ca9L,
    0xa1e7aed8eb8bcL,
    0xa2301ffb99757L,
    0xa2789dacfe68cL,
    0xa2c127ef47a75L,
    0xa309bec4a2d34L,
    0xa352622f3def7L,
    0xa39b1231475f8L,
    0xa3e3ceccede7cL,
    0xa42c980460ad8L,
    0xa4756dd9cf36eL,
    0xa4be504f696b1L,
    0xa5073f675f924L,
    0xa5503b23e255dL,
    0xa599438722c04L,
    0xa5e25893523d5L,
    0xa62b7a4aa29a2L,
    0xa674a8af46053L,
    0xa6bde3c36f0e6L,
    0xa7072b8950a73L,
    0xa75080031e22bL,
    0xa799e1330b359L,
    0xa7e34f1b4bf62L,
    0xa82cc9be14dcbL,
    0xa876511d9ac33L,
    0xa8bfe53c12e59L,
    0xa909861bb2e1dL,
    0xa95333beb0b7eL,
    0xa99cee2742c9eL,
    0xa9e6b5579fdc0L,
    0xaa308951ff14dL,
    0xaa7a6a1897fd3L,
    0xaac457ada2804L,
    0xab0e521356ebbL,
    0xab58594bedefbL,
    0xaba26d59a09efL,
    0xabec8e3ea86eeL,
    0xac36bbfd3f37aL,
    0xac80f6979f341L,
    0xaccb3e100301eL,
    0xad159268a5a1cL,
    0xad5ff3a3c2775L,
    0xadaa61c395493L,
    0xadf4dcca5a414L,
    0xae3f64ba4dec6L,
    0xae89f995ad3aeL,
    0xaed49b5eb5803L,
    0xaf1f4a17a4735L,
    0xaf6a05c2b82eaL,
    0xafb4ce622f2ffL,
    0xafffa3f84858dL,
    0xb04a868742ee5L,
    0xb09576115e994L,
    0xb0e07298db666L,
    0xb12b7c1ff9c62L,
    0xb17692a8fa8ceL,
    0xb1c1b6361ef31L,
    0xb20ce6c9a8953L,
    0xb2582465d973cL,
    0xb2a36f0cf3f3aL,
    0xb2eec6c13adddL,
    0xb33a2b84f15fbL,
    0xb3859d5a5b0b1L,
    0xb3d11c43bbd62L,
    0xb41ca843581bbL,
    0xb468415b749b1L,
    0xb4b3e78e56786L,
    0xb4ff9ade433c6L,
    0xb54b5b4d80d4aL,
    0xb59728de5593aL,
    0xb5e303930830cL,
    0xb62eeb6ddfc87L,
    0xb67ae07123dc3L,
    0xb6c6e29f1c52bL,
    0xb712f1fa1177bL,
    0xb75f0e844bfc7L,
    0xb7ab384014f76L,
    0xb7f76f2fb5e47L,
    0xb843b35578a52L,
    0xb89004b3a7804L,
    0xb8dc634c8d229L,
    0xb928cf22749e4L,
    0xb9754837a96b7L,
    0xb9c1ce8e77681L,
    0xba0e62292ad7eL,
    0xba5b030a1064aL,
    0xbaa7b133751e3L,
    0xbaf46ca7a67a8L,
    0xbb413568f255aL,
    0xbb8e0b79a6f1fL,
    0xbbdaeedc12f83L,
    0xbc27df9285776L,
    0xbc74dd9f4de50L,
    0xbcc1e904bc1d3L,
    0xbd0f01c520628L,
    0xbd5c27e2cb5e5L,
    0xbda95b600e20bL,
    0xbdf69c3f3a207L,
    0xbe43ea82a13b6L,
    0xbe91462c95b60L,
    0xbedeaf3f6a3c3L,
    0xbf2c25bd71e09L,
    0xbf79a9a9001d2L,
    0xbfc73b0468d30L,
    0xc014d9d2004aaL,
    0xc06286141b33dL,
    0xc0b03fcd0ea5dL,
    0xc0fe06ff301f5L,
    0xc14bdbacd586aL,
    0xc199bdd85529dL,
    0xc1e7ad8405be6L,
    0xc235aab23e61eL,
    0xc283b5655699aL,
    0xc2d1cd9fa652cL,
    0xc31ff36385e29L,
    0xc36e26b34e066L,
    0xc3bc679157e38L,
    0xc40ab5fffd07bL,
    0xc45912019768cL,
    0xc4a77b9881650L,
    0xc4f5f2c715c31L,
    0xc544778fafb23L,
    0xc59309f4aaca0L,
    0xc5e1a9f8630adL,
    0xc630579d34dddL,
    0xc67f12e57d14cL,
    0xc6cddbd398ea4L,
    0xc71cb269e601fL,
    0xc76b96aac2686L,
    0xc7ba88988c933L,
    0xc8098835a3612L,
    0xc8589584661a1L,
    0xc8a7b087346f5L,
    0xc8f6d9406e7b6L,
    0xc9460fb274c23L,
    0xc99553dfa8314L,
    0xc9e4a5ca6a1f9L,
    0xca3405751c4dbL,
    0xca8372e220e61L,
    0xcad2ee13da7ccL,
    0xcb22770cac0faL,
    0xcb720dcef906aL,
    0xcbc1b25d25338L,
    0xcc1164b994d23L,
    0xcc6124e6ac88cL,
    0xccb0f2e6d1675L,
    0xcd00cebc68e88L,
    0xcd50b869d8f10L,
    0xcda0aff187d02L,
    0xcdf0b555dc3faL,
    0xce40c8993d63dL,
    0xce90e9be12cbaL,
    0xcee118c6c470aL,
    0xcf3155b5bab74L,
    0xcf81a08d5e6edL,
    0xcfd1f95018d17L,
    0xd022600053846L,
    0xd072d4a07897cL,
    0xd0c35732f2871L,
    0xd113e7ba2c38dL,
    0xd164863890feeL,
    0xd1b532b08c969L,
    0xd205ed248b287L,
    0xd256b596f948cL,
    0xd2a78c0a43f73L,
    0xd2f87080d89f2L,
    0xd34962fd2517bL,
    0xd39a638197a3cL,
    0xd3eb72109ef22L,
    0xd43c8eacaa1d7L,
    0xd48db95828ac7L,
    0xd4def2158a91fL,
    0xd53038e7402ceL,
    0xd5818dcfba488L,
    0xd5d2f0d16a1c3L,
    0xd62461eec14bfL,
    0xd675e12a31e80L,
    0xd6c76e862e6d4L,
    0xd7190a0529c51L,
    0xd76ab3a99745bL,
    0xd7bc6b75eab1fL,
    0xd80e316c98398L,
    0xd86005901478fL,
    0xd8b1e7e2d479dL,
    0xd903d8674db2cL,
    0xd955d71ff6076L,
    0xd9a7e40f43c8aL,
    0xd9f9ff37adb4aL,
    0xda4c289baaf6fL,
    0xda9e603db3286L,
    0xdaf0a6203e4f6L,
    0xdb42fa45c4dfeL,
    0xdb955cb0bfbb7L,
    0xdbe7cd63a8315L,
    0xdc3a4c60f7feaL,
    0xdc8cd9ab294e5L,
    0xdcdf7544b6b92L,
    0xdd321f301b461L,
    0xdd84d76fd269fL,
    0xddd79e065807eL,
    0xde2a72f628713L,
    0xde7d5641c0658L,
    0xded047eb9d12dL,
    0xdf2347f63c159L,
    0xdf7656641b78cL,
    0xdfc97337b9b5fL,
    0xe01c9e7395b56L,
    0xe06fd81a2ece1L,
    0xe0c3202e04c5eL,
    0xe11676b197d17L,
    0xe169dba76894aL,
    0xe1bd4f11f8221L,
    0xe210d0f3c7fbbL,
    0xe264614f5a129L,
    0xe2b8002730c72L,
    0xe30bad7dcee91L,
    0xe35f6955b7b78L,
    0xe3b333b16ee12L,
    0xe4070c9378843L,
    0xe45af3fe592e8L,
    0xe4aee9f495dddL,
    0xe502ee78b3ff7L,
    0xe557018d3970bL,
    0xe5ab2334ac7eeL,
    0xe5ff537193e75L,
    0xe653924676d76L,
    0xe6a7dfb5dcecbL,
    0xe6fc3bc24e351L,
    0xe750a66e532ebL,
    0xe7a51fbc74c84L,
    0xe7f9a7af3c60cL,
    0xe84e3e4933c7eL,
    0xe8a2e38ce53e0L,
    0xe8f7977cdb740L,
    0xe94c5a1ba18bdL,
    0xe9a12b6bc3182L,
    0xe9f60b6fcc1c8L,
    0xea4afa2a490daL,
    0xea9ff79dc6d14L,
    0xeaf503ccd2be6L,
    0xeb4a1eb9fa9d1L,
    0xeb9f4867cca6fL,
    0xebf480d8d786eL,
    0xec49c80faa594L,
    0xec9f1e0ed4ac2L,
    0xecf482d8e67f1L,
    0xed49f67070436L,
    0xed9f78d802dc2L,
    0xedf50a122f9e6L,
    0xee4aaa2188511L,
    0xeea059089f2d1L,
    0xeef616ca06dd7L,
    0xef4be368527f7L,
    0xefa1bee615a28L,
    0xeff7a945e4488L,
    0xf04da28a52e5aL,
    0xf0a3aab5f6609L,
    0xf0f9c1cb6412aL,
    0xf14fe7cd31c7cL,
    0xf1a61cbdf5be7L,
    0xf1fc60a046a84L,
    0xf252b376bba98L,
    0xf2a91543ec595L,
    0xf2ff860a70c22L,
    0xf35605cce1614L,
    0xf3ac948dd7274L,
    0xf403324feb781L,
    0xf459df15b82adL,
    0xf4b09ae1d78a2L,
    0xf50765b6e4541L,
    0xf55e3f9779ba6L,
    0xf5b5288633626L,
    0xf60c2085ad652L,
    0xf6632798844f9L,
    0xf6ba3dc155227L,
    0xf7116302bd527L,
    0xf768975f5ac86L,
    0xf7bfdad9cbe14L,
    0xf8172d74af6e2L,
    0xf86e8f32a4b46L,
    0xf8c600164b6ddL,
    0xf91d802243c89L,
    0xf9750f592e678L,
    0xf9ccadbdac61dL,
    0xfa245b525f439L,
    0xfa7c1819e90d9L,
    0xfad3e416ec354L,
    0xfb2bbf4c0ba55L,
    0xfb83a9bbeabd2L,
    0xfbdba3692d514L,
    0xfc33ac5677ab9L,
    0xfc8bc4866e8aeL,
    0xfce3ebfbb7238L,
    0xfd3c22b8f71f2L,
    0xfd9468c0d49cdL,
    0xfdecbe15f6315L,
    0xfe4522bb02e6eL,
    0xfe9d96b2a23daL,
    0xfef619ff7c2b3L,
    0xff4eaca4391b6L,
    0xffa74ea381efdL
  };
}