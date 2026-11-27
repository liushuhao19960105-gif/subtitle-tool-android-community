import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core


class DesktopTests(unittest.TestCase):
    def test_lexicon_full_110_and_word_boundaries(self):
        with core.resource('dialogue_lexicon_v191.tsv').open(encoding='utf-8') as f:
            self.assertEqual(sum(1 for _ in f)-1, 110)
        terms=[x['en'] for x in core.lexicon('Get off the bus, not get off on it.')]
        self.assertIn('get off the bus',terms)
        self.assertIn('get off on',terms)
        self.assertNotIn('cock', [x['en'] for x in core.lexicon('a cocktail')])
        self.assertNotIn('come', [x['en'] for x in core.lexicon('become friends')])
        self.assertLessEqual(len(core.lexicon(' '.join(['fuck','ass','daddy','screw']*40))),10)

    def test_filename_ass_uses_white_and_no_labels(self):
        cues=[core.Cue(1,0,1900,'Oh!',0,'啊'),core.Cue(2,1900,4200,'Come here!',1,'过来！')]
        result=core.ass_text(cues)
        self.assertTrue(result.startswith('\ufeff[Script Info]'))
        self.assertEqual(result.count('Dialogue: 0,'),2)
        self.assertIn('Noto Sans CJK SC,62,&H00FFFFFF',result)
        self.assertIn('0:00:00.00,0:00:01.90',result)
        self.assertEqual(core.output_name(Path('My Video.mkv')),'My Video.zh.ass')
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'demo.zh.ass'
            core.export_ass(cues,path)
            raw=path.read_bytes()
            self.assertTrue(raw.startswith(b'\xef\xbb\xbf'))
            self.assertEqual(raw.count(b'\xef\xbb\xbf'),1)

    def test_mock_deepgram_speaker_and_group(self):
        data={'results':{'utterances':[{'speaker':0,'words':[
            {'start':0,'end':.7,'punctuated_word':'Hello'},
            {'start':.75,'end':1.5,'punctuated_word':'there.'}]},
            {'speaker':1,'words':[{'start':1.6,'end':2.4,'punctuated_word':'Hi!'}]}]}}
        cues=core._deepgram(data)
        self.assertEqual(len(cues),2)
        self.assertEqual(cues[0].speaker,0)
        self.assertEqual(cues[1].speaker,1)
        self.assertEqual(cues[0].en,'Hello there.')

    def test_checkpoint_identity_and_resume_validation(self):
        with tempfile.TemporaryDirectory() as d:
            location=Path(d)
            with patch.object(core,'APP_DIR',location):
                video=location/'video.mp4';video.write_bytes(b'fake video')
                key=core.fingerprint(video,'Deepgram','https://example.com/chat/completions','model','标准')
                payload={'identity':key,'first':1,'review':0,'deep':0,'usage':{},
                         'cues':[core.asdict(core.Cue(i,i*1000,i*1000+900,'Hello',0,'你好'))
                                 for i in range(1,3)]}
                core.json_write(core.checkpoint_path(),payload)
                self.assertIsNotNone(core.load_checkpoint(key))
                self.assertIsNone(core.load_checkpoint('another'))
                self.assertIsNone(core.load_checkpoint(core.fingerprint(video,'Deepgram','https://example.com/chat/completions','model','快速')))
                core.clear_checkpoint()
                self.assertFalse(core.checkpoint_path().exists())

    def test_translate_mock_mode_1_3_4_and_token_accounting(self):
        class MockResponse:
            status_code=200
            ok=True
            def json(self):
                return {'usage':{'prompt_tokens':12,'completion_tokens':6},
                        'choices':[{'finish_reason':'stop','message':{'content':'{"items":[{"id":1,"zh":"啊"}]}'}}]}
        class FakeSession:
            count=0
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def post(self,*args,**kwargs):
                self.count+=1
                self_allowed=kwargs.get('allow_redirects') is False
                if not self_allowed:raise AssertionError('redirects must be disabled')
                return MockResponse()
        with tempfile.TemporaryDirectory() as d,patch.object(core,'APP_DIR',Path(d)):
            for mode,expected in [('快速',1),('标准',3),('高质量',4)]:
                fake=FakeSession()
                with patch.object(core.requests,'Session',lambda:fake):
                    cues,usage=core.translate([core.Cue(1,0,1400,'Ah',0)],
                        'https://example.com/chat/completions','key','demo',mode,'id_'+mode,
                        core.Event(),lambda *_:None,lambda *_:None)
                    self.assertEqual(fake.count,expected)
                    self.assertEqual(usage.calls,expected)
                    self.assertEqual(usage.input_tokens,expected*12)
                    self.assertEqual(cues[0].zh,'啊')
                    self.assertIn('估算 $',usage.display(1.2,2.1))
        self.assertEqual(core.Usage(unknown_calls=1).calls,0)

    def test_assembly_words_time_units(self):
        obj={'utterances':[{'speaker':'A','words':[{'start':1000,'end':1800,'text':'Hello.'}]},
                           {'speaker':'B','words':[{'start':2200,'end':2600,'text':'Hi!'}]}]}
        cues=core._assembly(obj)
        self.assertEqual(cues[0].start,1000)
        self.assertEqual(cues[1].speaker,1)

if __name__=='__main__':unittest.main()
