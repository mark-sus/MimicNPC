package me.mvk.mimicNPC.voice;

import java.util.Random;

public class VoiceDistortion {

    private static final Random RANDOM = new Random();

    public static short[] apply(short[] input, double pitchFactor, int bitcrushDepth) {
        short[] pitched = pitchShift(input, pitchFactor);
        bitcrush(pitched, bitcrushDepth);
        addStutter(pitched);
        return pitched;
    }

    // change voice pitch for NPC
    private static short[] pitchShift(short[] input, double factor) {
        int newLength = (int) (input.length / factor);
        short[] output = new short[newLength];
        for (int i = 0; i < newLength; i++) {
            int srcIndex = (int) (i * factor);
            output[i] = srcIndex < input.length ? input[srcIndex] : 0;
        }
        return output;
    }

    private static void bitcrush(short[] samples, int depth) {
        if (depth <= 0) return;
        int mask = ~((1 << depth) - 1);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (samples[i] & mask);
        }
    }

    private static void addStutter(short[] samples) {
        if (samples.length < 4000) return;
        int stutterAt = 1000 + RANDOM.nextInt(samples.length - 3000);
        int stutterLen = 200 + RANDOM.nextInt(400);
        for (int rep = 0; rep < 2; rep++) {
            int dst = stutterAt + rep * stutterLen;
            if (dst + stutterLen >= samples.length) break;
            System.arraycopy(samples, stutterAt, samples, dst, stutterLen);
        }
    }
}
