package com.subtitletool.standalone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public Download output without requesting broad storage access. */
public final class AudioDownloadsManager {
    private AudioDownloadsManager() {}
    public static final class Result {
        public final String name;
        public final Uri uri;
        Result(String name,Uri uri){this.name=name;this.uri=uri;}
    }
    public static Result save(Context ctx,File audio,String wanted,String mime,String extension,AtomicBoolean stop)throws IOException {
        if(Build.VERSION.SDK_INT<29)throw new IOException("旧版安卓需使用系统保存窗口");
        if(audio==null || !audio.isFile() || audio.length()<44)throw new IOException("没有可保存的音频");
        ContentResolver resolver=ctx.getContentResolver();
        String expectedExtension=extension.toLowerCase(Locale.ROOT);
        for(int i=1;i<=10;i++) {
            String candidate=AudioTimeRange.numbered(wanted,i);
            Uri out=null;boolean retain=false;
            try {
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,candidate);
                values.put(MediaStore.Downloads.MIME_TYPE,mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");
                values.put(MediaStore.Downloads.IS_PENDING,1);
                out=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(out==null)throw new IOException("无法创建音频文件");
                try(FileInputStream input=new FileInputStream(audio);
                    OutputStream output=resolver.openOutputStream(out,"w")) {
                    if(output==null)throw new IOException("无法写入下载文件夹");
                    byte[] buffer=new byte[65536];int n;
                    while((n=input.read(buffer))!=-1){
                        if(stop.get())throw new java.io.InterruptedIOException("音频保存已取消");
                        output.write(buffer,0,n);
                    }
                    output.flush();
                }
                ContentValues finished=new ContentValues();finished.put(MediaStore.Downloads.IS_PENDING,0);
                if(resolver.update(out,finished,null,null)<1)throw new IOException("系统未能完成音频保存");
                String actual=AssExportManager.displayName(resolver,out);
                if(actual==null)actual="";
                if(!actual.toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
                    ContentValues rename=new ContentValues();
                    rename.put(MediaStore.Downloads.DISPLAY_NAME,candidate);
                    rename.put(MediaStore.Downloads.MIME_TYPE,mime);
                    try{resolver.update(out,rename,null,null);}catch(Exception ignored){}
                    actual=AssExportManager.displayName(resolver,out);
                    if(actual==null)actual="";
                }
                if(actual.toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
                    retain=true;return new Result(actual,out);
                }
                if(i==10) {
                    // Never destroy fully written audio on a noncompliant device.
                    retain=true;return new Result(actual.isEmpty()?candidate:actual,out);
                }
            }catch(IOException e){throw e;}
            catch(Exception e){throw new IOException("保存音频失败："+e.getMessage(),e);}
            finally{if(out!=null && !retain)try{resolver.delete(out,null,null);}catch(Exception ignored){}}
        }
        throw new IOException("无法保存音频");
    }
}
