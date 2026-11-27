"""Windows subtitle workstation core. Local video extraction; online ASR and translation.

Never stores API keys in task files. There is no video playback/preview.
"""
from __future__ import annotations
import csv
import hashlib
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from threading import Event
from typing import Callable
from urllib.parse import urlsplit
import requests

APP_DIR = Path(os.environ.get('LOCALAPPDATA') or Path.home()) / 'SubtitleToolPC'
ASR = ('Deepgram Nova-3', 'AssemblyAI STT-3.7')
PROVIDERS = {
    'DeepSeek': ('https://api.deepseek.com/chat/completions', 'deepseek-chat'),
    'GLM': ('https://open.bigmodel.cn/api/paas/v4/chat/completions', 'glm-4.5'),
    'Kimi CN': ('https://api.moonshot.cn/v1/chat/completions', 'kimi-k2.5'),
    'Kimi International': ('https://api.moonshot.ai/v1/chat/completions', 'kimi-k2.5'),
    'SiliconFlow': ('https://api.siliconflow.cn/v1/chat/completions', 'deepseek-ai/DeepSeek-V3.2'),
    'Volcengine': ('https://ark.cn-beijing.volces.com/api/v3/chat/completions', ''),
    'Bailian': ('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', 'qwen-plus'),
    'Custom HTTPS': ('', ''),
}

@dataclass
class Cue:
    id: int
    start: int
    end: int
    en: str
    speaker: int = -1
    zh: str = ''
    uncertain: bool = False

@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    calls: int = 0
    unknown_calls: int = 0

    def record(self, data: dict) -> None:
        usage = data.get('usage') or {}
        inp, out = usage.get('prompt_tokens'), usage.get('completion_tokens')
        if isinstance(inp, int) and isinstance(out, int) and 0 <= inp <= 100_000_000 and 0 <= out <= 100_000_000:
            self.input_tokens += inp
            self.output_tokens += out
            self.calls += 1
        else:
            self.unknown_calls += 1

    def display(self, price_in: float | None, price_out: float | None) -> str:
        msg = f'输入 {self.input_tokens:,} / 输出 {self.output_tokens:,} Token；有用量响应 {self.calls} 次'
        if self.unknown_calls:
            msg += f'；另有 {self.unknown_calls} 次未返回用量'
        if price_in is not None and price_out is not None:
            fee = (self.input_tokens * price_in + self.output_tokens * price_out) / 1_000_000
            msg += f' | 估算 ${fee:.4f}（USD）'
        else:
            msg += ' | 未设置单价，费用未知'
        return msg + '（不含识别费、未知网络请求计费）'


def json_write(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', encoding='utf-8', dir=path.parent, delete=False, suffix='.tmp') as tmp:
        json.dump(data, tmp, ensure_ascii=False, indent=2)
        tmp.flush()
        os.fsync(tmp.fileno())
        temp = Path(tmp.name)
    os.replace(temp, path)


def json_load(path: Path, default=None):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return default


def resource(filename: str) -> Path:
    # PyInstaller one-file extracts its packaged data under _MEIPASS.
    import sys
    return Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parent)) / filename


def lexicon(text: str, limit: int = 10) -> list[dict]:
    """Only matched English whole phrases. Hints are advisory, never substitutions."""
    file = resource('dialogue_lexicon_v191.tsv')
    entries = []
    with file.open(encoding='utf-8-sig', newline='') as fh:
        reader = csv.DictReader(fh, delimiter='\t')
        needed = {'id', 'trigger', 'category', 'context_zh', 'translation_zh', 'avoid_zh'}
        if not needed.issubset(reader.fieldnames or []):
            raise ValueError('对白词库格式不正确')
        for row in reader:
            if not all(row.get(k) for k in needed):
                raise ValueError('对白词库有缺失字段')
            entries.append(row)
    result = []
    for row in sorted(entries, key=lambda x: -len(x['trigger'])):
        expr = r'(?<![\w])' + re.escape(row['trigger']) + r'(?![\w])'
        if re.search(expr, text, flags=re.I):
            result.append({'en': row['trigger'], 'type': row['category'], 'when': row['context_zh'],
                           'candidate': row['translation_zh'], 'do_not_assume': row['avoid_zh']})
            if len(result) == limit:
                break
    return result


