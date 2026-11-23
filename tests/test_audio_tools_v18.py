"""V1.8 source checks and real JVM execution of time parsing + WAV clipping.
Network APIs and Android device codecs are not exercised by these tests.
"""
from pathlib import Path
import subprocess, tempfile, textwrap, unittest
ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

class AudioToolsV18(unittest.TestCase):
    def test_new_views_and_offline_flow(self):
        s=(PKG/'MainActivity.java').read_text()
        for term in ('音频工具','完整音轨','截取片段','导出音频','AudioClipExporter.extract',
                     'AudioDownloadsManager.save','AudioTimeRange.parse','AudioTimeRange.validate',
                     'save.putExtra(Intent.EXTRA_TITLE,name)','pendingAudioFile==null'):
            self.assertIn(term,s)
        method=s.split('private void exportAudio(){',1)[1].split('private void export(){',1)[0]
        self.assertNotIn('saveKeys()',method)
        self.assertNotIn('pipeline.transcribe(',method)
        self.assertNotIn('assemblyTranscriber.transcribe(',method)
        self.assertNotIn('translateAndPolish(',method)

    def test_button_visual_hierarchy(self):
        s=(PKG/'MainActivity.java').read_text()
        for term in ('RippleDrawable','quietBtn(', '开始生成字幕', 'stopBtn.setVisibility(View.GONE)',
                     'exportBtn=btn("下载中文字幕 ASS",false)', 'audioExportBtn=btn("导出音频",false)',
                     'new RadioGroup(this)','TransitionManager.beginDelayedTransition'):
            self.assertIn(term,s)

    def test_aac_no_reencode_and_wav_fallback(self):
        s=(PKG/'AudioClipExporter.java').read_text()
        for term in ('MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4',
                     'muxer.writeSampleData(', '"audio/mp4a-latm".equals(',
                     'AudioExtractor.extract(ctx,uri,rawDir,stop',
                     'AudioIntegrity.validatePcmWav(out)',
                     'ex.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)'):
            self.assertIn(term,s)
        self.assertNotIn('https://',s)

    def test_media_store_writes_file_and_checks_extension(self):
        s=(PKG/'AudioDownloadsManager.java').read_text()
        for term in ('MediaStore.Downloads.DISPLAY_NAME','MediaStore.Downloads.RELATIVE_PATH',
                     'MediaStore.Downloads.IS_PENDING','AudioTimeRange.numbered(wanted,i)',
                     'AssExportManager.displayName(resolver,out)','if(i==10)',
                     'retain=true;return new Result('):
            self.assertIn(term,s)

    def test_real_java_time_parsing_and_names(self):
        java=r'''
package com.subtitletool.standalone;
class VerifyTimes {
    static void yes(boolean ok){if(!ok)throw new AssertionError();}
    static void rejects(String s){
        try{AudioTimeRange.parse(s);throw new AssertionError("accepted " + s);}
        catch(IllegalArgumentException valid){}
    }
    public static void main(String[] args){
        yes(AudioTimeRange.parse("00:32:00")==1920000000L);
        yes(AudioTimeRange.parse("01:01:05")==3665000000L);
        yes(AudioTimeRange.parse("0:0:0")==0);
        rejects("1:70:01");rejects("01:23");rejects("01:22:6x");
        rejects("-1:22:10");rejects("100:00:00");
        try {AudioTimeRange.validate(2000000L,1000000L);throw new AssertionError();}
        catch(IllegalArgumentException expected){}
        AudioTimeRange.validate(1920000000L,2100000000L);
        String name=AudioTimeRange.fileName("movie.720p.mp4",true,1920000000L,2100000000L,".m4a");
        yes(name.equals("movie.720p.00-32-00_00-35-00.m4a"));
        yes(AudioTimeRange.numbered(name,2).endsWith(" (2).m4a"));
        yes(AudioTimeRange.fileName("demo.mp4",false,0,0,".wav").equals("demo.音轨.wav"));
        System.out.println("range & file names OK");
    }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp)/'com/subtitletool/standalone';d.mkdir(parents=True)
            (d/'AudioTimeRange.java').write_bytes((PKG/'AudioTimeRange.java').read_bytes())
            (d/'VerifyTimes.java').write_text(java)
            r=subprocess.run(['javac',str(d/'AudioTimeRange.java'),str(d/'VerifyTimes.java')],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.VerifyTimes'],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn('range & file names OK',r.stdout)

    def test_real_java_wave_crop_with_android_stubs(self):
        """Compile the real extractor against minimal stand-ins and execute its actual WAV slicing."""
        stubs={
        'android/content/Context.java': 'package android.content; public class Context {}',
        'android/net/Uri.java': 'package android.net; public class Uri {}',
        'android/media/MediaFormat.java': '''package android.media;
 public class MediaFormat {
 public static final String KEY_MIME="mime", KEY_DURATION="duration";
 public String getString(String k){return "audio/mp4a-latm";}
 public boolean containsKey(String k){return true;}
 public long getLong(String k){return 100000000L;}
 }''',
        'android/media/MediaExtractor.java': '''package android.media;
 import android.content.Context; import android.net.Uri; import java.nio.ByteBuffer;
 public class MediaExtractor {
 public static final int SEEK_TO_PREVIOUS_SYNC=0;
 public void setDataSource(Context c,Uri uri,Object headers){}
 public void setDataSource(String path){}
 public int getTrackCount(){return 1;}
 public MediaFormat getTrackFormat(int i){return new MediaFormat();}
 public void selectTrack(int i){}
 public void release(){}
 public int readSampleData(ByteBuffer b,int offset){return -1;}
 public long getSampleTime(){return -1;}
 public int getSampleFlags(){return 0;}
 public boolean advance(){return false;}
 public void seekTo(long time,int mode){}
 }''',
        'android/media/MediaMuxer.java': '''package android.media;
 import java.nio.ByteBuffer;
 public class MediaMuxer {
 public static class OutputFormat { public static final int MUXER_OUTPUT_MPEG_4=0;}
 public MediaMuxer(String path,int mode){}
 public int addTrack(MediaFormat f){return 0;}
 public void start(){} public void stop(){} public void release(){}
 public void writeSampleData(int i,ByteBuffer b,MediaCodec.BufferInfo info){}
 }''',
        'android/media/MediaCodec.java': '''package android.media;
 public class MediaCodec {public static class BufferInfo {
 public void set(int o,int s,long p,int flags){}
 }}''',
        'com/subtitletool/standalone/AudioExtractor.java': '''package com.subtitletool.standalone;
 import android.content.Context;import android.net.Uri;import java.io.File;import java.util.concurrent.atomic.AtomicBoolean;
 public class AudioExtractor {
 public interface Progress {void onProgress(int p);}
 public static class AudioFile{public File file;public String mime;}
 public static AudioFile extract(Context c,Uri u,File d,AtomicBoolean s,Progress p){return null;}
 }''',
        'com/subtitletool/standalone/VerifyCrop.java': r'''
