"""Compile actual CloudPipeline.java against a minimal Android-free JSON/test adapter."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

JSON_OBJECT='''package org.json;
import java.util.*;
public class JSONObject {
 private final Map<String,Object> items=new LinkedHashMap<>();
 public JSONObject(){} public JSONObject(String s){};
 public JSONObject put(String key,Object value){items.put(key,value);return this;}
 public JSONArray optJSONArray(String key){return (JSONArray) items.get(key);}
 public JSONObject getJSONObject(String key){return (JSONObject)items.get(key);}
 public String optString(String key,String fallback){return items.containsKey(key)?String.valueOf(items.get(key)):fallback;}
 public String getString(String key){return String.valueOf(items.get(key));}
 public int getInt(String key){return ((Number)items.get(key)).intValue();}
 public boolean has(String key){return items.containsKey(key);}
 public String toString(){return items.toString();}
}
'''
JSON_ARRAY='''package org.json;
import java.util.*;
public class JSONArray {
 private final List<Object> items=new ArrayList<>();
 public JSONArray put(Object o){items.add(o);return this;}
 public int length(){return items.size();}
 public JSONObject getJSONObject(int index){return (JSONObject)items.get(index);}
 public JSONObject optJSONObject(int index){return (JSONObject)items.get(index);}
}
'''
ADAPTER='''package com.subtitletool.standalone;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
class AudioExtractor {static class AudioFile {File file;String mime;}}
class AudioIntegrity {static void validatePcmWav(File f) {}}
class ProviderPresets {static String normalizeEndpoint(String x){return x;}}
class SubtitleData {
 static class Cue {
  int id,speaker;long startMs,endMs;String en,zh="";
  Cue(int id,long a,long b,String en,int speaker){
   this.id=id;startMs=a;endMs=b;this.en=en;this.speaker=speaker;
  }
 }
 static List<Cue> fromDeepgram(JSONObject d){return new ArrayList<>();}
}
class TranslationCheckpoint {
 int firstDone,reviewDone,deepDone;
 void save(android.content.Context context) {}
}
class UsageLedger {
 void record(JSONObject response) {}
}
enum TranslationMode {QUICK,STANDARD,HIGH}

'''
ANDROID_CONTEXT='''package android.content;
public class Context {}
'''
CHECK='''package com.subtitletool.standalone;
import java.util.*;
import java.lang.reflect.*;
import org.json.*;
public class CompileCheck {
 static SubtitleData.Cue c(int id,int sp,String en){return new SubtitleData.Cue(id,id*1000,id*1000+850,en,sp);}
 static void ok(boolean truth,String message){if(!truth)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  List<SubtitleData.Cue> cues=new ArrayList<>();
  for(int i=1;i<=31;i++)cues.add(c(i,i<=16?2:3,i==16?"finished.":"part"));
  Method batch=CloudPipeline.class.getDeclaredMethod("batchRanges",List.class,int.class);
  batch.setAccessible(true);
  @SuppressWarnings("unchecked") List<int[]> ranges=(List<int[]>) batch.invoke(null,cues,15);
  int prev=0;
  for(int[] range:ranges){ok(range[0]==prev,"range gap");ok(range[1]>range[0],"empty");prev=range[1];}
  ok(prev==cues.size(),"some cues skipped");
  Method groups=CloudPipeline.class.getDeclaredMethod("sentenceGroups",List.class,int.class,int.class);
  groups.setAccessible(true);
  JSONArray result=(JSONArray)groups.invoke(null,cues,0,16);
  ok(result.length()>0,"no sentence groups");
  System.out.println("CloudPipeline.java syntax and context grouping OK");
 }
}
'''

class CloudCompileV15(unittest.TestCase):
 def test_cloud_syntax_and_context_batches(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);pkg=root/'com/subtitletool/standalone';pkg.mkdir(parents=True)
   js=root/'org/json';js.mkdir(parents=True)
   (pkg/'CloudPipeline.java').write_bytes((PKG/'CloudPipeline.java').read_bytes())
   (pkg/'Adapter.java').write_text(ADAPTER)
   android=root/'android/content';android.mkdir(parents=True)
   (android/'Context.java').write_text(ANDROID_CONTEXT)
   (pkg/'CompileCheck.java').write_text(CHECK)
   (js/'JSONObject.java').write_text(JSON_OBJECT)
   (js/'JSONArray.java').write_text(JSON_ARRAY)
   r=subprocess.run(['javac']+[str(f) for f in root.rglob('*.java')],capture_output=True,text=True,timeout=30)
   self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run(['java','-cp',str(root),'com.subtitletool.standalone.CompileCheck'],capture_output=True,text=True,timeout=30)
   self.assertEqual(r.returncode,0,r.stderr)
   self.assertIn('CloudPipeline.java syntax',r.stdout)

if __name__=='__main__':unittest.main()
