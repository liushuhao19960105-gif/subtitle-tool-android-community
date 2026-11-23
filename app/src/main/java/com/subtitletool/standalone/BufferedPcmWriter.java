package com.subtitletool.standalone;

import java.io.IOException;
import java.io.RandomAccessFile;

/** Writes little-endian PCM in 64 KiB blocks instead of two file writes per sample. */
public final class BufferedPcmWriter {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final RandomAccessFile file;
    private final byte[] bytes = new byte[BUFFER_SIZE];
    private int count;

    public BufferedPcmWriter(RandomAccessFile file) { this.file = file; }

    public void write16(int sample) throws IOException {
        if (count + 2 > bytes.length) flush();
        bytes[count++] = (byte) (sample & 0xFF);
        bytes[count++] = (byte) ((sample >>> 8) & 0xFF);
    }

    public void flush() throws IOException {
        if (count > 0) { file.write(bytes, 0, count); count = 0; }
    }
}
