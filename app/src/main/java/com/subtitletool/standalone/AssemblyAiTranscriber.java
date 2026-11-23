package com.subtitletool.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pre-recorded AssemblyAI upload + submit + cancellable status polling. Never uploads video. */
public final class AssemblyAiTranscriber {
    private static final String API="https://api.assemblyai.com/v2/";
    private static final int MAX_RESPONSE=24*1024*1024;
    private static final long MAX_POLL_MS=60L*60*1000;
    private final AtomicBoolean stop;
    private volatile HttpURLConnection active;
    public AssemblyAiTranscriber(AtomicBoolean stop) {this.stop=stop;}
    public void cancel() {stop.set(true);HttpURLConnection c=active;if(c!=null)c.disconnect();}
    private void check()throws InterruptedException {if(stop.get() || Thread.currentThread().isInterrupted())
        throw new InterruptedException("任务已取消");}
    private JSONObject request(String path,String method,String key,byte[] json,
                               AudioExtractor.AudioFile audio) throws Exception {
        check();
        HttpURLConnection c=(HttpURLConnection)new URL(API+path).openConnection();
        active=c;
        c.setRequestMethod(method);
        c.setConnectTimeout(30000);
        c.setReadTimeout(75000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        // AssemblyAI API uses the raw key without Deepgram Token or OAuth Bearer prefix.
        c.setRequestProperty("Authorization",key);
        c.setRequestProperty("Accept","application/json");
        if(!"GET".equals(method)) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",audio!=null?"application/octet-stream":"application/json");
            if(audio!=null) {
                long size=audio.file.length();
                if(size<100 || size>2_000_000_000L)throw new IOException("WAV 音频大小不受支持");
                c.setFixedLengthStreamingMode(size);
            } else c.setFixedLengthStreamingMode(json.length);
        }
        try {
            if(!"GET".equals(method)) {
                try(OutputStream out=c.getOutputStream()) {
                    if(audio!=null) {
                        try(InputStream in=new BufferedInputStream(new FileInputStream(audio.file))) {
                            byte[] buffer=new byte[65536];int n;
                            while((n=in.read(buffer))!=-1) {check();out.write(buffer,0,n);}
                        }
                    } else {check();out.write(json);}
                }
            }
            check();
            int code=c.getResponseCode();
            if(code<200 || code>=300) {
                // Don't expose the provider's raw response: it can contain account data.
                if(code==401 || code==403)throw new IOException("AssemblyAI 身份验证失败（HTTP "+code+"），请检查 API Key 或账号权限");
                if(code==429)throw new IOException("AssemblyAI 请求超出并发或频率限制（HTTP 429）");
                if(code==400 || code==422)throw new IOException("AssemblyAI 拒绝请求/音频（HTTP "+code+"），请检查账号模型权限与音频格式");
                if(code>=500)throw new IOException("AssemblyAI 服务暂不可用（HTTP "+code+"），请稍后再试");
                throw new IOException("AssemblyAI 请求失败（HTTP "+code+"）");
            }
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) {
                    check();
                    if(out.size()+n>MAX_RESPONSE)throw new IOException("AssemblyAI 响应超过允许大小");
                    out.write(buffer,0,n);
                }
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally {active=null;c.disconnect();}
    }
    public List<SubtitleData.Cue> transcribe(AudioExtractor.AudioFile file,String key,
                                            CloudPipeline.Callback cb) throws Exception {
        if(key==null || key.trim().isEmpty())throw new IllegalArgumentException("请先保存 AssemblyAI API Key");
        if(!"audio/wav".equals(file.mime))throw new IOException("AssemblyAI 当前仅支持本机生成的标准 WAV");
        AudioIntegrity.validatePcmWav(file.file);
        cb.update(23,"WAV 校验通过，正在上传音频到 AssemblyAI…");
        JSONObject upload=request("upload","POST",key,null,file);
        String audioUrl=upload.optString("upload_url","");
        // Only accept provider-issued audio URLs. Never send local paths or arbitrary URLs.
        if(!audioUrl.startsWith("https://") || audioUrl.length()>2048 || audioUrl.contains("\n"))
            throw new IOException("AssemblyAI 上传接口未返回有效音频地址");
        check();
        cb.update(29,"音频上传完成，提交 STT-3.7 识别任务…");
        JSONObject payload=new JSONObject()
            .put("audio_url",audioUrl)
            .put("speech_models",new JSONArray().put("assemblyai-stt-3-7"))
            .put("language_code","en")
            .put("speaker_labels",true);
        JSONObject created=request("transcript","POST",key,
                payload.toString().getBytes(StandardCharsets.UTF_8),null);
        String id=created.optString("id","");
        if(!id.matches("[A-Za-z0-9_-]{6,128}"))throw new IOException("AssemblyAI 未返回有效任务编号");
        cb.update(30,"AssemblyAI 已接收任务，正在等待识别结果…");
        long deadline=System.nanoTime()+MAX_POLL_MS*1_000_000L;
        int count=0;
        while(true) {
            check();
            if(System.nanoTime()>deadline)
                throw new IOException("AssemblyAI 识别等待超过 60 分钟。任务可能仍在服务端运行，请在控制台检查");
            JSONObject data=request("transcript/"+id,"GET",key,null,null);
            String status=data.optString("status","");
            if("completed".equals(status)) {
                String used=data.optString("speech_model_used","");
                // Do not silently accept a lower-grade fallback model.
                if(!used.isEmpty() && !"assemblyai-stt-3-7".equals(used))
                    throw new IOException("AssemblyAI 使用了不同模型（"+used+"），已阻止误标 STT-3.7");
                List<SubtitleData.Cue> cues=SubtitleData.fromAssemblyAI(data);
                cb.update(38,"AssemblyAI 识别完成，共 "+cues.size()+" 段英文对白");
                return cues;
            }
            if("error".equals(status))
                throw new IOException("AssemblyAI 识别任务失败；请到后台查看任务错误详情（不自动重复计费）");
            if(!"processing".equals(status) && !"queued".equals(status))
                throw new IOException("AssemblyAI 返回未知任务状态，已停止继续请求");
            count++;
            if(count%5==1)cb.update(31,"AssemblyAI "+("queued".equals(status)?"排队中":"识别中")+"，正在等待结果…");
            // Short cancellable sleeps let the Stop button respond promptly.
            for(int j=0;j<15;j++) {check();Thread.sleep(200);}
        }
    }
}