def ffmpeg_binary() -> str:
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except (ImportError, RuntimeError, OSError):
        binary = shutil.which('ffmpeg')
        if not binary:
            raise RuntimeError('找不到 FFmpeg。请安装 imageio-ffmpeg 或将 ffmpeg.exe 放入 PATH。')
        return binary


def to_wav(source: Path, dest: Path, stop: Event, report: Callable[[str], None]) -> None:
    """Convert locally to 16k mono PCM WAV. Only this WAV is uploaded, never the video."""
    report('正在本机提取音频，转换为 16 kHz 单声道 WAV…')
    args = [ffmpeg_binary(), '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
            '-i', str(source), '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', str(dest)]
    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
    proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, creationflags=flags)
    try:
        while proc.poll() is None:
            if stop.is_set():
                proc.kill()
                proc.communicate()
                raise InterruptedError('任务已停止')
            time.sleep(.15)
        _, errors = proc.communicate(timeout=5)
        if proc.returncode != 0 or not dest.is_file() or dest.stat().st_size < 44:
            raise RuntimeError('音频提取失败：' + errors.decode('utf-8', 'replace')[-400:])
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.communicate()


def _request(session, method, url, stop: Event, **kwargs) -> requests.Response:
    if stop.is_set():
        raise InterruptedError('任务已停止')
    kwargs.setdefault('timeout', (25, 180))
    r = session.request(method, url, allow_redirects=False, **kwargs)
    if stop.is_set():
        r.close()
        raise InterruptedError('任务已停止')
    if r.status_code >= 300:
        code = r.status_code
        r.close()  # Provider response may contain sensitive account data. Don't print it.
        if code in (401, 403):
            raise RuntimeError(f'API 认证失败 HTTP {code}，请检查密钥和模型权限')
        raise RuntimeError(f'接口请求失败 HTTP {code}')
    return r


def _group_words(words: list[dict]) -> list[Cue]:
    valid = sorted((w for w in words if w['end'] > w['start'] >= 0 and w['text'].strip()), key=lambda w: w['start'])
    if not valid:
        raise RuntimeError('语音识别没有返回有效的英语对白与时间码')
    cues: list[Cue] = []
    buffer: list[dict] = []
    def flush():
        if buffer:
            en = ' '.join(w['text'].strip() for w in buffer)
            cues.append(Cue(len(cues)+1, round(buffer[0]['start']*1000),
                            max(round(buffer[0]['start']*1000)+250, round(buffer[-1]['end']*1000)),
                            en, buffer[0]['speaker'], uncertain=buffer[0]['speaker'] < 0))
            buffer.clear()
    for w in valid:
        if buffer:
            last = buffer[-1]
            if (w['speaker'] != last['speaker'] or w['start'] - last['end'] > .75 or
                w['end'] - buffer[0]['start'] > 6.8 or
                sum(len(x['text'])+1 for x in buffer) + len(w['text']) > 75):
                flush()
        buffer.append(w)
        if re.search(r'[.!?]$', w['text'].strip()) and w['end'] - buffer[0]['start'] >= 1.25:
            flush()
    flush()
    if len(cues) > 4000:
        raise RuntimeError('字幕超过 4000 条，当前版本暂不支持')
    return cues


