"""Windows PC regression: model JSON errors must not lose paid work."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import core


class JSONRecoveryTests(unittest.TestCase):
    def test_safe_local_format_repair(self):
        text='``json\n{"items":[{"id":1,"zh":"你好"}]}\n``'
        # Ordinary fenced and plain subtitle JSON are accepted without new API calls.
        self.assertEqual(core._parse_model_json('{"items":[{"id":1,"zh":"你好"}]}')['items'][0]['zh'],'你好')
        self.assertEqual(core._parse_model_json('{"items":[{"id":1,"zh":"你好",},],}')['items'][0]['zh'],'你好')
        self.assertEqual(core._parse_model_json('{"items":[{"id":1,"zh":"你\n好"}]}')['items'][0]['zh'],'你\n好')
        with self.assertRaisesRegex(RuntimeError,'继续任务'):
            core._parse_model_json('{"items":[{"id":1,"zh":}]}')
        with self.assertRaisesRegex(RuntimeError,'没有返回 JSON'):
            core._parse_model_json('model error without JSON')

    def test_partial_progress_stays_saved_no_automatic_retry(self):
        first=[{'id':i,'zh':f'第{i}条'} for i in range(1,16)]
        class Session:
            def __init__(self):self.count=0
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def post(self,*args,**kwargs):
                self.count+=1
                txt=json.dumps({'items':first},ensure_ascii=False) if self.count==1 else '{"items":[{"id":16,"zh":}]}'
                payload={'usage':{'prompt_tokens':10,'completion_tokens':5},
                         'choices':[{'finish_reason':'stop','message':{'content':txt}}]}
                class Response:
                    status_code=200
                    ok=True
                    def json(self):return payload
                return Response()
        with tempfile.TemporaryDirectory() as tmp,patch.object(core,'APP_DIR',Path(tmp)):
            session=Session()
            cues=[core.Cue(i,i*1000,i*1000+900,f'English {i}',0) for i in range(1,17)]
            with patch.object(core.requests,'Session',lambda:session):
                with self.assertRaisesRegex(RuntimeError,'继续任务'):
                    core.translate(cues,'https://example.com/chat/completions','secret','model',
                                   '快速','job-id',core.Event(),lambda *_:None,lambda *_:None)
            saved=core.load_checkpoint('job-id')
            self.assertEqual(saved['first'],1)
            self.assertEqual(saved['cues'][0]['zh'],'第1条')
            self.assertEqual(saved['cues'][-1]['zh'],'')
            self.assertEqual(saved['usage']['calls'],2)
            self.assertEqual(session.count,2)


if __name__=='__main__':
    unittest.main()
