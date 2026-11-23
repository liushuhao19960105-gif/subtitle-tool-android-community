"""Regression checks for the V1.8.3 contextual subtitle review stage."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/subtitletool/standalone/CloudPipeline.java").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle").read_text(encoding="utf-8")


class ContextualReviewTests(unittest.TestCase):
    def test_third_pass_after_translation(self):
        self.assertIn("private static final String REVIEW_SYSTEM", SOURCE)
        self.assertLess(SOURCE.index("cues.get(i).zh=finalMapping.get"), SOURCE.index("final List<int[]> reviewRanges="))
        self.assertLess(SOURCE.index("final List<int[]> reviewRanges="), SOURCE.index("cues.get(i).zh=reviewed.get"))

    def test_cross_batch_chinese_context(self):
        self.assertIn('input.put("previous_chinese",toArray(cues,Math.max(0,from-8),from,true))', SOURCE)
        self.assertIn('input.put("next_chinese",toArray(cues,to,Math.min(cues.size(),to+8),true))', SOURCE)
        self.assertIn('input.put("target",toArray(cues,from,to,true))', SOURCE)
        self.assertIn('input.put("sentence_groups",sentenceGroups(cues,from,to))', SOURCE)

    def test_validation_before_mutation(self):
        review = SOURCE.split("final List<int[]> reviewRanges=", 1)[1]
        self.assertLess(review.index("parseMapping(checked,cues,from,to)"), review.index("cues.get(i).zh=reviewed.get"))
        self.assertIn("if(arr==null || arr.length()!=to-from)", SOURCE)
        self.assertIn("if(!map.containsKey(cues.get(i).id))", SOURCE)

    def test_review_emphasizes_meaning_and_readability(self):
        start = SOURCE.index("private static final String REVIEW_SYSTEM")
        end = SOURCE.index("public void translateAndPolish", start)
        prompt = SOURCE[start:end]
        for phrase in ("问答衔接", "符合中国人", "不能混合", "粗口", "不得删除", "无问题请原样返回"):
            self.assertIn(phrase, prompt)

    def test_version(self):
        self.assertIn("versionCode 16", GRADLE)
        self.assertIn("1.8.5-quality-community", GRADLE)


if __name__ == "__main__":
    unittest.main()
