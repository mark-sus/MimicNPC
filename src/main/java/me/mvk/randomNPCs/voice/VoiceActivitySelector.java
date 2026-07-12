package me.mvk.randomNPCs.voice;

public final class VoiceActivitySelector {

    // Поріг RMS-амплітуди (з діапазону short, максимум 32767), нижче якого кадр вважається тишею.
    // Значення підібране емпірично: звичайна кімнатна тиша/шум мікрофона зазвичай нижче ~150-250.
    private static final int DEFAULT_SILENCE_RMS_THRESHOLD = 300;

    // Розмір кадру для оцінки гучності: 20 мс при 48kHz
    private static final int FRAME_SIZE = 960;

    // Мінімальна частка "голосних" кадрів у відрізку, щоб вважати його придатним (не тишею)
    private static final double MIN_VOICED_RATIO = 0.15;

    private VoiceActivitySelector() {
    }

    public static short[] extractLoudestSegment(short[] full, int segmentLength) {
        return extractLoudestSegment(full, segmentLength, DEFAULT_SILENCE_RMS_THRESHOLD);
    }


    public static short[] extractLoudestSegment(short[] full, int segmentLength, int silenceRmsThreshold) {
        if (full == null || full.length == 0 || segmentLength <= 0) {
            return null;
        }

        // Якщо в буфері менше даних, ніж потрібно - беремо все, що є, якщо там взагалі є голос
        if (full.length <= segmentLength) {
            return hasVoiceActivity(full, silenceRmsThreshold) ? full : null;
        }

        int frameCount = full.length / FRAME_SIZE;
        if (frameCount == 0) {
            return null;
        }

        double[] frameRms = new double[frameCount];
        boolean[] frameVoiced = new boolean[frameCount];
        for (int f = 0; f < frameCount; f++) {
            double rms = rms(full, f * FRAME_SIZE, FRAME_SIZE);
            frameRms[f] = rms;
            frameVoiced[f] = rms > silenceRmsThreshold;
        }

        int framesPerSegment = Math.max(1, segmentLength / FRAME_SIZE);
        if (framesPerSegment > frameCount) {
            // Відрізок довший за те, що можемо оцінити по кадрах - fallback на весь буфер
            return hasVoiceActivity(full, silenceRmsThreshold) ? trimOrPad(full, segmentLength) : null;
        }

        double bestScore = -1;
        int bestStartFrame = 0;
        double windowSum = 0;
        int voicedInWindow = 0;

        for (int f = 0; f < frameCount; f++) {
            windowSum += frameRms[f];
            if (frameVoiced[f]) voicedInWindow++;

            if (f >= framesPerSegment) {
                int dropped = f - framesPerSegment;
                windowSum -= frameRms[dropped];
                if (frameVoiced[dropped]) voicedInWindow--;
            }

            if (f >= framesPerSegment - 1) {
                int windowStartFrame = f - framesPerSegment + 1;
                double voicedRatio = (double) voicedInWindow / framesPerSegment;
                double avgRms = windowSum / framesPerSegment;
                // Пріоритет - частка голосних кадрів, середня гучність лише розрізняє рівні варіанти
                double score = voicedRatio * 1_000_000.0 + avgRms;
                if (score > bestScore) {
                    bestScore = score;
                    bestStartFrame = windowStartFrame;
                }
            }
        }

        int startSample = bestStartFrame * FRAME_SIZE;
        short[] result = trimOrPad(subArray(full, startSample, segmentLength), segmentLength);

        return hasVoiceActivity(result, silenceRmsThreshold) ? result : null;
    }

    private static short[] subArray(short[] src, int start, int length) {
        int available = Math.min(length, src.length - start);
        short[] out = new short[Math.max(available, 0)];
        if (available > 0) {
            System.arraycopy(src, start, out, 0, available);
        }
        return out;
    }

    private static short[] trimOrPad(short[] src, int targetLength) {
        if (src.length == targetLength) return src;
        short[] out = new short[targetLength];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, targetLength));
        return out;
    }

    private static boolean hasVoiceActivity(short[] samples, int silenceRmsThreshold) {
        if (samples == null || samples.length == 0) return false;
        int frameCount = Math.max(1, samples.length / FRAME_SIZE);
        int voicedFrames = 0;
        for (int f = 0; f < frameCount; f++) {
            int offset = f * FRAME_SIZE;
            int len = Math.min(FRAME_SIZE, samples.length - offset);
            if (len <= 0) break;
            if (rms(samples, offset, len) > silenceRmsThreshold) voicedFrames++;
        }
        return voicedFrames >= Math.max(1, (int) Math.ceil(frameCount * MIN_VOICED_RATIO));
    }

    private static double rms(short[] samples, int offset, int length) {
        int end = Math.min(offset + length, samples.length);
        int count = end - offset;
        if (count <= 0) return 0;
        long sumSquares = 0;
        for (int i = offset; i < end; i++) {
            sumSquares += (long) samples[i] * samples[i];
        }
        return Math.sqrt((double) sumSquares / count);
    }
}