package com.subtitletool.standalone;
import java.io.*; import java.util.concurrent.atomic.AtomicBoolean;
public class VerifyCrop {
 static void yes(boolean x){if(!x)throw new AssertionError();}
 static void w16(RandomAccessFile f,int v)throws Exception{f.write(v&255);f.write((v>>8)&255);}
 static void w32(RandomAccessFile f,long v)throws Exception{for(int i=0;i<4;i++)f.write((int)(v>>(8*i))&255);}
 public static void main(String[] args)throws Exception {
   File source=new File(args[0],"source.wav"),dest=new File(args[0],"clip.wav");
   try(RandomAccessFile f=new RandomAccessFile(source,"rw")){
     int bytes=4*32000;
     f.writeBytes("RIFF");w32(f,36+bytes);f.writeBytes("WAVEfmt ");w32(f,16);
     w16(f,1);w16(f,1);w32(f,16000);w32(f,32000);w16(f,2);w16(f,16);
     f.writeBytes("data");w32(f,bytes);
     for(int i=0;i<bytes/2;i++)w16(f,i%30000);
   }
   AudioClipExporter.cropWav(source,dest,1250000L,2750000L,new AtomicBoolean(false));
   AudioIntegrity.validatePcmWav(dest);
   yes(dest.length()==44+48000);
   try(RandomAccessFile f=new RandomAccessFile(dest,"r")) {
     f.seek(44);int n=(f.read()&255)|((f.read()&255)<<8);
     yes(n==20000);
     f.seek(44+47998);int last=(f.read()&255)|((f.read()&255)<<8);
     yes(last==13999);
   }
   boolean cancelled=false;
   try{AudioClipExporter.cropWav(source,dest,0,3000000L,new AtomicBoolean(true));}
   catch(InterruptedException e){cancelled=true;}
   yes(cancelled);
   System.out.println("WAV sample-accurate crop & cancellation OK");
 }
}'''
        }
        with tempfile.TemporaryDirectory() as tmp:
            for rel,body in stubs.items():
                p=Path(tmp)/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
            sources=[str(Path(tmp)/rel) for rel in stubs]
            sources += [str(PKG/'AudioClipExporter.java'),str(PKG/'AudioIntegrity.java'),str(PKG/'AudioTimeRange.java')]
            r=subprocess.run(['javac','-d',tmp,*sources],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.VerifyCrop',tmp],capture_output=True,text=True,timeout=20)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn('sample-accurate crop',r.stdout)

    def test_real_java_downloads_collision_uses_extension_safe_name(self):
        stubs={
            'android/net/Uri.java': 'package android.net; public class Uri {public final int i;public Uri(int i){this.i=i;}}',
            'android/content/ContentValues.java': '''package android.content;
