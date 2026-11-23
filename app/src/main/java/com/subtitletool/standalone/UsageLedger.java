package com.subtitletool.standalone;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Locale;

/** Confirmed Chat Completions API usage only. This is NOT a provider invoice. */
final class UsageLedger {
    private static final String FILENAME="last_translation_usage_v191.json";
    private static final int LIMIT=32_000;
    private final Context context;
    private final String job;
    private long inputTokens,outputTokens;
    private int reportedCalls,unreportedCalls;
    private boolean storageOk=true;

    private UsageLedger(Context context,String job){
        this.context=context;this.job=job;
    }
    static UsageLedger open(Context context,String job,boolean resume){
        UsageLedger item=new UsageLedger(context,job);
        File file=new File(context.getFilesDir(),FILENAME);
        if(resume && file.isFile() && file.length()>4 && file.length()<LIMIT){
            try {
                JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                if(data.optInt("version")==1 && job.equals(data.optString("job"))){
                    long in=data.getLong("inputTokens"),out=data.getLong("outputTokens");
                    int reported=data.getInt("reportedCalls"),missing=data.getInt("unreportedCalls");
                    if(in>=0 && out>=0 && reported>=0 && missing>=0 &&
                       in<=1_000_000_000_000L && out<=1_000_000_000_000L){
                        item.inputTokens=in;item.outputTokens=out;
                        item.reportedCalls=reported;item.unreportedCalls=missing;
                        return item;
                    }
                }
            }catch(Exception ignored){/* Invalid or stale data is not reused. */}
        }
        // Reset the prior job; the only retained identifier is an anonymous hash.
        if(file.isFile() && !file.delete())item.storageOk=false;
        return item;
    }
    private void save() throws Exception {
        JSONObject json=new JSONObject()
            .put("version",1).put("job",job)
            .put("inputTokens",inputTokens).put("outputTokens",outputTokens)
            .put("reportedCalls",reportedCalls).put("unreportedCalls",unreportedCalls);
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>LIMIT)throw new java.io.IOException("用量统计超过大小限制");
        File destination=new File(context.getFilesDir(),FILENAME);
        File temp=new File(context.getFilesDir(),FILENAME+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){
            out.write(bytes);out.getFD().sync();
        }
        try{
            Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }catch(AtomicMoveNotSupportedException ignored){
            Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** Record once per successful HTTP response, even if subtitle JSON later fails validation. */
    synchronized void record(JSONObject response){
        JSONObject usage=response==null?null:response.optJSONObject("usage");
        long in=usage==null?-1:usage.optLong("prompt_tokens",-1);
        long out=usage==null?-1:usage.optLong("completion_tokens",-1);
        if(in>=0 && out>=0 && in<=100_000_000 && out<=100_000_000){
            inputTokens+=in;outputTokens+=out;reportedCalls++;
        }else unreportedCalls++;
        try { save(); }
        catch(Exception ignored){storageOk=false;}
    }
    synchronized String summary(double inputPrice,double outputPrice){
        StringBuilder text=new StringBuilder("翻译 API 实际返回用量：输入 ")
            .append(inputTokens).append(" / 输出 ").append(outputTokens)
            .append(" Token（有用量的响应 ").append(reportedCalls).append(" 次）");
        if(unreportedCalls>0)text.append("\n另外 ").append(unreportedCalls).append(" 次响应未返回用量，无法统计其 Token");
        if(inputPrice>=0 && outputPrice>=0 && (inputPrice>0 || outputPrice>0)){
            double estimated=(inputTokens*inputPrice+outputTokens*outputPrice)/1_000_000.0;
            text.append("\n按手动填写单价估算：$").append(String.format(Locale.US,"%.4f",estimated));
            if(unreportedCalls>0)text.append("（不含未上报用量）");
        }else text.append("\n请填写实际模型单价，才能估算费用");
        text.append("\n仅统计已收到响应的翻译接口；不含语音识别费、未返回用量的请求或断网后未知计费");
        if(!storageOk)text.append("\n注意：本机用量持久化失败，关闭后可能无法恢复此统计");
        return text.toString();
    }
    /** User-supplied USD price per million tokens, no invented provider pricing. */
    static double price(String entered){
        try {
            double value=Double.parseDouble(entered.trim());
            return Double.isFinite(value) && value>=0 && value<=10000?value:-1;
        }catch(Exception e){return -1;}
    }
}
