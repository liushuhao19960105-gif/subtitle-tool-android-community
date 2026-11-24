package com.subtitletool.standalone;

/** Same recognition and output format; only the number of translation passes changes. */
enum TranslationMode {
    QUICK("quick","快速（1轮）",1),
    STANDARD("standard","标准（3轮）",3),
    HIGH("high","高质量（4轮）",4);
    final String id,label;
    final int passes;
    TranslationMode(String id,String label,int passes){
        this.id=id;this.label=label;this.passes=passes;
    }
    static TranslationMode fromIndex(int value){
        return value==0?QUICK:value==2?HIGH:STANDARD;
    }
    static TranslationMode fromId(String value){
        for(TranslationMode m:values())if(m.id.equals(value))return m;
        return STANDARD;
    }
    static String[] labels(){
        TranslationMode[] all=values();
        String[] result=new String[all.length];
        for(int i=0;i<all.length;i++)result[i]=all[i].label;
        return result;
    }
}
