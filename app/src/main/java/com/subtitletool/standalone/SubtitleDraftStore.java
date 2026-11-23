package com.subtitletool.standalone;

import android.content.Context;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;
import java.util.Arrays;

/** Local editable cue snapshot; never includes keys, audio or requests to cloud APIs. */
final class SubtitleDraftStore {
    private static final String DRAFT="last_editable_cues.json";
    private static final int MAX_BYTES=8_000_000;
    private SubtitleDraftStore(){}
    static File location(Context context){return new File(context.getFilesDir(),DRAFT);}
    static boolean exists(Context context){
        File file=location(context);
        return file.isFile() && file.length()>4 && file.length()<=MAX_BYTES;
    }
    static void clear(Context context){
        location(context).delete();
        new File(context.getFilesDir(),DRAFT+".tmp").delete();
    }
    static void validate(List<SubtitleData.Cue> cues) throws IOException {
        if(cues==null || cues.isEmpty() || cues.size()>4000)
            throw new IOException("字幕数据为空或超出允许范围");
        int previousId=0;
        for(SubtitleData.Cue cue:cues){
            if(cue==null || cue.id<=previousId || cue.startMs<0 || cue.endMs<=cue.startMs
                || cue.en==null || cue.en.isEmpty() || cue.zh==null
                || cue.zh.trim().isEmpty() || cue.zh.trim().length()>250)
                throw new IOException("第 "+(cue==null?"?":cue.id)+" 条字幕格式无效");
            previousId=cue.id;
        }
    }
    private static void replace(File temp,File destination) throws IOException {
        try {
            Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        }catch(AtomicMoveNotSupportedException unsupported){
            Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void save(Context context,List<SubtitleData.Cue> cues) throws Exception {
        validate(cues);
        byte[] json=SubtitleData.toJson(cues).toString().getBytes(StandardCharsets.UTF_8);
        if(json.length>MAX_BYTES)throw new IOException("字幕数据文件过大");
        File destination=location(context);
        File temp=new File(context.getFilesDir(),DRAFT+".tmp");
        try(FileOutputStream output=new FileOutputStream(temp)){
            output.write(json);output.getFD().sync();
        }
        replace(temp,destination);
    }
    static List<SubtitleData.Cue> load(Context context) throws Exception {
        File file=location(context);
        if(!exists(context))throw new IOException("没有可编辑的字幕，请先完成一次字幕制作");
        List<SubtitleData.Cue> cues=SubtitleData.fromJson(new JSONArray(
            new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)));
        validate(cues);
        return cues;
    }
    /** Repair the previous edit after an interruption between draft and ASS writes.
     *  Only called for the already-validated current editable draft. */
    static boolean ensureAssMatchesDraft(Context context,List<SubtitleData.Cue> cues) throws Exception {
        validate(cues);
        File target=new File(context.getFilesDir(),"last_result.ass");
        byte[] expected=AssWriter.render(cues).getBytes(StandardCharsets.UTF_8);
        if(target.isFile() && target.length()==expected.length &&
                Arrays.equals(Files.readAllBytes(target.toPath()),expected))return false;
        writeAss(context,cues);
        return true;
    }
    static void writeAss(Context context,List<SubtitleData.Cue> cues) throws Exception {
        validate(cues);
        File destination=new File(context.getFilesDir(),"last_result.ass");
        File temp=new File(context.getFilesDir(),"last_result.ass.editor.tmp");
        byte[] data=AssWriter.render(cues).getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream output=new FileOutputStream(temp)){
            output.write(data);output.getFD().sync();
        }
        replace(temp,destination);
    }
}
