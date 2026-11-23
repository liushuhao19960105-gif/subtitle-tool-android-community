package com.subtitletool.standalone;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes real .ass files into public Download without a MIME-driven save picker. */
public final class AssExportManager {
    private AssExportManager() {}
    public static final class Result {
        public final Uri uri;
        public final String name;
        Result(Uri uri,String name){this.uri=uri;this.name=name;}
    }

    public static String displayName(ContentResolver resolver,Uri uri) {
        try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst()) return c.getString(0);
        }catch(Exception ignored) { }
        return "";
    }

    /** Android 10+; app-owned files in MediaStore.Downloads need no storage permission.
     * A few OEM providers append " (2)" AFTER .ass on collision. Retry with
     * " (2).ass" instead, and preserve the completed file on a last-resort error.
     */
    public static Result saveToDownloads(Context context,File file,String proposedName) throws IOException {
        if(Build.VERSION.SDK_INT<29)throw new IOException("当前安卓版本需使用系统文件保存窗口");
        if(file==null || !file.isFile() || file.length()<30)throw new IOException("没有可导出的字幕文件");
        String expected=AssExportNaming.normalized(proposedName);
        ContentResolver resolver=context.getContentResolver();
        final int maxAttempts=12;
        for(int attempt=1;attempt<=maxAttempts;attempt++) {
            String candidate=AssExportNaming.numberedCopy(expected,attempt);
            Uri uri=null;
            boolean retain=false;
            try {
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,candidate);
                values.put(MediaStore.Downloads.MIME_TYPE,AssExportNaming.MIME_ASS);
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");
                values.put(MediaStore.Downloads.IS_PENDING,1);
                uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IOException("无法创建系统下载文件");
                try(FileInputStream in=new FileInputStream(file);
                    OutputStream out=resolver.openOutputStream(uri,"w")) {
                    if(out==null)throw new IOException("系统下载文件无法写入");
                    byte[] buffer=new byte[32768];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    out.flush();
                }
                ContentValues finish=new ContentValues();
                finish.put(MediaStore.Downloads.IS_PENDING,0);
                if(resolver.update(uri,finish,null,null)<1)
                    throw new IOException("无法完成下载文件写入");
                String actual=displayName(resolver,uri);
                if(!AssExportNaming.hasAssExtension(actual)) {
                    // A rename may fix a vendor that changed the file extension.
                    try {
                        ContentValues rename=new ContentValues();
                        rename.put(MediaStore.Downloads.DISPLAY_NAME,candidate);
                        rename.put(MediaStore.Downloads.MIME_TYPE,AssExportNaming.MIME_ASS);
                        resolver.update(uri,rename,null,null);
                    } catch(Exception ignored) { /* Retry under a collision-free name. */ }
                    actual=displayName(resolver,uri);
                }
                if(AssExportNaming.hasAssExtension(actual)) {
                    retain=true;
                    return new Result(uri,actual);
                }
                if(attempt==maxAttempts) {
                    // A complete exported file must never be deleted merely because
                    // an OEM media provider re-labelled its extension.
                    retain=true;
                    return new Result(uri,actual.isEmpty()?"系统未返回文件名":actual);
                }
                // The file has fully written but its extension is unsuitable.
                // Remove only this newly created retry candidate and try again.
            } catch(IOException ex) { throw ex;
            } catch(Exception ex) { throw new IOException("导出 ASS 失败："+ex.getMessage(),ex);
            } finally {
                if(uri!=null && !retain)
                    try { resolver.delete(uri,null,null); }catch(Exception ignored) { }
            }
        }
        throw new IOException("系统无法创建 ASS 文件");
    }
}
