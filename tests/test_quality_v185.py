"""Compile and run V1.8.5 quality checks with an isolated pure Java cue adapter."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/"app/src/main/java/com/subtitletool/standalone"
ADAPTER="""package com.subtitletool.standalone;
class SubtitleData {
 static class Cue {
  int id,speaker; long startMs,endMs;boolean uncertain;
  String en,zh="";
  Cue(int id,long a,long b,String en,int speaker,String zh){
   this.id=id;startMs=a;endMs=b;this.en=en;this.speaker=speaker;this.zh=zh;
  }
 }
}
"""
CHECK=r"""package com.subtitletool.standalone;
import java.util.*;
public class TestQuality {
 static void ok(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static SubtitleData.Cue cue(int id,long start,long end,String en,String zh){
  return new SubtitleData.Cue(id,start,end,en,1,zh);
 }
 public static void main(String[] args){
  List<SubtitleData.Cue> items=new ArrayList<>();
  items.add(cue(1,0,3800,"We should recheck the entire conversation.",
      "我们今天应该重新检查这整段对白的逻辑，然后再重新安排字幕的换行位置。"));
  items.add(cue(2,4000,4200,"I have to say this right now.","这句话真的太长了根本来不及在这个时间看清楚"));
  items.add(cue(3,5000,6000,"This is not a complete sentence.","了。"));
  items.add(cue(4,6200,6800,"Ah","啊啊啊啊啊啊"));
  items.add(cue(5,6850,7500,"Oh","啊啊啊啊啊啊"));
  items.add(cue(6,8200,8800,"She has already departed.","我现在马上出发"));
  items.add(cue(7,8900,9400,"They are leaving already.","我现在马上出发"));
  items.add(cue(8,9600,9900,"Panting.","（喘息）……（呻吟）"));
  items.add(cue(9,10000,10300,"Gasping.","（喘息）……（呻吟）"));
  SubtitleQuality.Report report=SubtitleQuality.inspect(items);
  ok(report.findings.size()>=2,"expected reading speed and orphan particle warnings");
  ok(!report.summary().contains("API"),"reports must not expose credentials");
  for(SubtitleQuality.Finding issue:report.findings)
      ok(issue.cueId!=5,"real repeated vocalizations must be preserved");
  boolean differingEnglishDuplicateFlagged=false;
  for(SubtitleQuality.Finding issue:report.findings){
      if(issue.cueId==7 && issue.concern.contains("相邻字幕译文相同"))differingEnglishDuplicateFlagged=true;
      ok(issue.cueId!=9 || !issue.concern.contains("相邻字幕译文相同"),
          "grouped vocalizations should not be flagged as redundant");
  }
  ok(differingEnglishDuplicateFlagged,"distinct English lines sharing Chinese need review");
  // Flag likely lost meaning but do not change any translated characters.
  List<SubtitleData.Cue> semantic=new ArrayList<>();
  semantic.add(cue(10,11000,13000,"I don't want to go.","我现在想走"));
  semantic.add(cue(11,13500,15000,"We need 12 minutes.","我们还需要时间"));
  semantic.add(cue(12,15500,17500,"I do not want to go.","我不想走"));
  semantic.add(cue(13,17800,18700,"We need 2 minutes.","我们还需要两分钟"));
  SubtitleQuality.Report clues=SubtitleQuality.inspect(semantic);
  boolean missingNegation=false,missingNumber=false;
  for(SubtitleQuality.Finding clue:clues.findings){
      if(clue.cueId==10 && clue.concern.contains("否定"))missingNegation=true;
      if(clue.cueId==11 && clue.concern.contains("数字"))missingNumber=true;
      ok(clue.cueId!=12 && clue.cueId!=13,"correctly translated negation/number must not be flagged");
  }
  ok(missingNegation && missingNumber,"missing negation and numeric clues are advisory");
  ok(semantic.get(0).zh.equals("我现在想走"),"quality checks must never rewrite a translation");
  String ass=AssWriter.render(Collections.singletonList(items.get(0)));
  ok(ass.contains("\\N"),"long Chinese sentence needs a balanced display break");
  ok(ass.contains("我们今天应该重新检查"),"original content must be retained");
  String noPunct=AssWriter.render(Collections.singletonList(
      cue(8,0,5000,"A sentence.","这是一段没有任何标点但是需要合理分行的中文测试内容它不能被直接删除")));
  ok(noPunct.contains("\\N"),"punctuation-free Chinese needs a graceful wrap");
  System.out.println("V1.8.5 quality and Chinese wrap checks OK");
 }
}
"""
class QualityV185(unittest.TestCase):
    def test_offline_checks_and_non_destructive_line_breaking(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            pkg=root/"com/subtitletool/standalone"
            pkg.mkdir(parents=True)
            for name in ("AssWriter.java","SubtitleQuality.java"):
                (pkg/name).write_bytes((PKG/name).read_bytes())
            (pkg/"SubtitleData.java").write_text(ADAPTER,encoding="utf-8")
            (pkg/"TestQuality.java").write_text(CHECK,encoding="utf-8")
            build=subprocess.run(["javac"]+[str(x) for x in root.rglob("*.java")],
                                 capture_output=True,text=True,timeout=30)
            self.assertEqual(build.returncode,0,build.stderr)
            run=subprocess.run(["java","-cp",str(root),
                                "com.subtitletool.standalone.TestQuality"],
                               capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("quality and Chinese wrap checks OK",run.stdout)

    def test_report_stays_private_and_does_not_block_subtitle_export(self):
        activity=(PKG/"MainActivity.java").read_text(encoding="utf-8")
        self.assertIn("SubtitleQuality.inspect(cues)",activity)
        self.assertIn('getFilesDir(),"last_quality_report.txt"',activity)
        self.assertIn("qualityBtn.setVisibility(View.VISIBLE)",activity)
        self.assertIn("Diagnostic report is advisory",activity)
        source=(PKG/"SubtitleQuality.java").read_text(encoding="utf-8")
        self.assertNotIn("HttpURLConnection",source)
        self.assertNotIn("API Key",source)

if __name__=="__main__":unittest.main()
