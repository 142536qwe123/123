import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/**
 * 音频归一化工具：将音频峰值调整到 0.99（避免爆音）
 * 用法：java NormalizeAudio <输入文件> [输出文件]
 * 默认输出文件为 out.wav
 */
public class NormalizeAudio {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: java NormalizeAudio <输入音频文件> [输出文件]");
            System.err.println("     输出文件默认为 out.wav");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args.length >= 2 ? args[1] : "out.wav";

        try {
            normalize(inputFile, outputFile);
            System.out.println("归一化完成，输出文件: " + outputFile);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 归一化音频，使峰值振幅达到 0.99
     * @param inputPath  输入音频文件路径
     * @param outputPath 输出音频文件路径
     */
    private static void normalize(String inputPath, String outputPath)
            throws UnsupportedAudioFileException, IOException, LineUnavailableException {
        File inFile = new File(inputPath);
        File outFile = new File(outputPath);

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(inFile)) {
            AudioFormat format = ais.getFormat();
            // 仅支持 PCM 编码
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED &&
                format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                throw new UnsupportedAudioFileException("仅支持 PCM 编码 (PCM_SIGNED 或 PCM_UNSIGNED)");
            }

            int sampleSizeInBits = format.getSampleSizeInBits();
            int bytesPerSample = sampleSizeInBits / 8;
            boolean isBigEndian = format.isBigEndian();
            boolean isSigned = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;

            // 读取全部音频数据（若文件过大可改用流式处理，但为简化代码此处全部读入内存）
            byte[] audioBytes = ais.readAllBytes();
            int totalSamples = audioBytes.length / bytesPerSample;

            // 第一遍：计算峰值（绝对值最大值）
            double peak = 0.0;
            for (int i = 0; i < totalSamples; i++) {
                int offset = i * bytesPerSample;
                double sample = decodeSample(audioBytes, offset, sampleSizeInBits, isBigEndian, isSigned);
                double absSample = Math.abs(sample);
                if (absSample > peak) {
                    peak = absSample;
                }
            }

            // 计算归一化系数
            double k = (peak == 0.0) ? 1.0 : (0.99 / peak);

            // 第二遍：应用系数并饱和处理
            for (int i = 0; i < totalSamples; i++) {
                int offset = i * bytesPerSample;
                double original = decodeSample(audioBytes, offset, sampleSizeInBits, isBigEndian, isSigned);
                double adjusted = original * k;
                // 饱和到 [-1.0, 1.0]（理论上调整后不会超过 0.99，但浮点误差可能略超）
                if (adjusted > 1.0) adjusted = 1.0;
                if (adjusted < -1.0) adjusted = -1.0;
                encodeSample(audioBytes, offset, adjusted, sampleSizeInBits, isBigEndian, isSigned);
            }

            // 写入新文件
            AudioInputStream outAis = new AudioInputStream(
                    new java.io.ByteArrayInputStream(audioBytes),
                    format,
                    audioBytes.length / format.getFrameSize()
            );
            AudioSystem.write(outAis, AudioFileFormat.Type.WAVE, outFile);
        }
    }

    /**
     * 解码 PCM 样本为 double（范围 [-1.0, 1.0]）
     */
    private static double decodeSample(byte[] data, int offset, int bits, boolean bigEndian, boolean signed) {
        if (bits == 8) {
            int unsigned = data[offset] & 0xFF;
            if (signed) {
                return (unsigned - 128) / 128.0;
            } else {
                return unsigned / 255.0;
            }
        } else if (bits == 16) {
            int value;
            if (bigEndian) {
                value = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            } else {
                value = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            }
            if (!signed) {
                value -= 32768;
            }
            return value / 32768.0;
        } else if (bits == 24) {
            int value;
            if (bigEndian) {
                value = ((data[offset] & 0xFF) << 16) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        (data[offset + 2] & 0xFF);
            } else {
                value = (data[offset] & 0xFF) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        ((data[offset + 2] & 0xFF) << 16);
            }
            // 24 位有符号扩展为 32 位
            if ((value & 0x800000) != 0) {
                value |= 0xFF000000;
            }
            return value / 8388608.0; // 2^23
        } else if (bits == 32) {
            int value;
            if (bigEndian) {
                value = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
            } else {
                value = (data[offset] & 0xFF) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        ((data[offset + 2] & 0xFF) << 16) |
                        ((data[offset + 3] & 0xFF) << 24);
            }
            return value / 2147483648.0; // 2^31
        } else {
            throw new IllegalArgumentException("不支持的采样位数: " + bits);
        }
    }

    /**
     * 编码 double 样本到字节数组
     */
    private static void encodeSample(byte[] data, int offset, double sample, int bits, boolean bigEndian, boolean signed) {
        if (bits == 8) {
            int value;
            if (signed) {
                value = (int) Math.round(sample * 127.0);
                value = Math.max(-128, Math.min(127, value));
                data[offset] = (byte) (value & 0xFF);
            } else {
                value = (int) Math.round((sample + 1.0) / 2.0 * 255.0);
                value = Math.max(0, Math.min(255, value));
                data[offset] = (byte) (value & 0xFF);
            }
        } else if (bits == 16) {
            int value;
            if (signed) {
                value = (int) Math.round(sample * 32767.0);
                value = Math.max(-32768, Math.min(32767, value));
            } else {
                value = (int) Math.round((sample + 1.0) / 2.0 * 65535.0);
                value = Math.max(0, Math.min(65535, value));
                value -= 32768;
            }
            if (bigEndian) {
                data[offset] = (byte) ((value >> 8) & 0xFF);
                data[offset + 1] = (byte) (value & 0xFF);
            } else {
                data[offset] = (byte) (value & 0xFF);
                data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            }
        } else if (bits == 24) {
            int value;
            if (signed) {
                value = (int) Math.round(sample * 8388607.0); // 2^23-1
                value = Math.max(-8388608, Math.min(8388607, value));
            } else {
                value = (int) Math.round((sample + 1.0) / 2.0 * 16777215.0);
                value = Math.max(0, Math.min(16777215, value));
                value -= 8388608;
            }
            if (bigEndian) {
                data[offset] = (byte) ((value >> 16) & 0xFF);
                data[offset + 1] = (byte) ((value >> 8) & 0xFF);
                data[offset + 2] = (byte) (value & 0xFF);
            } else {
                data[offset] = (byte) (value & 0xFF);
                data[offset + 1] = (byte) ((value >> 8) & 0xFF);
                data[offset + 2] = (byte) ((value >> 16) & 0xFF);
            }
        } else if (bits == 32) {
            int value;
            if (signed) {
                value = (int) Math.round(sample * 2147483647.0);
                value = Math.max(-2147483648, Math.min(2147483647, value));
            } else {
                value = (int) Math.round((sample + 1.0) / 2.0 * 4294967295.0);
                value = Math.max(0, Math.min(4294967295L, value));
                value -= 2147483648L;
            }
            if (bigEndian) {
                data[offset] = (byte) ((value >> 24) & 0xFF);
                data[offset + 1] = (byte) ((value >> 16) & 0xFF);
                data[offset + 2] = (byte) ((value >> 8) & 0xFF);
                data[offset + 3] = (byte) (value & 0xFF);
            } else {
                data[offset] = (byte) (value & 0xFF);
                data[offset + 1] = (byte) ((value >> 8) & 0xFF);
                data[offset + 2] = (byte) ((value >> 16) & 0xFF);
                data[offset + 3] = (byte) ((value >> 24) & 0xFF);
            }
        } else {
            throw new IllegalArgumentException("不支持的采样位数: " + bits);
        }
    }
}