def _deepgram(data: dict) -> list[Cue]:
    res = data.get('results') or {}
    utterances = res.get('utterances') or []
    words = []
    if utterances:
        for utterance in utterances:
            for w in utterance.get('words') or []:
                words.append({'start': float(w.get('start', -1)), 'end': float(w.get('end', -1)),
                              'text': str(w.get('punctuated_word') or w.get('word') or ''),
                              'speaker': int(w.get('speaker') if w.get('speaker') is not None else utterance.get('speaker', -1))})
    else:
        alts = ((res.get('channels') or [{}])[0].get('alternatives') or [{}])
        for w in alts[0].get('words') or []:
            words.append({'start':float(w.get('start', -1)), 'end':float(w.get('end', -1)),
                          'text': str(w.get('punctuated_word') or w.get('word') or ''),
                          'speaker':int(w.get('speaker') if w.get('speaker') is not None else -1)})
    return _group_words(words)


def _assembly(data: dict) -> list[Cue]:
    utt = data.get('utterances') or []
    words = []
    mapping: dict[str,int] = {}
    def label(s):
        if not s:
            return -1
        if s not in mapping:
            mapping[s] = len(mapping)
        return mapping[s]
    for u in utt:
        speaker = label(u.get('speaker'))
        for w in (u.get('words') or [u]):
            if w.get('start') is None or w.get('end') is None:
                continue
            words.append({'start': float(w['start'])/1000, 'end':float(w['end'])/1000,
                          'text':str(w.get('text') or ''), 'speaker':speaker})
    if not words:
        for w in data.get('words') or []:
            if w.get('start') is not None and w.get('end') is not None:
                words.append({'start':float(w['start'])/1000, 'end':float(w['end'])/1000,
                              'text':str(w.get('text') or ''), 'speaker':label(w.get('speaker'))})
    return _group_words(words)


def transcribe(wav: Path, provider: str, key: str, stop: Event, report: Callable[[str], None]) -> list[Cue]:
    if not key.strip():
        raise ValueError('请填写语音识别 API Key')
    with requests.Session() as session:
        if provider == ASR[0]:
            report('Deepgram Nova-3 正在识别英语对白…')
            url = 'https://api.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true&utterances=true&diarize_model=latest'
            with wav.open('rb') as stream:
                r = _request(session,'POST',url,stop,headers={'Authorization': 'Token '+key,
                           'Content-Type':'audio/wav'},data=stream,timeout=(30,650))
            return _deepgram(r.json())
        if provider != ASR[1]:
            raise ValueError('语音识别平台不受支持')
        report('AssemblyAI 正在上传 WAV 音频…')
        with wav.open('rb') as stream:
            r = _request(session,'POST','https://api.assemblyai.com/v2/upload',stop,
                         headers={'Authorization':key,'Content-Type':'application/octet-stream'},data=stream,timeout=(30,650))
        url = r.json().get('upload_url', '')
        if not url.startswith('https://'):
            raise RuntimeError('AssemblyAI 未返回可用音频 URL')
        report('AssemblyAI STT-3.7 已提交识别任务…')
        r = _request(session,'POST','https://api.assemblyai.com/v2/transcript',stop,
                     headers={'Authorization':key},json={'audio_url':url,'speech_models':['assemblyai-stt-3-7'],
                                                          'language_code':'en','speaker_labels':True})
        job = str(r.json().get('id') or '')
        if not re.fullmatch(r'[A-Za-z0-9_-]{6,128}',job):
            raise RuntimeError('AssemblyAI 未返回正确的任务编号')
        for _ in range(1200):
            if stop.is_set():
                raise InterruptedError('任务已停止')
            r = _request(session,'GET','https://api.assemblyai.com/v2/transcript/'+job,stop,
                         headers={'Authorization':key},timeout=(25,75))
            data = r.json()
            if data.get('status') == 'completed':
                used = data.get('speech_model_used','')
                if used and used != 'assemblyai-stt-3-7':
                    raise RuntimeError('AssemblyAI 返回的模型不符合 STT-3.7 设置')
                return _assembly(data)
            if data.get('status') == 'error':
                raise RuntimeError('AssemblyAI 识别失败，请检查服务商控制台，避免重复计费')
            if data.get('status') not in ('queued','processing'):
                raise RuntimeError('AssemblyAI 返回未知任务状态')
            if stop.wait(3):
                raise InterruptedError('任务已停止')
        raise TimeoutError('AssemblyAI 识别超过一小时，任务可能仍在服务端运行')