import java.util.HashMap;
public class ContentValues extends HashMap<String,Object> {
 public void put(String k,String v){super.put(k,v);} public void put(String k,Integer v){super.put(k,v);}
}''',
            'android/content/ContentResolver.java': '''package android.content;
import android.net.Uri;import java.io.*;import java.util.*;
public class ContentResolver {
 public int calls=0,deletes=0,bytes=0;public final HashMap<Integer,String> names=new HashMap<>();
 public Uri insert(Uri collection,ContentValues v){
   calls++;String base=(String)v.get("name");
   names.put(calls,calls==1?base+" (2)":base);return new Uri(calls);
 }
 public OutputStream openOutputStream(Uri uri,String mode){
   return new ByteArrayOutputStream(){@Override public void close()throws IOException{
      super.close();bytes+=size();
   }};
 }
 public int update(Uri uri,ContentValues values,String x,String[] args){return 1;}
 public int delete(Uri uri,String x,String[] args){deletes++;return 1;}
 public String getName(Uri uri){return names.get(uri.i);}
}''',
            'android/content/Context.java': '''package android.content;
public class Context {public final ContentResolver resolver=new ContentResolver();
 public ContentResolver getContentResolver(){return resolver;}}''',
            'android/os/Build.java': '''package android.os;
public class Build{public static class VERSION{public static int SDK_INT=33;}}''',
            'android/os/Environment.java': '''package android.os;
public class Environment{public static final String DIRECTORY_DOWNLOADS="Download";}''',
            'android/provider/MediaStore.java': '''package android.provider;
import android.net.Uri;
public class MediaStore{public static class Downloads{
 public static final String DISPLAY_NAME="name", MIME_TYPE="mime",
 RELATIVE_PATH="path",IS_PENDING="pending";
 public static final Uri EXTERNAL_CONTENT_URI=new Uri(1000);
}}''',
            'com/subtitletool/standalone/AssExportManager.java': '''package com.subtitletool.standalone;
import android.content.ContentResolver;import android.net.Uri;
public class AssExportManager{public static String displayName(ContentResolver r,Uri uri){return r.getName(uri);}}''',
            'com/subtitletool/standalone/VerifyAudioSave.java': r'''package com.subtitletool.standalone;
import android.content.*;import java.io.*;import java.util.concurrent.atomic.AtomicBoolean;
public class VerifyAudioSave {
 static void yes(boolean x){if(!x)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
   File in=new File(args[0],"sample.m4a");
   try(FileOutputStream out=new FileOutputStream(in)){out.write(new byte[1000]);}
   Context ctx=new Context();
   AudioDownloadsManager.Result saved=AudioDownloadsManager.save(ctx,in,"clip.m4a","audio/mp4",".m4a",new AtomicBoolean(false));
   yes(saved.name.equals("clip (2).m4a"));
   yes(ctx.resolver.calls==2);yes(ctx.resolver.deletes==1);
   yes(ctx.resolver.bytes==2000);
   System.out.println("MediaStore collision retry OK");
 }
}'''
        }
        with tempfile.TemporaryDirectory() as tmp:
            for rel,body in stubs.items():
                p=Path(tmp)/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
            sources=[str(Path(tmp)/rel) for rel in stubs]+[
                str(PKG/'AudioDownloadsManager.java'),str(PKG/'AudioTimeRange.java')]
            r=subprocess.run(['javac','-d',tmp,*sources],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.VerifyAudioSave',tmp],
                             capture_output=True,text=True,timeout=20)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn('collision retry OK',r.stdout)

if __name__=='__main__':unittest.main()
