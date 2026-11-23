package com.subtitletool.standalone;

import java.net.URI;

/** Verified OpenAI-compatible Chat Completions presets; no API keys embedded. */
public final class ProviderPresets {
    private ProviderPresets() {}
    public static final class Provider {
        public final String id, label, endpoint, defaultModel, note;
        public final boolean custom;
        Provider(String id,String label,String endpoint,String defaultModel,String note,boolean custom){
            this.id=id;this.label=label;this.endpoint=endpoint;
            this.defaultModel=defaultModel;this.note=note;this.custom=custom;
        }
        public String keySlot(){return id;}
        public String modelPref(){return "model_"+id;}
        public String endpointPref(){return "endpoint_"+id;}
    }
    /** Keep first two positions unchanged to migrate legacy DeepSeek and GLM settings. */
    public static final Provider[] ALL = {
        new Provider("deepseek","DeepSeek 官方","https://api.deepseek.com/chat/completions","deepseek-chat","官方 DeepSeek API",false),
        new Provider("glm","智谱 GLM 官方","https://open.bigmodel.cn/api/paas/v4/chat/completions","glm-4.5","智谱开放平台通用接口，非 Coding 专属接口",false),
        new Provider("kimi_cn","Kimi / 月之暗面（中国）","https://api.moonshot.cn/v1/chat/completions","kimi-k2.5","需要中国站 Moonshot API Key",false),
        new Provider("kimi_intl","Kimi / Moonshot（国际）","https://api.moonshot.ai/v1/chat/completions","kimi-k2.5","需要国际站 Moonshot API Key",false),
        new Provider("siliconflow","硅基流动 SiliconFlow","https://api.siliconflow.cn/v1/chat/completions","deepseek-ai/DeepSeek-V3.2","模型 ID 以硅基流动模型广场为准",false),
        new Provider("volcengine","火山引擎 / 火山方舟","https://ark.cn-beijing.volces.com/api/v3/chat/completions","doubao-seed-2-1-pro-260915","需先开通模型；也可填写自己的推理接入点 ID",false),
        new Provider("bailian","阿里云百炼 / 通义千问","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions","qwen-plus","示例为中国站；其他地域请用自定义接口",false),
        new Provider("custom","自定义 OpenAI 兼容接口","","","请输入受信任的 HTTPS API 地址和模型 ID",true)
    };
    public static Provider byIndex(int index){return ALL[index>=0&&index<ALL.length?index:0];}
    public static String[] labels(){String[] out=new String[ALL.length];for(int i=0;i<ALL.length;i++)out[i]=ALL[i].label;return out;}
    /** Accept either full Chat Completions URL or a provider's base URL. */
    public static String normalizeEndpoint(String userValue){
        if(userValue==null)throw new IllegalArgumentException("请填写翻译接口地址");
        String s=userValue.trim();
        if(s.isEmpty() || s.length()>512)throw new IllegalArgumentException("请填写有效的 HTTPS 接口地址");
        try {
            URI u = new URI(s);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getHost().isEmpty()
                    || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null)
                throw new IllegalArgumentException("只支持 HTTPS 接口地址，不能包含用户名、查询参数或片段");
            String path=u.getRawPath()==null?"":u.getRawPath();
            if(!path.endsWith("/chat/completions")) {
                s=s.replaceAll("/+$","")+"/chat/completions";
            }
            return s;
        }catch(java.net.URISyntaxException ex){throw new IllegalArgumentException("接口地址格式无效");}
    }
}
