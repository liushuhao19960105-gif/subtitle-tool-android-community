"""V1.8.4 durable recovery, safe retry and signing isolation regression guards."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"app/src/main/java/com/subtitletool/standalone"

class ResumeV184Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cp=(JAVA/"TranslationCheckpoint.java").read_text(encoding="utf-8")
        cls.pipeline=(JAVA/"CloudPipeline.java").read_text(encoding="utf-8")
        cls.main=(JAVA/"MainActivity.java").read_text(encoding="utf-8")

    def test_checkpoint_file_is_app_private_and_atomic(self):
        self.assertIn('new File(ctx.getFilesDir(),FILE)',self.cp)
        self.assertIn('FileOutputStream(tmp)',self.cp)
        self.assertIn('out.getFD().sync()',self.cp)
        self.assertIn('StandardCopyOption.REPLACE_EXISTING',self.cp)
        self.assertIn('firstDone',self.cp)
        self.assertIn('reviewDone',self.cp)
        self.assertIn('SubtitleData.toJson(cues)',self.cp)
        self.assertNotIn('getExternalFilesDir',self.cp)
        self.assertNotIn('getExternalStorage',self.cp)

    def test_checkpoint_rejects_stale_or_corrupt_input(self):
        for marker in ('source.equals(o.getString("source"))',
                       'config.equals(o.getString("config"))',
                       'CloudPipeline.hasCompletedPrefix',
                       'arr.length()>4000','o.getInt("version")!=VERSION',
                       'c.id!=i+1'):
            self.assertIn(marker,self.cp)
        self.assertIn("OpenableColumns.SIZE",self.cp)
        self.assertIn('65536',self.cp)
        self.assertIn("SHA-256",self.cp)
        self.assertNotIn('API_KEY',self.cp)

    def test_only_completed_batches_are_skipped(self):
        self.assertIn("n<checkpoint.firstDone",self.pipeline)
        self.assertIn("n<checkpoint.reviewDone",self.pipeline)
        first=self.pipeline.index("Map<Integer,String> finalMapping=parseMapping")
        firstsave=self.pipeline.index("checkpoint.firstDone=n+1")
        review=self.pipeline.index("Map<Integer,String> reviewed=parseMapping")
        reviewsave=self.pipeline.index("checkpoint.reviewDone=n+1")
        self.assertLess(first,firstsave)
        self.assertLess(review,reviewsave)
        self.assertIn("checkpoint.save(context)",self.pipeline)
        self.assertIn("previousSummary.append(cues.get(i).zh)",self.pipeline)

    def test_retry_only_explicit_rate_limit(self):
        self.assertIn("if(code==429)",self.pipeline)
        self.assertIn("catch(RateLimitException limited)",self.pipeline)
        self.assertIn("if(attempt==2)throw limited",self.pipeline)
        self.assertIn("Thread.sleep",self.pipeline)
        self.assertNotRegex(self.pipeline,r"catch\s*\(\s*IOException\s+\w+\s*\)\s*\{[^}]*chatOnce")
        self.assertIn("if(code>=500)throw new IOException",self.pipeline)

    def test_resume_before_asr_and_keep_state_on_failure(self):
        self.assertLess(self.main.index("TranslationCheckpoint.load(this,sourceIdentity,configIdentity)"),
                        self.main.index("AudioExtractor.extract(this,uri,work,stop,"))
        self.assertIn("checkpoint=TranslationCheckpoint.fresh",self.main)
        self.assertIn("pipeline.translateAndPolish(cues,translate,endpoint,model,this::showProgress,checkpoint,this)",self.main)
        self.assertIn('TranslationCheckpoint.clear(this);',self.main)
        self.assertIn("清除未完成翻译进度",self.main)

    def test_version_and_public_signing_safety(self):
        gradle=(ROOT/"app/build.gradle").read_text()
        self.assertIn("versionCode 15",gradle)
        self.assertIn("1.8.4-resume-community",gradle)
        self.assertNotIn("SUBTITLE_SIGNING_",gradle)
        wf=(ROOT/".github/workflows/community-ci.yml").read_text()
        self.assertNotIn("secrets.",wf)

if __name__=="__main__":
    unittest.main()
