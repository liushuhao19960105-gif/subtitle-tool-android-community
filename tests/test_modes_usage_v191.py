"""Selected work only: translation modes, provider-returned usage, context-sensitive slang."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/"app/src/main/java/com/subtitletool/standalone"
MODE=(BASE/"TranslationMode.java").read_text(encoding="utf-8")
PIPELINE=(BASE/"CloudPipeline.java").read_text(encoding="utf-8")
LEDGER=(BASE/"UsageLedger.java").read_text(encoding="utf-8")
CHECKPOINT=(BASE/"TranslationCheckpoint.java").read_text(encoding="utf-8")
MAIN=(BASE/"MainActivity.java").read_text(encoding="utf-8")
FIXTURES=(ROOT/"tests/fixtures/slang_context_v191.tsv").read_text(encoding="utf-8")

JAVA_CHECK="""package com.subtitletool.standalone;
public class ModeCheck {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        check(TranslationMode.fromIndex(0)==TranslationMode.QUICK,"quick mode");
        check(TranslationMode.fromIndex(1)==TranslationMode.STANDARD,"standard mode");
        check(TranslationMode.fromIndex(2)==TranslationMode.HIGH,"high mode");
        check(TranslationMode.fromIndex(99)==TranslationMode.STANDARD,"unknown index fallback");
        check(TranslationMode.fromId("unknown")==TranslationMode.STANDARD,"unknown id");
        check(TranslationMode.QUICK.passes==1,"quick pass count");
        check(TranslationMode.STANDARD.passes==3,"standard pass count");
        check(TranslationMode.HIGH.passes==4,"high pass count");
        System.out.println("mode selection and pass counts OK");
    }
}
"""

class ModesAndUsageTests(unittest.TestCase):
    def test_mode_java_behaviour(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/"com/subtitletool/standalone"
            source.mkdir(parents=True)
            (source/"TranslationMode.java").write_text(MODE,encoding="utf-8")
            (source/"ModeCheck.java").write_text(JAVA_CHECK,encoding="utf-8")
            build=subprocess.run(["javac",str(source/"TranslationMode.java"),str(source/"ModeCheck.java")],
                                 text=True,capture_output=True,timeout=30)
            self.assertEqual(build.returncode,0,build.stderr)
            run=subprocess.run(["java","-cp",directory,"com.subtitletool.standalone.ModeCheck"],
                               text=True,capture_output=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("pass counts OK",run.stdout)

    def test_modes_have_distinct_request_paths_and_checkpoints(self):
        for marker in (
            "if(mode!=TranslationMode.QUICK)", "if(mode==TranslationMode.HIGH)",
            "checkpoint.deepDone=n+1", "checkpoint.save(context)",
            "finalMapping=mapping", "TRANSLATE_SYSTEM",
            "POLISH_SYSTEM", "REVIEW_SYSTEM"):
            self.assertIn(marker,PIPELINE)
        self.assertIn('if(mode==TranslationMode.STANDARD)return standard;',CHECKPOINT)
        self.assertIn('o.optInt("deepDone",0)',CHECKPOINT)
        self.assertIn('"quality="+mode.id',CHECKPOINT)
        self.assertIn('final TranslationMode mode=TranslationMode.fromIndex(',MAIN)
        self.assertIn("qualityModeSpinner.setEnabled(false)",MAIN)

    def test_usage_is_provider_reported_and_excludes_unverified_fees(self):
        for marker in ("prompt_tokens","completion_tokens","reportedCalls",
                       "unreportedCalls", "getFilesDir()", "StandardCopyOption.ATOMIC_MOVE",
                       "out.getFD().sync()", "record(JSONObject response)",
                       "inputTokens*inputPrice+outputTokens*outputPrice",
                       "不含语音识别费","未知计费"):
            self.assertIn(marker,LEDGER)
        self.assertIn("if(ledger!=null)ledger.record(response)",PIPELINE)
        self.assertLess(PIPELINE.index("if(ledger!=null)ledger.record(response)"),
                        PIPELINE.index("JSONArray choices=response.optJSONArray("))
        self.assertIn("usageInfo.setText(usage.summary(",MAIN)
        self.assertIn("inputPrice.getText().toString()",MAIN)
        self.assertIn("outputPrice.getText().toString()",MAIN)
        self.assertNotIn("SUBTITLE_SIGNING_",LEDGER)
        self.assertNotIn("Authorization",LEDGER)

    def test_contextual_slurs_and_ambiguous_phrases_have_a_test_matrix(self):
        cases=FIXTURES.strip().splitlines()
        self.assertGreaterEqual(len(cases),21)
        self.assertTrue(all(len(line.split("\t"))==3 for line in cases))
        for marker in ("Get off the bus.", "Go down on someone.",
                       "[moaning]", "Ah! Ah!", "No, I cannot.", "Daddy is home."):
            self.assertIn(marker,FIXTURES)
        for marker in ("get off the bus","screw up","good girl","不能把",
                       "Ah、Oh、Mm"):
            self.assertIn(marker.lower(),PIPELINE.lower())
        self.assertIn("不擅自色情化",FIXTURES)

if __name__=="__main__":
    unittest.main()
