from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

STUBS = {
 'android/net/Uri.java': 'package android.net; public class Uri {public final int id; public Uri(int i){id=i;}}',
 'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=33;}}',
 'android/os/Environment.java': 'package android.os; public class Environment {public static final String DIRECTORY_DOWNLOADS="Download";}',
 'android/provider/OpenableColumns.java': 'package android.provider; public class OpenableColumns {public static final String DISPLAY_NAME="_display_name";}',
 'android/provider/MediaStore.java': 'package android.provider; public class MediaStore {public static class Downloads {public static final String DISPLAY_NAME="display_name", MIME_TYPE="mime_type", RELATIVE_PATH="relative_path", IS_PENDING="is_pending"; public static final android.net.Uri EXTERNAL_CONTENT_URI=new android.net.Uri(-1);}}',
 'android/database/Cursor.java': 'package android.database; public interface Cursor extends java.lang.AutoCloseable {boolean moveToFirst();String getString(int i);void close();}',
 'android/content/ContentValues.java': '''package android.content; public class ContentValues extends java.util.HashMap<String,Object> {
     public void put(String key,String value){super.put(key,value);}
     public void put(String key,Integer value){super.put(key,value);}
     }''',
 'android/content/ContentResolver.java': '''package android.content; public abstract class ContentResolver {
     public abstract android.net.Uri insert(android.net.Uri uri, ContentValues values);
     public abstract java.io.OutputStream openOutputStream(android.net.Uri uri, String mode);
     public abstract int update(android.net.Uri uri, ContentValues values, String where,String[] params);
     public abstract android.database.Cursor query(android.net.Uri uri,String[] cols,String sel,String[] args,String sort);
     public abstract int delete(android.net.Uri uri,String where,String[] args);
     }''',
 'android/content/Context.java': 'package android.content; public abstract class Context {public abstract ContentResolver getContentResolver();}',
 'com/subtitletool/standalone/TestMediaStoreV171.java': r'''package com.subtitletool.standalone;
import android.content.*;import android.net.*;import android.database.*;
import java.io.*;import java.util.*;
public final class TestMediaStoreV171 {
  static class FakeResolver extends ContentResolver {
    final Map<Integer,String> names=new HashMap<>();
    final Map<Integer,ByteArrayOutputStream> bytes=new HashMap<>();
    final Set<String> existing=new HashSet<>();
    int next=0,deleted=0;
    private String distinct(String wanted,Integer own){
      boolean duplicate=existing.contains(wanted);
      for(var e:names.entrySet())if(!e.getKey().equals(own) && e.getValue().equals(wanted))duplicate=true;
      return duplicate?wanted+" (2)":wanted;
    }
    public Uri insert(Uri uri,ContentValues v){
      int id=++next;
      String wanted=(String)v.get("display_name");
      names.put(id,distinct(wanted,null));bytes.put(id,new ByteArrayOutputStream());return new Uri(id);
    }
    public OutputStream openOutputStream(Uri u,String mode){return bytes.get(u.id);}
    public int update(Uri u,ContentValues v,String where,String[] params){
      if(v.containsKey("display_name"))names.put(u.id,distinct((String)v.get("display_name"),u.id));
      return 1;
    }
    public Cursor query(Uri u,String[] cols,String sel,String[] args,String sort){
      String name=names.get(u.id);return new Cursor(){public boolean moveToFirst(){return name!=null;}public String getString(int i){return name;}public void close(){}};
    }
    public int delete(Uri u,String where,String[] args){deleted++;names.remove(u.id);bytes.remove(u.id);return 1;}
  }
  static class FakeContext extends Context{
    final FakeResolver r;FakeContext(FakeResolver r){this.r=r;}
    public ContentResolver getContentResolver(){return r;}
  }
  static void require(boolean b,String msg){if(!b)throw new AssertionError(msg);}
  public static void main(String[] args)throws Exception{
    File f=File.createTempFile("source",".ass");f.deleteOnExit();
    try(FileOutputStream out=new FileOutputStream(f)){out.write("[Script Info]\n[V4+ Styles]\n[Events]\nDialogue: 0".getBytes("UTF-8"));}
    FakeResolver a=new FakeResolver();a.existing.add("video.zh.ass");
    AssExportManager.Result x=AssExportManager.saveToDownloads(new FakeContext(a),f,"video.zh.ass");
    require("video.zh (2).ass".equals(x.name),"wrong retry name: "+x.name);
    require(a.deleted==1,"first incorrect candidate should be removed");
    require(a.names.size()==1,"exactly one final saved file");
    require(a.bytes.get(x.uri.id).size()==f.length(),"content must be saved");

    FakeResolver b=new FakeResolver();
    for(int i=1;i<=12;i++)b.existing.add(AssExportNaming.numberedCopy("video.zh.ass",i));
    AssExportManager.Result y=AssExportManager.saveToDownloads(new FakeContext(b),f,"video.zh.ass");
    require(y.name.endsWith(".ass (2)"),"fallback name expected: "+y.name);
    require(b.names.containsKey(y.uri.id),"must retain last completed file despite wrong suffix");
    require(b.bytes.get(y.uri.id).size()==f.length(),"fallback content must remain intact");
    require(b.deleted==11,"should discard only retries, not last completed file");
    System.out.println("MediaStore collision handling OK");
  }
}''',
}

class RealAndroidExporterSimulation(unittest.TestCase):
    def test_media_store_collision_and_last_resort_preservation(self):
        with tempfile.TemporaryDirectory() as t:
            basedir=Path(t)
            for name,src in STUBS.items():
                file=basedir/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(src)
            java_files=[str(f) for f in basedir.rglob('*.java')]
            java_files += [str(PKG/'AssExportNaming.java'),str(PKG/'AssExportManager.java')]
            comp=subprocess.run(['javac','-d',str(basedir),*java_files],capture_output=True,text=True,timeout=30)
            self.assertEqual(comp.returncode,0,comp.stderr)
            run=subprocess.run(['java','-cp',str(basedir),'com.subtitletool.standalone.TestMediaStoreV171'],capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn('MediaStore collision handling OK',run.stdout)

if __name__=='__main__':unittest.main()
