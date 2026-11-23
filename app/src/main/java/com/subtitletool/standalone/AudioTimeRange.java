package com.subtitletool.standalone;

import java.util.Locale;

/** Parse clock positions without locale ambiguity; all values are microseconds. */
public final class AudioTimeRange {
    private AudioTimeRange() {}
    public static long parse(String clock) {
        if(clock==null)throw new IllegalArgumentException("请输入时间（例如 00:32:00）");
        String[] fields=clock.trim().split(":",-1);
        if(fields.length!=3)throw new IllegalArgumentException("时间格式应为 时:分:秒，例如 00:32:00");
        try {
            for(String f:fields)if(f.isEmpty() || !f.matches("[0-9]{1,3}"))
                throw new NumberFormatException("non-digit");
            long h=Long.parseLong(fields[0]);
            long m=Long.parseLong(fields[1]);
            long s=Long.parseLong(fields[2]);
            if(m>=60 || s>=60 || h>99)throw new IllegalArgumentException("分钟和秒数必须小于 60，小时数最多 99");
            return ((h*3600+m*60+s)*1000000L);
        }catch(NumberFormatException e){throw new IllegalArgumentException("时间格式应为 时:分:秒，例如 00:32:00");}
    }
    public static void validate(long startUs,long endUs) {
        if(startUs<0 || endUs<=startUs)
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if(endUs-startUs<1000000L)
            throw new IllegalArgumentException("片段至少需要 1 秒");
    }
    public static String compact(long us) {
        long s=us/1000000L;
        return String.format(Locale.ROOT,"%02d-%02d-%02d",s/3600,(s/60)%60,s%60);
    }
    public static String fileName(String inputName,boolean clip,long startUs,long endUs,String ext) {
        String stem=inputName==null?"":inputName.trim();
        stem=stem.replaceAll("[\\\\/\\p{Cntrl}<>:\"|?*]","_");
        int dot=stem.lastIndexOf('.');
        if(dot>0 && dot>=stem.length()-6)stem=stem.substring(0,dot);
        if(stem.isEmpty())stem="视频";
        String suffix=clip?"."+compact(startUs)+"_"+compact(endUs):".音轨";
        int limit=Math.max(1,160-suffix.length()-ext.length());
        if(stem.length()>limit)stem=stem.substring(0,limit);
        return stem+suffix+ext;
    }
    public static String numbered(String name,int attempt) {
        if(attempt<=1)return name;
        int dot=name.lastIndexOf('.');
        if(dot<0)return name+" ("+attempt+")";
        String suffix=" ("+attempt+")";
        String stem=name.substring(0,dot);
        int limit=Math.max(1,180-name.substring(dot).length()-suffix.length());
        if(stem.length()>limit)stem=stem.substring(0,limit);
        return stem+suffix+name.substring(dot);
    }
}
