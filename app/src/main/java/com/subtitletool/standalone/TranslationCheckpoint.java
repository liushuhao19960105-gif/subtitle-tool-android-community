package com.subtitletool.standalone;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single-video, application-private V1.8.4 checkpoint. Never stores API keys. */
final class TranslationCheckpoint {
    private static final int VERSION=1;
    private static final String FILE="translation_checkpoint_v184.json";
    final String source, config;
    final List<SubtitleData.Cue> cues;
    int firstDone, reviewDone, deepDone;

    private TranslationCheckpoint(String source,String config,List<SubtitleData.Cue> cues,
                                  int firstDone,int reviewDone) {
        this.source=source;this.config=config;this.cues=cues;
        this.firstDone=firstDone;this.reviewDone=reviewDone;this.deepDone=0;
    }

    static TranslationCheckpoint fresh(String source,String config,List<SubtitleData.Cue> cues) {
        return new TranslationCheckpoint(source,config,cues,0,0);
    }

    private static String digest(byte[] bytes) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        byte[] hash=md.digest(bytes);
        StringBuilder out=new StringBuilder();
        for(byte value:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }

    static String configuration(String asr,String endpoint,String model) throws Exception {
        // The hash only binds provider settings, not user API keys or personal content.
        String settings="v184:"+asr+"\n"+ProviderPresets.normalizeEndpoint(endpoint)+"\n"+model.trim();
        return digest(settings.getBytes(StandardCharsets.UTF_8));
    }

    /** Standard uses the prior fingerprint so existing V1.8.4 progress remains resumable. */
    static String configuration(String asr,String endpoint,String model,TranslationMode mode) throws Exception {
        String standard=configuration(asr,endpoint,model);
        if(mode==TranslationMode.STANDARD)return standard;
        return digest((standard+"\nquality="+mode.id).getBytes(StandardCharsets.UTF_8));
    }
    /** Requires source size and a content sample to guard against accidental reuse. */
    static String sourceIdentity(Context context,Uri uri,String name) {
        if(uri==null)return null;
        try {
            long size=-1;
            try(Cursor cursor=context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE},null,null,null)) {
                if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0))
                    size=cursor.getLong(0);
            }
            if(size<=0)return null;
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            md.update(("v184\n"+uri+"\n"+name+"\n"+size+"\n").getBytes(StandardCharsets.UTF_8));
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input==null)return null;
                byte[] bytes=new byte[8192];
                int remaining=65536,n;
                while(remaining>0 && (n=input.read(bytes,0,Math.min(bytes.length,remaining)))!=-1){
                    md.update(bytes,0,n);remaining-=n;
                }
                if(remaining==65536)return null;
            }
            StringBuilder out=new StringBuilder();
            for(byte value:md.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            return out.toString();
        }catch(Exception e){return null;} // Unsupported providers: fail closed, restart instead.
    }

    private static File file(Context ctx){return new File(ctx.getFilesDir(),FILE);}
    static TranslationCheckpoint load(Context ctx,String source,String config) {
        if(source==null)return null;
        File path=file(ctx);
        if(!path.isFile() || path.length()>8_000_000 || path.length()<16)return null;
        try {
            JSONObject o=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
            if(o.getInt("version")!=VERSION || !source.equals(o.getString("source"))
                    || !config.equals(o.getString("config")))return null;
            JSONArray arr=o.getJSONArray("cues");
            if(arr.length()==0 || arr.length()>4000)return null;
            List<SubtitleData.Cue> cues=SubtitleData.fromJson(arr);
            int first=o.getInt("firstDone"),review=o.getInt("reviewDone"),deep=o.optInt("deepDone",0);
            int countFirst=CloudPipeline.batchCount(cues,15);
            int countReview=CloudPipeline.batchCount(cues,20);
            if(first<0 || first>countFirst || review<0 || review>countReview ||
                    deep<0 || deep>countReview || (review>0 && first!=countFirst) ||
                    (deep>0 && review!=countReview))return null;
            // Validate every completed batch. Never reuse partial/blank responses.
            if(!CloudPipeline.hasCompletedPrefix(cues,15,first))return null;
            if(review>0 && !CloudPipeline.hasCompletedPrefix(cues,20,review))return null;
            if(deep>0 && !CloudPipeline.hasCompletedPrefix(cues,20,deep))return null;
            for(int i=0;i<cues.size();i++){
                SubtitleData.Cue c=cues.get(i);
                if(c.id!=i+1 || c.en==null || c.en.isEmpty() || c.endMs<=c.startMs)return null;
            }
            TranslationCheckpoint result=new TranslationCheckpoint(source,config,cues,first,review);
            result.deepDone=deep;
            return result;
        }catch(Exception e){return null;} // Corrupt/stale checkpoint cannot poison output.
    }

    void save(Context ctx) throws Exception {
        if(source==null)return;
        JSONObject o=new JSONObject();
        o.put("version",VERSION).put("source",source).put("config",config)
         .put("firstDone",firstDone).put("reviewDone",reviewDone).put("deepDone",deepDone)
         .put("cues",SubtitleData.toJson(cues));
        File path=file(ctx),tmp=new File(ctx.getFilesDir(),FILE+".tmp");
        byte[] bytes=o.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>8_000_000)throw new java.io.IOException("恢复数据超过安全限制");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(bytes);out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(),path.toPath(),StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        }catch(AtomicMoveNotSupportedException unsupported){
            // Same private directory, with a synced temporary file; fallback remains recoverable.
            Files.move(tmp.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void clear(Context ctx) {
        file(ctx).delete();
        new File(ctx.getFilesDir(),FILE+".tmp").delete();
    }
}
