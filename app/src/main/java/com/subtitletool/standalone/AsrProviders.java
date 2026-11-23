package com.subtitletool.standalone;

/** Stable ASR choice identifiers. Index 0 preserves existing Deepgram installations. */
public final class AsrProviders {
    private AsrProviders() {}
    public static final String DEEPGRAM="deepgram";
    public static final String ASSEMBLYAI="assemblyai";
    public static final class Provider {
        public final String id,label,keySlot,model,description;
        private Provider(String id,String label,String keySlot,String model,String description) {
            this.id=id; this.label=label; this.keySlot=keySlot;
            this.model=model; this.description=description;
        }
    }
    public static final Provider[] ALL={
        new Provider(DEEPGRAM,"Deepgram · Nova-3","deepgram","nova-3","已验证可用的原识别方案"),
        new Provider(ASSEMBLYAI,"AssemblyAI · STT-3.7","assemblyai","assemblyai-stt-3-7","高精度预录制识别，支持说话人区分")
    };
    public static Provider byIndex(int i){return ALL[i>=0 && i<ALL.length?i:0];}
    public static String[] labels(){String[] names=new String[ALL.length];for(int i=0;i<ALL.length;i++)names[i]=ALL[i].label;return names;}
}
