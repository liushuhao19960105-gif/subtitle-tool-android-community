"""Video Subtitle Tool PC: fixed-width ivory/forest-green Windows desktop UI."""
from __future__ import annotations
import json
from pathlib import Path
import queue
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import core
import security

BG='#F6F4ED'; PANEL='#FFFEFB'; GREEN='#396B53'; HOVER='#2E5642'; INK='#28382E'; MUTED='#68766C'; STROKE='#E1E8DE'
FONT='Microsoft YaHei UI'
SETTINGS=core.APP_DIR/'settings.json'


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('视频字幕工具')
        self.geometry('1200x840')
        self.minsize(1020,740)
        self.configure(bg=BG)
        self.task: threading.Thread | None = None
        self.stop = threading.Event()
        self.messages: queue.Queue = queue.Queue()
        self.cues: list[core.Cue] = []
        self.last_output: Path | None = None
        self.current_issues: list[str] = []
        saved=core.json_load(SETTINGS,{}) or {}
        self.video=tk.StringVar(value='')
        self.destination=tk.StringVar(value=saved.get('destination',''))
        self.asr_provider=tk.StringVar(value=saved.get('asr',core.ASR[0]))
        self.model_provider=tk.StringVar(value=saved.get('provider','DeepSeek'))
        self.endpoint=tk.StringVar(value=saved.get('endpoint',core.PROVIDERS['DeepSeek'][0]))
        self.model=tk.StringVar(value=saved.get('model',core.PROVIDERS['DeepSeek'][1]))
        self.mode=tk.StringVar(value=saved.get('mode','标准'))
        self.input_price=tk.StringVar(value=saved.get('input_price',''))
        self.output_price=tk.StringVar(value=saved.get('output_price',''))
        self.asr_key=tk.StringVar()
        self.translate_key=tk.StringVar()
        self.status=tk.StringVar(value='就绪 · 选择视频后开始')
        self.usage=tk.StringVar(value='Token 用量：尚无数据')
        self.selected=tk.StringVar(value='尚未选择视频或音频')
        self.progress=tk.DoubleVar(value=0)
        self._configure_style()
        self._ui()
        self._load_keys()
        self._load_last()
        for var in (self.video,self.asr_provider,self.model,self.endpoint,self.mode):
            var.trace_add('write', lambda *args:self.after(160,self._resume_state))
        self.protocol('WM_DELETE_WINDOW',self._on_close)
        self.after(120,self._drain)

    def _configure_style(self):
        style=ttk.Style(self)
        style.theme_use('clam')
        style.configure('TCombobox',font=(FONT,10),padding=6,fieldbackground=PANEL,
                        background=PANEL,foreground=INK,arrowsize=14)
        style.configure('TProgressbar',troughcolor=STROKE,background=GREEN,borderwidth=0,thickness=11)
        style.configure('Treeview',font=(FONT,10),rowheight=34,background=PANEL,fieldbackground=PANEL,foreground=INK)
        style.configure('Treeview.Heading',font=(FONT,10,'bold'),background=BG,foreground=INK)
        style.map('Treeview',background=[('selected','#DCEBDD')])

    def _label(self,where,text,size=10,color=INK,bold=False):
        return tk.Label(where,text=text,bg=where.cget('bg'),fg=color,font=(FONT,size,'bold' if bold else 'normal'),anchor='w')

    def _section(self,parent,title):
        box=tk.Frame(parent,bg=PANEL,highlightthickness=1,highlightbackground=STROKE,padx=18,pady=13)
        box.pack(fill='x',pady=(0,11))
        self._label(box,title,12,INK,True).pack(anchor='w',pady=(0,9))
        return box

    def _button(self,where,title,command,primary=False,width=14):
        btn=tk.Button(where,text=title,command=command,width=width,relief='flat',bd=0,cursor='hand2',
             padx=12,pady=8,font=(FONT,10,'bold'),bg=GREEN if primary else '#E9EFE8',
             fg='white' if primary else GREEN,activebackground=HOVER if primary else '#D9E7D9',
             activeforeground='white' if primary else GREEN)
        return btn

    def _entry(self,where,var,width=20,secret=False):
        e=tk.Entry(where,textvariable=var,width=width,show='●' if secret else '',font=(FONT,10),
                   relief='solid',bd=1,highlightthickness=0,bg='white',fg=INK,insertbackground=INK)
        return e

    def _row_field(self,frame,text,var,width=20,secret=False):
        c=tk.Frame(frame,bg=PANEL)
        self._label(c,text,9,MUTED).pack(anchor='w',pady=(0,4))
        e=self._entry(c,var,width,secret)
        e.pack(fill='x',ipady=6)
        return c,e

    def _ui(self):
        header=tk.Frame(self,bg=BG,padx=30,pady=17)
        header.pack(fill='x')
        logo=tk.Label(header,text='CC',bg=GREEN,fg='white',font=(FONT,14,'bold'),width=4,height=2)
        logo.pack(side='left')
        tk.Label(header,text='视频字幕工具',font=(FONT,22,'bold'),bg=BG,fg=INK).pack(side='left',padx=15)
        tk.Label(header,text='PC 桌面版  ·  单文件 ASS 导出',font=(FONT,10),bg=BG,fg=MUTED).pack(side='right')
        outer=tk.Frame(self,bg=BG,padx=27,pady=0)
        outer.pack(fill='both',expand=True)
        top=self._section(outer,'接口设置')
        row=tk.Frame(top,bg=PANEL)
        row.pack(fill='x')
        a=tk.Frame(row,bg=PANEL);a.pack(side='left',fill='x',expand=True,padx=(0,12))
        self._label(a,'语音识别服务',9,MUTED).pack(anchor='w')
        ttk.Combobox(a,textvariable=self.asr_provider,values=core.ASR,state='readonly').pack(fill='x',pady=(4,0))
        b=tk.Frame(row,bg=PANEL);b.pack(side='left',fill='x',expand=True,padx=(0,12))
        self._label(b,'翻译 API 平台',9,MUTED).pack(anchor='w')
        self.provider_box=ttk.Combobox(b,textvariable=self.model_provider,values=list(core.PROVIDERS),state='readonly')
        self.provider_box.pack(fill='x',pady=(4,0));self.provider_box.bind('<<ComboboxSelected>>',self._provider_changed)
        c=tk.Frame(row,bg=PANEL);c.pack(side='left',fill='x',expand=True)
        self._label(c,'翻译质量模式',9,MUTED).pack(anchor='w')
        ttk.Combobox(c,textvariable=self.mode,values=['快速','标准','高质量'],state='readonly').pack(fill='x',pady=(4,0))
        api=tk.Frame(top,bg=PANEL);api.pack(fill='x',pady=(9,0))
        for text,var,weight in [('识别 API Key',self.asr_key,1),('翻译 API Key',self.translate_key,1),('模型 ID',self.model,1)]:
            block,entry=self._row_field(api,text,var,34,secret='Key' in text)
            block.pack(side='left',fill='x',expand=bool(weight),padx=(0,12))
        endpoint=tk.Frame(top,bg=PANEL);endpoint.pack(fill='x',pady=(10,0))
        block,_=self._row_field(endpoint,'翻译接口地址（HTTPS）',self.endpoint,86)
        block.pack(side='left',fill='x',expand=True)
        keys=tk.Frame(top,bg=PANEL);keys.pack(fill='x',pady=(10,0))
        self._button(keys,'安全保存 API',self._save_keys,width=14).pack(side='left')
        self._button(keys,'清除已保存密钥',self._clear_keys,width=16).pack(side='left',padx=8)
        self._label(keys,'使用 Windows 当前用户账户加密；不写入断点或日志',9,MUTED).pack(side='left',padx=10)
        prices=tk.Frame(top,bg=PANEL);prices.pack(fill='x',pady=(10,0))
        for title,var in [('输入美元/百万 Token',self.input_price),('输出美元/百万 Token',self.output_price)]:
            block,_=self._row_field(prices,title,var,20)
            block.pack(side='left',padx=(0,15))
        self._label(prices,'费用是估算值，未知用量/语音识别费用另计',9,MUTED).pack(side='left',padx=8)
        video=self._section(outer,'输入文件')
        file_row=tk.Frame(video,bg=PANEL);file_row.pack(fill='x')
        self._button(file_row,'选择视频 / 音频',self._pick,primary=True,width=18).pack(side='left')
        self._button(file_row,'输出位置',self._pick_dest,width=12).pack(side='left',padx=9)
        self.file_panel=tk.Frame(video,bg=BG,padx=13,pady=9)
        self.file_panel.pack(fill='x',pady=(10,0))
        tk.Label(self.file_panel,text='当前文件',bg=BG,fg=GREEN,font=(FONT,9,'bold'),anchor='w').pack(fill='x')
        tk.Label(self.file_panel,textvariable=self.selected,bg=BG,fg=INK,font=(FONT,13,'bold'),anchor='w',wraplength=1030,justify='left').pack(fill='x',pady=(3,0))
        tasks=self._section(outer,'任务控制')
        buttons=tk.Frame(tasks,bg=PANEL);buttons.pack(anchor='w')
        self.start_btn=self._button(buttons,'开始生成字幕',self._start,primary=True,width=17)
        self.start_btn.pack(side='left',padx=(0,10))
        self.export_btn=self._button(buttons,'导出 ASS',self._export,width=12)
        self.export_btn.pack(side='left',padx=(0,10))
        self.edit_btn=self._button(buttons,'字幕文字编辑',self._editor,width=15)
        self.edit_btn.pack(side='left',padx=(0,10))
        self.report_btn=self._button(buttons,'质量报告',self._report,width=12)
        self.report_btn.pack(side='left')
        secondary=tk.Frame(tasks,bg=PANEL);secondary.pack(anchor='w',pady=(7,0))
        self.stop_btn=self._button(secondary,'停止任务',self._stop,width=12)
        self.stop_btn.pack(side='left',padx=(0,10));self.stop_btn.config(state='disabled')
        self.clear_btn=self._button(secondary,'清除进度',self._clear,width=12)
        self.clear_btn.pack(side='left');self.clear_btn.pack_forget()
        self._label(tasks,'停止后可继续同一视频、接口、模型与质量模式。清除后按钮自动隐藏。',9,MUTED).pack(anchor='w',pady=(9,0))
        taskprogress=self._section(outer,'处理进度')
        ttk.Progressbar(taskprogress,variable=self.progress,maximum=100).pack(fill='x',pady=(2,9))
        tk.Label(taskprogress,textvariable=self.status,bg=PANEL,fg=GREEN,font=(FONT,10),anchor='w').pack(fill='x')
        tk.Label(taskprogress,textvariable=self.usage,bg=PANEL,fg=MUTED,font=(FONT,9),anchor='w',wraplength=1080).pack(fill='x',pady=(5,0))
        logs=tk.Frame(outer,bg=PANEL,highlightthickness=1,highlightbackground=STROKE,padx=14,pady=11)
        logs.pack(fill='both',expand=True,pady=(0,9))
        self._label(logs,'运行日志',11,INK,True).pack(anchor='w',pady=(0,7))
        self.logbox=tk.Text(logs,height=5,bg=BG,fg=INK,wrap='word',font=(FONT,9),bd=0,relief='flat',state='disabled')
        self.logbox.pack(fill='both',expand=True)
        tk.Label(self,text='本地提取音频 · 外部语音识别 / 翻译 API 可能收费 · 仅导出单色 ASS',
                 font=(FONT,9),bg=BG,fg=MUTED,anchor='w',padx=29).pack(fill='x',pady=(0,6))
        self._resume_state()

    def _provider_changed(self,event=None):
        endpoint,model=core.PROVIDERS.get(self.model_provider.get(),('',''))
        self.endpoint.set(endpoint)
        self.model.set(model)

    def _pick(self):
        value=filedialog.askopenfilename(title='选择视频或音频',filetypes=[
            ('视频与音频','*.mp4 *.mkv *.avi *.mov *.webm *.wmv *.mp3 *.wav *.m4a *.flac *.aac'),('所有文件','*.*')])
        if value:
            self.video.set(value)
            self.selected.set(Path(value).name)
            if not self.destination.get():
                self.destination.set(str(Path(value).parent))
            self._save_settings()
            self._resume_state()

    def _pick_dest(self):
        path=filedialog.askdirectory(title='选择 ASS 输出目录',initialdir=self.destination.get() or None)
        if path:
            self.destination.set(path)
            self._save_settings()

    def _save_settings(self):
        core.json_write(SETTINGS,{'asr':self.asr_provider.get(),'provider':self.model_provider.get(),
                 'endpoint':self.endpoint.get(),'model':self.model.get(),'mode':self.mode.get(),
                 'input_price':self.input_price.get(),'output_price':self.output_price.get(),
                 'destination':self.destination.get()})

    def _load_keys(self):
        try:
            keys=security.load_keys()
            self.asr_key.set(keys.get('asr_'+self.asr_provider.get(),''))
            self.translate_key.set(keys.get('translate_'+self.model_provider.get(),''))
        except (OSError,ValueError,KeyError,RuntimeError) as exc:
            self._log('已保存 API Key 无法解密，请重新输入并保存。')

    def _save_keys(self):
        try:
            keys=security.load_keys() if security.SECRET_FILE.exists() else {}
            keys['asr_'+self.asr_provider.get()]=self.asr_key.get()
            keys['translate_'+self.model_provider.get()]=self.translate_key.get()
            security.save_keys(keys)
            self._log('API Key 已用 Windows DPAPI 加密保存')
            messagebox.showinfo('保存成功','API 已安全保存到 Windows 当前用户账户。')
        except Exception as exc:
            messagebox.showerror('保存失败',str(exc))

    def _clear_keys(self):
        if messagebox.askyesno('清除已保存密钥','确定清除本机加密保存的 API Key 吗？'):
            security.delete_keys();self.asr_key.set('');self.translate_key.set('')
            self._log('已清除保存的 API Key')

    def _load_last(self):
        data=core.json_load(core.APP_DIR/'last_result.json',{}) or {}
        try:
            self.cues=[core.Cue(**item) for item in data.get('cues',[])]
            raw=data.get('output')
            self.last_output=Path(raw) if raw else None
            self.current_issues=data.get('issues') or []
            if self.last_output and self.last_output.exists():
                self._log('已载入上一次生成的字幕，可重新导出或逐句编辑')
        except (ValueError,TypeError):
            self.cues=[]

    def _identity(self):
        try:
            path=Path(self.video.get())
            if not path.is_file() or not self.model.get().strip():
                return ''
            return core.fingerprint(path,self.asr_provider.get(),
                core.normalized_endpoint(self.endpoint.get()),self.model.get().strip(),self.mode.get())
        except (OSError,ValueError):
            return ''

    def _resume_state(self):
        if self.task and self.task.is_alive():
            return
        state=core.load_checkpoint(self._identity()) if self._identity() else None
        self.start_btn.config(text='继续任务' if state else '开始生成字幕')
        if state:
            self.clear_btn.pack(side='left')
        else:
            self.clear_btn.pack_forget()

    def _clear(self):
        core.clear_checkpoint()
        self._resume_state()
        self.status.set('进度已清除，下次将从头开始')
        self._log('未完成进度已清除')

    def _start(self):
        if self.task and self.task.is_alive():
            return
        path=Path(self.video.get())
        if not path.is_file():
            messagebox.showwarning('缺少视频','请先选择视频或音频文件。');return
        try:
            core.normalized_endpoint(self.endpoint.get())
            if not self.translate_key.get().strip() or not self.model.get().strip():
                raise ValueError('请填写翻译 API Key 和模型 ID')
            if not self.asr_key.get().strip() and not core.load_checkpoint(self._identity()):
                raise ValueError('请填写语音识别 API Key')
            for value in (self.input_price.get(),self.output_price.get()):
                if value.strip() and (not 0<=float(value)<=10000):
                    raise ValueError('Token 单价须为 0~10000 或留空')
            prices=[float(v) if v.strip() else None for v in (self.input_price.get(),self.output_price.get())]
        except (ValueError,TypeError) as exc:
            messagebox.showerror('配置不完整',str(exc));return
        self._save_settings()
        self.stop=threading.Event()
        self.start_btn.config(state='disabled');self.stop_btn.config(state='normal')
        self.clear_btn.pack_forget()
        self.progress.set(3)
        params=(path,self.asr_provider.get(),self.asr_key.get(),self.endpoint.get(),
                self.model.get().strip(),self.translate_key.get(),self.mode.get(),
                Path(self.destination.get() or path.parent),self.stop)
        def report(s):self.messages.put(('log',s))
        def usage(u):self.messages.put(('usage',u,prices))
        def worker():
            try:
                self.messages.put(('progress',5))
                cues,u,path=core.run_job(*params,report,usage)
                self.messages.put(('done',cues,u,path,prices))
            except InterruptedError as exc:
                self.messages.put(('stopped',str(exc)))
            except Exception as exc:
                self.messages.put(('error',str(exc)))
        self.task=threading.Thread(target=worker,daemon=True)
        self.task.start()
        self._log('已开始：'+path.name)

    def _stop(self):
        self.stop.set()
        self.status.set('正在停止任务…已完成批次会保存断点')
        self.stop_btn.config(state='disabled')

    def _drain(self):
        try:
            while True:
                message=self.messages.get_nowait()
                typ=message[0]
                if typ=='log':
                    self._log(message[1]);self.status.set(message[1])
                    if '音频' in message[1]:self.progress.set(14)
                    elif '识别' in message[1]:self.progress.set(30)
                    elif '翻译' in message[1]:self.progress.set(57)
                    elif '校验' in message[1]:self.progress.set(86)
                elif typ=='usage':
                    _,usage,prices=message
                    self.usage.set('Token 用量：'+usage.display(*prices))
                elif typ=='progress':self.progress.set(message[1])
                elif typ=='done':
                    _,self.cues,usage,self.last_output,prices=message
                    self.current_issues=core.subtitle_quality(self.cues)
                    self.progress.set(100)
                    self.status.set('字幕已完成：'+self.last_output.name)
                    self.usage.set('Token 用量：'+usage.display(*prices))
                    self._log('保存成功：'+str(self.last_output))
                    self._log(f'质量检查：{len(self.current_issues)} 处建议复核')
                    self._finish()
                elif typ=='stopped':
                    self._log(message[1]);self.status.set('任务已停止，可继续任务或清除进度')
                    self._finish()
                elif typ=='error':
                    self._log('处理失败：'+message[1]);self.status.set('处理失败，已完成批次可以续传')
                    self._finish()
                    messagebox.showerror('处理失败',message[1])
        except queue.Empty:
            pass
        self.after(140,self._drain)

    def _finish(self):
        self.start_btn.config(state='normal')
        self.stop_btn.config(state='disabled')
        self.after(160,self._resume_state)

    def _log(self,message):
        self.logbox.config(state='normal')
        self.logbox.insert('end',time.strftime('[%H:%M:%S] ')+str(message)+'\n')
        self.logbox.see('end')
        self.logbox.config(state='disabled')

    def _export(self):
        if not self.cues:
            messagebox.showinfo('暂无字幕','请先完成字幕处理。');return
        name=self.last_output.name if self.last_output else '视频.zh.ass'
        filename=filedialog.asksaveasfilename(title='导出单色 ASS 字幕',defaultextension='.ass',
                  initialfile=name,filetypes=[('ASS 字幕','*.ass')])
        if filename:
            try:
                core.export_ass(self.cues,Path(filename))
                self.last_output=Path(filename)
                self._log('已导出 '+filename)
                messagebox.showinfo('导出成功','ASS 字幕已保存。')
            except Exception as exc:
                messagebox.showerror('导出失败',str(exc))

    def _report(self):
        if not self.cues:
            messagebox.showinfo('暂无质量报告','请先完成字幕处理。');return
        report='质量初检：'+('未发现明显异常' if not self.current_issues else f'发现 {len(self.current_issues)} 处建议复核')
        report+='\n\n'+'\n'.join(self.current_issues[:180])
        if len(self.current_issues)>180:
            report+=f'\n另有 {len(self.current_issues)-180} 处，请在文字列表中核对'
        self._show_text('字幕质量报告',report)

    def _show_text(self,title,value):
        popup=tk.Toplevel(self);popup.title(title);popup.geometry('790x520');popup.configure(bg=BG)
        txt=tk.Text(popup,wrap='word',bg=PANEL,fg=INK,font=(FONT,10),bd=0,padx=18,pady=16)
        txt.pack(fill='both',expand=True,padx=15,pady=15)
        txt.insert('1.0',value);txt.config(state='disabled')

    def _editor(self):
        if not self.cues:
            messagebox.showinfo('暂无可编辑字幕','请先完成一次字幕处理。');return
        popup=tk.Toplevel(self);popup.title('字幕文字列表与逐句编辑');popup.geometry('1040x670');popup.configure(bg=BG)
        self._label(popup,'点击下面的字幕条目，修改中文后保存。不播放视频，不更改英文与时间码。',10,MUTED).pack(fill='x',padx=16,pady=13)
        panel=tk.Frame(popup,bg=BG);panel.pack(fill='both',expand=True,padx=15)
        table=ttk.Treeview(panel,columns=('time','en','zh'),show='headings',selectmode='browse')
        for col,title,width in [('time','时间码',150),('en','英语原文',390),('zh','中文字幕',390)]:
            table.heading(col,text=title);table.column(col,width=width,anchor='w')
        scroll=ttk.Scrollbar(panel,orient='vertical',command=table.yview);table.configure(yscrollcommand=scroll.set)
        table.pack(side='left',fill='both',expand=True);scroll.pack(side='right',fill='y')
        def fmt(ms):return f'{ms//60000:02d}:{ms//1000%60:02d}.{ms%1000//10:02d}'
        for i,c in enumerate(self.cues):
            table.insert('','end',iid=str(i),values=(f'{fmt(c.start)}—{fmt(c.end)}',c.en,c.zh))
        bottom=tk.Frame(popup,bg=BG);bottom.pack(fill='x',padx=17,pady=16)
        self._label(bottom,'当前字幕的中文字幕',10,INK,True).pack(anchor='w')
        editor=tk.Text(bottom,height=3,wrap='word',font=(FONT,11),bg=PANEL,fg=INK,bd=1,relief='solid')
        editor.pack(fill='x',pady=(7,9))
        def choose(event=None):
            items=table.selection()
            if items:
                i=int(items[0]);editor.delete('1.0','end');editor.insert('1.0',self.cues[i].zh)
        table.bind('<<TreeviewSelect>>',choose)
        def save():
            sel=table.selection()
            if not sel:
                return
            i=int(sel[0]);zh=editor.get('1.0','end').strip()
            if not zh or len(zh)>250:
                messagebox.showwarning('字幕不合法','字幕不能为空，最多 250 字。');return
            before=self.cues[i].zh
            self.cues[i].zh=zh
            try:
                output=self.last_output or Path(self.destination.get() or core.APP_DIR)/'视频.zh.ass'
                core.export_ass(self.cues,output)
                self.current_issues=core.subtitle_quality(self.cues)
                core.json_write(core.APP_DIR/'last_result.json',{'output':str(output),'cues':[core.asdict(c) for c in self.cues],
                                                            'issues':self.current_issues})
                table.item(sel[0],values=(table.item(sel[0])['values'][0],self.cues[i].en,zh))
                self._log(f'第 {self.cues[i].id} 条字幕修改后已更新 ASS')
            except Exception as exc:
                self.cues[i].zh=before
                messagebox.showerror('保存失败',str(exc))
        self._button(bottom,'保存修改到 ASS',save,primary=True,width=18).pack(anchor='e')
        if self.cues:
            table.selection_set('0');table.focus('0');choose()

    def _on_close(self):
        if self.task and self.task.is_alive():
            if not messagebox.askyesno('任务进行中','确认停止任务并关闭？已完成批次会保留断点。'):
                return
            self.stop.set()
        self.destroy()


if __name__=='__main__':
    App().mainloop()
