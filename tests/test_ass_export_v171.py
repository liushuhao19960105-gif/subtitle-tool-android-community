from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

class AssExportV171Checks(unittest.TestCase):
    def test_media_store_retries_without_losing_last_copy(self):
        source=(PKG/'AssExportManager.java').read_text()
        activity=(PKG/'MainActivity.java').read_text()
        self.assertIn('AssExportNaming.numberedCopy(expected,attempt)',source)
        self.assertIn('if(attempt==maxAttempts)',source)
        self.assertIn('retain=true;',source)
        self.assertIn('if(uri!=null && !retain)',source)
        self.assertIn('字幕已保存，文件名需调整',activity)
        self.assertIn('不需要重新识别或翻译',activity)

    def test_java_collision_names_and_suffix(self):
        java=r'''
package com.subtitletool.standalone;
public final class TestExportNameV171 {
  static void eq(String actual,String expected) {
    if(!actual.equals(expected))throw new AssertionError(actual+" != "+expected);
  }
  public static void main(String[] args){
    eq(AssExportNaming.numberedCopy("movie.zh.ass",1),"movie.zh.ass");
    eq(AssExportNaming.numberedCopy("movie.zh.ass",2),"movie.zh (2).ass");
    eq(AssExportNaming.numberedCopy("movie.zh.ass",13),"movie.zh (13).ass");
    eq(AssExportNaming.numberedCopy("视频.zh.ass.txt",2),"视频.zh (2).ass");
    if(AssExportNaming.hasAssExtension("movie.zh.ass (2)"))throw new AssertionError("wrong extension");
    if(!AssExportNaming.hasAssExtension("movie.zh (2).ass"))throw new AssertionError("correct extension");
    String over=AssExportNaming.numberedCopy("x".repeat(300)+".ass",2);
    if(over.length()>180 || !over.endsWith(" (2).ass"))throw new AssertionError(over);
    System.out.println("export naming regression OK");
  }
}
'''
        with tempfile.TemporaryDirectory() as td:
            dest=Path(td)/'com/subtitletool/standalone'; dest.mkdir(parents=True)
            (dest/'AssExportNaming.java').write_bytes((PKG/'AssExportNaming.java').read_bytes())
            (dest/'TestExportNameV171.java').write_text(java)
            res=subprocess.run(['javac',str(dest/'AssExportNaming.java'),str(dest/'TestExportNameV171.java')],capture_output=True,text=True,timeout=30)
            self.assertEqual(res.returncode,0,res.stderr)
            res=subprocess.run(['java','-cp',td,'com.subtitletool.standalone.TestExportNameV171'],capture_output=True,text=True,timeout=30)
            self.assertEqual(res.returncode,0,res.stderr)
            self.assertIn('regression OK',res.stdout)

if __name__=='__main__':unittest.main()
