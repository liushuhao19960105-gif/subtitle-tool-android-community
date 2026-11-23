"""AssemblyAI adapter validation with real Java parser, synthetic API responses and compilation."""
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

JSON_OBJ=r'''package org.json;
import java.util.*;
public class JSONObject {
 private final Map<String,Object> map=new LinkedHashMap<>();
 public JSONObject() {}
 public JSONObject(String source) {} // syntax-only for network path (not invoked)
 public JSONObject put(String k,Object value){map.put(k,value);return this;}
 public boolean has(String k){return map.containsKey(k);}
 public boolean isNull(String k){return map.get(k)==null;}
 public int optInt(String k,int fallback){Object n=map.get(k);return n instanceof Number?((Number)n).intValue():fallback;}
 public long optLong(String k,long fallback){Object n=map.get(k);return n instanceof Number?((Number)n).longValue():fallback;}
 public double optDouble(String k,double fallback){Object n=map.get(k);return n instanceof Number?((Number)n).doubleValue():fallback;}
 public String optString(String k,String fallback){Object n=map.get(k);return n==null?fallback:n.toString();}
 public boolean optBoolean(String k,boolean fallback){Object n=map.get(k);return n instanceof Boolean?(Boolean)n:fallback;}
 public JSONObject getJSONObject(String k){return (JSONObject)map.get(k);}
 public JSONArray getJSONArray(String k){return (JSONArray)map.get(k);}
 public JSONArray optJSONArray(String k){return (JSONArray)map.get(k);}
 public String getString(String k){return (String)map.get(k);}
 public int getInt(String k){return ((Number)map.get(k)).intValue();}
 public long getLong(String k){return ((Number)map.get(k)).longValue();}
 public String toString(){return map.toString();}
}
'''
JSON_ARR=r'''package org.json;
import java.util.*;
public class JSONArray {
 private final List<Object> list=new ArrayList<>();
 public JSONArray put(Object value){list.add(value);return this;}
 public int length(){return list.size();}
 public JSONObject getJSONObject(int i){return (JSONObject)list.get(i);}
 public JSONObject optJSONObject(int i){Object v=i>=0&&i<list.size()?list.get(i):null;return v instanceof JSONObject?(JSONObject)v:null;}
}
'''
ADAPTER=r'''package com.subtitletool.standalone;
import java.io.File;
class AudioExtractor {public static class AudioFile {File file;String mime;}}
class AudioIntegrity {static void validatePcmWav(File file){} }
class CloudPipeline {public interface Callback {void update(int p,String s);} }
'''
RUNNER=r'''package com.subtitletool.standalone;
import java.util.*;
import org.json.*;
public class CheckAssemblyAI {
 static void ok(boolean v,String s){if(!v)throw new AssertionError(s);}
 static JSONObject word(long a,long b,String t,String speaker,double conf){
   JSONObject w=new JSONObject().put("start",a).put("end",b).put("text",t).put("confidence",conf);
   if(speaker!=null)w.put("speaker",speaker);
   return w;
 }
 public static void main(String[] args)throws Exception {
   ok(AsrProviders.byIndex(0).id.equals("deepgram"),"legacy model default");
   ok(AsrProviders.byIndex(1).keySlot.equals("assemblyai"),"new key slot");
   ok(AsrProviders.byIndex(99).id.equals("deepgram"),"invalid index fallback");
   JSONObject first=new JSONObject().put("speaker","A").put("words",new JSONArray()
       .put(word(1200,1600,"Hello,","A",0.99))
       .put(word(1640,2850,"world.","A",0.98)));
   JSONObject second=new JSONObject().put("speaker","B").put("words",new JSONArray()
       .put(word(3210,3600,"What?","B",0.92)));
   JSONObject full=new JSONObject().put("utterances",new JSONArray().put(first).put(second));
   List<SubtitleData.Cue> cues=SubtitleData.fromAssemblyAI(full);
   ok(cues.size()==2,"two speaker cues");
   ok(cues.get(0).startMs==1200 && cues.get(0).endMs==2850,"milliseconds, not seconds");
   ok(cues.get(0).speaker==0 && cues.get(1).speaker==1,"speaker A/B stable IDs");
   ok(cues.get(1).en.equals("What?"),"word text");
   ok(cues.get(1).startMs==3210,"second offset");
   List<SubtitleData.Cue> fallback=SubtitleData.fromAssemblyAI(new JSONObject()
      .put("words",new JSONArray().put(word(9023,9445,"Yep.",null,0.93))));
   ok(fallback.size()==1,"fallback top-level words");
   ok(fallback.get(0).speaker==-1 && fallback.get(0).uncertain,"unknown speaker not guessed");
   List<SubtitleData.Cue> single=SubtitleData.fromAssemblyAI(new JSONObject()
      .put("utterances",new JSONArray().put(new JSONObject().put("speaker","D")
      .put("start",10000).put("end",12000).put("text","No per-word timing."))));
   ok(single.size()==1 && single.get(0).en.equals("No per-word timing."),"fallback utterance");
   boolean fails=false;
   try {SubtitleData.fromAssemblyAI(new JSONObject());}catch(IllegalArgumentException e){fails=true;}
   ok(fails,"empty transcript rejection");
   System.out.println("AssemblyAI real Java word/diarization/timing adapter OK");
 }
}
'''

