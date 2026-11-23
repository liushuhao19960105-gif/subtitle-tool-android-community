"""V1.7 source-level UX checks: native views, async saved secrets, preserved outputs."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
SRC=(ROOT/'app/src/main/java/com/subtitletool/standalone/MainActivity.java').read_text()

class SmoothUiTests(unittest.TestCase):
    def test_compact_single_screen(self):
        for phrase in ('视频文件','模型设置','开始制作','API 密钥与接口',
                       '保存设置','选择视频或音频','下载中文字幕 ASS','单色字幕'):
            self.assertIn(phrase,SRC)
        for verbose in ('无需 Termux','选取视频 → 语音识别','当前版本需联网调用所选识别平台',
                        '切换识别平台不会自动启动第二次识别'):
            self.assertNotIn(verbose,SRC)
    def test_native_dialog_and_smooth_animations(self):
        self.assertIn('Spinner.MODE_DIALOG',SRC)
        self.assertIn('TransitionManager.beginDelayedTransition',SRC)
        self.assertIn('setCredentialsExpanded',SRC)
        self.assertIn('settingsToggle.setEnabled(false)',SRC)
        self.assertIn('settingsToggle.setEnabled(true)',SRC)
        self.assertIn('setSmoothScrollingEnabled(true)',SRC)
        self.assertIn('endpointGroup.setVisibility(p.custom?',SRC)
        self.assertIn('secret.setTransformationMethod(',SRC)
        self.assertIn('secret.setSelection(',SRC)
    def test_async_save_is_not_on_ui_thread(self):
        start=SRC.index('private void saveSettingsAsync()')
        end=SRC.index('private void finishSettingsSave()',start)
        method=SRC[start:end]
        for text in ('new Thread(()->{','secure.save(asr.keySlot,asrSecret)',
                     'secure.save(provider.keySlot(),translationSecret)',
                     'main.post(()->{','finishSettingsSave()',
                     'setEnabled(false);startBtn.setEnabled(false)',
                     'commit()', 'savedInfo'):
            if text!='savedInfo':self.assertIn(text,method)
        self.assertNotIn('Log.d(',method)
    def test_keys_never_appear_in_display_or_logs(self):
        self.assertIn('asrKey.setText("")',SRC)
        self.assertIn('modelKey.setText("")',SRC)
        self.assertIn('secure.has(asrSlot)',SRC)
        self.assertIn('secure.has(slot)',SRC)
        self.assertIn('if(!saveKeys())return',SRC)
    def test_pipeline_and_exports_remain_unchanged(self):
        for x in ('AudioExtractor.extract(this,uri,work,stop',
                  'assemblyTranscriber.transcribe(audio,asrSecret',
                  'pipeline.transcribe(audio,asrSecret',
                  'pipeline.translateAndPolish(cues,translate,endpoint,model',
                  'AssWriter.render(cues)', 'AssExportManager.saveToDownloads(this,file,wanted)'):
            self.assertIn(x,SRC)
        build=(ROOT/'app/build.gradle').read_text()
        self.assertRegex(build,r'versionCode\s+1[0-9]')
        self.assertRegex(build,r"versionName '1\.(?:[789])\.")

if __name__=='__main__':unittest.main()
