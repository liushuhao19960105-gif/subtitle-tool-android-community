package com.subtitletool.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.method.PasswordTransformationMethod;
import android.text.method.HideReturnsTransformationMethod;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** True native Android entry point. No localhost server, Termux, root or WebView dependency. */
public final class MainActivity extends Activity {
    private static final int PICK_INPUT=41, SAVE_ASS=42, SAVE_AUDIO=43;
    private static final int BG=Color.rgb(243,240,234), GREEN=Color.rgb(82,117,87), INK=Color.rgb(36,40,36);
    private static final int MUTED=Color.rgb(105,111,103), BORDER=Color.rgb(212,209,201), WHITE=Color.rgb(255,254,251);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop=new AtomicBoolean(false);
    private SecureKeys secure;
    private SharedPreferences prefs;
    private CloudPipeline pipeline;
    private AssemblyAiTranscriber assemblyTranscriber;
    private Uri pickedUri;
    private String originalName="";
    private EditText asrKey, modelKey, modelName, apiEndpoint;
    private TextView providerNote, asrNote, asrKeyLabel;
    private LinearLayout credentialsPanel, endpointGroup, configCard;
    private Button settingsToggle, saveBtn;
    private boolean credentialsExpanded=false;
    private int activeProviderIndex=0, activeAsrIndex=0;
    private Spinner providerSpinner, asrSpinner, qualityModeSpinner;
    private EditText inputPrice, outputPrice;
    private TextView usageInfo;
    private volatile UsageLedger taskUsage;
    private double currentInputPrice=-1,currentOutputPrice=-1;
    private TextView savedInfo, selectedInfo, status, logs;
    private ProgressBar progress;
    private Button startBtn, stopBtn, exportBtn, logBtn, clearCheckpointBtn, qualityBtn, previewBtn;
    private String latestQualityReport="";
    private LinearLayout taskActionsRow, selectedFilePanel;
    private volatile int resumeCheckGeneration=0;
    private boolean resumeAvailable=false;
    private final Runnable pendingResumeRefresh=()->refreshResumeState();
    private Button audioToolToggle,audioExportBtn;
    private LinearLayout audioPanel,audioTimeFields,audioToolCard;
    private RadioGroup audioMode;
    private EditText audioFrom,audioTo;
    private File pendingAudioFile;
    private String pendingAudioName;
    private boolean busy=false;
    private File completedAss;
    private String outputName="视频.zh.ass";
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        secure=new SecureKeys(this);
        prefs=getSharedPreferences("subtitle_ui",MODE_PRIVATE);
        // Migrate the previously saved model once; preserve existing encrypted keys.
        int oldIndex=prefs.getInt("provider",0);
        ProviderPresets.Provider old=ProviderPresets.byIndex(oldIndex);
        if(!prefs.contains(old.modelPref()) && prefs.contains("model"))
            prefs.edit().putString(old.modelPref(),prefs.getString("model",old.defaultModel)).apply();
        render();
        File prior=new File(getFilesDir(),"last_result.ass");
        if(prior.isFile() && prior.length()>30) {
            boolean upgraded=false, reflowFailed=false;
            try { upgraded=upgradeSavedAss(prior); }
            catch(Exception ignored) { reflowFailed=true; /* Keep the original intact. */ }
            completedAss=prior;
            outputName=prefs.getString("last_file","视频.zh.ass");
            exportBtn.setVisibility(View.VISIBLE);
            status.setText(upgraded?"上次字幕已整理，可直接下载":
                reflowFailed?"上次字幕已恢复（排版优化失败）":
                "上次字幕已恢复，可直接下载");
        }
        File priorQuality=new File(getFilesDir(),"last_quality_report.txt");
        if(priorQuality.isFile() && priorQuality.length()<16000){
            try{
                latestQualityReport=new String(Files.readAllBytes(priorQuality.toPath()),StandardCharsets.UTF_8);
                qualityBtn.setVisibility(View.VISIBLE);
            }catch(Exception ignored){/* Stored report is optional. */}
        }
        updateSavedInfo();
    }
    @Override protected void onResume(){
        super.onResume();
        if(previewBtn!=null){
            boolean available=!busy && SubtitleDraftStore.exists(this) &&
                new File(getFilesDir(),"last_result.ass").isFile();
            previewBtn.setVisibility(available?View.VISIBLE:View.GONE);
        }
        if(qualityBtn!=null){
            File report=new File(getFilesDir(),"last_quality_report.txt");
            if(report.isFile() && report.length()<16000){
                try{
                    latestQualityReport=new String(Files.readAllBytes(report.toPath()),StandardCharsets.UTF_8);
                    qualityBtn.setVisibility(View.VISIBLE);
                }catch(Exception ignored){}
            }
        }
    }
    /** One-time local upgrade. Keeps an untouched backup and makes no API call. */
    private boolean upgradeSavedAss(File prior) throws Exception {
        String old=new String(Files.readAllBytes(prior.toPath()),StandardCharsets.UTF_8);
        if(!AssLegacyReflow.needsUpgrade(old))return false;
        String newer=AssLegacyReflow.upgrade(old);
        File backup=new File(getFilesDir(),"last_result.before_v15.ass");
        if(!backup.exists())Files.copy(prior.toPath(),backup.toPath());
        File tmp=new File(getFilesDir(),"last_result.v15.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(newer.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        Files.move(tmp.toPath(),prior.toPath(),StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
    private GradientDrawable rect(int color,boolean border) {
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(15));
        if(border)g.setStroke(dp(1),BORDER);return g;
    }
    private TextView txt(String text,int sp,int color,boolean strong) {
        TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);
        if(strong)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setLineSpacing(dp(2),1.0f);return v;
    }
    private LinearLayout vertical(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);return b;}
    private void gap(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private void add(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(-1,-2));}
    private LinearLayout card(LinearLayout parent) {
        LinearLayout section=vertical();section.setPadding(dp(15),dp(16),dp(15),dp(16));
        section.setBackground(rect(WHITE,true));add(parent,section);return section;
    }
    private Button btn(String title,boolean primary) {
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(title);b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT,primary?Typeface.BOLD:Typeface.NORMAL);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));
        b.setPadding(dp(14),dp(7),dp(14),dp(7));
        b.setTextColor(primary?Color.WHITE:GREEN);
        GradientDrawable base=rect(primary?GREEN:WHITE,!primary);
        int pressed=primary?0x35FFFFFF:0x18527557;
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed),base,null));
        b.setElevation(primary?dp(1):0);
        b.setStateListAnimator(null);
        return b;
    }
    /** Flat text-action button for low-priority controls (logs, collapse). */
    private Button quietBtn(String title){
        Button b=btn(title,false);b.setBackground(new RippleDrawable(
            ColorStateList.valueOf(0x18527557),rect(BG,false),null));
        b.setTextSize(13);b.setElevation(0);return b;
    }
    private EditText input(String hint,boolean secret) {
        EditText e=new EditText(this);e.setTextSize(14);e.setSingleLine(true);e.setHint(hint);
        e.setTextColor(INK);e.setHintTextColor(MUTED);e.setPadding(dp(12),dp(11),dp(12),dp(11));
        e.setBackground(rect(WHITE,true));
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private void fieldLabel(LinearLayout root,String title) {
        add(root,txt(title,12,MUTED,false));gap(root,6);
    }
    private void hideKeyboard(){
        View focused=getCurrentFocus();
        if(focused!=null){
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null)imm.hideSoftInputFromWindow(focused.getWindowToken(),0);
            focused.clearFocus();
        }
    }
    private void transition(LinearLayout container) {
        if(container!=null){
            AutoTransition t=new AutoTransition();t.setDuration(170);
            TransitionManager.beginDelayedTransition(container,t);
        }
    }
    private void setCredentialsExpanded(boolean expand,boolean animated) {
        if(credentialsPanel==null)return;
        if(credentialsExpanded==expand && credentialsPanel.getVisibility()==(expand?View.VISIBLE:View.GONE))return;
        if(!expand)hideKeyboard();
        if(animated)transition(configCard);
        credentialsExpanded=expand;
        credentialsPanel.setVisibility(expand?View.VISIBLE:View.GONE);
        settingsToggle.setText(expand?"收起密钥设置  ▴":"API 密钥与接口  ▾");
    }
    /** Toggle display without clearing the secret or moving the caret. */
    private LinearLayout secretField(EditText secret) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(secret,new LinearLayout.LayoutParams(0,dp(46),1));
        Button reveal=btn("显示",false);reveal.setTextSize(12);reveal.setMinWidth(dp(64));
        reveal.setFocusable(false);reveal.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams option=new LinearLayout.LayoutParams(dp(66),dp(46));
        option.leftMargin=dp(7);row.addView(reveal,option);
        reveal.setOnClickListener(v->{
            int cursor=secret.getSelectionStart();
            boolean hidden=secret.getTransformationMethod() instanceof PasswordTransformationMethod;
            secret.setTransformationMethod(hidden?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());
            reveal.setText(hidden?"隐藏":"显示");
            if(cursor>=0)secret.setSelection(Math.min(cursor,secret.length()));
        });
        return row;
    }
    private Spinner selector(String[] labels){
        // Dialog mode behaves consistently across vendors; no dropdown covering the keyboard.
        Spinner spinner=new Spinner(this,Spinner.MODE_DIALOG);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);
        spinner.setAdapter(adapter);
        spinner.setBackground(rect(WHITE,true));
        spinner.setPadding(dp(10),dp(5),dp(6),dp(5));
        spinner.setMinimumHeight(dp(46));
        return spinner;
    }
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);scroll.setSmoothScrollingEnabled(true);
        LinearLayout root=vertical();root.setPadding(dp(16),dp(22),dp(16),dp(34));
        root.setFocusableInTouchMode(true);root.requestFocus();
        scroll.addView(root);setContentView(scroll);

        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo,new LinearLayout.LayoutParams(dp(43),dp(43)));
        LinearLayout head=vertical();head.setPadding(dp(11),0,0,0);
        add(head,txt("视频字幕工具",22,INK,true));add(head,txt("V1.9.0",12,MUTED,false));
        header.addView(head);add(root,header);

        gap(root,19);
        LinearLayout videoCard=card(root);
        add(videoCard,txt("视频文件",16,INK,true));gap(videoCard,11);
        Button choose=btn("选择视频或音频",false);add(videoCard,choose);
        gap(videoCard,11);
        selectedFilePanel=vertical();selectedFilePanel.setPadding(dp(13),dp(12),dp(13),dp(12));
        selectedFilePanel.setBackground(rect(BG,false));
        add(selectedFilePanel,txt("当前文件",12,GREEN,true));gap(selectedFilePanel,5);
        selectedInfo=txt("尚未选择视频或音频",15,INK,true);
        selectedInfo.setMaxLines(3);selectedInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(selectedFilePanel,selectedInfo);add(videoCard,selectedFilePanel);
        choose.setOnClickListener(v->pickVideo());

        gap(root,12);
        configCard=card(root);
        add(configCard,txt("模型设置",16,INK,true));gap(configCard,14);
        fieldLabel(configCard,"语音识别");
        asrSpinner=selector(AsrProviders.labels());add(configCard,asrSpinner);
        int asrIndex=prefs.getInt("asr_provider",0);
        if(asrIndex<0 || asrIndex>=AsrProviders.ALL.length)asrIndex=0;
        activeAsrIndex=asrIndex;asrSpinner.setSelection(asrIndex);
        asrNote=txt("",12,MUTED,false);gap(configCard,6);add(configCard,asrNote);

        gap(configCard,15);
        fieldLabel(configCard,"翻译平台");
        providerSpinner=selector(ProviderPresets.labels());add(configCard,providerSpinner);
        int providerIndex=prefs.getInt("provider",0);
        if(providerIndex<0 || providerIndex>=ProviderPresets.ALL.length)providerIndex=0;
        activeProviderIndex=providerIndex;providerSpinner.setSelection(providerIndex);
        gap(configCard,12);
        fieldLabel(configCard,"翻译模型 ID");
        modelName=input("模型 ID",false);modelName.setSelectAllOnFocus(true);modelName.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add(configCard,modelName);
        gap(configCard,12);
        fieldLabel(configCard,"翻译质量模式");
        qualityModeSpinner=selector(TranslationMode.labels());add(configCard,qualityModeSpinner);
        qualityModeSpinner.setSelection(prefs.getInt("quality_mode",1));
        gap(configCard,5);
        add(configCard,txt("快速：初译1轮；标准：初译+润色+终审3轮；高质量：再加深度复核4轮。费用随请求数增加。",12,MUTED,false));
        gap(configCard,13);
        fieldLabel(configCard,"翻译模型价格（美元 / 百万 Token，由你自行填写）");
        inputPrice=input("输入 Token 单价（美元/百万）",false);
        outputPrice=input("输出 Token 单价（美元/百万）",false);
        inputPrice.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        outputPrice.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputPrice.setText(prefs.getString("usage_input_price",""));
        outputPrice.setText(prefs.getString("usage_output_price",""));
        add(configCard,inputPrice);
        gap(configCard,6);add(configCard,outputPrice);
        gap(configCard,5);
        add(configCard,txt("不同模型与平台价格不同；这里只估算翻译 API，不含语音识别费。未填写单价仍会统计已上报的 Token。",12,MUTED,false));

        savedInfo=txt("",12,GREEN,false);gap(configCard,9);add(configCard,savedInfo);
        LinearLayout toggles=new LinearLayout(this);toggles.setOrientation(LinearLayout.HORIZONTAL);
        settingsToggle=btn("API 密钥与接口  ▾",false);
        saveBtn=btn("保存设置",false);
        LinearLayout.LayoutParams toggleLp=new LinearLayout.LayoutParams(0,dp(46),1.25f);
        toggleLp.rightMargin=dp(7);toggles.addView(settingsToggle,toggleLp);
        toggles.addView(saveBtn,new LinearLayout.LayoutParams(0,dp(46),0.75f));
        gap(configCard,10);add(configCard,toggles);

        credentialsPanel=vertical();gap(credentialsPanel,14);
        asrKeyLabel=txt("语音识别 API Key",12,MUTED,false);add(credentialsPanel,asrKeyLabel);gap(credentialsPanel,6);
        asrKey=input("输入识别平台密钥",true);add(credentialsPanel,secretField(asrKey));
        gap(credentialsPanel,13);
        fieldLabel(credentialsPanel,"翻译平台 API Key");
        modelKey=input("输入翻译平台密钥",true);modelKey.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add(credentialsPanel,secretField(modelKey));
        endpointGroup=vertical();gap(endpointGroup,13);fieldLabel(endpointGroup,"自定义 API 地址");
        apiEndpoint=input("https://.../chat/completions",false);apiEndpoint.setSelectAllOnFocus(true);
        add(endpointGroup,apiEndpoint);add(credentialsPanel,endpointGroup);
        providerNote=txt("",12,MUTED,false);gap(credentialsPanel,5);add(credentialsPanel,providerNote);
        credentialsPanel.setVisibility(View.GONE);add(configCard,credentialsPanel);
        credentialsExpanded=false;
        showAsrProvider(asrIndex);showProvider(providerIndex);updateSavedInfo();
        boolean needsKey=!secure.has(AsrProviders.byIndex(asrIndex).keySlot)
            || !secure.has(ProviderPresets.byIndex(providerIndex).keySlot());
        setCredentialsExpanded(needsKey,false);
        settingsToggle.setOnClickListener(v->setCredentialsExpanded(!credentialsExpanded,true));
        saveBtn.setOnClickListener(v->saveSettingsAsync());
        TextWatcher resumeConfigWatcher=new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence text,int start,int before,int count){}
            @Override public void afterTextChanged(Editable text){
                if(pickedUri!=null && !busy){
                    main.removeCallbacks(pendingResumeRefresh);
                    main.postDelayed(pendingResumeRefresh,500);
                }
            }
        };
        modelName.addTextChangedListener(resumeConfigWatcher);
        apiEndpoint.addTextChangedListener(resumeConfigWatcher);
        qualityModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                prefs.edit().putInt("quality_mode",position).apply();
                if(pickedUri!=null && !busy)refreshResumeState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        TextWatcher priceWatcher=new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence x,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence x,int start,int before,int count){}
            @Override public void afterTextChanged(Editable x){
                prefs.edit().putString("usage_input_price",inputPrice.getText().toString().trim())
                    .putString("usage_output_price",outputPrice.getText().toString().trim()).apply();
            }
        };
        inputPrice.addTextChangedListener(priceWatcher);
        outputPrice.addTextChangedListener(priceWatcher);

        gap(root,12);
        LinearLayout taskCard=card(root);
        add(taskCard,txt("开始制作",16,INK,true));gap(taskCard,12);
        startBtn=btn("开始生成字幕",true);startBtn.setMinHeight(dp(52));add(taskCard,startBtn);
        taskActionsRow=new LinearLayout(this);taskActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        taskActionsRow.setGravity(Gravity.CENTER_VERTICAL);taskActionsRow.setVisibility(View.GONE);
        stopBtn=quietBtn("停止任务");stopBtn.setEnabled(false);stopBtn.setVisibility(View.GONE);
        stopBtn.setMinHeight(dp(44));
        LinearLayout.LayoutParams stopLayout=new LinearLayout.LayoutParams(0,dp(48),1f);
        stopLayout.rightMargin=dp(6);taskActionsRow.addView(stopBtn,stopLayout);
        startBtn.setOnClickListener(v->start());stopBtn.setOnClickListener(v->cancel());
        clearCheckpointBtn=quietBtn("清除进度");clearCheckpointBtn.setVisibility(View.GONE);
        clearCheckpointBtn.setMinHeight(dp(44));
        taskActionsRow.addView(clearCheckpointBtn,new LinearLayout.LayoutParams(0,dp(48),1f));
        gap(taskCard,7);add(taskCard,taskActionsRow);
        clearCheckpointBtn.setOnClickListener(v->{
            if(busy)return;
            new AlertDialog.Builder(this).setTitle("清除恢复进度？")
                .setMessage("下次开始将重新识别并翻译。已完成的 ASS 文件不会删除。")
                .setNegativeButton("取消",null)
                .setPositiveButton("确认清除",(dialog,which)->{
                    TranslationCheckpoint.clear(this);
                    resumeCheckGeneration++;
                    resumeAvailable=false;
                    renderResumeControls();
                    status.setText("进度已清除，下次将从头开始");
                }).show();
        });
        gap(taskCard,14);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(false);
        progress.setMax(100);progress.setProgress(0);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        progress.setVisibility(View.GONE);add(taskCard,progress);
        status=txt("就绪",13,MUTED,false);gap(taskCard,8);add(taskCard,status);
        usageInfo=txt("尚无本次任务的翻译 API Token 数据；请注意语音识别费用另计。",12,MUTED,false);
        gap(taskCard,8);add(taskCard,usageInfo);
        exportBtn=btn("下载中文字幕 ASS",false);exportBtn.setVisibility(View.GONE);
        gap(taskCard,10);add(taskCard,exportBtn);exportBtn.setOnClickListener(v->export());
        qualityBtn=quietBtn("查看字幕质量报告");qualityBtn.setVisibility(View.GONE);
        gap(taskCard,6);add(taskCard,qualityBtn);
        qualityBtn.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("字幕质量初检").setMessage(latestQualityReport)
            .setPositiveButton("知道了",null).show());
        previewBtn=quietBtn("字幕预览与编辑");previewBtn.setVisibility(View.GONE);
        gap(taskCard,6);add(taskCard,previewBtn);
        previewBtn.setOnClickListener(v->{
            if(busy)return;
            if(!SubtitleDraftStore.exists(this)){
                status.setText("请先完成一次字幕制作，再进行预览和编辑");return;
            }
            startActivity(new Intent(this,SubtitleEditorActivity.class));
        });
        gap(taskCard,10);
        logBtn=quietBtn("运行日志  ▾");add(taskCard,logBtn);
        logs=txt("",12,MUTED,false);logs.setPadding(dp(9),dp(10),dp(9),dp(10));
        logs.setBackground(rect(BG,false));logs.setVisibility(View.GONE);gap(taskCard,7);add(taskCard,logs);
        logBtn.setOnClickListener(v->{
            boolean visible=logs.getVisibility()==View.VISIBLE;
            transition(taskCard);logs.setVisibility(visible?View.GONE:View.VISIBLE);
            logBtn.setText(visible?"运行日志  ▾":"收起日志  ▴");
        });

        // Secondary, collapsible offline audio tool: hidden until the user needs it.
        gap(root,12);
        audioToolCard=card(root);
        LinearLayout toolHead=new LinearLayout(this);toolHead.setOrientation(LinearLayout.HORIZONTAL);
        toolHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView toolTitle=txt("音频工具",16,INK,true);
        toolHead.addView(toolTitle,new LinearLayout.LayoutParams(0,-2,1));
        audioToolToggle=quietBtn("展开  ▾");
        toolHead.addView(audioToolToggle,new LinearLayout.LayoutParams(dp(95),dp(42)));
        add(audioToolCard,toolHead);
        audioPanel=vertical();audioPanel.setVisibility(View.GONE);
        gap(audioPanel,10);
        audioMode=new RadioGroup(this);
        audioMode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton allAudio=new RadioButton(this);
        allAudio.setId(View.generateViewId());allAudio.setText("完整音轨");allAudio.setTextSize(14);
        allAudio.setTextColor(INK);allAudio.setButtonTintList(ColorStateList.valueOf(GREEN));
        RadioButton clipAudio=new RadioButton(this);
        clipAudio.setId(View.generateViewId());clipAudio.setText("截取片段");clipAudio.setTextSize(14);
        clipAudio.setTextColor(INK);clipAudio.setButtonTintList(ColorStateList.valueOf(GREEN));
        RadioGroup.LayoutParams modeLeft=new RadioGroup.LayoutParams(0,dp(44));modeLeft.weight=1;
        RadioGroup.LayoutParams modeRight=new RadioGroup.LayoutParams(0,dp(44));modeRight.weight=1;
        audioMode.addView(allAudio,modeLeft);
        audioMode.addView(clipAudio,modeRight);
        audioMode.check(allAudio.getId());add(audioPanel,audioMode);
        audioTimeFields=vertical();gap(audioTimeFields,8);
        LinearLayout timeRow=new LinearLayout(this);timeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout fromColumn=vertical(),toColumn=vertical();
        fieldLabel(fromColumn,"开始时间");fieldLabel(toColumn,"结束时间");
        audioFrom=input("00:00:00",false);audioFrom.setText("00:32:00");audioFrom.setSelectAllOnFocus(true);
        audioTo=input("00:00:00",false);audioTo.setText("00:35:00");audioTo.setSelectAllOnFocus(true);
        add(fromColumn,audioFrom);add(toColumn,audioTo);
        LinearLayout.LayoutParams aLp=new LinearLayout.LayoutParams(0,-2,1);aLp.rightMargin=dp(9);
        timeRow.addView(fromColumn,aLp);timeRow.addView(toColumn,new LinearLayout.LayoutParams(0,-2,1));
        add(audioTimeFields,timeRow);
        add(audioTimeFields,txt("格式为 00:32:00；片段时间从视频开头计算",11,MUTED,false));
        audioTimeFields.setVisibility(View.GONE);add(audioPanel,audioTimeFields);
        audioMode.setOnCheckedChangeListener((group,id)->{
            transition(audioToolCard);
            audioTimeFields.setVisibility(id==clipAudio.getId()?View.VISIBLE:View.GONE);
        });
        gap(audioPanel,10);
        audioExportBtn=btn("导出音频",false);add(audioPanel,audioExportBtn);
        gap(audioPanel,8);
        add(audioPanel,txt("优先无损提取 AAC 为 M4A；其他音轨转为 WAV。无需 API。",11,MUTED,false));
        add(audioToolCard,audioPanel);
        audioToolToggle.setOnClickListener(v->{
            boolean open=audioPanel.getVisibility()!=View.VISIBLE;
            transition(audioToolCard);audioPanel.setVisibility(open?View.VISIBLE:View.GONE);
            audioToolToggle.setText(open?"收起  ▴":"展开  ▾");
        });
        audioExportBtn.setOnClickListener(v->exportAudio());

        asrSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if(position!=activeAsrIndex){
                    hideKeyboard();activeAsrIndex=position;
                    asrKey.setText(""); // Never mix unsaved secret input across providers.
                    showAsrProvider(position);
                    prefs.edit().putInt("asr_provider",position).apply();
                }
                updateSavedInfo();
                if(pickedUri!=null && !busy)refreshResumeState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if(position!=activeProviderIndex) {
                    hideKeyboard();
                    ProviderPresets.Provider old=ProviderPresets.byIndex(activeProviderIndex);
                    prefs.edit().putString(old.modelPref(),modelName.getText().toString().trim())
                        .putString(old.endpointPref(),apiEndpoint.getText().toString().trim()).apply();
                    activeProviderIndex=position;
                    modelKey.setText(""); // Never carry a typed secret into another provider's key slot.
                    showProvider(position);
                    prefs.edit().putInt("provider",position).apply();
                }
                updateSavedInfo();
                if(pickedUri!=null && !busy)refreshResumeState();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private void showAsrProvider(int index){
        AsrProviders.Provider p=AsrProviders.byIndex(index);
        asrKeyLabel.setText(p.label+" API Key");
        asrNote.setText("模型 · "+p.model);
    }
    private void showProvider(int index){
        ProviderPresets.Provider p=ProviderPresets.byIndex(index);
        apiEndpoint.setText(p.custom?prefs.getString(p.endpointPref(),p.endpoint):p.endpoint);
        apiEndpoint.setEnabled(p.custom); // Official presets are read-only; custom is editable.
        if(endpointGroup!=null){
            transition(configCard);
            endpointGroup.setVisibility(p.custom?View.VISIBLE:View.GONE);
        }
        providerNote.setVisibility(p.custom?View.VISIBLE:View.GONE);
        providerNote.setText(p.custom?"仅支持 HTTPS 的兼容接口":"");
        modelName.setText(prefs.getString(p.modelPref(),p.defaultModel));
    }
    private void updateSavedInfo(){
        if(savedInfo==null || providerSpinner==null || asrSpinner==null)return;
        String slot=ProviderPresets.byIndex(providerSpinner.getSelectedItemPosition()).keySlot();
        String asrSlot=AsrProviders.byIndex(asrSpinner.getSelectedItemPosition()).keySlot;
        boolean asrReady=secure.has(asrSlot), translationReady=secure.has(slot);
        savedInfo.setText("识别密钥 · "+(asrReady?"已保存":"未设置")+
                "      翻译密钥 · "+(translationReady?"已保存":"未设置"));
        savedInfo.setTextColor(asrReady&&translationReady?GREEN:MUTED);
        if(!asrReady || !translationReady)setCredentialsExpanded(true,true);
    }
    /** Save encryption and preferences off the main thread to avoid UI jank. */
    private void saveSettingsAsync(){
        if(busy || !saveBtn.isEnabled())return;
        final ProviderPresets.Provider provider=ProviderPresets.byIndex(providerSpinner.getSelectedItemPosition());
        final AsrProviders.Provider asr=AsrProviders.byIndex(asrSpinner.getSelectedItemPosition());
        final String model=modelName.getText().toString().trim();
        final String asrSecret=asrKey.getText().toString();
        final String translationSecret=modelKey.getText().toString();
        final String endpoint;
        try {
            if(model.isEmpty())throw new IllegalArgumentException("请输入翻译模型 ID");
            endpoint=provider.custom?ProviderPresets.normalizeEndpoint(apiEndpoint.getText().toString()):provider.endpoint;
        }catch(Exception e){
            new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("确定",null).show();
            return;
        }
        saveBtn.setText("保存中…");
        saveBtn.setEnabled(false);startBtn.setEnabled(false);
        providerSpinner.setEnabled(false);asrSpinner.setEnabled(false);
        modelName.setEnabled(false);modelKey.setEnabled(false);asrKey.setEnabled(false);
        apiEndpoint.setEnabled(false);
        new Thread(()->{
            try {
                secure.save(asr.keySlot,asrSecret);
                secure.save(provider.keySlot(),translationSecret);
                boolean ok=prefs.edit()
                    .putInt("asr_provider",activeAsrIndex).putInt("provider",activeProviderIndex)
                    .putString(provider.modelPref(),model).putString(provider.endpointPref(),endpoint)
                    .putString("model",model).commit();
                if(!ok)throw new IllegalStateException("配置写入失败");
                main.post(()->{
                    finishSettingsSave();
                    asrKey.setText("");modelKey.setText("");
                    updateSavedInfo();hideKeyboard();
                    boolean bothKeys=secure.has(asr.keySlot) && secure.has(provider.keySlot());
                    if(bothKeys)setCredentialsExpanded(false,true);
                    saveBtn.setText(bothKeys?"✓ 已保存":"已保存设置");
                    Toast.makeText(this,bothKeys?"设置已保存":"设置已保存，请补充未设置的密钥",Toast.LENGTH_SHORT).show();
                    main.postDelayed(()->{if(!isFinishing() && saveBtn!=null && saveBtn.isEnabled())
                        saveBtn.setText("保存设置");},1400);
                });
            }catch(Exception e){
                main.post(()->{
                    finishSettingsSave();
                    saveBtn.setText("保存设置");
                    new AlertDialog.Builder(this).setTitle("保存失败")
                        .setMessage(e.getMessage()==null?"请检查本机密钥存储":e.getMessage())
                        .setPositiveButton("确定",null).show();
                });
            }
        },"save-api-configuration").start();
    }
    private void finishSettingsSave(){
        saveBtn.setEnabled(true);startBtn.setEnabled(!busy);
        providerSpinner.setEnabled(true);asrSpinner.setEnabled(true);
        modelName.setEnabled(true);modelKey.setEnabled(true);asrKey.setEnabled(true);
        apiEndpoint.setEnabled(ProviderPresets.byIndex(activeProviderIndex).custom);
    }
    /** Returns false if validation or secure storage fails; never launch with invalid config. */
    private boolean saveKeys(){
        try {
            ProviderPresets.Provider provider=ProviderPresets.byIndex(providerSpinner.getSelectedItemPosition());
            String model=modelName.getText().toString().trim();
            if(model.isEmpty())throw new IllegalArgumentException("请填写当前服务商支持的模型 ID");
            String address=ProviderPresets.normalizeEndpoint(apiEndpoint.getText().toString());
            // Official presets must always use the verified official endpoint.
            if(!provider.custom)address=provider.endpoint;
            int asrIndex=asrSpinner.getSelectedItemPosition();
            secure.save(AsrProviders.byIndex(asrIndex).keySlot,asrKey.getText().toString());
            secure.save(provider.keySlot(),modelKey.getText().toString());
            prefs.edit().putInt("asr_provider",asrIndex)
                .putInt("provider",providerSpinner.getSelectedItemPosition())
                .putString(provider.modelPref(),model).putString(provider.endpointPref(),address)
                .putString("model",model).apply();
            asrKey.setText("");modelKey.setText("");updateSavedInfo();
            return true;
        }catch(Exception e){new AlertDialog.Builder(this).setMessage("保存失败："+e.getMessage())
            .setPositiveButton("确定",null).show();return false;}
    }
    private void pickVideo(){
        if(busy)return;
        hideKeyboard();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_INPUT);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null){
            if(requestCode==SAVE_AUDIO && pendingAudioFile!=null) {
                pendingAudioFile.delete();pendingAudioFile=null;pendingAudioName=null;
            }
            return;
        }
        Uri uri=data.getData();
        if(requestCode==PICK_INPUT) {
            pickedUri=uri;originalName=displayName(uri);
            try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException ignored) { }
            selectedInfo.setText(originalName);
            selectedInfo.setContentDescription("已选择："+originalName);
            refreshResumeState();
        } else if(requestCode==SAVE_AUDIO && pendingAudioFile!=null) {
            File ready=pendingAudioFile;
            try(java.io.FileInputStream in=new java.io.FileInputStream(ready);
                java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                if(out==null)throw new java.io.IOException("无法打开保存位置");
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
                String actual=AssExportManager.displayName(getContentResolver(),uri);
                status.setText("音频已保存："+actual);
                Toast.makeText(this,"音频已保存",Toast.LENGTH_LONG).show();
            }catch(Exception e){
                new AlertDialog.Builder(this).setMessage("保存音频失败："+friendlyError(e))
                    .setPositiveButton("确定",null).show();
            }finally{ready.delete();pendingAudioFile=null;pendingAudioName=null;}
        } else if(requestCode==SAVE_ASS && completedAss!=null) {
            // Android 8/9 fallback. Most supported phones use MediaStore directly.
            try(InputStream in=new java.io.FileInputStream(completedAss);
                java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                if(out==null)throw new java.io.IOException("无法打开目标文件");
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
                Uri saved=uri;
                String actual=AssExportManager.displayName(getContentResolver(),saved);
                if(!AssExportNaming.hasAssExtension(actual) && DocumentsContract.isDocumentUri(this,saved)) {
                    try {
                        Uri renamed=DocumentsContract.renameDocument(getContentResolver(),saved,
                            AssExportNaming.normalized(outputName));
                        if(renamed!=null)saved=renamed;
                    }catch(Exception ignored) { /* A provider might not support rename. */ }
                    actual=AssExportManager.displayName(getContentResolver(),saved);
                }
                if(!AssExportNaming.hasAssExtension(actual))
                    throw new java.io.IOException("文件管理器未保留 .ass 后缀，请在文件管理器重命名；字幕内容未损坏");
                status.setText("已保存单色字幕："+actual);
                Toast.makeText(this,"已保存："+actual,Toast.LENGTH_LONG).show();
            } catch(Exception e){new AlertDialog.Builder(this).setMessage("保存字幕遇到问题："+e.getMessage())
                .setPositiveButton("确定",null).show();}
        }
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "视频文件";
    }
    private void showProgress(int percent,String message){
        main.post(()->{
            progress.setVisibility(View.VISIBLE);progress.setProgress(percent);status.setText(message);log(message);
            UsageLedger usage=taskUsage;
            if(usage!=null && usageInfo!=null)usageInfo.setText(usage.summary(currentInputPrice,currentOutputPrice));
        });
    }
    private void log(String line){
        String old=logs.getText().toString();
        if(old.length()>5500)old=old.substring(old.length()-4000);
        logs.setText(old+(old.isEmpty()?"":"\n")+line);
    }
    /** View state is derived from a verified matching checkpoint, not a stale button click. */
    private void renderResumeControls(){
        if(startBtn==null || clearCheckpointBtn==null || taskActionsRow==null)return;
        startBtn.setText(resumeAvailable?"继续任务":"开始生成字幕");
        clearCheckpointBtn.setVisibility(resumeAvailable?View.VISIBLE:View.GONE);
        taskActionsRow.setVisibility((stopBtn!=null && stopBtn.getVisibility()==View.VISIBLE)
                || resumeAvailable?View.VISIBLE:View.GONE);
    }
    private void refreshResumeState(){
        final int generation=++resumeCheckGeneration;
        final Uri uri=pickedUri;
        if(startBtn==null || uri==null || busy){
            if(uri==null){resumeAvailable=false;renderResumeControls();}
            return;
        }
        final String name=originalName;
        final String asr=AsrProviders.byIndex(asrSpinner.getSelectedItemPosition()).id;
        final String endpoint=apiEndpoint.getText().toString();
        final String model=modelName.getText().toString().trim();
        final TranslationMode mode=TranslationMode.fromIndex(qualityModeSpinner.getSelectedItemPosition());
        resumeAvailable=false;
        renderResumeControls();
        new Thread(()->{
            boolean found=false;
            try {
                String source=TranslationCheckpoint.sourceIdentity(this,uri,name);
                String config=TranslationCheckpoint.configuration(asr,endpoint,model,mode);
                found=TranslationCheckpoint.load(this,source,config)!=null;
            }catch(Exception ignored){/* If identity cannot be verified, do not offer resume. */}
            final boolean result=found;
            main.post(()->{
                if(generation!=resumeCheckGeneration || busy || !uri.equals(pickedUri))return;
                resumeAvailable=result;
                renderResumeControls();
            });
        },"check-subtitle-resume-state").start();
    }
    private void start(){
        if(busy)return;
        if(pickedUri==null){status.setText("请先选择视频或音频");return;}
        // Persist pending typed secrets before launching; key inputs are never logged.
        if(!saveKeys())return;
        ProviderPresets.Provider provider=ProviderPresets.byIndex(providerSpinner.getSelectedItemPosition());
        String endpoint=ProviderPresets.normalizeEndpoint(apiEndpoint.getText().toString());
        String model=modelName.getText().toString().trim();
        final TranslationMode mode=TranslationMode.fromIndex(qualityModeSpinner.getSelectedItemPosition());
        currentInputPrice=UsageLedger.price(inputPrice.getText().toString());
        currentOutputPrice=UsageLedger.price(outputPrice.getText().toString());
        if((currentInputPrice<0 && !inputPrice.getText().toString().trim().isEmpty()) ||
           (currentOutputPrice<0 && !outputPrice.getText().toString().trim().isEmpty())){
            status.setText("Token 单价请输入 0 至 10000 之间的数字，或留空");return;
        }
        final AsrProviders.Provider asr=AsrProviders.byIndex(asrSpinner.getSelectedItemPosition());
        final String asrSecret,translate;
        try { asrSecret=secure.load(asr.keySlot);translate=secure.load(provider.keySlot()); }
        catch(Exception ex){status.setText("读取密钥失败，请重新保存");return;}
        if(asrSecret.isEmpty() || translate.isEmpty()) {
            setCredentialsExpanded(true,true);
            status.setText("请先填写识别和翻译 API Key");return;
        }
        if(model.isEmpty()){status.setText("请输入翻译模型名称");return;}
        final Uri uri=pickedUri;
        final String requestedName=AssWriter.outputName(originalName);
        hideKeyboard();
        busy=true;stop.set(false);startBtn.setEnabled(false);stopBtn.setEnabled(true);
        clearCheckpointBtn.setEnabled(false);
        stopBtn.setVisibility(View.VISIBLE);taskActionsRow.setVisibility(View.VISIBLE);
        audioExportBtn.setEnabled(false);audioToolToggle.setEnabled(false);
        for(int i=0;i<audioMode.getChildCount();i++)audioMode.getChildAt(i).setEnabled(false);
        asrSpinner.setEnabled(false);providerSpinner.setEnabled(false);
        modelName.setEnabled(false);asrKey.setEnabled(false);modelKey.setEnabled(false);
        qualityModeSpinner.setEnabled(false);inputPrice.setEnabled(false);outputPrice.setEnabled(false);
        apiEndpoint.setEnabled(false);saveBtn.setEnabled(false);settingsToggle.setEnabled(false);
        exportBtn.setVisibility(View.GONE);
        previewBtn.setVisibility(View.GONE);
        progress.setProgress(0);logs.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Thread worker=new Thread(()->{
            File work=new File(getCacheDir(),"subtitle_work");work.mkdirs();
            try {
                // A matching private checkpoint avoids re-uploading ASR audio and replaying finished batches.
                String sourceIdentity=TranslationCheckpoint.sourceIdentity(this,uri,originalName);
                String configIdentity=TranslationCheckpoint.configuration(asr.id,endpoint,model,mode);
                TranslationCheckpoint checkpoint=TranslationCheckpoint.load(this,sourceIdentity,configIdentity);
                // An unidentifiable file must not accidentally inherit a different task's usage.
                String job=(sourceIdentity==null?java.util.UUID.randomUUID().toString():sourceIdentity)+":"+configIdentity;
                taskUsage=UsageLedger.open(this,job);
                pipeline=new CloudPipeline(stop);
                pipeline.setUsageLedger(taskUsage);
                List<SubtitleData.Cue> cues;
                if(checkpoint!=null) {
                    cues=checkpoint.cues;
                    showProgress(39,"已找到相同视频的恢复进度：跳过英文识别及已完成的翻译批次");
                } else {
                    showProgress(3,"正在从视频中提取音频…");
                    AudioExtractor.AudioFile audio=AudioExtractor.extract(this,uri,work,stop,
                        percent -> showProgress(percent,"正在提取音频…（"+percent+"%）"));
                    if(stop.get())throw new InterruptedException("任务已取消");
                    showProgress(18,"已生成标准 PCM WAV（"+(audio.file.length()/1024/1024)+" MB）");
                    if(AsrProviders.ASSEMBLYAI.equals(asr.id)) {
                        assemblyTranscriber=new AssemblyAiTranscriber(stop);
                        cues=assemblyTranscriber.transcribe(audio,asrSecret,this::showProgress);
                    } else {
                        cues=pipeline.transcribe(audio,asrSecret,this::showProgress);
                    }
                    // Privacy: keep recognized data in private app storage, never upload video.
                    File transcript=new File(getFilesDir(),"last_english.json");
                    try(FileOutputStream out=new FileOutputStream(transcript)){
                        out.write(SubtitleData.toJson(cues).toString().getBytes(StandardCharsets.UTF_8));
                    }
                    if(sourceIdentity!=null) {
                        checkpoint=TranslationCheckpoint.fresh(sourceIdentity,configIdentity,cues);
                        checkpoint.save(this);
                    }
                }
                pipeline.translateAndPolish(cues,translate,endpoint,model,this::showProgress,checkpoint,this,mode);
                SubtitleQuality.Report quality=SubtitleQuality.inspect(cues);
                String qualityText=quality.firstDetails(30);
                String ass=AssWriter.render(cues);
                // Invalidate the previous edit draft BEFORE replacing its ASS file.
                // An unexpected exit must never pair an older draft with new output.
                SubtitleDraftStore.clear(this);
                File target=new File(getFilesDir(),"last_result.ass");
                File tmp=new File(getFilesDir(),"last_result.ass.tmp");
                try(FileOutputStream out=new FileOutputStream(tmp)){
                    out.write(ass.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if(!tmp.renameTo(target)){
                    try(FileOutputStream out=new FileOutputStream(target)){
                        out.write(ass.getBytes(StandardCharsets.UTF_8));
                    }
                    tmp.delete();
                }
                completedAss=target;outputName=requestedName;
                // The editable snapshot mirrors the newly persisted ASS.
                try { SubtitleDraftStore.save(this,cues); }
                catch(Exception ignored){/* ASS remains downloadable even without editor data. */}
                // Keep checkpoint until the final subtitle was actually persisted.
                if(checkpoint!=null)TranslationCheckpoint.clear(this);
                prefs.edit().putString("last_file",outputName).apply();
                // Quality reports contain local cue IDs/reasons only, never API secrets or video audio.
                try(FileOutputStream qualityFile=new FileOutputStream(new File(getFilesDir(),"last_quality_report.txt"))){
                    qualityFile.write(qualityText.getBytes(StandardCharsets.UTF_8));
                }catch(Exception ignored){/* Diagnostic report is advisory, not a reason to reject the ASS. */}
                String qualityNote=quality.findings.isEmpty()?"":"，建议复核 "+quality.findings.size()+" 处";
                showProgress(100,"已完成："+outputName+"（"+cues.size()+" 段对白"+qualityNote+"）");
                main.post(()->{exportBtn.setVisibility(View.VISIBLE);
                    latestQualityReport=qualityText;qualityBtn.setVisibility(View.VISIBLE);});
            } catch(InterruptedException e) {
                showProgress(progress.getProgress(),"任务已取消；之前完成的字幕仍保留在本机");
            } catch(Exception e) {
                if(stop.get())showProgress(progress.getProgress(),"任务已取消");
                else showProgress(progress.getProgress(),"处理失败："+friendlyError(e));
            } finally {
                pipeline=null;assemblyTranscriber=null;
                new File(work,"asr_audio.m4a").delete();new File(work,"asr_audio.wav").delete();
                main.post(()->{busy=false;startBtn.setEnabled(true);stopBtn.setEnabled(false);
                    clearCheckpointBtn.setEnabled(true);
                    stopBtn.setVisibility(View.GONE);audioExportBtn.setEnabled(true);audioToolToggle.setEnabled(true);
                    refreshResumeState();
                    for(int i=0;i<audioMode.getChildCount();i++)audioMode.getChildAt(i).setEnabled(true);
                    asrSpinner.setEnabled(true);providerSpinner.setEnabled(true);
                    modelName.setEnabled(true);asrKey.setEnabled(true);modelKey.setEnabled(true);
                    qualityModeSpinner.setEnabled(true);inputPrice.setEnabled(true);outputPrice.setEnabled(true);
                    apiEndpoint.setEnabled(ProviderPresets.byIndex(activeProviderIndex).custom);
                    saveBtn.setEnabled(true);settingsToggle.setEnabled(true);
                    if(completedAss!=null && completedAss.isFile())exportBtn.setVisibility(View.VISIBLE);
                    previewBtn.setVisibility(SubtitleDraftStore.exists(this) &&
                        completedAss!=null && completedAss.isFile()?View.VISIBLE:View.GONE);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});
            }
        },"subtitle-processing");
        worker.start();
    }
    private String friendlyError(Exception e){
        if(e instanceof java.io.IOException || e instanceof IllegalArgumentException) {
            String msg=e.getMessage(); if(msg!=null && msg.length()<180)return msg;
        }
        return e.getClass().getSimpleName()+"；如反复出现，请展开运行日志检查步骤";
    }
    private void cancel(){
        if(!busy)return;
        stop.set(true);if(pipeline!=null)pipeline.stop();
        if(assemblyTranscriber!=null)assemblyTranscriber.cancel();
        status.setText("正在安全停止任务…");stopBtn.setEnabled(false);
    }
    /** Export offline audio without touching API keys or calling the cloud. */
    private void exportAudio(){
        if(busy)return;
        if(pickedUri==null){status.setText("请先选择视频或音频");return;}
        final boolean clip=audioTimeFields.getVisibility()==View.VISIBLE;
        final long startUs,endUs;
        try {
            if(clip){
                startUs=AudioTimeRange.parse(audioFrom.getText().toString());
                endUs=AudioTimeRange.parse(audioTo.getText().toString());
                AudioTimeRange.validate(startUs,endUs);
            }else{startUs=0;endUs=0;}
        }catch(IllegalArgumentException e){
            new AlertDialog.Builder(this).setMessage(e.getMessage())
                .setPositiveButton("确定",null).show();return;
        }
        hideKeyboard();busy=true;stop.set(false);
        startBtn.setEnabled(false);stopBtn.setEnabled(true);stopBtn.setVisibility(View.VISIBLE);
        taskActionsRow.setVisibility(View.VISIBLE);
        audioExportBtn.setEnabled(false);audioToolToggle.setEnabled(false);
        for(int i=0;i<audioMode.getChildCount();i++)audioMode.getChildAt(i).setEnabled(false);
        asrSpinner.setEnabled(false);providerSpinner.setEnabled(false);
        modelName.setEnabled(false);saveBtn.setEnabled(false);settingsToggle.setEnabled(false);
        audioFrom.setEnabled(false);audioTo.setEnabled(false);
        progress.setProgress(0);progress.setVisibility(View.VISIBLE);
        status.setText("正在提取音频…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Uri uri=pickedUri;final String inputName=originalName;
        new Thread(()->{
            File dir=new File(getCacheDir(),"audio_export_work");dir.mkdirs();
            File output=null;
            try {
                showProgress(4,"正在提取音轨…");
                AudioClipExporter.Exported result=AudioClipExporter.extract(this,uri,dir,
                    startUs,endUs,stop,percent -> showProgress(percent,"正在提取音频…（"+percent+"%）"));
                output=result.file;
                if(stop.get())throw new InterruptedException("任务已取消");
                String name=AudioTimeRange.fileName(inputName,clip,startUs,endUs,result.extension);
                showProgress(95,"提取完成，正在保存到 Download…");
                if(Build.VERSION.SDK_INT>=29){
                    AudioDownloadsManager.Result saved=AudioDownloadsManager.save(this,output,name,
                        result.mime,result.extension,stop);
                    showProgress(100,"音频已保存："+saved.name);
                    if(!saved.name.toLowerCase(java.util.Locale.ROOT).endsWith(result.extension)){
                        main.post(()->new AlertDialog.Builder(this)
                            .setTitle("音频已保存，扩展名需要修正")
                            .setMessage("手机系统保存为："+saved.name+"\n请在下载文件夹将文件名末尾改为 "+result.extension)
                            .setPositiveButton("确定",null).show());
                    }
                }else {
                    pendingAudioFile=output;pendingAudioName=name;
                    final String mime=result.mime;
                    main.post(()->{
                        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        save.addCategory(Intent.CATEGORY_OPENABLE);save.setType(mime);
                        save.putExtra(Intent.EXTRA_TITLE,name);
                        startActivityForResult(save,SAVE_AUDIO);
                        status.setText("请选择音频保存位置");
                    });
                    output=null; // Keep until file-picker callback.
                }
            }catch(InterruptedException e){
                showProgress(progress.getProgress(),"音频提取已取消");
            }catch(Exception e){
                if(stop.get())showProgress(progress.getProgress(),"音频导出已取消");
                else showProgress(progress.getProgress(),"音频导出失败："+friendlyError(e));
            }finally{
                if(output!=null)output.delete();
                if(pendingAudioFile==null) {
                    new File(dir,"export_audio.m4a").delete();
                    new File(dir,"export_audio.wav").delete();
                    new File(dir,"wav_temp/asr_audio.wav").delete();
                }
                main.post(()->{
                    busy=false;startBtn.setEnabled(true);stopBtn.setEnabled(false);
                    stopBtn.setVisibility(View.GONE);audioExportBtn.setEnabled(true);
                    audioToolToggle.setEnabled(true);
                    refreshResumeState();
                    for(int i=0;i<audioMode.getChildCount();i++)audioMode.getChildAt(i).setEnabled(true);
                    asrSpinner.setEnabled(true);providerSpinner.setEnabled(true);
                    modelName.setEnabled(true);saveBtn.setEnabled(true);settingsToggle.setEnabled(true);
                    audioFrom.setEnabled(true);audioTo.setEnabled(true);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
        },"offline-audio-export").start();
    }

    private void export(){
        if(completedAss==null || !completedAss.isFile())return;
        outputName=AssExportNaming.normalized(outputName);
        if(Build.VERSION.SDK_INT>=29) {
            // Use MediaStore.Downloads instead of ACTION_CREATE_DOCUMENT:
            // vendor save pickers changed .ass to .txt or .bin based on MIME.
            exportBtn.setEnabled(false);
            final File file=completedAss;
            final String wanted=outputName;
            new Thread(()->{
                try {
                    AssExportManager.Result result=AssExportManager.saveToDownloads(this,file,wanted);
                    main.post(()->{
                        status.setText("已保存到 Download："+result.name);
                        if(AssExportNaming.hasAssExtension(result.name)) {
                            Toast.makeText(this,"已保存 ASS："+result.name,Toast.LENGTH_LONG).show();
                        } else {
                            new AlertDialog.Builder(this)
                                .setTitle("字幕已保存，文件名需调整")
                                .setMessage("字幕内容已完整保存到 Download，但手机系统将文件命名为：\n"
                                    +result.name+"\n\n请在文件管理器里将结尾修改为 .ass；不需要重新识别或翻译。")
                                .setPositiveButton("确定",null).show();
                        }
                    });
                }catch(Exception e) {
                    main.post(()->new AlertDialog.Builder(this)
                        .setTitle("ASS 导出失败")
                        .setMessage(e.getMessage()+"\n原字幕仍保存在 App 内，可再次尝试保存。")
                        .setPositiveButton("确定",null).show());
                }finally { main.post(()->exportBtn.setEnabled(true)); }
            },"ass-export").start();
            return;
        }
        // Android 8/9 fallback; use a subtitle-specific MIME instead of binary.
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(AssExportNaming.MIME_ASS);
        i.putExtra(Intent.EXTRA_TITLE,outputName);
        startActivityForResult(i,SAVE_ASS);
    }
}