class AssemblyAIV16Tests(unittest.TestCase):
    def test_api_contract(self):
        s=(PKG/'AssemblyAiTranscriber.java').read_text()
        for token in ('https://api.assemblyai.com/v2/', '"upload"', '"transcript"',
                      '"speech_models"','"assemblyai-stt-3-7"',
                      '"speaker_labels",true','"language_code","en"',
                      '"Authorization",key','"application/octet-stream"',
                      '"completed"','"queued"','"processing"','"error"',
                      'MAX_POLL_MS','AudioIntegrity.validatePcmWav(file.file)',
                      'void cancel()','speech_model_used'):
            self.assertIn(token,s)
        self.assertNotIn('new URL(audioUrl)',s)  # direct video/remote URL fetching disallowed
        self.assertNotIn('setRequestProperty("Authorization","Bearer "',s)
    def test_ui_and_old_key_migration(self):
        ui=(PKG/'MainActivity.java').read_text()
        secure=(PKG/'SecureKeys.java').read_text()
        self.assertIn('AsrProviders.labels()',ui)
        self.assertIn('prefs.getInt("asr_provider",0)',ui)
        self.assertIn('secure.load(asr.keySlot)',ui)
        self.assertIn('AssemblyAiTranscriber(stop)',ui)
        self.assertIn('pipeline.transcribe(audio,asrSecret',ui)
        self.assertIn('assemblyTranscriber.cancel()',ui)
        self.assertIn('deepgram|assemblyai|',secure)
        self.assertIn('AssWriter.render(cues)',ui)
        self.assertNotIn('下载彩色中文字幕',ui)
    def test_javac_real_parser_and_adapter(self):
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);pkg=base/'com/subtitletool/standalone';pkg.mkdir(parents=True)
            js=base/'org/json';js.mkdir(parents=True)
            (js/'JSONObject.java').write_text(JSON_OBJ)
            (js/'JSONArray.java').write_text(JSON_ARR)
            (pkg/'TestAdapter.java').write_text(ADAPTER)
            (pkg/'CheckAssemblyAI.java').write_text(RUNNER)
            for name in ('AsrProviders.java','AssemblyAiTranscriber.java','SubtitleData.java'):
                (pkg/name).write_bytes((PKG/name).read_bytes())
            p=subprocess.run(['javac']+[str(x) for x in base.rglob('*.java')],capture_output=True,text=True,timeout=45)
            self.assertEqual(p.returncode,0,p.stderr)
            p=subprocess.run(['java','-cp',str(base),'com.subtitletool.standalone.CheckAssemblyAI'],
                             capture_output=True,text=True,timeout=30)
            self.assertEqual(p.returncode,0,p.stderr)
            self.assertIn('adapter OK',p.stdout)

if __name__=='__main__':unittest.main()
