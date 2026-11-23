package com.subtitletool.standalone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Offline, advisory checks. Never rewrites dialogue or makes paid AI requests. */
final class SubtitleQuality {
    static final class Finding {
        final int cueId;
        final String concern;
        Finding(int cueId,String concern){this.cueId=cueId;this.concern=concern;}
        @Override public String toString(){return "第 "+cueId+" 条："+concern;}
    }
    static final class Report {
        final int cueCount;
        final List<Finding> findings;
        Report(int cueCount,List<Finding> findings){
            this.cueCount=cueCount;
            this.findings=Collections.unmodifiableList(new ArrayList<>(findings));
        }
        String summary(){
            return findings.isEmpty()?"字幕质量初检未发现明显异常":
                    "字幕质量初检：发现 "+findings.size()+" 处建议复核";
        }
        String firstDetails(int limit) {
            StringBuilder out=new StringBuilder(summary());
            for(int i=0;i<findings.size() && i<limit;i++)
                out.append("\n").append(findings.get(i));
            if(findings.size()>limit)out.append("\n其余 ").append(findings.size()-limit).append(" 处请在字幕预览功能中复核");
            return out.toString();
        }
    }
    private SubtitleQuality(){}
    /** Classify only genuinely isolated vocalizations, including adjacent grouped sounds. */
    private static boolean vocalOnly(String text){
        if(text==null || text.trim().isEmpty())return false;
        StringBuilder letters=new StringBuilder();
        String clean=text.replace("\\N","");
        for(int i=0;i<clean.length();i++){
            char c=clean.charAt(i);
            if(Character.isLetter(c) || Character.isDigit(c))letters.append(c);
        }
        String token=letters.toString();
        if(token.isEmpty() || token.length()>32)return false;
        String[] labels={"急促喘息","轻声喘息","喘息","呻吟","低吟","尖叫",
            "惊叫","惊呼","抽泣","哭泣","笑声","轻笑","大笑","喘气"};
        String singles="啊呀嗯唔哦噢呃哼哈嗬唉嘶嘿哇呜咦噫呼";
        for(int at=0;at<token.length();){
            boolean matched=false;
            for(String label:labels){
                if(token.startsWith(label,at)){at+=label.length();matched=true;break;}
            }
            if(!matched){
                if(singles.indexOf(token.charAt(at))<0)return false;
                at++;
            }
        }
        return true;
    }
    static Report inspect(List<SubtitleData.Cue> cues){
        List<Finding> issues=new ArrayList<>();
        if(cues==null)return new Report(0,issues);
        Set<Integer> ids=new HashSet<>();
        SubtitleData.Cue previous=null;
        for(SubtitleData.Cue c:cues){
            if(c==null)continue;
            String zh=c.zh==null?"":c.zh.trim();
            if(!ids.add(c.id))issues.add(new Finding(c.id,"字幕编号重复"));
            if(c.startMs<0 || c.endMs<=c.startMs)
                issues.add(new Finding(c.id,"时间码无效"));
            if(zh.isEmpty())issues.add(new Finding(c.id,"缺少中文字幕"));
            else {
                if(zh.matches("[。，！？…，、,.!?;；\\s]+"))
                    issues.add(new Finding(c.id,"只有标点，没有可显示对白"));
                if(zh.length()>50)
                    issues.add(new Finding(c.id,"中文字幕较长，建议检查换行或精简"));
                long duration=Math.max(1,c.endMs-c.startMs);
                if(zh.length()>=14 && duration<2300 && zh.length()*1000.0/duration>12.0)
                    issues.add(new Finding(c.id,"字幕显示时间较短，阅读可能困难"));
                if(zh.matches("^[的了着过得地][。！？]?$") && c.en!=null && c.en.trim().length()>=5)
                    issues.add(new Finding(c.id,"疑似孤立助词，建议连读前后字幕"));
                if(previous!=null && c.startMs>=previous.endMs &&
                    c.startMs-previous.endMs<=1200 && zh.equals(previous.zh) &&
                    zh.length()>=6 && !vocalOnly(zh) && c.en!=null && previous.en!=null &&
                    !c.en.trim().equalsIgnoreCase(previous.en.trim()))
                    issues.add(new Finding(c.id,"相邻字幕译文相同，建议检查是否重复"));
            }
            previous=c;
        }
        return new Report(cues.size(),issues);
    }
}
