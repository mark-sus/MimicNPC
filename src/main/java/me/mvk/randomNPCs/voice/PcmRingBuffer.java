package me.mvk.randomNPCs.voice;

public class PcmRingBuffer {

    private final short[] data;
    private int writePos = 0;
    private boolean filled = false;

    public PcmRingBuffer(int capacitySamples) {
        this.data = new short[Math.max(960, capacitySamples)];
    }

    public synchronized void write(short[] samples) {
        if (samples == null) return;
        for (short sample : samples) {
            data[writePos] = sample;
            writePos = (writePos + 1) % data.length;
            if (writePos == 0) filled = true;
        }
    }

    public synchronized short[] snapshot() {
        int length = filled ? data.length : writePos;
        if (length == 0) return null;
        short[] result = new short[length];
        if (!filled) {
            System.arraycopy(data, 0, result, 0, length);
        } else {
            int firstPartLen = data.length - writePos;
            System.arraycopy(data, writePos, result, 0, firstPartLen);
            System.arraycopy(data, 0, result, firstPartLen, writePos);
        }
        return result;
    }
}