def normalized_endpoint(endpoint: str) -> str:
    parsed = urlsplit(endpoint.strip())
    if parsed.scheme.lower() != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('翻译接口必须使用有效的 HTTPS 地址，不含用户名、查询参数或片段')
    base = endpoint.strip().rstrip('/')
    return base if base.endswith('/chat/completions') else base+'/chat/completions'

TRANSLATE_PROMPT = ("你是英语影视对白到简体中文的专业字幕翻译。按 target 的每个 id 返回一条非空中文字幕。"
 "保持原文的粗口、成人含义、喘息、呻吟与重复发声，不审查式净化，也不能编造未识别内容。"
 "结合 previous_english、next_english、previous_chinese、sentence_groups 及 dialogue_lexicon_hints 判断双关和多义词。"
 "词库是语境提示而不是固定替换：come here 是过来，get off the bus 是下车；只有明确成人语境才按成人义翻译。"
 "不要改变说话人、数量、否定、时间码、人物关系；不要加姓名或 A/B 前缀。只返回 JSON：{\"items\":[{\"id\":1,\"zh\":\"中文译文\"}]}。")
POLISH_PROMPT = ("你是中文影视字幕润色员。对照英文、初译 draft、词库提示和整句语境，纠正断裂、重复、孤立助词、误译的否定和粗话。"
 "保持每条字幕 id，不能合并人声或删掉真实呻吟/惊呼，不能添色情动作，不改时间轴。仅 JSON items[id,zh]。")
REVIEW_PROMPT = ("你是字幕质量终审。根据 target 的英文及当前中文、相邻中文和 dialogue_lexicon_hints 检查连续性、数字、否定、俚语、粗口、发声。"
 "已经正确的句子原样返回，不得误删/添加内容；按每个 id 返回非空 zh。仅 JSON items[id,zh]。")


def _chat(session, endpoint: str, key: str, model: str, system: str, data: dict,
          usage: Usage, stop: Event, on_usage: Callable[[],None]) -> dict:
    payload = {'model':model,'messages':[{'role':'system','content':system},
                                         {'role':'user','content':json.dumps(data,ensure_ascii=False)}], 'stream':False}
    for attempt in range(3):
        if stop.is_set():
            raise InterruptedError('任务已停止')
        # Do not repeat ambiguous timeouts; only retry explicit HTTP 429.
        r = session.post(endpoint,headers={'Authorization':'Bearer '+key},json=payload,
                         timeout=(30,150),allow_redirects=False)
        if r.status_code == 429 and attempt < 2:
            raw = r.headers.get('Retry-After','3')
            try: seconds=max(1,min(30,float(raw)))
            except ValueError: seconds=3
            r.close()
            if stop.wait(seconds):
                raise InterruptedError('任务已停止')
            continue
        if not r.ok:
            code=r.status_code
            r.close()
            raise RuntimeError(f'翻译 API 请求失败 HTTP {code}（未自动重复未知计费请求）')
        response = r.json()
        usage.record(response)
        on_usage()  # Record tokens even if later text/JSON validation fails.
        choices=response.get('choices') or []
        if not choices or choices[0].get('finish_reason') != 'stop':
            raise RuntimeError('模型返回结果不完整，请暂停后从断点继续')
        text = choices[0].get('message',{}).get('content','')
        if not isinstance(text,str) or '{' not in text or '}' not in text:
            raise RuntimeError('模型未返回 JSON 字幕')
        return json.loads(text[text.index('{'):text.rindex('}')+1])
    raise RuntimeError('翻译 API 限频重试已耗尽')


