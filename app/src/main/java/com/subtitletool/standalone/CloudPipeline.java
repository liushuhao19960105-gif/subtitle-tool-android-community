package com.subtitletool.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deepgram ASR + one-provider three-pass (translation, polishing, cross-batch review) subtitle pipeline. */
public final class CloudPipeline {
    public interface Callback { void update(int percent, String label); }
    private final AtomicBoolean cancel;
    private volatile HttpURLConnection active;
    private static final class RateLimitException extends IOException {
        final int waitSeconds;
        RateLimitException(int seconds){super("API 请求频率受限（HTTP 429）");waitSeconds=seconds;}
    }
    private static final int MAX_JSON=24*1024*1024;
    CloudPipeline(AtomicBoolean cancel){this.cancel=cancel;}
    public void stop() { cancel.set(true);HttpURLConnection c=active;if(c!=null)c.disconnect(); }
    private void check() throws InterruptedException { if(cancel.get()) throw new InterruptedException("任务已取消"); }
    private static byte[] limitedRead(InputStream stream,int max) throws Exception {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192]; int n;
            while((n=in.read(buf))!=-1) {
                if(out.size()+n>max) throw new IOException("API 响应超过允许大小");
                out.write(buf,0,n);
            }
            return out.toByteArray();
        }
    }
    private JSONObject post(String address,String key,String scheme,String mime,byte[] json,File media,int timeoutMs) throws Exception {
        check();
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        active=c;
        c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);c.setDoOutput(true);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization",scheme+" "+key);
        c.setRequestProperty("Content-Type",mime);
        c.setRequestProperty("Accept","application/json");
        long size=media!=null?media.length():json.length;
        if(media!=null && (size<100 || size>2_000_000_000L)) throw new IOException("音频大小不受支持，请尝试较短的素材");
        c.setFixedLengthStreamingMode(size);
        try {
            try(OutputStream out=c.getOutputStream()) {
                if(media!=null) {
                    try(InputStream in=new BufferedInputStream(new FileInputStream(media))) {
                        byte[] buf=new byte[65536];int n;
                        while((n=in.read(buf))!=-1) { check();out.write(buf,0,n); }
                    }
                } else out.write(json);
            }
            check();
            int code=c.getResponseCode();
            if(code<200 || code>=300) {
                // Never expose raw responses, subtitles, or secret material in error UI.
                boolean deepgram=address.startsWith("https://api.deepgram.com/");
                if(deepgram && code==400)throw new IOException(
                    "Deepgram 拒绝音频（HTTP 400）；WAV 已通过本机校验，请检查原片音轨或查看 Deepgram Logs");
                if(deepgram && (code==401 || code==403))throw new IOException(
                    "Deepgram 认证失败（HTTP "+code+"），请检查 Key 权限");
                if(code==429) {
                    int seconds=2;
                    try { seconds=Integer.parseInt(c.getHeaderField("Retry-After")); }
                    catch(Exception ignored) { /* Retry-After date uses fallback. */ }
                    throw new RateLimitException(Math.max(2,Math.min(30,seconds)));
                }
                if(code>=500)throw new IOException("API 服务临时故障（HTTP "+code+"），请稍后再试");
                throw new IOException((deepgram?"Deepgram":"翻译 API")+" 请求失败（HTTP "+code+"）");
            }
            return new JSONObject(new String(limitedRead(c.getInputStream(),MAX_JSON),StandardCharsets.UTF_8));
        } finally { active=null;c.disconnect(); }
    }
    public List<SubtitleData.Cue> transcribe(AudioExtractor.AudioFile file,String dgKey,Callback cb) throws Exception {
        if(dgKey.isEmpty()) throw new IllegalArgumentException("请先保存 Deepgram API Key");
        // Fail locally, before HTTP, if the produced file is not standard PCM WAV.
        if(!"audio/wav".equals(file.mime)) throw new IOException("当前仅支持标准 WAV 上传");
        AudioIntegrity.validatePcmWav(file.file);
        cb.update(23,"WAV 检查通过，正在识别英文对白与说话人…");
        JSONObject data=post("https://api.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true&utterances=true&diarize_model=latest",
                dgKey,"Token",file.mime,null,file.file,610000);
        check();
        List<SubtitleData.Cue> cues=SubtitleData.fromDeepgram(data);
        cb.update(38,"识别完成，共 "+cues.size()+" 段对白");
        return cues;
    }
    private JSONObject chat(String key,String model,String endpoint,JSONArray messages) throws Exception {
        for(int attempt=0;attempt<3;attempt++) {
            check();
            try { return chatOnce(key,model,endpoint,messages); }
            catch(RateLimitException limited) {
                if(attempt==2)throw limited;
                long until=System.nanoTime()+limited.waitSeconds*1000000000L;
                while(System.nanoTime()<until) {
                    check();
                    long left=(until-System.nanoTime())/1000000L;
                    if(left>0)Thread.sleep(Math.min(250,left));
                }
            }
        }
        throw new IOException("频率限制重试已耗尽");
    }
    private JSONObject chatOnce(String key,String model,String endpoint,JSONArray messages) throws Exception {
        JSONObject body=new JSONObject();body.put("model",model);body.put("messages",messages);body.put("stream",false);
        JSONObject response=post(endpoint,key,"Bearer","application/json",
                body.toString().getBytes(StandardCharsets.UTF_8),null,150000);
        JSONArray choices=response.optJSONArray("choices");
        if(choices==null || choices.length()==0) throw new IOException("模型没有返回翻译结果");
        JSONObject choice=choices.getJSONObject(0);
        if(!"stop".equals(choice.optString("finish_reason",""))) throw new IOException("模型输出不完整，已停止生成字幕");
        String text=choice.getJSONObject("message").optString("content","").trim();
        if(text.isEmpty()) throw new IOException("模型返回空译文");
        // Allow the occasional ```json fence while requiring a strict, fully checked JSON object.
        int start=text.indexOf('{'), end=text.lastIndexOf('}');
        if(start<0 || end<=start) throw new IOException("模型输出不是有效 JSON");
        return new JSONObject(text.substring(start,end+1));
    }
    private static JSONArray requestMessages(String system,JSONObject input) throws Exception {
        JSONArray a=new JSONArray();
        a.put(new JSONObject().put("role","system").put("content",system));
        a.put(new JSONObject().put("role","user").put("content",input.toString()));
        return a;
    }
    private static final String TRANSLATE_SYSTEM=
        "你是影视英语对白到简体中文的专业字幕译者。重点是让一整句中文自然、顺畅、连贯，而不是独立翻译每一条英语碎片。"+
        "先通读 target、sentence_groups、前后相邻英文和 previous_chinese，分清人物关系、说话者、语气、问句和否定。"+
        "sentence_groups 仅帮助理解跨多条字幕的同一英文句子，其 ids 仍属于 target；不要把不同说话者的对白合并。"+
        "同一句可以在相邻且属于同一说话者的 ID 之间自然重新分配，但整句话只能翻译一次，不可重复、漏译、交换发言者。"+
        "注意译文跨条拼接后必须是通顺的中文；不要留下单独的‘了。’‘的’‘着’，也不要半句重复下一条。"+
        "口语要自然，保留原意中的否定、疑问、语气与情绪；遇到明显可疑的英文识别不能擅自编造原声不存在的内容。"+
        "特别处理英语俚语、粗口、性暗示和成人对白：先结合整句、人物关系和相邻语境判断词义，再译成中国观众听得懂的自然口语；不要逐词硬译、书面化净化或一律译为同一个脏字。"+
        "保留原话的粗俗程度、攻击性、调情意味、主谓对象及命令语气；原文明显露骨时可直接使用准确的中文粗话或性词汇，不用含糊委婉词代替；原文不露骨时绝不额外色情化。"+
        "fuck、fucking、shit、bitch、ass、dick、cock、pussy、balls、screw、nasty、hard、come/cum、get off、go down、turn on、eat out 等均可能多义；必须区分感叹、骂人、形容、字面动作与具体性含义。"+
        "例如 Fuck! 可依情绪译为‘操！’或‘靠！’，fucking good 是加强语气而非性动作；come here 是‘过来’，只有明确性语境中的 cum/come 才译作‘高潮/射精’等相符含义；hard 也可能只是‘困难/坚硬’。"+
        "不要因出现 fuck、bitch、daddy、baby、good girl 等词就自行推断某种性行为、人物关系或身份；英文指代不明时保持中性，不编造身体部位、姿势、动作或对象。"+
        "尊重语域：辱骂像辱骂、调情像调情、口头禅像口头禅、惊叹像惊叹；中文口语简短有力，不要加入原文没有的脏话，也不要把每句粗口都机械替换成‘该死’。"+
        "素材可能有成人场景。真实的喘息、呻吟、惊叫、短促发声和感叹词都具有观看意义，不是可随意删除的无意义噪声。"+
        "英语识别中的 Ah、Oh、Mm、Uh、Yes 等短促声音和重复次数要尽量保留；不能为了简洁将多个真实发声全部省略或译为空白。"+
        "如果英文明确标注 [moaning]、[gasps]、[breathing heavily] 等非语言声音，可分别译成（呻吟）、（惊喘）、（急促喘息）；"+
        "如果仅有 Ah、Oh 等拟声词，应译成自然的中文发声而不是擅自推测成某种动作；不能把普通口头应答误当作呻吟。"+
        "你只能看到语音识别文本，不能听原音；不得根据场景推测未识别出来的声音或增写色情描写。"+
        "人名、作品名、品牌全片统一，Naughty America 等名称应保留原文，别翻成普通词语。"+
        "不加说话人前缀，不在字幕内加颜色或样式标签。"+
        "严格只返回 JSON：{\"items\":[{\"id\":1,\"zh\":\"中文译文\"}]}，每个 target ID 恰好一条，且非空，不输出 Markdown。";
    private static final String POLISH_SYSTEM=
        "你是中文影视字幕终审，任务是跨条整句润色而不是机械逐条润色。"+
        "结合英文、sentence_groups、邻近上下文和初译 draft，检查每组中文按时间顺序连起来是否自然、是否重复和是否漏掉否定问句。"+
        "重点修正中文断裂，如‘交往 / 也有一小段时间了’、孤立助词、突然的代词转移与口语不自然。"+
        "重点复核双关词与成人俚语的上下文义：fuck、shit、bitch、dick、pussy、come/cum、hard、nasty 等既可能是粗口也可能是其他词义；不能仅凭单词推断性行为。"+
        "核对初译是否无端把露骨粗话洗成文雅话、把普通话翻成色情台词、弄错褒贬强弱、动作对象或主语；在不增删事实的前提下改成简洁地道的中文字幕。"+
        "保持原文的重复、口语情绪和实际粗俗度；润色不得审查式删除已识别出的粗口、脏话、性含义，也不得凭想象添加更露骨的台词。"+
        "特别注意：成人场景中已识别出的喘息、呻吟、惊呼和连续语气词不能当作重复垃圾删除。"+
        "保留每个 target ID 的真实发声，包括多次 Ah、Oh、Mm；必要时让发声自然衔接，但不可合并不同说话者，也不可把纯发声改写成新对白。"+
        "明确的声音事件可以用（喘息）（呻吟）等简短说明，普通 Oh、Yeah 要按实际词义译，不要臆测没有证据的动作。"+
        "允许在相同说话者且同一连续英文句子的相邻 ID 内移动中文词组，让每条都在句意完整处断开。"+
        "严格保留每个 ID 的一份非空中文，时间码由程序管理；不增加或改变台词事实，语音识别明显错误时不要编造细节。"+
        "不同说话者的对白不能重排到对方身上，不加 A/B/C 或人名标签，全部使用普通文字，不返回配色。"+
        "只输出 JSON：{\"items\":[{\"id\":1,\"zh\":\"润色译文\"}]}，每个 target ID 恰好一条，不输出 Markdown。";
    /** Final review runs after every batch has Chinese text, so neighbors are no longer untranslated. */
    private static final String REVIEW_SYSTEM=
        "你是简体中文影视字幕质量校验员。输入为已完成初译和润色的字幕，任务是终审核对英文原文和前后已翻译的中文，纠正跨字幕、跨批次的不连贯问题。"+
        "按时间顺序连读 previous_chinese、target 和 next_chinese，检查人物称呼、代词指向、问答衔接、语气延续、故事逻辑、前后用词和句式一致性。"+
        "逐条对照 target 的 en 及 sentence_groups，核验否定、时态、命令、数字、重复发声、漏译、错译；译文要符合中国人观看影视字幕的习惯，精炼、自然、易于一眼读懂，不使用生硬翻译腔。"+
        "英文俚语、粗口、性暗示及成人对白按具体语境译，保留原意、褒贬和粗俗程度；不能审查式净化已识别的内容，也不可把普通词汇误改成性行为或凭想象增加色情台词。"+
        "已识别出的 Ah、Oh、Mm、Uh、惊喘及声音事件不得删除；不同人的对白不能混合或改换说话者；保留人名、品牌原文和术语一致性。"+
        "仅可修改 target 中各条的 zh，previous_chinese 和 next_chinese 只供理解、不得作为输出；如果现有文字无问题请原样返回，避免无意义改写。"+
        "保持 target ID 的顺序、数量及对应关系，每个 ID 都必须返回一条非空中文，不改变时间轴，不使用说话者标签、彩色或排版命令。"+
        "严格只返回 JSON：{\"items\":[{\"id\":1,\"zh\":\"校验后字幕\"}]}，不输出 Markdown。";
    public void translateAndPolish(List<SubtitleData.Cue> cues,String key,String endpoint,String model,Callback cb)throws Exception {
        translateAndPolish(cues,key,endpoint,model,cb,null,null);
    }
    public void translateAndPolish(List<SubtitleData.Cue> cues,String key,String endpoint,String model,
                                   Callback cb,TranslationCheckpoint checkpoint,android.content.Context context)throws Exception {
        endpoint=ProviderPresets.normalizeEndpoint(endpoint);
        if(key==null || key.trim().isEmpty()) throw new IllegalArgumentException("请先保存翻译服务商的 API Key");
        if(model==null || model.trim().isEmpty()) throw new IllegalArgumentException("请填写翻译模型 ID");
        final List<int[]> ranges=batchRanges(cues,15);
        final int batches=ranges.size();
        StringBuilder previousSummary=new StringBuilder();
        for(int n=0;n<batches;n++) {
            check();
            int from=ranges.get(n)[0],to=ranges.get(n)[1];
            JSONObject input=new JSONObject();
            input.put("target",toArray(cues,from,to,true));
            input.put("sentence_groups",sentenceGroups(cues,from,to));
            input.put("previous_english",toArray(cues,Math.max(0,from-6),from,false));
            input.put("next_english",toArray(cues,to,Math.min(cues.size(),to+6),false));
            input.put("previous_chinese",previousSummary.toString());
            cb.update(39+(int)(50.0*n/Math.max(1,batches)),"正在翻译第 "+(n+1)+"/"+batches+" 组…");
            JSONObject translated=chat(key,model,endpoint,requestMessages(TRANSLATE_SYSTEM,input));
            Map<Integer,String> mapping=parseMapping(translated,cues,from,to);
            JSONObject polish=new JSONObject(input.toString());
            polish.put("draft",mappingJson(mapping,cues,from,to));
            cb.update(39+(int)(50.0*(n+.5)/Math.max(1,batches)),"正在同模型润色第 "+(n+1)+"/"+batches+" 组…");
            JSONObject polished=chat(key,model,endpoint,requestMessages(POLISH_SYSTEM,polish));
            Map<Integer,String> finalMapping=parseMapping(polished,cues,from,to);
            // Make final atomic only when all returned IDs pass validation.
            for(int i=from;i<to;i++) cues.get(i).zh=finalMapping.get(cues.get(i).id);
            previousSummary.setLength(0);
            for(int i=Math.max(0,to-7);i<to;i++) previousSummary.append(cues.get(i).zh).append(' ');
        }
        // Review after translation of the whole video, not independently inside each first-pass batch.
        final List<int[]> reviewRanges=batchRanges(cues,20);
        for(int n=0;n<reviewRanges.size();n++) {
            check();
            int from=reviewRanges.get(n)[0],to=reviewRanges.get(n)[1];
            JSONObject input=new JSONObject();
            input.put("target",toArray(cues,from,to,true));
            input.put("sentence_groups",sentenceGroups(cues,from,to));
            input.put("previous_chinese",toArray(cues,Math.max(0,from-8),from,true));
            input.put("next_chinese",toArray(cues,to,Math.min(cues.size(),to+8),true));
            cb.update(89+(int)(5.0*n/Math.max(1,reviewRanges.size())),
                "正在校验中文字幕连贯性 "+(n+1)+"/"+reviewRanges.size()+" 组…");
            JSONObject checked=chat(key,model,endpoint,requestMessages(REVIEW_SYSTEM,input));
            Map<Integer,String> reviewed=parseMapping(checked,cues,from,to);
            // Apply only after the complete batch passed the existing strict ID and nonempty checks.
            for(int i=from;i<to;i++) cues.get(i).zh=reviewed.get(cues.get(i).id);
        }
        check();cb.update(94,"字幕翻译与整体校验完成，正在生成单色字幕…");
    }
    /** Prefer breaking requests at a speaker change, pause or English sentence boundary. */
    private static List<int[]> batchRanges(List<SubtitleData.Cue> cues,int desired) {
        List<int[]> result=new ArrayList<>();
        for(int from=0;from<cues.size();) {
            int nominal=Math.min(cues.size(),from+desired);
            int end=nominal;
            if(nominal<cues.size()) {
                int bestDistance=Integer.MAX_VALUE;
                int low=Math.max(from+8,nominal-3);
                int high=Math.min(cues.size()-1,nominal+3);
                for(int b=low;b<=high;b++) {
                    if(!isBoundary(cues.get(b-1),cues.get(b))) continue;
                    int distance=Math.abs(b-nominal);
                    if(distance<bestDistance){end=b;bestDistance=distance;}
                }
            }
            result.add(new int[]{from,end});from=end;
        }
        return result;
    }
    private static boolean isBoundary(SubtitleData.Cue left,SubtitleData.Cue right) {
        if(left.speaker>=0 && right.speaker>=0 && left.speaker!=right.speaker)return true;
        if(right.startMs-left.endMs>=1000)return true;
        return left.en!=null && left.en.trim().matches("(?s).*[.!?]\\s*$");
    }
    /** Context hint only: not a permission to merge different speakers. */
    private static JSONArray sentenceGroups(List<SubtitleData.Cue> cues,int from,int to)throws Exception {
        JSONArray result=new JSONArray();
        int i=from;
        while(i<to) {
            int end=i+1;
            while(end<to && end-i<6) {
                SubtitleData.Cue left=cues.get(end-1),right=cues.get(end);
                if(left.speaker<0 || right.speaker<0 || left.speaker!=right.speaker ||
                   right.startMs-left.endMs>1200 || right.endMs-cues.get(i).startMs>11000 ||
                   isBoundary(left,right))break;
                end++;
            }
            JSONArray ids=new JSONArray();StringBuilder en=new StringBuilder();
            for(int k=i;k<end;k++) {
                SubtitleData.Cue cue=cues.get(k);ids.put(cue.id);
                if(en.length()>0)en.append(' ');
                en.append(cue.en==null?"":cue.en);
            }
            result.put(new JSONObject().put("ids",ids).put("speaker",cues.get(i).speaker)
                       .put("english_whole",en.toString()));
            i=end;
        }
        return result;
    }
    private JSONArray toArray(List<SubtitleData.Cue> cues,int start,int end,boolean includeId)throws Exception {
        JSONArray array=new JSONArray();
        for(int i=start;i<end;i++) {
            SubtitleData.Cue c=cues.get(i);JSONObject o=new JSONObject();
            if(includeId){o.put("id",c.id);o.put("start_ms",c.startMs);o.put("end_ms",c.endMs);}
            o.put("en",c.en);o.put("speaker",c.speaker);
            if(!c.zh.isEmpty())o.put("zh",c.zh);
            array.put(o);
        }
        return array;
    }
    private JSONArray mappingJson(Map<Integer,String> mapping,List<SubtitleData.Cue> cues,int from,int to)throws Exception {
        JSONArray out=new JSONArray();
        for(int i=from;i<to;i++) out.put(new JSONObject().put("id",cues.get(i).id).put("zh",mapping.get(cues.get(i).id)));
        return out;
    }
    private static Map<Integer,String> parseMapping(JSONObject result,List<SubtitleData.Cue> cues,int from,int to)throws Exception {
        JSONArray arr=result.optJSONArray("items");
        if(arr==null || arr.length()!=to-from) throw new IOException("模型返回字幕条数与目标不一致，未写入结果");
        Map<Integer,String> map=new HashMap<>();
        for(int i=0;i<arr.length();i++) {
            JSONObject x=arr.optJSONObject(i);
            if(x==null || !x.has("id") || !x.has("zh")) throw new IOException("模型返回的字幕格式无效");
            int id=x.getInt("id");String translated=x.getString("zh").trim();
            if(translated.isEmpty() || translated.length()>250 || map.put(id,translated)!=null)
                throw new IOException("模型返回空白、重复或过长的字幕");
        }
        for(int i=from;i<to;i++) if(!map.containsKey(cues.get(i).id))
            throw new IOException("模型返回字幕编号不匹配，未修改原时间轴");
        return map;
    }
}
