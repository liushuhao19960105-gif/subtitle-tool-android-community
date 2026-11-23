package com.subtitletool.standalone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single-color ASS output. Speaker metadata is used ONLY to avoid joining unrelated dialogue. */
public final class AssWriter {
    public static final int FONT_SIZE = 62;
    public static final String RENDER_MARKER = "; SubtitleTool-Renderer: 1.5-monochrome";
    // ASS color notation: &HAABBGGRR. Opaque white with near-black edge.
    private static final String TEXT_COLOR = "&H00FFFFFF";
    private static final class Line {
        long start,end;int speaker;boolean uncertain;String text;
        Line(SubtitleData.Cue c,String t){start=c.startMs;end=c.endMs;speaker=c.speaker;uncertain=c.uncertain;text=t;}
    }
    public static String render(List<SubtitleData.Cue> cues) {
        StringBuilder s=new StringBuilder(8192);
        s.append("\uFEFF[Script Info]\nTitle: 视频字幕工具 - 单色中文字幕\n").append(RENDER_MARKER)
         .append("\nScriptType: v4.00+\nWrapStyle: 0\nScaledBorderAndShadow: yes\nPlayResX: 1920\nPlayResY: 1080\n\n")
         .append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        s.append("Style: Default,Noto Sans CJK SC,").append(FONT_SIZE).append(',').append(TEXT_COLOR)
         .append(",&H00000000,&H00101010,&H90000000,1,0,0,0,100,100,0,0,1,2.6,0.6,2,65,65,72,1\n")
         .append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        List<Line> lines=new ArrayList<>();
        for(SubtitleData.Cue cue:cues) {
            if(cue.zh==null || cue.zh.trim().isEmpty() || cue.endMs<=cue.startMs)continue;
            String clean=cue.zh.trim().replaceAll("^[A-Za-z][：:]\\s*","");
            if(clean.isEmpty())continue;
            Line prev=lines.isEmpty()?null:lines.get(lines.size()-1);
            // Never join two explicit, different speaker IDs, even though the visual color is the same.
            // For unknown IDs, join only a tiny grammatical tail at a near-zero gap.
            if(prev!=null && canJoin(prev,cue,clean)) {
                // Sound events are real content: combine display spans without dropping a single occurrence.
                prev.text=isPureVocalization(prev.text) && isPureVocalization(clean)
                    ? joinVocalizations(prev.text,clean) : joinChinese(prev.text,clean);
                prev.end=Math.max(prev.end,cue.endMs);
                prev.uncertain |= cue.uncertain;
                continue;
            }
            lines.add(new Line(cue,clean));
        }
        // Increase viewing time only in genuine gaps, without creating overlaps.
        for(int i=0;i<lines.size();i++){
            Line a=lines.get(i);
            if(a.end-a.start>=900)continue;
            long bound=i+1<lines.size()?lines.get(i+1).start-70:a.end;
            long safe=Math.min(a.start+1100,bound);
            if(safe>a.end)a.end=safe;
        }
        for(int i=0;i<lines.size();i++){
            Line a=lines.get(i);
            String displayed=wrap(escape(a.text));
            // Position simultaneous, explicitly different speakers separately; both remain white.
            if(i>0 && a.start<lines.get(i-1).end && a.speaker>=0 &&
                    lines.get(i-1).speaker>=0 && a.speaker!=lines.get(i-1).speaker)
                displayed="{\\an8}"+displayed;
            s.append("Dialogue: 0,").append(time(a.start)).append(',').append(time(a.end))
             .append(",Default,,0,0,0,,").append(displayed).append('\n');
        }
        return s.toString();
    }
    private static boolean canJoin(Line a,SubtitleData.Cue b,String btext) {
        long gap=b.startMs-a.end;
        if(gap<0)return false;
        int count=visibleLength(a.text)+visibleLength(btext);
        if(count>43)return false;
        long span=b.endMs-a.start;
        if(a.speaker>=0 && b.speaker>=0 && a.speaker!=b.speaker)return false;
        boolean vocalA=isPureVocalization(a.text), vocalB=isPureVocalization(btext);
        // Keep sound events distinct from spoken dialogue. Only group close, same-speaker
        // sounds, and keep each vocalization in the output. Unknown speakers are NOT guessed.
        if(vocalA || vocalB) {
            return vocalA && vocalB && a.speaker>=0 && a.speaker==b.speaker &&
                   gap<=(a.uncertain || b.uncertain ? 180 : 450) &&
                   span<=5600 && count<=34;
        }
        if(endsSentence(a.text))return false;
        boolean small=visibleLength(btext)<=5;
        // Same known speaker: allow a small gap to repair chopped clauses and trailing particles.
        if(a.speaker>=0 && a.speaker==b.speaker) {
            long maxGap=(a.uncertain || b.uncertain)?500:1050;
            if(gap>maxGap)return false;
            if(small && span<=9300)return true;
            if(gap<=380 && span<=7000 && count<=37)return true;
            return gap<=850 && span<=9500 && visibleLength(a.text)<=13 &&
                   visibleLength(btext)<=18 && count<=34;
        }
        // Two unknown speaker IDs: repair very short grammatical fragments only.
        if(a.speaker<0 && b.speaker<0)
            return gap<=140 && small && span<=5500 && count<=30;
        return false;
    }
    /** Classify ONLY standalone interjections or explicitly transcribed sound captions.
     *  Avoid treating meaningful speech such as "是啊" or "怎么了" as an expendable noise. */
    private static boolean isPureVocalization(String text) {
        if(text==null || text.trim().isEmpty())return false;
        StringBuilder letters=new StringBuilder();
        String clean=text.replace("\\N","");
        for(int i=0;i<clean.length();i++) {
            char c=clean.charAt(i);
            if(Character.isLetter(c) || Character.isDigit(c)) letters.append(c);
        }
        String token=letters.toString();
        if(token.isEmpty() || token.length()>20)return false;
        String[] soundLabels={"急促喘息","轻声喘息","喘息","呻吟","低吟","尖叫",
                "惊叫","惊呼","抽泣","哭泣","笑声","轻笑","大笑","喘气"};
        String singles="啊呀嗯唔哦噢呃哼哈嗬唉嘶嘿哇呜咦噫呼";
        // A previously grouped sequence (e.g. '（喘息）……（呻吟）') is still
        // a vocalization; otherwise the third sound would not join the group.
        for(int at=0;at<token.length();) {
            boolean found=false;
            for(String label:soundLabels) {
                if(token.startsWith(label,at)) { at+=label.length(); found=true; break; }
            }
            if(!found) {
                if(singles.indexOf(token.charAt(at))<0)return false;
                at++;
            }
        }
        return true;
    }
    private static String joinVocalizations(String a,String b) {
        // Retain all syllables and explicit sound descriptions; only remove weak trailing
        // punctuation at the JOIN, so rapid fragments display as one continuous caption.
        String left=a.replaceAll("[。，、,.]+$","").trim();
        String right=b.replaceAll("^[。，、,.]+","").replaceAll("[。，、,.]+$","").trim();
        return left.endsWith("……") ? left+right : left+"……"+right;
    }
    private static boolean endsSentence(String x){return x.matches("(?s).*[。！？!?]\\s*$");}
    private static int visibleLength(String x){return x==null?0:x.replace("\\N", "").trim().length();}
    private static String joinChinese(String a,String b) {
        if(!a.isEmpty() && !b.isEmpty() && a.charAt(a.length()-1)<128 && b.charAt(0)<128 &&
           Character.isLetter(a.charAt(a.length()-1))&&Character.isLetter(b.charAt(0)))
            return a+" "+b;
        return a+b;
    }
    private static String escape(String raw) {
        return raw.replace("\\","／").replace("{","（").replace("}","）")
                  .replace("\r"," ").replace("\n"," ").trim();
    }
    private static String wrap(String txt) {
        if(txt.length()<=25)return txt;
        int pivot=-1,scoreBest=Integer.MAX_VALUE;
        for(int i=10;i<txt.length()-9;i++){
            if("，。！？；、,;!? ".indexOf(txt.charAt(i))<0)continue;
            int left=i+1,right=txt.length()-left;
            if(left>29 || right>29)continue;
            int score=Math.abs(left-right)+(left<12?7:0)+(right<12?7:0);
            if(score<scoreBest){pivot=left;scoreBest=score;}
        }
        return pivot<0?txt:txt.substring(0,pivot).trim()+"\\N"+txt.substring(pivot).trim();
    }
    private static String time(long milli) {
        long cs=Math.max(0,milli/10);
        long h=cs/360000,m=(cs/6000)%60,sec=(cs/100)%60,hund=cs%100;
        return String.format(Locale.US,"%d:%02d:%02d.%02d",h,m,sec,hund);
    }
    public static String outputName(String source) {
        if(source==null || source.trim().isEmpty())source="视频";
        source=source.replace('\\','_').replace('/','_').replaceAll("[\\p{Cntrl}<>:\"|?*]","_").trim();
        int dot=source.lastIndexOf('.');
        if(dot>0)source=source.substring(0,dot);
        if(source.length()>100)source=source.substring(0,100);
        if(source.isEmpty())source="视频";
        return source+".zh.ass";
    }
}
