package com.subtitletool.standalone;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android platform only: decode the selected audio track to a standard
 * 16 kHz mono 16-bit PCM WAV. No AAC-to-M4A remuxing: some syntactically valid
 * MP4/M4A files are refused by the speech service, causing HTTP 400.
 * No third-party binary, Termux, root, or cloud video upload is required.
 */
public final class AudioExtractor {
    private AudioExtractor() { }
    public static final class AudioFile {
        public final File file; public final String mime;
        AudioFile(File file, String mime) { this.file=file;this.mime=mime; }
    }
    public interface Progress { void onProgress(int percent); }
    public static AudioFile extract(Context ctx, Uri uri, File dir, AtomicBoolean stop) throws Exception {
        return extract(ctx, uri, dir, stop, percent -> { });
    }
    public static AudioFile extract(Context ctx, Uri uri, File dir, AtomicBoolean stop, Progress progress) throws Exception {
        MediaExtractor ex=new MediaExtractor();
        try {
            ex.setDataSource(ctx,uri,null);
            int track=-1;
            MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++) {
                MediaFormat f=ex.getTrackFormat(i);
                String m=f.getString(MediaFormat.KEY_MIME);
                if(m!=null && m.startsWith("audio/")) { track=i;fmt=f;break; }
            }
            if(track<0 || fmt==null) throw new IllegalArgumentException("文件没有可识别的音轨");
            ex.selectTrack(track);
            String mime=fmt.getString(MediaFormat.KEY_MIME);
            // Always convert to a predictably decodable container.
            // Streaming decoder writes a temporary file; the video is never
            // loaded into heap memory or uploaded to the cloud.
            File out=new File(dir,"asr_audio.wav");
            decodeToWav(ex,fmt,mime,out,stop,progress);
            AudioIntegrity.validatePcmWav(out);
            return new AudioFile(out,"audio/wav");
        } finally { ex.release(); }
    }
    private static void checkStop(AtomicBoolean stop) throws InterruptedException {
        if(stop.get()) throw new InterruptedException("任务已取消");
    }
    private static void decodeToWav(MediaExtractor ex,MediaFormat source,String mime,File out,AtomicBoolean stop,Progress progress) throws Exception {
        MediaCodec decoder=MediaCodec.createDecoderByType(mime);
        boolean started=false;
        try(RandomAccessFile wav=new RandomAccessFile(out,"rw")) {
            wav.setLength(0);
            for(int i=0;i<44;i++) wav.write(0);
            decoder.configure(source,null,null,0);
            decoder.start(); started=true;
            boolean inputDone=false,outputDone=false;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            int channels=source.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? source.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            int rate=source.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? source.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            Resampler resampler=new Resampler(wav);
            long durationUs=source.containsKey(MediaFormat.KEY_DURATION)?source.getLong(MediaFormat.KEY_DURATION):-1;
            int lastPercent=3;
            long lastActivityNs=System.nanoTime();
            while(!outputDone) {
                checkStop(stop);
                boolean progressed=false;
                if(!inputDone) {
                    int idx=decoder.dequeueInputBuffer(10000);
                    if(idx>=0) {
                        ByteBuffer in=decoder.getInputBuffer(idx);
                        if(in==null) throw new IllegalStateException("MediaCodec 输入缓冲区不可用");
                        in.clear();
                        int n=ex.readSampleData(in,0);
                        if(n<0) { decoder.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true; }
                        else {
                            decoder.queueInputBuffer(idx,0,n,Math.max(0,ex.getSampleTime()),0);
                            ex.advance();
                        }
                        progressed=true;
                    }
                }
                int idx=decoder.dequeueOutputBuffer(info,10000);
                if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual=decoder.getOutputFormat();
                    channels=actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT)? actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT):channels;
                    rate=actual.containsKey(MediaFormat.KEY_SAMPLE_RATE)?actual.getInteger(MediaFormat.KEY_SAMPLE_RATE):rate;
                    encoding=actual.containsKey(MediaFormat.KEY_PCM_ENCODING)?actual.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(channels<1 || channels>8 || rate<8000 || rate>192000) throw new IllegalArgumentException("音轨声道/采样率不受支持");
                    progressed=true;
                } else if(idx>=0) {
                    ByteBuffer pcm=decoder.getOutputBuffer(idx);
                    if(pcm!=null && info.size>0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        pcm.position(info.offset);
                        pcm.limit(info.offset+info.size);
                        resampler.write(pcm.slice().order(ByteOrder.LITTLE_ENDIAN),rate,channels,encoding);
                        // Report decoded playback position, not bytes of video loaded.
                        if(durationUs>0 && info.presentationTimeUs>=0) {
                            double fraction=Math.min(1., Math.max(0., (double)info.presentationTimeUs/durationUs));
                            int percent=Math.min(17,3+(int)(14*fraction));
                            if(percent>lastPercent) { lastPercent=percent;progress.onProgress(percent); }
                        }
                    }
                    outputDone=(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    decoder.releaseOutputBuffer(idx,false);
                    progressed=true;
                }
                if(progressed) lastActivityNs=System.nanoTime();
                else if(System.nanoTime()-lastActivityNs>60_000_000_000L)
                    throw new IllegalStateException("音频解码器连续60秒无响应；建议停止任务并尝试其他视频格式");
            }
            resampler.finish();
            long bytes=wav.length()-44;
            if(bytes>0xFFFFFF00L) throw new IllegalArgumentException("音频超过 WAV 容量限制");
            wav.seek(0); writeWaveHeader(wav,(int)bytes);
        } finally {
            if(started) { try { decoder.stop(); } catch(Exception ignored) { } }
            decoder.release();
        }
    }
    private static void w16(RandomAccessFile f,int v)throws Exception { f.write(v&255);f.write((v>>8)&255); }
    private static void w32(RandomAccessFile f,long v)throws Exception { for(int i=0;i<4;i++) f.write((int)((v>>(8*i))&255)); }
    private static void writeWaveHeader(RandomAccessFile w,int bytes)throws Exception {
        w.writeBytes("RIFF");w32(w,36L+bytes);w.writeBytes("WAVEfmt ");w32(w,16);
        w16(w,1);w16(w,1);w32(w,16000);w32(w,32000);w16(w,2);w16(w,16);
        w.writeBytes("data");w32(w,bytes);
    }
    /** Linear interpolation, keeps phase across decoder output buffer boundaries. */
    private static final class Resampler {
        private final BufferedPcmWriter writer;
        private double nextSample=0;
        private long inputIndex=0;
        private float prev=0;
        private boolean hasPrev=false;
        private int oldRate=-1;
        Resampler(RandomAccessFile file){this.writer=new BufferedPcmWriter(file); }
        void write(ByteBuffer pcm,int rate,int channels,int encoding) throws Exception {
            if(oldRate!=-1 && oldRate!=rate) throw new IllegalArgumentException("音轨中途改变采样率，暂不支持");
            oldRate=rate;
            int sampleBytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
            if(encoding!=AudioFormat.ENCODING_PCM_FLOAT && encoding!=AudioFormat.ENCODING_PCM_16BIT)
                throw new IllegalArgumentException("音频 PCM 采样格式不支持");
            int frames=pcm.remaining()/(channels*sampleBytes);
            for(int i=0;i<frames;i++) {
                double mono=0;
                for(int c=0;c<channels;c++) {
                    if(encoding==AudioFormat.ENCODING_PCM_FLOAT) mono+=Math.max(-1f,Math.min(1f,pcm.getFloat()));
                    else mono+=pcm.getShort()/32768.0;
                }
                float cur=(float)(mono/channels);
                if(!hasPrev) {
                    hasPrev=true; prev=cur;
                    put(cur);nextSample=rate/16000.0;
                    inputIndex=0;
                } else {
                    inputIndex++;
                    while(nextSample<=inputIndex+1e-9) {
                        float sample=(float)(prev+(cur-prev)*(nextSample-(inputIndex-1)));
                        put(sample);
                        nextSample+=rate/16000.0;
                    }
                    prev=cur;
                }
            }
        }
        void put(float x)throws Exception {
            int value=(int)(Math.max(-1.,Math.min(1.,x))*32767.0);
            writer.write16(value);
        }
        void finish() throws Exception { writer.flush(); }
    }
}