def _mapping(result: dict, part: list[Cue]) -> dict[int,str]:
    items = result.get('items')
    if not isinstance(items,list) or len(items)!=len(part):
        raise RuntimeError('模型返回字幕数量不正确，未写入断点')
    got={}
    for item in items:
        if not isinstance(item,dict) or type(item.get('id')) is not int or not isinstance(item.get('zh'),str):
            raise RuntimeError('模型返回字幕字段错误')
        ident=item['id']; zh=item['zh'].strip()
        if ident in got or not zh or len(zh)>250:
            raise RuntimeError('模型返回重复、空白或过长字幕')
        got[ident]=zh
    if set(got)!={c.id for c in part}:
        raise RuntimeError('模型返回的字幕编号和目标不匹配')
    return got


def _cues_payload(cues: list[Cue]) -> list[dict]:
    return [{'id':c.id,'en':c.en,'zh':c.zh,'speaker':c.speaker,'start_ms':c.start,'end_ms':c.end} for c in cues]


def _ranges(total: int, batch: int):
    return [(i,min(total,i+batch)) for i in range(0,total,batch)]


def fingerprint(source: Path, asr: str, endpoint: str, model: str, mode: str) -> str:
    stat=source.stat()
    h=hashlib.sha256()
    for value in (str(source.resolve()),str(stat.st_size),str(stat.st_mtime_ns),asr,endpoint,model,mode):
        h.update(value.encode('utf-8'));h.update(b'\0')
    with source.open('rb') as stream:
        h.update(stream.read(128*1024))
    return h.hexdigest()


def checkpoint_path() -> Path:
    return APP_DIR/'checkpoint.json'


def load_checkpoint(identity: str) -> dict | None:
    data=json_load(checkpoint_path())
    if not isinstance(data,dict) or data.get('identity')!=identity:
        return None
    try:
        cues=[Cue(**c) for c in data['cues']]
        if not 0<len(cues)<=4000 or [c.id for c in cues]!=list(range(1,len(cues)+1)):
            return None
        if any(c.end <= c.start or not c.en for c in cues):
            return None
        first,review,deep=(int(data.get(s,0)) for s in ('first','review','deep'))
        a,b=_ranges(len(cues),15),_ranges(len(cues),20)
        if first<0 or first>len(a) or review<0 or review>len(b) or deep<0 or deep>len(b):
            return None
        if review and first!=len(a) or deep and review!=len(b):
            return None
        if any(not c.zh.strip() for low,high in a[:first] for c in cues[low:high]):
            return None
        return data
    except (TypeError,ValueError,KeyError):
        return None


def clear_checkpoint() -> None:
    checkpoint_path().unlink(missing_ok=True)


