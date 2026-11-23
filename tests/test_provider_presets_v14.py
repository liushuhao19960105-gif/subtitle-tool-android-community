import subprocess
import tempfile
import unittest
from pathlib import Path

PROJECT=Path(__file__).resolve().parents[1]
PKG=PROJECT/'app/src/main/java/com/subtitletool/standalone'

class ProviderPresetsV14(unittest.TestCase):
    def test_java_presets_and_https_validator(self):
        src=PKG/'ProviderPresets.java'
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'com/subtitletool/standalone'
            path.mkdir(parents=True)
            (path/'ProviderPresets.java').write_bytes(src.read_bytes())
            (path/'ProviderTest.java').write_text('''
package com.subtitletool.standalone;
public class ProviderTest {
  static void check(boolean ok){if(!ok)throw new AssertionError();}
  static void invalid(String u) {
    try {ProviderPresets.normalizeEndpoint(u); throw new AssertionError("accepted: "+u);}
    catch(IllegalArgumentException good) {}
  }
  public static void main(String[] args){
    check(ProviderPresets.ALL.length == 8);
    check(ProviderPresets.ALL[0].id.equals("deepseek"));
    check(ProviderPresets.ALL[1].id.equals("glm"));
    check(ProviderPresets.ALL[2].id.equals("kimi_cn"));
    check(ProviderPresets.ALL[3].id.equals("kimi_intl"));
    check(ProviderPresets.ALL[4].id.equals("siliconflow"));
    check(ProviderPresets.ALL[5].id.equals("volcengine"));
    check(ProviderPresets.ALL[6].id.equals("bailian"));
    check(ProviderPresets.ALL[7].custom);
    for(int i=0;i<ProviderPresets.ALL.length-1;i++){
      var p=ProviderPresets.ALL[i];
      check(!p.custom);
      check(p.endpoint.startsWith("https://"));
      check(p.endpoint.endsWith("/chat/completions"));
      check(p.defaultModel.length()>2);
      check(ProviderPresets.normalizeEndpoint(p.endpoint).equals(p.endpoint));
    }
    check(ProviderPresets.normalizeEndpoint(" https://example.com/v1/ ").equals("https://example.com/v1/chat/completions"));
    check(ProviderPresets.normalizeEndpoint("https://example.com/v1/chat/completions").equals("https://example.com/v1/chat/completions"));
    invalid("http://example.com/v1");
    invalid("https://user:pw@example.com/v1");
    invalid("https://example.com/v1?token=secret");
    invalid("https://example.com/v1#fragment");
    invalid("file:///tmp/test");
    invalid("");
    check(ProviderPresets.byIndex(999).id.equals("deepseek"));
    System.out.println("Eight provider presets and HTTPS guard verified");
  }
}
''',encoding='utf-8')
            compile=subprocess.run(['javac',str(path/'ProviderPresets.java'),str(path/'ProviderTest.java')],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,compile.returncode,compile.stderr)
            execute=subprocess.run(['java','-cp',tmp,'com.subtitletool.standalone.ProviderTest'],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,execute.returncode,execute.stderr)
            self.assertIn('Eight provider',execute.stdout)
    def test_ui_and_pipeline_use_same_endpoint(self):
        ui=(PKG/'MainActivity.java').read_text()
        pipeline=(PKG/'CloudPipeline.java').read_text()
        for term in ['ProviderPresets.labels()', 'apiEndpoint.setEnabled(p.custom)', 'provider.keySlot()',
                     'secure.load(provider.keySlot())','pipeline.translateAndPolish(cues,translate,endpoint,model']:
            self.assertIn(term,ui)
        self.assertIn('ProviderPresets.normalizeEndpoint(endpoint)',pipeline)
        self.assertNotIn('"deepseek".equals(provider)',pipeline)
        self.assertIn('c.setInstanceFollowRedirects(false)',pipeline)
    def test_provider_key_silos_and_legacy_keys(self):
        secure=(PKG/'SecureKeys.java').read_text()
        self.assertIn('deepgram|assemblyai|deepseek|glm|kimi_cn|kimi_intl|siliconflow|volcengine|bailian|custom',secure)
        ui=(PKG/'MainActivity.java').read_text()
        self.assertIn('old.modelPref()',ui)
        self.assertIn('modelKey.setText("")',ui)
        self.assertIn('apiEndpoint.setEnabled(p.custom)',ui)
        self.assertIn('if(!saveKeys())return',ui)
    def test_single_ass_output_and_unchanged_audio(self):
        a=(PKG/'MainActivity.java').read_text()
        assert a.count('下载中文字幕 ASS')==1
        self.assertIn('AudioExtractor.extract(this,uri,work,stop',a)
        self.assertIn('AssWriter.render(cues)',a)
        self.assertIn('AssWriter.outputName(originalName)',a)
        self.assertIn('translateAndPolish(cues,translate,endpoint,model',a)
    def test_privacy_no_real_api_key(self):
        for source in (PKG/'ProviderPresets.java',PKG/'MainActivity.java',PKG/'CloudPipeline.java'):
            self.assertNotIn('sk-',source.read_text())

if __name__=='__main__': unittest.main()
