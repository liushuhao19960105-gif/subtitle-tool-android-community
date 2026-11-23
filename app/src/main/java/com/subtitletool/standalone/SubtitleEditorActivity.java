package com.subtitletool.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;

/** V1.9.0: private, local cue preview and manual Chinese edit. No server calls. */
public final class SubtitleEditorActivity extends Activity {
    private static final int BG=Color.rgb(243,240,234),WHITE=Color.rgb(255,254,251);
    private static final int INK=Color.rgb(36,40,36),GREEN=Color.rgb(82,117,87),MUTED=Color.rgb(105,111,103);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<String> rows=new ArrayList<>();
    private List<SubtitleData.Cue> cues;
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView summary;
    private Button issuesButton;
    private boolean saving=false;

    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String value,int sp,int color,boolean bold){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private static String clock(long millis){
        long seconds=Math.max(0,millis/1000);
        return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
    }
    private static String shortened(String text){
        String clean=(text==null?"":text).replace('\n',' ').trim();
        return clean.length()>85?clean.substring(0,85)+"…":clean;
    }
    private static String rowText(SubtitleData.Cue cue){
        return "#"+cue.id+"   "+clock(cue.startMs)+" → "+clock(cue.endMs)
               +"\n英：" + shortened(cue.en) +"\n中："+shortened(cue.zh);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(16));
        root.setBackgroundColor(BG);
        TextView title=label("字幕预览与编辑",21,INK,true);
        root.addView(title);
        summary=label("正在读取上次生成的字幕…",13,MUTED,false);
        LinearLayout.LayoutParams summaryLp=new LinearLayout.LayoutParams(-1,-2);
        summaryLp.topMargin=dp(10);summaryLp.bottomMargin=dp(12);
        root.addView(summary,summaryLp);
        issuesButton=new Button(this);
        issuesButton.setAllCaps(false);
        issuesButton.setTextSize(14);issuesButton.setTextColor(GREEN);
        issuesButton.setText("查看需复核字幕");
        issuesButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams issuesLp=new LinearLayout.LayoutParams(-1,dp(44));
        issuesLp.bottomMargin=dp(9);root.addView(issuesButton,issuesLp);
        list=new ListView(this);
        list.setBackgroundColor(WHITE);
        list.setDividerHeight(dp(1));
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1f));
        Button done=new Button(this);
        done.setAllCaps(false);done.setText("返回字幕工具");
        done.setTextColor(WHITE);done.setTextSize(15);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(GREEN);shape.setCornerRadius(dp(12));
        done.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF),shape,null));
        LinearLayout.LayoutParams bottom=new LinearLayout.LayoutParams(-1,dp(52));
        bottom.topMargin=dp(12);root.addView(done,bottom);
        done.setOnClickListener(v->{if(!saving)finish();});
        setContentView(root);
        adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){
            @Override public View getView(int position,View convertView,ViewGroup parent){
                TextView view=(TextView)super.getView(position,convertView,parent);
                view.setSingleLine(false);
                view.setMaxLines(3);
                view.setTextSize(14);
                view.setTextColor(INK);
                view.setPadding(dp(12),dp(12),dp(12),dp(12));
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->{
            if(!saving && cues!=null && position<cues.size())edit(position);
        });
        new Thread(()->{
            try {
                List<SubtitleData.Cue> loaded=SubtitleDraftStore.load(this);
                main.post(()->{
                    cues=loaded;
                    for(SubtitleData.Cue cue:cues)rows.add(rowText(cue));
                    adapter.notifyDataSetChanged();
                    summary.setText("共 "+cues.size()+" 条 · 点击任意字幕修改中文。英文和时间码保持不变。");
                    refreshProblemNavigator();
                });
            }catch(Exception e){
                main.post(()->{
                    summary.setText("未找到可编辑的字幕。请先完成一次字幕制作。");
                    Toast.makeText(this,"无法打开字幕预览",Toast.LENGTH_LONG).show();
                });
            }
        },"load-subtitle-editor").start();
    }
    /** Help non-technical users jump straight from a quality hint to its subtitle. */
    private void refreshProblemNavigator(){
        if(issuesButton==null || cues==null || saving)return;
        LinkedHashMap<Integer,String> issueByCue=new LinkedHashMap<>();
        for(SubtitleQuality.Finding finding:SubtitleQuality.inspect(cues).findings)
            if(!issueByCue.containsKey(finding.cueId))
                issueByCue.put(finding.cueId,finding.concern);
        if(issueByCue.isEmpty()){
            issuesButton.setVisibility(View.GONE);
            return;
        }
        issuesButton.setText("查看需复核字幕（"+issueByCue.size()+" 条）");
        issuesButton.setVisibility(View.VISIBLE);
        issuesButton.setOnClickListener(v->{
            if(saving || cues==null)return;
            LinkedHashMap<Integer,String> current=new LinkedHashMap<>();
            for(SubtitleQuality.Finding finding:SubtitleQuality.inspect(cues).findings){
                if(!current.containsKey(finding.cueId))current.put(finding.cueId,finding.concern);
                if(current.size()>=60)break;
            }
            if(current.isEmpty()){refreshProblemNavigator();return;}
            final ArrayList<Integer> ids=new ArrayList<>(current.keySet());
            String[] labels=new String[ids.size()];
            for(int i=0;i<ids.size();i++)
                labels[i]="第 "+ids.get(i)+" 条 · "+current.get(ids.get(i));
            new AlertDialog.Builder(this)
                .setTitle("选择要复核的字幕"+(issueByCue.size()>60?"（前60条）":""))
                .setItems(labels,(dialog,which)->{
                    if(saving)return;
                    int targetId=ids.get(which);
                    for(int i=0;i<cues.size();i++){
                        if(cues.get(i).id==targetId){
                            list.setSelection(i);
                            edit(i);
                            break;
                        }
                    }
                })
                .setNegativeButton("关闭",null)
                .show();
        });
    }
    @Override public void onBackPressed(){
        if(saving){
            Toast.makeText(this,"字幕正在保存，请完成后返回",Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private void edit(int index){
        final SubtitleData.Cue cue=cues.get(index);
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(12),dp(18),0);
        TextView en=label("原文："+cue.en,14,MUTED,false);
        panel.addView(en);
        EditText input=new EditText(this);
        input.setText(cue.zh);
        input.setSelection(input.length());
        input.setSingleLine(false);input.setMinLines(3);input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(250)});
        input.setTextColor(INK);input.setTextSize(16);
        LinearLayout.LayoutParams inputLp=new LinearLayout.LayoutParams(-1,-2);
        inputLp.topMargin=dp(12);panel.addView(input,inputLp);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("修改第 "+cue.id+" 条中文字幕")
            .setView(panel)
            .setNegativeButton("取消",null)
            .setPositiveButton("保存修改",null)
            .create();
        dialog.setOnShowListener(ignored->{
            Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v->{
                if(saving)return;
                String edited=input.getText().toString().trim();
                if(edited.isEmpty()){input.setError("中文字幕不能为空");return;}
                if(edited.length()>250){input.setError("最多 250 字");return;}
                if(edited.equals(cue.zh)){dialog.dismiss();return;}
                saving=true;positive.setEnabled(false);
                final String original=cue.zh;
                new Thread(()->{
                    try {
                        cue.zh=edited;
                        SubtitleDraftStore.save(this,cues);
                        SubtitleDraftStore.writeAss(this,cues);
                        SubtitleQuality.Report quality=SubtitleQuality.inspect(cues);
                        try(FileOutputStream out=new FileOutputStream(
                                new File(getFilesDir(),"last_quality_report.txt"))){
                            out.write(quality.firstDetails(30).getBytes(StandardCharsets.UTF_8));
                        }catch(Exception ignoredWrite){/* Report is optional. */}
                        main.post(()->{
                            rows.set(index,rowText(cue));
                            adapter.notifyDataSetChanged();
                            saving=false;
                            refreshProblemNavigator();
                            dialog.dismiss();
                            Toast.makeText(this,"字幕已保存，ASS 已同步更新",Toast.LENGTH_SHORT).show();
                        });
                    }catch(Exception e){
                        cue.zh=original;
                        try {
                            SubtitleDraftStore.save(this,cues);
                            SubtitleDraftStore.writeAss(this,cues);
                        }catch(Exception ignoredRollback){}
                        main.post(()->{
                            saving=false;positive.setEnabled(true);
                            Toast.makeText(this,"修改保存失败，原字幕已尽量保留",Toast.LENGTH_LONG).show();
                        });
                    }
                },"save-edited-subtitle").start();
            });
        });
        dialog.show();
    }
}