def translate(cues: list[Cue], endpoint: str, key: str, model: str, mode: str,
              identity: str, stop: Event, report: Callable[[str],None],
              usage_report: Callable[[Usage],None], resume: dict | None = None) -> tuple[list[Cue],Usage]:
    if mode not in ('快速','标准','高质量'):
        raise ValueError('未知翻译质量模式')
    endpoint=normalized_endpoint(endpoint)
    if not model.strip() or not key.strip():
        raise ValueError('请填写翻译模型 ID 和 API Key')
    state=resume or {'identity':identity,'first':0,'review':0,'deep':0,'cues':[asdict(c) for c in cues], 'usage':asdict(Usage())}
    if resume:
        cues=[Cue(**c) for c in state['cues']]
    usage=Usage(**state.get('usage',{}))
    def save():
        state['cues']=[asdict(c) for c in cues]
        state['usage']=asdict(usage)
        json_write(checkpoint_path(),state)
    def on_usage():
        save()
        usage_report(usage)
    save()
    with requests.Session() as session:
        for n,(start,end) in enumerate(_ranges(len(cues),15)):
            if n < state['first']:
                continue
            if stop.is_set():
                raise InterruptedError('任务已停止，断点已保存')
            part=cues[start:end]
            context={'target':_cues_payload(part),
                     'previous_english':_cues_payload(cues[max(0,start-6):start]),
                     'next_english':_cues_payload(cues[end:end+6]),
                     'previous_chinese':_cues_payload(cues[max(0,start-7):start]),
                     'dialogue_lexicon_hints':lexicon(' '.join(c.en for c in part))}
            report(f'正在翻译第 {n+1} / {len(_ranges(len(cues),15))} 组')
            mapped=_mapping(_chat(session,endpoint,key,model,TRANSLATE_PROMPT,context,usage,stop,on_usage),part)
            if mode != '快速':
                context['draft']=[{'id':c.id,'zh':mapped[c.id]} for c in part]
                mapped=_mapping(_chat(session,endpoint,key,model,POLISH_PROMPT,context,usage,stop,on_usage),part)
            for c in part:
                c.zh=mapped[c.id]
            state['first']=n+1
            save()
        if mode != '快速':
            review_ranges=_ranges(len(cues),20)
            passes=[('review',REVIEW_PROMPT),('deep',REVIEW_PROMPT+' 复核第二次：仅修改真实错误。')] if mode=='高质量' else [('review',REVIEW_PROMPT)]
            for stage,prompt in passes:
                for n,(start,end) in enumerate(review_ranges):
                    if n < state[stage]:
                        continue
                    if stop.is_set():
                        raise InterruptedError('任务已停止，断点已保存')
                    part=cues[start:end]
                    context={'target':_cues_payload(part),
                             'previous_chinese':_cues_payload(cues[max(0,start-8):start]),
                             'next_chinese':_cues_payload(cues[end:end+8]),
                             'dialogue_lexicon_hints':lexicon(' '.join(c.en for c in part))}
                    report(('高质量深度复核' if stage=='deep' else '全片字幕校验')+f' {n+1}/{len(review_ranges)}')
                    mapped=_mapping(_chat(session,endpoint,key,model,prompt,context,usage,stop,on_usage),part)
                    for c in part:
                        c.zh=mapped[c.id]
                    state[stage]=n+1
                    save()
    return cues,usage


def subtitle_quality(cues: list[Cue]) -> list[str]:
    issues=[]
    for i,c in enumerate(cues):
        z=c.zh.strip()
        if not z:
            issues.append(f'第 {c.id} 条：缺少中文字幕')
        if len(z)>50:
            issues.append(f'第 {c.id} 条：字幕较长')
        if c.end<=c.start:
            issues.append(f'第 {c.id} 条：时间码无效')
        if len(z)>=14 and (c.end-c.start)<2300 and len(z)*1000/max(1,c.end-c.start)>12:
            issues.append(f'第 {c.id} 条：阅读时间可能不足')
        if re.fullmatch('[的了着过得地][。！？]?',z) and len(c.en)>4:
            issues.append(f'第 {c.id} 条：孤立助词')
        if re.search(r"\b(?:not|no|never|don't|can't|won't|doesn't|isn't)\b", c.en.lower()) and not re.search('[不没无别未否勿莫非拒止]',z) and len(z)>1:
            issues.append(f'第 {c.id} 条：英文含否定，需检查是否漏译')
        if re.search(r'\d+',c.en) and not re.search('[0-9零一二两三四五六七八九十百千万亿〇]',z):
            issues.append(f'第 {c.id} 条：英文含数字，需检查数量')
        if i and z and z==cues[i-1].zh and len(z)>=6 and c.en!=cues[i-1].en \
                and not re.fullmatch(r'[（)）(啊呀嗯唔哦噢呃哼哈嗬唉嘶嘿哇呜咦呼喘息呻吟低吟急促轻声\s，。…]+', z):
            issues.append(f'第 {c.id} 条：相邻译文重复，建议核对（保留发声）')
    return issues


