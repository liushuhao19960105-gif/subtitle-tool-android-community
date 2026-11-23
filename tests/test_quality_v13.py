import tempfile
import subprocess
import textwrap
import unittest
from pathlib import Path

PKG=Path(__file__).resolve().parents[1]/'app/src/main/java/com/subtitletool/standalone'

class QualityV13(unittest.TestCase):
    def test_reflow_java_real(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)/'com/subtitletool/standalone'
            folder.mkdir(parents=True)
            for filename in ('AssWriter.java','AssLegacyReflow.java'):
                (folder/filename).write_bytes((PKG/filename).read_bytes())
            (folder/'SubtitleData.java').write_text(textwrap.dedent('''
               package com.subtitletool.standalone;
               public final class SubtitleData {
                 public static final class Cue {
                   public final int id,speaker;
                   public final long startMs,endMs;
                   public final String en;
                   public final boolean uncertain;
                   public String zh="";
                   public Cue(int id,long a,long b,String en,int speaker,boolean uncertain){
                     this.id=id;this.startMs=a;this.endMs=b;this.en=en;
                     this.speaker=speaker;this.uncertain=uncertain;
                   }
                 }
               }
            '''))
            (folder/'TestV13.java').write_text(r'''
package com.subtitletool.standalone;
public class TestV13 {
  static void assertOk(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void main(String[] args)throws Exception{
    String old="[Script Info]\nTitle: sample\n[V4+ Styles]\n"
      +"Style: S3,Noto Sans CJK SC,54,&H0089D7FF,....\n[Events]\n"
      +"Dialogue: 0,0:00:40.35,0:00:45.47,S3,,0,0,0,,杰西卡已经不再是我那个乖巧懂事的女孩\n"
      +"Dialogue: 0,0:00:45.47,0:00:46.19,S3,,0,0,0,,了。\n"
      +"Dialogue: 0,0:00:46.20,0:00:47.00,S2,,0,0,0,,什么？\n"
      +"Dialogue: 0,0:00:47.10,0:00:49.30,S3,,0,0,0,,我们明明相处得很好，\\N性生活也是如此。\n"
      +"Dialogue: 0,0:00:49.32,0:00:49.60,Neutral,,0,0,0,,{\\an8}嘿！\n";
    String out=AssLegacyReflow.upgrade(old);
    assertOk(out.contains("SubtitleTool-Renderer: 1.5-monochrome"),"marker");
    assertOk(out.contains("Noto Sans CJK SC,62,"),"font size");
    assertOk(out.contains("杰西卡已经不再是我那个乖巧懂事的女孩了。"),"lone suffix");
    assertOk(!out.contains(",,了。"),"no single particle cue");
    assertOk(out.contains("性生活"),"do not split multi-character words");
    assertOk(!out.contains("（／an8）"),"no leaked ASS override tags");
    assertOk(out.contains(",Default,"),"all speakers are monochrome");
    assertOk(!AssLegacyReflow.needsUpgrade(out),"upgrade not repeated");
    assertOk(AssLegacyReflow.upgrade(out).equals(out),"idempotent");
    System.out.println("V1.3 layout/migration OK");
  }
}
''')
            r=subprocess.run(['javac']+[str(x) for x in folder.glob('*.java')],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.TestV13'],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn('V1.3 layout/migration OK',r.stdout)

    def test_cloud_prompt_quality(self):
        txt=(PKG/'CloudPipeline.java').read_text()
        for word in ['Naughty America','同一说话者','不可重复','前后相邻英文','只输出 JSON']:
            self.assertIn(word,txt)
        self.assertIn('o.put("start_ms",c.startMs)',txt)
        self.assertIn('o.put("end_ms",c.endMs)',txt)

    def test_output_only_single_ass(self):
        ui=(PKG/'MainActivity.java').read_text()
        self.assertIn('upgradeSavedAss(prior)',ui)
        self.assertIn('last_result.before_v15.ass',ui)
        self.assertIn('Files.move(tmp.toPath()',ui)
        self.assertIn('下载中文字幕 ASS',ui)
        self.assertIn('i.setType(AssExportNaming.MIME_ASS)',ui)
        self.assertNotIn('下载 TXT',ui)

    def test_no_weak_single_word_whitening(self):
        data=(PKG/'SubtitleData.java').read_text()
        self.assertIn('lowConfidence>=2',data)
        self.assertIn('lowConfidence*3>=batch.size()',data)
        self.assertIn('first.speaker<0',data)

if __name__=='__main__':unittest.main()
