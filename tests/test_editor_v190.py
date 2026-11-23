"""V1.9.0 preview/editor safety checks without network or signing material."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
PKG=ROOT/"app/src/main/java/com/subtitletool/standalone"
ACTIVITY=(PKG/"SubtitleEditorActivity.java").read_text(encoding="utf-8")
DRAFT=(PKG/"SubtitleDraftStore.java").read_text(encoding="utf-8")
MAIN=(PKG/"MainActivity.java").read_text(encoding="utf-8")
MANIFEST=(ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

class EditorV190Tests(unittest.TestCase):
    def test_editor_is_private_and_local_only(self):
        self.assertIn('android:name=".SubtitleEditorActivity"',MANIFEST)
        self.assertIn('android:exported="false"',MANIFEST)
        for marker in ("SubtitleDraftStore.load(this)","SubtitleDraftStore.save(this,cues)",
                       "SubtitleDraftStore.writeAss(this,cues)",
                       "new InputFilter.LengthFilter(250)",'setTitle("修改第 "+cue.id+" 条中文字幕")',
                       "cue.zh=edited","cue.zh=original"):
            self.assertIn(marker,ACTIVITY)
        self.assertNotIn("HttpURLConnection",ACTIVITY)
        self.assertNotIn("cloud",ACTIVITY.lower())
        self.assertNotIn("getSharedPreferences",ACTIVITY)
        self.assertNotIn("API Key",ACTIVITY)

    def test_atomic_storage_and_validation(self):
        for marker in ("getFilesDir()","SubtitleData.toJson(cues)",
                       "SubtitleData.fromJson(new JSONArray(",
                       "getFD().sync()","StandardCopyOption.ATOMIC_MOVE",
                       "MAX_BYTES=8_000_000","cue.id<=previousId",
                       "cue.zh.trim().isEmpty()","AssWriter.render(cues)"):
            self.assertIn(marker,DRAFT)
        self.assertNotIn("getExternalStorage",DRAFT)
        self.assertNotIn("SUBTITLE_SIGNING_",DRAFT)

    def test_quality_report_items_are_clickable_and_edits_are_safe(self):
        for marker in ('issuesButton.setText("查看需复核字幕（"',
                       'SubtitleQuality.inspect(cues).findings',
                       'list.setSelection(i)', 'edit(i)',
                       'if(saving || cues==null)return;',
                       'if(saving){', '字幕正在保存，请完成后返回',
                       'setItems(labels,(dialog,which)->'):
            self.assertIn(marker,ACTIVITY)
        self.assertLess(ACTIVITY.index('saving=false;\\n                            refreshProblemNavigator();'),
                        ACTIVITY.index('Toast.makeText(this,"字幕已保存'))
        self.assertIn('if(current.size()>=60)break;',ACTIVITY)

    def test_entry_point_and_export_continuity(self):
        self.assertIn('quietBtn("字幕预览与编辑")',MAIN)
        self.assertIn("SubtitleDraftStore.clear(this);",MAIN)
        self.assertIn("SubtitleDraftStore.save(this,cues)",MAIN)
        self.assertIn("startActivity(new Intent(this,SubtitleEditorActivity.class))",MAIN)
        self.assertIn('new File(getFilesDir(),"last_result.ass")',MAIN)
        self.assertIn("if(busy)return;",MAIN)

if __name__=="__main__":
    unittest.main()
