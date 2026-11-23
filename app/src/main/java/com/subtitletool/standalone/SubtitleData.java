package com.subtitletool.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public final class SubtitleData {
    public static final class Cue {
        public final int id;
        public final long startMs, endMs;
        public final String en;
        public final int speaker; // -1 unknown, otherwise stable (unverified) ASR identifier
        public final boolean uncertain;
        public String zh = "";
        public Cue(int id, long startMs, long endMs, String en, int speaker, boolean uncertain) {
            this.id=id; this.startMs=startMs; this.endMs=Math.max(startMs + 250, endMs);
            this.en=en; this.speaker=speaker; this.uncertain=uncertain;
        }
    }
    private static final class Word {
        double start,end;
        String text;
        int speaker;
        boolean uncertain;
        Word(JSONObject w, int fallback) {
            start = w.optDouble("start", -1); end = w.optDouble("end", -1);
            text = w.optString("punctuated_word", w.optString("word", "")).trim();
            speaker = w.has("speaker") && !w.isNull("speaker") ? w.optInt("speaker", -1) : fallback;
            uncertain = speaker < 0;
            if(w.has("speaker_confidence") && !w.isNull("speaker_confidence")) {
                double conf=w.optDouble("speaker_confidence", -1);
                if (conf >= 0 && conf < .65) uncertain = true;
            }
        }
        Word(double startSec,double endSec,String content,int speaker,boolean uncertain) {
            this.start=startSec;this.end=endSec;this.text=content==null?"":content.trim();
            this.speaker=speaker;this.uncertain=uncertain || speaker<0;
        }
        boolean valid() { return start >= 0 && end > start && end < 86400 && !text.isEmpty(); }
    }
    public static List<Cue> fromDeepgram(JSONObject json) throws Exception {
        List<Word> words=new ArrayList<>();
        JSONObject results=json.getJSONObject("results");
        JSONArray utterances=results.optJSONArray("utterances");
        if(utterances != null && utterances.length()>0) {
            for(int i=0;i<utterances.length();i++) {
                JSONObject u=utterances.optJSONObject(i);
                if(u==null) continue;
                int speaker=u.optInt("speaker", -1);
                JSONArray stream=u.optJSONArray("words");
                if(stream==null) continue;
                for(int j=0;j<stream.length();j++) {
                    JSONObject w=stream.optJSONObject(j);
                    if(w==null) continue;
                    Word word=new Word(w,speaker);
                    if(word.valid()) words.add(word);
                }
            }
        } else {
            JSONArray channels=results.getJSONArray("channels");
            JSONObject alternative=channels.getJSONObject(0).getJSONArray("alternatives").getJSONObject(0);
            JSONArray stream=alternative.getJSONArray("words");
            for(int j=0;j<stream.length();j++) {
                JSONObject w=stream.optJSONObject(j);
                if(w==null) continue;
                Word word=new Word(w,-1);
                if(word.valid()) words.add(word);
            }
        }
        return groupWords(words,"Deepgram");
    }
    /** AssemblyAI word boundaries are integer milliseconds, unlike Deepgram's seconds. */
    public static List<Cue> fromAssemblyAI(JSONObject json) throws Exception {
        List<Word> words=new ArrayList<>();
        Map<String,Integer> speakers=new HashMap<>();
        JSONArray utterances=json.optJSONArray("utterances");
        if(utterances!=null && utterances.length()>0) {
            for(int i=0;i<utterances.length();i++) {
                JSONObject u=utterances.optJSONObject(i);if(u==null)continue;
                int speaker=assemblySpeaker(u.optString("speaker",""),speakers);
                JSONArray stream=u.optJSONArray("words");
                if(stream!=null && stream.length()>0) {
                    for(int j=0;j<stream.length();j++) {
                        JSONObject w=stream.optJSONObject(j);
                        if(w!=null)addAssemblyWord(words,w,speaker,speakers);
                    }
                } else {
                    // Some utterance configurations omit per-word data. Preserve the
                    // whole utterance with its millisecond timestamps instead of losing it.
                    addAssemblyWord(words,u,speaker,speakers);
                }
            }
        } else {
            JSONArray stream=json.optJSONArray("words");
            if(stream!=null)for(int j=0;j<stream.length();j++) {
                JSONObject w=stream.optJSONObject(j);
                if(w!=null)addAssemblyWord(words,w,-1,speakers);
            }
        }
        return groupWords(words,"AssemblyAI");
    }
    private static int assemblySpeaker(String label,Map<String,Integer> speakers) {
        if(label==null || label.isEmpty() || "null".equalsIgnoreCase(label))return -1;
        Integer existing=speakers.get(label);
        if(existing!=null)return existing;
        int assigned=speakers.size();speakers.put(label,assigned);return assigned;
    }
    private static void addAssemblyWord(List<Word> words,JSONObject w,int fallback,
                                        Map<String,Integer> speakers) {
        if(!w.has("start") || !w.has("end"))return;
        long start=w.optLong("start",-1),end=w.optLong("end",-1);
        String content=w.optString("text","");
        int speaker=w.has("speaker")&&!w.isNull("speaker")?
            assemblySpeaker(w.optString("speaker",""),speakers):fallback;
        double confidence=w.optDouble("confidence",1.0);
        // Speaker ID and ASR confidence are independent. Mark low-confidence
        // words uncertain for internal quality handling; never display a label.
        Word word=new Word(start/1000.0,end/1000.0,content,speaker,confidence<0.55);
        if(word.valid())words.add(word);
    }
    private static List<Cue> groupWords(List<Word> words,String provider) {
        words.sort((a,b) -> Double.compare(a.start,b.start));
        if(words.isEmpty()) throw new IllegalArgumentException(provider+" 没有返回有效英文语音时间轴");
        List<Cue> cues=new ArrayList<>();
        List<Word> batch=new ArrayList<>();
        for (Word w: words) {
            if(!batch.isEmpty()) {
                Word last=batch.get(batch.size()-1);
                double len=w.end-batch.get(0).start;
                int chars=0; for(Word b:batch) chars+=b.text.length()+1;
                // Give an English clause more room before imposing a mechanical split;
                // a forced split still occurs for speaker changes, long silence,
                // very long captions or exceptionally long audio spans.
                if(w.speaker != last.speaker || w.start-last.end > .75 || len>6.8 || chars+w.text.length()>75) {
                    append(cues,batch); batch.clear();
                }
            }
            batch.add(w);
            if ((w.text.endsWith(".") || w.text.endsWith("!") || w.text.endsWith("?")) &&
                    w.end-batch.get(0).start>=1.25) {
                append(cues,batch); batch.clear();
            }
        }
        append(cues,batch);
        if(cues.size()>4000) throw new IllegalArgumentException("字幕片段过多，暂不支持");
        return cues;
    }
    private static void append(List<Cue> cues, List<Word> batch) {
        if(batch.isEmpty()) return;
        StringBuilder sb=new StringBuilder();
        int lowConfidence=0;
        for(Word w: batch) {
            if(sb.length()>0) sb.append(' ');
            sb.append(w.text);
            if(w.uncertain) lowConfidence++;
        }
        Word first=batch.get(0); Word last=batch.get(batch.size()-1);
        // A single moderately uncertain word should not turn a whole 12-word
        // utterance white. Never color a segment with no speaker ID; keep the
        // unknown/low-confidence segments neutral instead of guessing gender.
        boolean uncertain=first.speaker<0 ||
                (lowConfidence>=2 && lowConfidence*3>=batch.size());
        cues.add(new Cue(cues.size()+1, Math.round(first.start*1000), Math.round(last.end*1000),
                sb.toString(),first.speaker,uncertain));
    }
    public static JSONArray toJson(List<Cue> cues) throws Exception {
        JSONArray a=new JSONArray();
        for(Cue c:cues) {
            JSONObject o=new JSONObject(); o.put("id",c.id);o.put("start",c.startMs);o.put("end",c.endMs);
            o.put("en",c.en);o.put("zh",c.zh);o.put("speaker",c.speaker);o.put("uncertain",c.uncertain);
            a.put(o);
        }
        return a;
    }
    public static List<Cue> fromJson(JSONArray a) throws Exception {
        List<Cue> cues=new ArrayList<>();
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            Cue c=new Cue(o.getInt("id"),o.getLong("start"),o.getLong("end"),
                    o.getString("en"),o.getInt("speaker"),o.optBoolean("uncertain",false));
            c.zh=o.optString("zh",""); cues.add(c);
        }
        return cues;
    }
}
