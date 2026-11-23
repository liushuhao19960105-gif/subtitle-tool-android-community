package com.subtitletool.standalone;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline audio export. AAC tracks are remuxed without re-encoding; other
 * supported formats use the existing decoder as a 16kHz mono WAV fallback.
 * No microphone, network, cloud service, or additional permissions required.
 */
public final class AudioClipExporter {
    private AudioClipExporter() {}
    public interface Progress { void onProgress(int value); }
    public static final class Exported {
        public final File file;
        public final String extension;
        public final String mime;
        public final boolean unchangedAac;
        Exported(File f,String e,String m,boolean nativeTrack) {
            file=f;extension=e;mime=m;unchangedAac=nativeTrack;
        }
    }
    public static Exported extract(Context ctx,Uri uri,File dir,long fromUs,long toUs,
                                   AtomicBoolean stop,Progress progress) throws Exception {
        boolean clip=toUs>0;
        if(clip)AudioTimeRange.validate(fromUs,toUs);
        if(!dir.exists() && !dir.mkdirs())throw new java.io.IOException("无法建立临时目录");
        MediaExtractor ex=new MediaExtractor();
        try {
            ex.setDataSource(ctx,uri,null);
            int audioTrack=-1;MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++) {
                MediaFormat f=ex.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("audio/")){audioTrack=i;fmt=f;break;}
            }
            if(audioTrack<0 || fmt==null)throw new IllegalArgumentException("视频没有可识别的音轨");
            if(fmt.containsKey(MediaFormat.KEY_DURATION)) {
                long duration=fmt.getLong(MediaFormat.KEY_DURATION);
                if(duration>0 && fromUs>=duration)
                    throw new IllegalArgumentException("开始时间超过音频总时长");
            }
            ex.selectTrack(audioTrack);
            if("audio/mp4a-latm".equals(fmt.getString(MediaFormat.KEY_MIME))) {
                File m4a=new File(dir,"export_audio.m4a");
                try {
                    remuxAac(ex,fmt,m4a,fromUs,toUs,stop,progress);
                    verifyM4a(m4a);
                    return new Exported(m4a,".m4a","audio/mp4",true);
                }catch(InterruptedException cancel){throw cancel;}
                catch(Exception incompatible){
                    // A given vendor may reject an AAC stream. Do not write a
                    // misleading broken M4A: switch to a verified WAV instead.
                    m4a.delete();
                }
            }
        } finally {ex.release();}
        if(stop.get())throw new InterruptedException("任务已取消");
        progress.onProgress(9);
        File rawDir=new File(dir,"wav_temp");rawDir.mkdirs();
        AudioExtractor.AudioFile full=AudioExtractor.extract(ctx,uri,rawDir,stop,
            percent -> progress.onProgress(Math.min(90,9+percent*4)));
        if(!clip) return new Exported(full.file,".wav","audio/wav",false);
        File out=new File(dir,"export_audio.wav");
        cropWav(full.file,out,fromUs,toUs,stop);
        full.file.delete();
        AudioIntegrity.validatePcmWav(out);
        progress.onProgress(95);
        return new Exported(out,".wav","audio/wav",false);
    }
    /** For 16kHz mono PCM16 WAV, each second is exactly 32,000 bytes. */
    static void cropWav(File input,File output,long fromUs,long toUs,AtomicBoolean stop)throws Exception {
        AudioIntegrity.validatePcmWav(input);
        try(RandomAccessFile source=new RandomAccessFile(input,"r");
            RandomAccessFile target=new RandomAccessFile(output,"rw")) {
            long totalBytes=source.length()-44;
            long first=Math.min(totalBytes,Math.max(0,fromUs/1000000L*32000L + (fromUs%1000000L)*32000L/1000000L));
            long last=Math.min(totalBytes,Math.max(0,toUs/1000000L*32000L + (toUs%1000000L)*32000L/1000000L));
            first&=~1L;last&=~1L;
            if(last<=first)throw new IllegalArgumentException("选定片段在音频范围之外");
            long size=last-first;
            if(size>0xFFFFFF00L)throw new IllegalArgumentException("音频片段超出 WAV 文件限制");
            byte[] header=new byte[44];source.seek(0);source.readFully(header);
            putLE32(header,4,36L+size);putLE32(header,40,size);
            target.setLength(0);target.write(header);
            source.seek(44+first);
            byte[] buffer=new byte[65536];long remaining=size;
            while(remaining>0){
                if(stop.get())throw new InterruptedException("任务已取消");
                int n=source.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(n<0)throw new java.io.EOFException("音频截取不完整");
                target.write(buffer,0,n);remaining-=n;
            }
        }
    }
    static void putLE32(byte[] x,int offset,long v){for(int i=0;i<4;i++)x[offset+i]=(byte)(v >>> (8*i));}
    /** Direct AAC rewrapping only: encoded samples are never re-encoded. */
    private static void remuxAac(MediaExtractor ex,MediaFormat fmt,File target,long startUs,long endUs,
                                 AtomicBoolean stop,Progress progress)throws Exception {
        MediaMuxer muxer=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started=false;int count=0;
        try {
            int outTrack=muxer.addTrack(fmt);muxer.start();started=true;
            if(startUs>0)ex.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long duration=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION):-1;
            long firstPts=-1;
            ByteBuffer buffer=ByteBuffer.allocateDirect(512*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true) {
                if(stop.get())throw new InterruptedException("任务已取消");
                buffer.clear();
                int size=ex.readSampleData(buffer,0);
                if(size<0)break;
                long t=ex.getSampleTime();
                if(t<0)break;
                if(endUs>0 && t>=endUs)break;
                if(t>=startUs){
                    if(firstPts<0)firstPts=t;
                    info.set(0,size,Math.max(0,t-firstPts),ex.getSampleFlags());
                    buffer.position(0);buffer.limit(size);
                    muxer.writeSampleData(outTrack,buffer,info);
                    count++;
                }
                if(duration>0){
                    long span=endUs>0?endUs-startUs:duration-startUs;
                    long elapsed=Math.max(0,t-startUs);
                    if(span>0)progress.onProgress(Math.max(5,Math.min(92,5+(int)(87.0*elapsed/span))));
                }
                if(!ex.advance())break;
            }
            if(count==0)throw new IllegalArgumentException("选定时间段没有可提取的音频");
        }finally {
            if(started)try{muxer.stop();}catch(Exception ignored){}
            muxer.release();
        }
    }
    private static void verifyM4a(File file)throws Exception {
        if(!file.isFile() || file.length()<512)throw new java.io.IOException("导出的 M4A 为空或过短");
        MediaExtractor check=new MediaExtractor();
        try {
            check.setDataSource(file.getAbsolutePath());
            boolean audio=false;
            for(int i=0;i<check.getTrackCount();i++){
                String mime=check.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("audio/")){check.selectTrack(i);audio=true;break;}
            }
            if(!audio || check.getSampleTime()<0)
                throw new java.io.IOException("音频格式无法读取");
        }finally{check.release();}
    }
}
