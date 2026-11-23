package com.subtitletool.standalone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pure local, no-API migration of previously generated ASS files. */
public final class AssLegacyReflow {
    private AssLegacyReflow() { }
    public static boolean needsUpgrade(String s) {
        return s != null && s.contains("[V4+ Styles]") && s.contains("[Events]") &&
               !s.contains(AssWriter.RENDER_MARKER);
    }
    public static String upgrade(String old) throws IOException {
        if(!needsUpgrade(old)) return old;
        List<SubtitleData.Cue> cues=new ArrayList<>();
        for(String source:old.split("\\r?\\n")) {
            if(!source.startsWith("Dialogue: ")) continue;
            String[] p=source.substring(10).split(",",10);
            if(p.length<10) throw new IOException("旧 ASS 对白字段不完整");
            long start=parseAssTime(p[1]), end=parseAssTime(p[2]);
            if(end<=start) continue;
            String kind=p[3].trim();
            int speaker=-1;
            if(kind.matches("S[0-5]")) speaker=Integer.parseInt(kind.substring(1));
            String content=p[9].replace("{\\an8}","");
            // The old ASS uses \N only for wrapping, not a literal newline.
            content=content.replace("\\N","").trim();
            if(content.isEmpty()) continue;
            SubtitleData.Cue cue=new SubtitleData.Cue(cues.size()+1,start,end,"",speaker,speaker<0);
            cue.zh=content;cues.add(cue);
        }
        if(cues.isEmpty()) throw new IOException("旧 ASS 没有可读取的对白");
        return AssWriter.render(cues);
    }
    static long parseAssTime(String s) throws IOException {
        String[] a=s.trim().split("[:.]");
        if(a.length!=4)throw new IOException("旧 ASS 时间格式不支持");
        try {return ((Long.parseLong(a[0])*3600+Long.parseLong(a[1])*60+
                      Long.parseLong(a[2]))*100+Long.parseLong(a[3]))*10;}
        catch(NumberFormatException e){throw new IOException("旧 ASS 时间码格式错误",e);}
    }
}