def _wrap_chinese(text: str) -> str:
    if len(text)<=25:
        return text
    low,high=max(9,len(text)-29),min(29,len(text)-9)
    if low>high:
        return text
    possible=[]
    for i in range(low,high+1):
        if text[i] in '，。！？；、,.;!?：:)]}）】':
            continue
        if text[i-1].isascii() and text[i].isascii() and text[i-1].isalnum() and text[i].isalnum():
            continue
        score=abs(len(text)-2*i)*3-(24 if text[i-1] in '，。！？；、,;!?' else 0)
        if text[i] in '的了着过得地':
            score+=12
        possible.append((score,i))
    if not possible:
        return text
    i=min(possible)[1]
    return text[:i].strip()+'\\N'+text[i:].strip()


def ass_text(cues: list[Cue]) -> str:
    head=('\ufeff[Script Info]\nTitle: 视频字幕工具 - 单色中文字幕\n'
          'ScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\nPlayResX: 1920\nPlayResY: 1080\n\n'
          '[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n'
          'Style: Default,Noto Sans CJK SC,62,&H00FFFFFF,&H00000000,&H00101010,&H90000000,1,0,0,0,100,100,0,0,1,2.6,0.6,2,65,65,72,1\n\n'
          '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n')
    def clock(ms):
        cs=max(0,ms//10)
        return f'{cs//360000}:{cs//6000%60:02d}:{cs//100%60:02d}.{cs%100:02d}'
    out=[head]
    for c in cues:
        if not c.zh.strip() or c.end<=c.start:
            continue
        clean=c.zh.strip().replace('\\','／').replace('{','（').replace('}','）').replace('\r',' ').replace('\n',' ')
        out.append(f'Dialogue: 0,{clock(c.start)},{clock(c.end)},Default,,0,0,0,,{_wrap_chinese(clean)}\n')
    return ''.join(out)


def export_ass(cues: list[Cue], path: Path) -> None:
    # One complete .ass file, never intermediate SRT/TXT; write atomically.
    path.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile('w',encoding='utf-8-sig',dir=path.parent,delete=False,suffix='.tmp') as fh:
        text=ass_text(cues)
        if text.startswith('\ufeff'):
            text=text[1:]
        fh.write(text)
        fh.flush()
        os.fsync(fh.fileno())
        tmp=Path(fh.name)
    os.replace(tmp,path)


def output_name(file: Path) -> str:
    name=file.stem
    name=re.sub(r'[\\/:*?"<>|\x00-\x1f]','_',name).strip()[:100] or '视频'
    return name+'.zh.ass'


def run_job(video: Path, asr: str, asr_key: str, endpoint: str, model: str, translate_key: str,
            mode: str, destination: Path, stop: Event, report: Callable[[str],None],
            usage_report: Callable[[Usage],None]) -> tuple[list[Cue],Usage,Path]:
    identity=fingerprint(video,asr,normalized_endpoint(endpoint),model,mode)
    state=load_checkpoint(identity)
    if state:
        report('发现相同视频/模型/模式的有效断点，从已完成的字幕批次继续…')
        cues=[Cue(**entry) for entry in state['cues']]
    else:
        with tempfile.TemporaryDirectory(prefix='subtitle_pc_') as folder:
            wav=Path(folder)/'audio.wav'
            to_wav(video,wav,stop,report)
            if stop.is_set():
                raise InterruptedError('已停止')
            cues=transcribe(wav,asr,asr_key,stop,report)
        json_write(checkpoint_path(),{'identity':identity,'first':0,'review':0,'deep':0,
                                      'cues':[asdict(c) for c in cues],'usage':asdict(Usage())})
        state=load_checkpoint(identity)
    report('准备翻译与对白词库…')
    cues,usage=translate(cues,endpoint,translate_key,model,mode,identity,stop,report,usage_report,state)
    path=destination/output_name(video)
    export_ass(cues,path)
    json_write(APP_DIR/'last_result.json',{'source':str(video),'output':str(path),'cues':[asdict(c) for c in cues],
                                          'usage':asdict(usage),'issues':subtitle_quality(cues)})
    clear_checkpoint()  # Delete progress only when ASS and last edit snapshot were saved.
    return cues,usage,path
