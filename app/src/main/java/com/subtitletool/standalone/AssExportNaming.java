package com.subtitletool.standalone;

import java.util.Locale;

/** Sanitizes the actual exported file name; the final extension is always .ass. */
public final class AssExportNaming {
    private AssExportNaming() {}
    public static final String MIME_ASS = "text/x-ass";

    public static String normalized(String proposed) {
        String name=proposed==null?"":proposed.trim();
        name=name.replace('\\','_').replace('/','_')
            .replaceAll("[\\p{Cntrl}<>:\"|?*]", "_");
        // Correct filenames that older versions' Android document pickers
        // presented as e.g. movie.zh.ass.txt or movie.zh.ass.bin.
        while(name.toLowerCase(Locale.ROOT).endsWith(".bin") ||
                name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            name=name.substring(0,name.lastIndexOf('.'));
        }
        if(name.isEmpty() || name.equals(".")) name="视频.zh.ass";
        if(!name.toLowerCase(Locale.ROOT).endsWith(".ass")) name += ".ass";
        if(name.length()>180) {
            String stem=name.substring(0,name.length()-4);
            name=stem.substring(0,Math.min(176,stem.length()))+".ass";
        }
        return name;
    }
    /** Collision suffix must be before .ass, never after it. attempt 1 = original. */
    public static String numberedCopy(String base, int attempt) {
        String normalized=normalized(base);
        if(attempt<=1)return normalized;
        String suffix=" ("+attempt+").ass";
        String stem=normalized.substring(0,normalized.length()-4);
        int maxStem=Math.max(1,180-suffix.length());
        if(stem.length()>maxStem)stem=stem.substring(0,maxStem);
        return stem+suffix;
    }
    public static boolean hasAssExtension(String actual) {
        return actual!=null && actual.toLowerCase(Locale.ROOT).endsWith(".ass");
    }
}
