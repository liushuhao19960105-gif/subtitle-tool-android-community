"""V1.8.4: regression guards for user-requested visual and resume improvements."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/subtitletool/standalone/MainActivity.java").read_text(encoding="utf-8")

class ResumeUiTests(unittest.TestCase):
    def test_header_and_selected_file_are_readable(self):
        self.assertIn('txt("视频字幕工具",22,INK,true)',MAIN)
        self.assertIn('txt("V1.9.1",12,MUTED,false)',MAIN)
        self.assertNotIn('txt("单色字幕 · V1.8"',MAIN)
        self.assertIn('selectedInfo=txt("尚未选择视频或音频",15,INK,true)',MAIN)
        self.assertIn('selectedInfo.setMaxLines(3)',MAIN)
        self.assertIn('selectedInfo.setText(originalName)',MAIN)
        self.assertIn('selectedFilePanel.setBackground(rect(BG,false))',MAIN)

    def test_two_short_actions_share_a_row(self):
        self.assertIn('taskActionsRow.setOrientation(LinearLayout.HORIZONTAL)',MAIN)
        self.assertIn('taskActionsRow.addView(stopBtn,stopLayout)',MAIN)
        self.assertIn('taskActionsRow.addView(clearCheckpointBtn,new LinearLayout.LayoutParams(0,dp(48),1f))',MAIN)
        self.assertIn('quietBtn("停止任务")',MAIN)
        self.assertIn('quietBtn("清除进度")',MAIN)
        self.assertNotIn('quietBtn("停止当前任务")',MAIN)
        self.assertNotIn('quietBtn("清除未完成翻译进度")',MAIN)

    def test_clear_immediately_hides_resume(self):
        self.assertIn('TranslationCheckpoint.clear(this);',MAIN)
        self.assertIn('resumeCheckGeneration++;',MAIN)
        self.assertIn('resumeAvailable=false;',MAIN)
        self.assertIn('renderResumeControls();',MAIN)
        self.assertIn('进度已清除，下次将从头开始',MAIN)

    def test_continue_requires_verified_matching_progress(self):
        self.assertIn('resumeAvailable?"继续任务":"开始生成字幕"',MAIN)
        self.assertIn('TranslationCheckpoint.load(this,source,config)!=null',MAIN)
        self.assertIn('if(generation!=resumeCheckGeneration || busy || !uri.equals(pickedUri))return;',MAIN)
        self.assertIn('new Thread(()->{',MAIN)
        self.assertIn('refreshResumeState();',MAIN)
        self.assertIn('if(sourceIdentity!=null)',MAIN)
        self.assertNotIn('new AlertDialog.Builder(this).setTitle("继续任务")',MAIN)
if __name__=="__main__":
    unittest.main()
