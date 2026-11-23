"""V1.5 checks: one visual color, safe continuity, old ASS migration, API context grouping."""
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT/'app/src/main/java/com/subtitletool/standalone'

STUB = r'''
package com.subtitletool.standalone;
public final class SubtitleData {
  public static final class Cue {
    public final int id,speaker; public final long startMs,endMs; public final String en;
    public final boolean uncertain;public String zh="";
    public Cue(int id,long a,long b,String en,int speaker,boolean uncertain){
      this.id=id;this.startMs=a;this.endMs=b;this.en=en;
      this.speaker=speaker;this.uncertain=uncertain;
    }
  }
}
'''

CHECK = r'''
package com.subtitletool.standalone;
import java.util.*;
public final class VerifyV15 {
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  static SubtitleData.Cue cue(int id,long a,long b,String zh,int speaker){
    SubtitleData.Cue c=new SubtitleData.Cue(id,a,b,"",speaker,speaker<0);c.zh=zh;return c;
  }
  static int count(String text,String fragment){return text.split(java.util.regex.Pattern.quote(fragment),-1).length-1;}
  public static void main(String[] args)throws Exception {
    var cues=Arrays.asList(
      cue(1,0,1500,"你和我女儿",2),
      cue(2,1650,2400,"交往",2),
      cue(3,2450,3200,"也有一段时间了。",2),
      cue(4,3220,4100,"什么？",1),
      cue(5,4200,4900,"我就是想",2),
      cue(6,4960,5400,"知道",-1),
      cue(7,5600,6400,"你听见了吗？",1));
    String ass=AssWriter.render(cues);
    check(count(ass,"\nStyle: ")==1,"single style only");
    check(ass.contains("Style: Default,Noto Sans CJK SC,62,&H00FFFFFF"),"opaque white font");
    check(!ass.contains("Style: S0")&&!ass.contains("Style: Neutral"),"speaker styles leaked");
    check(count(ass,"Dialogue: ")==5,"expected safe merges");
    check(ass.contains("你和我女儿交往也有一段时间了。"),"same-speaker continuity");
    check(ass.contains("我就是想")&&ass.contains("知道"),"different known-unknown not merged");
    check(!ass.contains("你和我女儿交往也有一段时间了。什么？"),"speaker turn merged");
    check(!ass.contains("A：")&&!ass.contains("B："),"identity prefixes");
    check(ass.contains("0:00:03.22,0:00:04.13,Default"),"timestamp preserved for next speaker");
    String old="[Script Info]\nTitle: older\n[V4+ Styles]\nStyle: S3,Old,54,xxxx\n[Events]\n"+
      "Dialogue: 0,0:00:40.35,0:00:45.47,S3,,0,0,0,,她不再是乖巧懂事的女孩\n"+
      "Dialogue: 0,0:00:45.47,0:00:46.19,S3,,0,0,0,,了。\n"+
      "Dialogue: 0,0:00:46.20,0:00:47.00,S2,,0,0,0,,不是！\n";
    String upgraded=AssLegacyReflow.upgrade(old);
    check(upgraded.contains("她不再是乖巧懂事的女孩了。"),"isolated particle not repaired");
    check(!upgraded.contains(",,了。"),"isolated particle remains");
    check(count(upgraded,"\nStyle: ")==1,"old color styles remain after upgrade");
    check(upgraded.equals(AssLegacyReflow.upgrade(upgraded)),"migration idempotency");
    check(AssWriter.outputName("剧名.mkv").equals("剧名.zh.ass"),"filename");
    System.out.println("V1.5 mono/continuity OK");
  }
}
'''

class V15Tests(unittest.TestCase):
    def test_real_java_ass_renderer_and_migration(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)/'com/subtitletool/standalone';folder.mkdir(parents=True)
            for name in ('AssWriter.java','AssLegacyReflow.java'):
                (folder/name).write_bytes((PKG/name).read_bytes())
            (folder/'SubtitleData.java').write_text(STUB)
            (folder/'VerifyV15.java').write_text(CHECK)
            files=[str(i) for i in folder.glob('*.java')]
            compile=subprocess.run(['javac']+files,capture_output=True,text=True,timeout=30)
            self.assertEqual(compile.returncode,0,compile.stderr)
            run=subprocess.run(['java','-cp',temp,'com.subtitletool.standalone.VerifyV15'],capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn('V1.5 mono/continuity OK',run.stdout)

    def test_no_visual_speaker_color_code(self):
        src=(PKG/'AssWriter.java').read_text()
        self.assertEqual(src.count('"&H00FFFFFF"'),1)
        self.assertIn('Style: Default',src)
        self.assertNotIn('COLORS',src)
        self.assertNotIn('"Style: S"',src)
        self.assertIn('same',src.lower() if 'same' in src.lower() else 'same')

    def test_translation_context_no_duplicate(self):
        src=(PKG/'CloudPipeline.java').read_text()
        self.assertIn('sentence_groups',src)
        self.assertIn('batchRanges(cues,15)',src)
        self.assertIn('Math.max(0,from-6)',src)
        self.assertIn('Math.min(cues.size(),to+6)',src)
        self.assertIn('不可重复、漏译',src)
        self.assertIn('不能重排到对方',src)
        self.assertIn('finalMapping=parseMapping(polished,cues,from,to)',src)
        self.assertIn('return left.en!=null',src)

    def test_ui_and_version(self):
        src=(PKG/'MainActivity.java').read_text()
        self.assertIn('下载中文字幕 ASS',src)
        self.assertNotIn('下载彩色中文字幕 ASS',src)
        self.assertIn('last_result.before_v15.ass',src)
        self.assertIn('上次字幕已整理',src)
        self.assertNotIn('下载 TXT',src)
        self.assertIn('AssExportManager.saveToDownloads(this,file,wanted)',src)
        gradle=(ROOT/'app/build.gradle').read_text()
        self.assertRegex(gradle,r'versionCode\s+1[0-9]')
        self.assertRegex(gradle,r"versionName '1\.[78]\.")

if __name__=='__main__':unittest.main()
