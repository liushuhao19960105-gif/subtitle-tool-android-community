from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/'app/src/main/java/com/subtitletool/standalone'

class AssExportFixChecks(unittest.TestCase):
    def test_no_binary_mime_export(self):
        activity=(PKG/'MainActivity.java').read_text()
        self.assertNotIn('i.setType("application/octet-stream")',activity)
        self.assertNotIn('i.setType("text/plain")',activity)
        self.assertIn('AssExportManager.saveToDownloads(this,file,wanted)',activity)
        self.assertIn('i.setType(AssExportNaming.MIME_ASS)',activity)
        self.assertIn('Build.VERSION.SDK_INT>=29',activity)
    def test_media_store_save_and_actual_name_check(self):
        src=(PKG/'AssExportManager.java').read_text()
        self.assertIn('MediaStore.Downloads.EXTERNAL_CONTENT_URI',src)
        self.assertIn('MediaStore.Downloads.DISPLAY_NAME,candidate',src)
        self.assertIn('MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS',src)
        self.assertIn('MediaStore.Downloads.IS_PENDING,1',src)
        self.assertIn('MediaStore.Downloads.IS_PENDING,0',src)
        self.assertIn('AssExportNaming.hasAssExtension(actual)',src)
        self.assertIn('resolver.delete(uri,null,null)',src)
        self.assertIn('A complete exported file must never be deleted',src)
    def test_legacy_save_verifies_extension(self):
        src=(PKG/'MainActivity.java').read_text()
        self.assertIn('DocumentsContract.renameDocument',src)
        self.assertIn('AssExportManager.displayName',src)
    def test_name_normalization_with_javac(self):
        code=r"""
package com.subtitletool.standalone;
public final class CheckAssName {
  static void eq(String result,String exp){if(!result.equals(exp))throw new AssertionError(result+" != "+exp);}
  public static void main(String[] args){
    eq(AssExportNaming.normalized("影片.zh.ass"),"影片.zh.ass");
    eq(AssExportNaming.normalized("影片.zh.ass.bin"),"影片.zh.ass");
    eq(AssExportNaming.normalized("movie.zh.ass.txt"),"movie.zh.ass");
    eq(AssExportNaming.normalized("A/B.zh.ass"),"A_B.zh.ass");
    eq(AssExportNaming.normalized(""),"视频.zh.ass");
    if(!AssExportNaming.hasAssExtension("字幕.ASS"))throw new AssertionError();
    if(AssExportNaming.hasAssExtension("字幕.ass.bin"))throw new AssertionError();
    System.out.println("ASS names OK");
  }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            pkg=Path(tmp)/'com/subtitletool/standalone';pkg.mkdir(parents=True)
            (pkg/'AssExportNaming.java').write_bytes((PKG/'AssExportNaming.java').read_bytes())
            (pkg/'CheckAssName.java').write_text(code)
            r=subprocess.run(['javac',str(pkg/'AssExportNaming.java'),str(pkg/'CheckAssName.java')],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.CheckAssName'],capture_output=True,text=True,timeout=30)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertIn('ASS names OK',r.stdout)

if __name__=='__main__':unittest.main()
