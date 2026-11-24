"""Run real Java glossary matching; confirm contextual hints, boundaries and non-replacement."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/"app/src/main/java/com/subtitletool/standalone/DialogueLexicon.java"
ASSET=ROOT/"app/src/main/assets/dialogue_lexicon_v191.tsv"
MAIN=(ROOT/"app/src/main/java/com/subtitletool/standalone/MainActivity.java").read_text(encoding="utf-8")
PIPELINE=(ROOT/"app/src/main/java/com/subtitletool/standalone/CloudPipeline.java").read_text(encoding="utf-8")

JSON_OBJECT=r"""package org.json;
import java.util.LinkedHashMap;
import java.util.Map;
public class JSONObject {
 public final Map<String,Object> data=new LinkedHashMap<>();
 public JSONObject put(String key,Object value){data.put(key,value);return this;}
 public String toString(){return data.toString();}
}
"""
JSON_ARRAY=r"""package org.json;
import java.util.ArrayList;
import java.util.List;
public class JSONArray {
 public final List<Object> values=new ArrayList<>();
 public JSONArray put(Object value){values.add(value);return this;}
 public int length(){return values.size();}
 public String toString(){return values.toString();}
}
"""
CUE=r"""package com.subtitletool.standalone;
final class SubtitleData {
 static final class Cue { final String en; Cue(String text){en=text;} }
}
"""
CHECK=r"""package com.subtitletool.standalone;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class LexiconTest {
 static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
 static String hints(DialogueLexicon lexicon,String english)throws Exception {
  return lexicon.hints(Collections.singletonList(new SubtitleData.Cue(english)),0,1).toString();
 }
 public static void main(String[] args)throws Exception {
  DialogueLexicon glossary;
  try(InputStreamReader input=new InputStreamReader(new FileInputStream(args[0]),StandardCharsets.UTF_8)){
    glossary=DialogueLexicon.load(input);
  }
  ok(glossary.size()>=100,"missing curated entries");
  ok(hints(glossary,"Come here!").contains("come here"),"specific phrase not matched");
  ok(!hints(glossary,"We become friends.").contains("en=come"),"substring false positive");
  ok(!hints(glossary,"The cocktail is delicious.").contains("en=cock"),"cocktail false positive");
  ok(hints(glossary,"Get off the bus!").contains("get off the bus"),"ordinary get-off meaning");
  ok(hints(glossary,"They get off on it.").contains("get off on"),"contextual alternative");
  ok(hints(glossary,"Oh, ah, [moaning]!").contains("moaning"),"sound-label support");
  ok(hints(glossary,"Holy shit!").contains("holy shit"),"multiword swear priority");
  ok(glossary.hints(Collections.singletonList(new SubtitleData.Cue("Oh!")),0,1).length()<=10,
     "unbounded hints");
  ok(glossary.hints(Collections.singletonList(new SubtitleData.Cue("Ah!")),1,1).length()==0,
     "out-of-range should be empty");
  try {DialogueLexicon.load(new StringReader("wrong header\n"));throw new AssertionError("invalid header accepted");}
  catch(IOException expected){}
  System.out.println("Context-aware glossary Java regression passed");
 }
}
"""
class DialogueLexiconTests(unittest.TestCase):
    def test_110_curated_entries_are_well_formed(self):
        lines=ASSET.read_text(encoding="utf-8").splitlines()
        self.assertEqual(lines[0],"id\ttrigger\tcategory\tcontext_zh\ttranslation_zh\tavoid_zh")
        self.assertGreaterEqual(len(lines)-1,100)
        seen=set()
        for line in lines[1:]:
            fields=line.split("\t")
            self.assertEqual(len(fields),6)
            self.assertTrue(all(fields))
            self.assertNotIn(fields[0],seen)
            seen.add(fields[0])
        for phrase in ("get off the bus","get off on","come here","daddy",
                       "bitch","fucking","moaning","breathing heavily","break up"):
            self.assertIn("\t"+phrase+"\t",ASSET.read_text(encoding="utf-8"))

    def test_java_matching_real_asset_without_android(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            pkg=root/"com/subtitletool/standalone";pkg.mkdir(parents=True)
            js=root/"org/json";js.mkdir(parents=True)
            (pkg/"DialogueLexicon.java").write_bytes(SOURCE.read_bytes())
            (pkg/"SubtitleData.java").write_text(CUE,encoding="utf-8")
            (pkg/"LexiconTest.java").write_text(CHECK,encoding="utf-8")
            (js/"JSONObject.java").write_text(JSON_OBJECT,encoding="utf-8")
            (js/"JSONArray.java").write_text(JSON_ARRAY,encoding="utf-8")
            build=subprocess.run(["javac"]+[str(x) for x in root.rglob("*.java")],
                                 capture_output=True,text=True,timeout=30)
            self.assertEqual(build.returncode,0,build.stderr)
            run=subprocess.run(["java","-cp",str(root),
                 "com.subtitletool.standalone.LexiconTest",str(ASSET)],
                 capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("glossary Java regression passed",run.stdout)

    def test_glossary_integrates_only_as_optional_context_hints(self):
        self.assertIn('getAssets().open("dialogue_lexicon_v191.tsv")',MAIN)
        self.assertIn("pipeline.setDialogueLexicon(DialogueLexicon.load(reader))",MAIN)
        self.assertEqual(PIPELINE.count("addDialogueHints(input,cues,from,to);"),3)
        self.assertIn('input.put("dialogue_lexicon_hints",glossary.hints(cues,from,to))',PIPELINE)
        self.assertIn("不是替换规则",PIPELINE)
        self.assertIn("不得强制选用",PIPELINE)
        self.assertNotIn("replaceAll(english",PIPELINE)
        self.assertNotIn("cue.zh=hint",PIPELINE)

if __name__=="__main__":unittest.main()
