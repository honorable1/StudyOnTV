import {useEffect,useMemo,useRef,useState} from 'react';
import {copyText} from './lib/clipboard';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Plus,Upload,Download,FileJson,Search,X,Pencil,Trash2,Check,Copy,Sparkles,ChevronLeft,ChevronRight,RefreshCw,ClipboardCopy,ExternalLink} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Label} from '@/components/ui/label';
import {Textarea} from '@/components/ui/textarea';
import {Badge} from '@/components/ui/badge';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription,DialogFooter} from '@/components/ui/dialog';
import {api} from './api';
import {Choice} from './RulesPage';

export function MathText({text,className=''}:{text:string,className?:string}){const ref=useRef<HTMLDivElement>(null);useEffect(()=>{if(!ref.current)return;ref.current.textContent=text||'';(window as any).SOTV_MATH?.(ref.current);},[text]);return <div ref={ref} className={className}/>;}
export function answerOf(q:any){if(q.type==='subjective')return q.modelAnswer||'由家长批改';if(q.type==='text')return(q.answers||[]).join(' / ');const a=q.type==='boolean'?['正确','错误']:(q.a||[]);return q.type==='multiple'?(q.correctIndices||[]).map((i:number)=>a[i]).join('、'):a[q.k]||'';}
const labels:{[key:string]:string}={single:'单选',multiple:'多选',boolean:'判断',text:'填空',subjective:'主观题'};
const dot:{[key:string]:string}={single:'bg-teal-600 dark:bg-teal-400',multiple:'bg-blue-600 dark:bg-blue-400',boolean:'bg-amber-600 dark:bg-amber-400',text:'bg-violet-600 dark:bg-violet-400',subjective:'bg-rose-600 dark:bg-rose-400'};
const blank={id:'',type:'single',q:'',opts:['','','',''],correct:[0],accepted:'',difficulty:2,tip:'',modelAnswer:'',rubric:'',subject:'',level:''};
export function download(data:any,name:string){const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),3000);}
const PAGE=25;

function Stars({n}:{n:number}){return <span className="whitespace-nowrap text-[11px] tracking-wide" title={n+' 星'}><span className="text-amber-600 dark:text-amber-400">{'★'.repeat(n)}</span><span className="text-muted-foreground/40">{'☆'.repeat(5-n)}</span></span>;}

export default function QuestionBank({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const query=useQuery({queryKey:['bank',sessionId],queryFn:()=>api('/api/questions?limit=5000&enabledOnly=false')});
 const questions=query.data?.items||[];
 const subjects:any[]=[...new Set(questions.map((q:any)=>q.subject).filter(Boolean))].sort();
 const levels:any[]=[...new Set(questions.map((q:any)=>q.level).filter(Boolean))].sort();
 const [search,setSearch]=useState(''),[typeFilter,setTypeFilter]=useState('all'),[starFilter,setStarFilter]=useState('all'),[subjectFilter,setSubjectFilter]=useState('all'),[levelFilter,setLevelFilter]=useState('all'),[sort,setSort]=useState('default');
 const [page,setPage]=useState(0),[selected,setSelected]=useState<string[]>([]),[drawer,setDrawer]=useState<string|null>(null);
 const [showAll,setShowAll]=useState(false);
 const [form,setForm]=useState<any>(null),[importing,setImporting]=useState(false),[text,setText]=useState(''),[fileName,setFileName]=useState(''),[remove,setRemove]=useState<any>(null),[importSubject,setImportSubject]=useState(''),[importLevel,setImportLevel]=useState('');
 const file=useRef<HTMLInputElement>(null);
 const [aiOpen,setAiOpen]=useState(false),[aiTab,setAiTab]=useState<'api'|'agent'>('agent');
 const [aiForm,setAiForm]=useState({topic:'',types:{single:true,multiple:true,boolean:true,text:true,subjective:false} as any,count:10,difficulty:'mixed',extra:'',withTip:true,skipDuplicates:true});
 const [aiLoading,setAiLoading]=useState(false),[aiItems,setAiItems]=useState<any[]>([]),[aiError,setAiError]=useState('');
 const grading=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const configured=!!(grading.data?.endpoint&&grading.data?.model);
 const aiProvider=(grading.data?.providers||[]).find((p:any)=>p.id===grading.data?.provider)?.name||'AI';

 const pausedCount=questions.filter((q:any)=>q.enabled===false).length;
 const filtered=useMemo(()=>{const k=search.trim().toLowerCase();const list=questions.filter((q:any)=>(showAll||q.enabled!==false)&&(typeFilter==='all'||(q.type||'single')===typeFilter)&&(starFilter==='all'||String(q.difficulty||2)===starFilter)&&(subjectFilter==='all'||(q.subject||'')===subjectFilter)&&(levelFilter==='all'||(q.level||'')===levelFilter)&&(!k||(q.q+' '+answerOf(q)+' '+q.tip+' '+(q.subject||'')+' '+(q.level||'')).toLowerCase().includes(k)));
  if(sort==='diff')return [...list].sort((a:any,b:any)=>(b.difficulty||2)-(a.difficulty||2));
  if(sort==='type')return [...list].sort((a:any,b:any)=>String(a.type).localeCompare(String(b.type))||(b.difficulty||2)-(a.difficulty||2));
  return list;
 },[questions,search,typeFilter,starFilter,subjectFilter,levelFilter,sort,showAll]);
 const shown=filtered.slice(page*PAGE,page*PAGE+PAGE);
 const current=drawer?questions.find((q:any)=>q.id===drawer):null;
 const drawerIndex=current?filtered.findIndex((q:any)=>q.id===current.id):-1;

 useEffect(()=>{if(page*PAGE>=filtered.length)setPage(0);},[filtered.length,page]);
 useEffect(()=>{if(drawer&&!questions.some((q:any)=>q.id===drawer))setDrawer(null);},[questions,drawer]);

 const bulkDelete=useMutation({mutationFn:(ids:string[])=>api('/api/questions/delete','POST',{ids}),onSuccess:(r:any)=>{setSelected([]);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已删除 ${r.deleted} 题`);},onError:(e:any)=>toast.error(e.message)});
 const bulkPause=useMutation({mutationFn:({ids,enabled}:{ids:string[],enabled:boolean})=>api('/api/questions/pause','POST',{ids,enabled}),onSuccess:(r:any,{enabled})=>{setSelected([]);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(enabled?`已恢复 ${r.updated} 题`:`已暂停 ${r.updated} 题`);},onError:(e:any)=>toast.error(e.message)});
 const save=useMutation({mutationFn:(body:any)=>api('/api/questions','POST',body),onSuccess:()=>{client.invalidateQueries({queryKey:['bank',sessionId]});setForm(null);toast.success('题目已保存');},onError:(e:any)=>toast.error(e.message)});
 const importingMutation=useMutation({mutationFn:(items:any[])=>api('/api/questions','POST',{items}),onSuccess:(r:any)=>{client.invalidateQueries({queryKey:['bank',sessionId]});setImporting(false);toast.success(`已导入 ${r.added} 题`);},onError:(e:any)=>toast.error(e.message)});
 const deleting=useMutation({mutationFn:(id:string)=>api('/api/questions/'+encodeURIComponent(id),'DELETE'),onSuccess:()=>{setRemove(null);setDrawer(null);client.invalidateQueries({queryKey:['bank',sessionId]});toast.success('已删除');},onError:(e:any)=>toast.error(e.message)});
 const saveGenerated=useMutation({mutationFn:(items:any[])=>api('/api/questions','POST',{items}),onSuccess:(r:any)=>{client.invalidateQueries({queryKey:['bank',sessionId]});toast.success(`已保存 ${r.added} 题到题库`);setAiItems([]);setAiOpen(false);},onError:(e:any)=>toast.error(e.message)});

 const toggle=(id:string)=>setSelected(list=>list.includes(id)?list.filter(x=>x!==id):[...list,id]);
 function edit(q?:any,copy=false){setDrawer(null);setForm(q?{id:copy?'':q.id,type:q.type||'single',q:q.q,opts:q.type==='boolean'?['正确','错误']:[...(q.a||[])],correct:q.type==='multiple'?[...(q.correctIndices||[])]:[q.k||0],accepted:(q.answers||[]).join('\n'),difficulty:q.difficulty||2,tip:q.tip||'',modelAnswer:q.modelAnswer||'',rubric:q.rubric||'',subject:q.subject||'',level:q.level||''}:{...blank});}
 function field(key:string,value:any){setForm((f:any)=>({...f,[key]:value}));}
 function submit(){const f=form;const q:any={q:f.q.trim(),type:f.type,difficulty:Number(f.difficulty),tip:f.tip,subject:f.subject.trim(),level:f.level.trim()};if(!q.q)return toast.error('请填写题干');if(f.id)q.id=f.id;if(f.type==='subjective'){q.modelAnswer=f.modelAnswer;q.rubric=f.rubric;}else if(f.type==='text'){q.answers=f.accepted.split('\n').map((s:string)=>s.trim()).filter(Boolean);if(!q.answers.length)return toast.error('请填写至少一个可接受答案');}else{q.a=f.type==='boolean'?['正确','错误']:f.opts.map((o:string)=>o.trim());if(f.type!=='boolean'&&q.a.some((o:string)=>!o))return toast.error('选项不能为空，多余的选项请删除');if(q.a.length<2)return toast.error('至少需要两个选项');const correct=f.correct.filter((i:number)=>i<q.a.length);if(!correct.length)return toast.error('请点击选项左侧的圆圈标记正确答案');if(f.type==='multiple')q.correctIndices=[...correct].sort();else q.k=correct[0];}save.mutate(q);}
 async function pick(e:any){const f=e.target.files?.[0];if(!f)return;if(f.size>4000000)return toast.error('文件过大，请拆分导入');setFileName(f.name);setText((await f.text()).replace(/^\uFEFF/,''));}
 function importNow(){try{const parsed=JSON.parse(text.replace(/^\uFEFF/,''));const items=Array.isArray(parsed)?parsed:parsed.items||[parsed];if(!items.length)return toast.error('题库不能为空');importingMutation.mutate(items.map((q:any)=>({...q,subject:q.subject||importSubject.trim(),level:q.level||importLevel.trim()})));}catch{toast.error('JSON 格式有误');}}
 function openAi(){setAiTab('agent');setAiError('');setAiOpen(true);}
 async function generate(){if(!aiForm.topic.trim())return toast.error('请填写出题范围');if(!Object.values(aiForm.types).some(Boolean))return toast.error('至少选择一种题型');setAiLoading(true);setAiError('');try{const result=await api('/api/questions/generate','POST',{topic:aiForm.topic,types:Object.keys(aiForm.types).filter(k=>aiForm.types[k]),count:aiForm.count,difficulty:aiForm.difficulty,extra:aiForm.extra,withTip:aiForm.withTip,skipDuplicates:aiForm.skipDuplicates},90000);setAiItems((result.items||[]).map((q:any)=>({...q,include:true})));const skipped=result.duplicates?`，跳过 ${result.duplicates} 道重复题`:'';toast.success(`生成 ${result.items.length} 题${skipped}`);}catch(e:any){setAiError(e.message);}finally{setAiLoading(false);}}
 const copy=async(value:string)=>{const r=await copyText(value);if(r==='ok')toast.success('已复制');else if(r==='empty')toast.error('没有可复制的内容');else toast.error('复制失败，请手动选中文字复制');};
 const base=location.origin;
 const agentPrompt=`请给「题了个题」出 ${aiForm.count} 道题，范围：${aiForm.topic||'（填写出题范围）'}。\n只返回 JSON：{"items":[{"type":"single","q":"题干","a":["选项A","选项B"],"k":1,"difficulty":2,"tip":"解析"}]}\ntype 可用 single / multiple（correctIndices）/ boolean（a 固定 ["正确","错误"]，k 0 或 1）/ text（answers）/ subjective（modelAnswer、rubric）。\n难度 1-5，题干不要 HTML。`;
 const jsonPrompt=agentPrompt+`\n\n写好后再调用：POST ${base}/api/questions\n请求头：Content-Type: application/json、Authorization: Bearer <管理令牌>\n请求体：{"items":[题目数组]}`;
 const aiSelected=aiItems.filter((q:any)=>q.include).length;

 return <>
  <div className="flex flex-wrap items-center gap-2">
   <Button onClick={()=>edit()}><Plus/>新增题目</Button>
   <div className="relative inline-flex"><Button variant="secondary" onClick={openAi}><Sparkles/>AI 出题</Button><span className="pointer-events-none absolute -right-1.5 -top-1.5 rounded-full bg-primary px-1.5 py-0.5 text-[9px] font-semibold leading-none text-primary-foreground shadow-sm">新</span></div>
   <Button variant="outline" onClick={()=>{setText('');setFileName('');setImporting(true);}}><Upload/>导入</Button>
   <Button variant="outline" onClick={()=>download({version:2,items:questions},'studyontv-bank.json')}><Download/>导出</Button>
  </div>

  <div className="flex flex-wrap items-center gap-2 mt-5">
   <div className="relative flex-1 min-w-[220px]"><Search className="absolute left-3 top-2.5 size-4 text-muted-foreground"/><Input className="pl-9 pr-9" placeholder="搜索题干、答案或解析" value={search} onChange={e=>{setSearch(e.target.value);setPage(0);}}/>{search&&<button aria-label="清空搜索" className="absolute right-2 top-2 rounded p-1 text-muted-foreground hover:bg-secondary" onClick={()=>{setSearch('');setPage(0);}}><X className="size-4"/></button>}</div>
   <select aria-label="题型筛选" className="h-9 rounded-lg border bg-background px-3 text-sm" value={typeFilter} onChange={e=>{setTypeFilter(e.target.value);setPage(0);}}><option value="all">全部题型</option>{Object.entries(labels).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select>
   <select aria-label="难度筛选" className="h-9 rounded-lg border bg-background px-3 text-sm" value={starFilter} onChange={e=>{setStarFilter(e.target.value);setPage(0);}}><option value="all">全部难度</option>{[5,4,3,2,1].map(n=><option key={n} value={String(n)}>{'★'.repeat(n)}</option>)}</select>
   <select aria-label="科目筛选" className="h-9 rounded-lg border bg-background px-3 text-sm" value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setPage(0);}}><option value="all">全部科目</option>{subjects.map(v=><option key={v} value={v}>{v}</option>)}</select>
   <select aria-label="年级筛选" className="h-9 rounded-lg border bg-background px-3 text-sm" value={levelFilter} onChange={e=>{setLevelFilter(e.target.value);setPage(0);}}><option value="all">全部年级</option>{levels.map(v=><option key={v} value={v}>{v}</option>)}</select>
   <Button size="sm" variant={showAll?"secondary":"ghost"} onClick={()=>setShowAll(v=>!v)}>{showAll?"全部（含已暂停）":"仅显示启用"}</Button><select aria-label="排序方式" className="h-9 rounded-lg border bg-background px-3 text-sm" value={sort} onChange={e=>setSort(e.target.value)}><option value="default">默认顺序</option><option value="diff">难度从高到低</option><option value="type">按题型</option></select>
  </div>

  <div className="mt-3 rounded-xl border bg-card overflow-hidden">
   {selected.length>0&&<div className="flex flex-wrap items-center gap-2 border-b bg-secondary/60 px-3 py-2 text-xs"><b>已选 {selected.length} 题</b><Button size="sm" variant="ghost" onClick={()=>setSelected(filtered.map((q:any)=>q.id))}>全选筛选结果（{filtered.length}）</Button><Button size="sm" variant="ghost" onClick={()=>setSelected([])}>清空</Button><Button size="sm" variant="destructive" className="ml-auto" disabled={bulkDelete.isPending} onClick={()=>{if(window.confirm(`确定删除选中的 ${selected.length} 道题？此操作无法撤销`))bulkDelete.mutate(selected);}}><Trash2/>删除所选</Button><Button size="sm" variant="ghost" disabled={bulkPause.isPending} onClick={()=>bulkPause.mutate({ids:selected,enabled:false})}>暂停</Button><Button size="sm" variant="ghost" disabled={bulkPause.isPending} onClick={()=>bulkPause.mutate({ids:selected,enabled:true})}>恢复</Button></div>}

   <div className="hidden md:block overflow-x-auto">
    <table className="w-full text-sm">
     <thead className="sticky top-0 bg-card text-xs text-muted-foreground"><tr className="border-b">
      <th className="w-10 px-3 py-2.5 text-left"><input type="checkbox" aria-label="全选当前页" className="size-4 accent-[var(--primary)]" checked={shown.length>0&&shown.every((q:any)=>selected.includes(q.id))} onChange={e=>{const ids=shown.map((q:any)=>q.id);setSelected(list=>e.target.checked?[...new Set([...list,...ids])]:list.filter(id=>!ids.includes(id)));}}/></th>
      <th className="px-3 py-2.5 text-left font-medium">题目</th><th className="w-20 px-3 py-2.5 text-left font-medium">题型</th><th className="w-20 px-3 py-2.5 text-left font-medium">科目</th><th className="w-20 px-3 py-2.5 text-left font-medium">年级</th><th className="w-24 px-3 py-2.5 text-left font-medium">难度</th><th className="w-[18%] px-3 py-2.5 text-left font-medium">答案</th><th className="w-28 px-3 py-2.5 text-right font-medium">操作</th>
     </tr></thead>
     <tbody>{shown.map((q:any)=><tr key={q.id} className="border-b last:border-0 hover:bg-secondary/40 cursor-pointer" onClick={()=>setDrawer(q.id)}>
      <td className="px-3 py-2.5" onClick={e=>e.stopPropagation()}><input type="checkbox" aria-label="选择题目" className="size-4 accent-[var(--primary)]" checked={selected.includes(q.id)} onChange={()=>toggle(q.id)}/></td>
      <td className="px-3 py-2.5"><div className="flex min-w-0 items-center gap-2"><span className={'size-1.5 shrink-0 rounded-full '+(dot[q.type]||dot.single)}/><MathText text={q.q} className="truncate"/></div></td>
      <td className="px-3 py-2.5"><Badge variant="secondary">{labels[q.type]||'单选'}</Badge></td>
      <td className="px-3 py-2.5 text-muted-foreground">{q.subject||'—'}</td>
      <td className="px-3 py-2.5 text-muted-foreground">{q.level||'—'}</td>
      <td className="px-3 py-2.5"><Stars n={q.difficulty||2}/></td>
      <td className="px-3 py-2.5"><MathText text={answerOf(q)} className="truncate text-muted-foreground"/></td>
      <td className="px-3 py-2.5" onClick={e=>e.stopPropagation()}><div className="flex justify-end gap-1">
       <Button size="icon" variant="ghost" className="size-8" aria-label="编辑题目" onClick={()=>edit(q)}><Pencil/></Button>
       <Button size="icon" variant="ghost" className="size-8" aria-label="复制为新题" onClick={()=>edit(q,true)}><Copy/></Button>
       <Button size="icon" variant="ghost" className="size-8" aria-label="删除题目" onClick={()=>setRemove(q)}><Trash2 className="text-destructive/60"/></Button>
      </div></td>
     </tr>)}</tbody>
    </table>
   </div>

   <div className="md:hidden divide-y">{shown.map((q:any)=><div key={q.id} role="button" tabIndex={0} className="px-3.5 py-3 active:bg-secondary/50" onClick={()=>setDrawer(q.id)} onKeyDown={e=>{if(e.key==='Enter')setDrawer(q.id);}}>
    <div className="flex items-center gap-2 text-xs"><input type="checkbox" aria-label="选择题目" className="size-4 accent-[var(--primary)]" checked={selected.includes(q.id)} onClick={e=>e.stopPropagation()} onChange={()=>toggle(q.id)}/><Badge variant="secondary">{labels[q.type]||'单选'}</Badge><Stars n={q.difficulty||2}/><span className="truncate text-muted-foreground">{q.subject||'—'}{q.level?' · '+q.level:''}</span><span className="ml-auto text-muted-foreground">{(q.tip||'').length?'有解析':''}</span></div>
    <div className="mt-1.5 text-sm font-medium leading-6"><MathText text={q.q}/></div>
    <MathText text={"答案："+answerOf(q)} className="mt-1 truncate text-xs text-muted-foreground"/>
   </div>)}</div>

   {!shown.length&&<div className="p-12 text-center text-sm text-muted-foreground">{query.isLoading?'加载题库…':<>没有符合条件的题目。<button className="ml-1 underline underline-offset-4" onClick={()=>{setSearch('');setTypeFilter('all');setStarFilter('all');}}>清除筛选</button></>}</div>}

   <div className="flex flex-wrap items-center gap-3 border-t px-3 py-2.5 text-xs text-muted-foreground"><span>共 <b className="text-foreground">{filtered.length}</b> 题{pausedCount>0&&!showAll&&<>（另有 {pausedCount} 题已暂停，点「全部（含已暂停）」查看）</>}</span><div className="ml-auto flex items-center gap-1"><Button size="sm" variant="outline" disabled={page===0} onClick={()=>setPage(p=>p-1)}>上一页</Button><span className="min-w-14 text-center">{page+1} / {Math.max(1,Math.ceil(filtered.length/PAGE))}</span><Button size="sm" variant="outline" disabled={(page+1)*PAGE>=filtered.length} onClick={()=>setPage(p=>p+1)}>下一页</Button></div></div>
  </div>

  {current&&<>
   <div className="fixed inset-0 z-40 bg-black/30" onClick={()=>setDrawer(null)}/>
   <aside className="fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l bg-background shadow-2xl sm:w-[480px]" aria-label="题目详情">
    <div className="flex items-center gap-2 border-b px-4 py-3"><h2 className="text-sm font-semibold">题目详情</h2><span className="font-mono text-[11px] text-muted-foreground"># {current.id.slice(0,8)}</span><Button size="icon" variant="ghost" className="ml-auto size-8" aria-label="关闭" onClick={()=>setDrawer(null)}><X/></Button></div>
    <div className="flex-1 overflow-y-auto px-5 py-4">
     <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-muted-foreground"><Badge variant="secondary">{labels[current.type]||'单选'}</Badge><Stars n={current.difficulty||2}/>{current.subject&&<span>· {current.subject}</span>}{current.level&&<span>· {current.level}</span>}</div>
     <MathText text={current.q} className="mb-4 whitespace-pre-wrap text-[16px] font-medium leading-7"/>
     {['single','multiple','boolean'].includes(current.type)&&<div className="space-y-2 mb-4">{(current.type==='boolean'?['正确','错误']:current.a||[]).map((opt:string,i:number)=>{const on=current.type==='multiple'?(current.correctIndices||[]).includes(i):i===current.k;return <div key={i} className={'flex gap-2.5 rounded-lg border p-2.5 text-sm '+(on?'border-primary/40 bg-primary/5':'')}><span className={'flex size-5 shrink-0 items-center justify-center rounded-full border text-[10px] '+(on?'border-primary bg-primary text-primary-foreground':'text-muted-foreground')}>{on?<Check className="size-3"/>:String.fromCharCode(65+i)}</span><span>{opt}</span></div>;})}</div>}
     <div className="mb-4"><h3 className="mb-1 text-xs font-semibold text-muted-foreground">{current.type==='text'?'可接受答案':current.type==='subjective'?'参考答案':'答案'}</h3><div className="whitespace-pre-wrap text-sm leading-6">{current.type==='text'?(current.answers||[]).join('\n'):answerOf(current)}</div></div>
     {current.type==='subjective'&&current.rubric&&<div className="mb-4"><h3 className="mb-1 text-xs font-semibold text-muted-foreground">评分标准</h3><div className="whitespace-pre-wrap text-sm leading-6">{current.rubric}</div></div>}
     {current.tip&&<div className="mb-4"><h3 className="mb-1 text-xs font-semibold text-muted-foreground">解析</h3><MathText text={current.tip} className="whitespace-pre-wrap text-sm leading-6"/></div>}
     <div><h3 className="mb-1 text-xs font-semibold text-muted-foreground">题目编号</h3><div className="font-mono text-xs text-muted-foreground">{current.id}</div></div>
    </div>
    <div className="flex flex-wrap items-center gap-1.5 border-t px-3 py-2.5">
     <Button size="sm" variant="outline" disabled={drawerIndex<=0} onClick={()=>setDrawer(filtered[drawerIndex-1]?.id||null)}><ChevronLeft/>上一题</Button>
     <Button size="sm" variant="outline" disabled={drawerIndex<0||drawerIndex>=filtered.length-1} onClick={()=>setDrawer(filtered[drawerIndex+1]?.id||null)}>下一题<ChevronRight/></Button>
     <div className="ml-auto flex gap-1.5"><Button size="sm" variant="ghost" onClick={()=>edit(current,true)}>复制</Button><Button size="sm" onClick={()=>edit(current)}>编辑</Button><Button size="sm" variant="ghost" className="text-destructive" onClick={()=>setRemove(current)}>删除</Button></div>
    </div>
   </aside>
  </>}

  <Dialog open={!!form} onOpenChange={v=>{if(!v)setForm(null);}}><DialogContent className="editor-dialog"><DialogHeader><DialogTitle>{form?.id?'编辑题目':'新增题目'}</DialogTitle><DialogDescription>支持五种题型，公式可用 $...$ 表示。</DialogDescription></DialogHeader>{form&&<div className="editor-body">
   <Field label="题型"><Choice label="题型" value={form.type} onChange={v=>{field('type',v);if(v!=='multiple')field('correct',[form.correct[0]??0]);}} options={Object.entries(labels)}/></Field>
   <Field label="题干"><Textarea rows={3} placeholder="请输入题目" value={form.q} onChange={e=>field('q',e.target.value)}/></Field>
   {['single','multiple','boolean'].includes(form.type)&&<Field label={form.type==='multiple'?'选项（点击圆圈标记所有正确答案）':'选项（点击圆圈标记正确答案）'}><div className="space-y-2">{(form.type==='boolean'?['正确','错误']:form.opts).map((o:string,i:number)=>{const on=form.correct.includes(i);return <div key={i} className="flex items-center gap-2"><button type="button" aria-label={`标记选项 ${String.fromCharCode(65+i)} 为正确答案`} aria-pressed={on} onClick={()=>field('correct',form.type==='multiple'?(on?form.correct.filter((x:number)=>x!==i):[...form.correct,i]):[i])} className={'size-8 shrink-0 rounded-full border flex items-center justify-center text-xs font-semibold '+(on?'bg-primary border-primary text-primary-foreground':'text-muted-foreground')}>{on?<Check className="size-4"/>:String.fromCharCode(65+i)}</button>{form.type==='boolean'?<span className="text-sm">{o}</span>:<><Input aria-label={`选项 ${String.fromCharCode(65+i)}`} value={o} placeholder={`选项 ${String.fromCharCode(65+i)}`} onChange={e=>field('opts',form.opts.map((x:string,j:number)=>j===i?e.target.value:x))}/>{form.opts.length>2&&<Button type="button" size="icon" variant="ghost" aria-label="删除此选项" onClick={()=>{field('opts',form.opts.filter((_:any,j:number)=>j!==i));field('correct',form.correct.filter((x:number)=>x!==i).map((x:number)=>x>i?x-1:x));}}><X/></Button>}</>}</div>;})}{form.type!=='boolean'&&form.opts.length<8&&<Button type="button" variant="outline" size="sm" onClick={()=>field('opts',[...form.opts,''])}><Plus/>添加选项</Button>}</div></Field>}
   {form.type==='text'&&<Field label="可接受答案，每行一个"><Textarea rows={3} value={form.accepted} onChange={e=>field('accepted',e.target.value)}/></Field>}
   {form.type==='subjective'&&<><Field label="参考答案"><Textarea rows={3} value={form.modelAnswer} onChange={e=>field('modelAnswer',e.target.value)}/></Field><Field label="评分标准"><Textarea rows={2} value={form.rubric} onChange={e=>field('rubric',e.target.value)}/></Field></>}
   <div className="grid grid-cols-2 gap-3"><Field label="科目"><Input list="bank-subjects" value={form.subject} onChange={e=>field('subject',e.target.value)} placeholder="例如 数学"/><datalist id="bank-subjects">{subjects.map(v=><option key={v} value={v}/>)}</datalist></Field><Field label="年级"><Input list="bank-levels" value={form.level} onChange={e=>field('level',e.target.value)} placeholder="例如 五年级"/><datalist id="bank-levels">{levels.map(v=><option key={v} value={v}/>)}</datalist></Field></div>
   <Field label="星级难度"><Choice label="星级难度" value={String(form.difficulty)} onChange={v=>field('difficulty',Number(v))} options={[1,2,3,4,5].map(s=>[String(s),'★'.repeat(s)])}/></Field>
   <Field label="解析"><Textarea rows={2} value={form.tip} onChange={e=>field('tip',e.target.value)}/></Field>
  </div>}<DialogFooter className="editor-footer"><Button variant="outline" onClick={()=>setForm(null)}>取消</Button><Button disabled={save.isPending} onClick={submit}>{save.isPending?'保存中…':'保存题目'}</Button></DialogFooter></DialogContent></Dialog>

  <Dialog open={importing} onOpenChange={setImporting}><DialogContent className="editor-dialog"><DialogHeader><DialogTitle>导入题库</DialogTitle><DialogDescription>选择 JSON 文件，或粘贴其他家长分享的内容。</DialogDescription></DialogHeader><div className="editor-body"><div className="grid grid-cols-2 gap-3 mb-3"><div><label className="text-xs font-medium">默认科目（可选）</label><Input className="mt-1" list="bank-subjects" value={importSubject} onChange={e=>setImportSubject(e.target.value)} placeholder="例如 数学"/></div><div><label className="text-xs font-medium">默认年级（可选）</label><Input className="mt-1" list="bank-levels" value={importLevel} onChange={e=>setImportLevel(e.target.value)} placeholder="例如 五年级"/></div></div><p className="text-xs text-muted-foreground mb-2">只给没有 subject / level 的题目补上；JSON 里已有的分类会保留。</p><Textarea aria-label="题库 JSON" rows={10} value={text} onChange={e=>setText(e.target.value)}/><input hidden type="file" ref={file} accept=".json,application/json" onChange={pick}/><div className="flex gap-3 items-center mt-4"><Button variant="outline" onClick={()=>{if(file.current){file.current.value='';file.current.click();}}}><Upload/>选择 JSON 文件</Button><span className="text-xs text-muted-foreground truncate">{fileName}</span></div></div><DialogFooter className="editor-footer"><Button variant="outline" onClick={()=>setImporting(false)}>取消</Button><Button onClick={importNow} disabled={importingMutation.isPending}>导入题库</Button></DialogFooter></DialogContent></Dialog>

  <Dialog open={!!remove} onOpenChange={v=>{if(!v)setRemove(null);}}><DialogContent><DialogHeader><DialogTitle>删除这道题？</DialogTitle><DialogDescription>{remove?.q}</DialogDescription></DialogHeader><DialogFooter><Button variant="outline" onClick={()=>setRemove(null)}>取消</Button><Button variant="destructive" disabled={deleting.isPending} onClick={()=>deleting.mutate(remove.id)}>删除</Button></DialogFooter></DialogContent></Dialog>

  <Dialog open={aiOpen} onOpenChange={v=>{if(!v&&!aiLoading)setAiOpen(false);}}><DialogContent className="flex max-h-[90svh] w-full max-w-3xl flex-col gap-0 overflow-hidden p-0 sm:max-w-3xl max-sm:top-0 max-sm:left-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0">
   <DialogHeader className="border-b px-5 py-4 text-left"><DialogTitle className="flex items-center gap-2"><Sparkles className="size-4 text-primary"/>AI 出题</DialogTitle></DialogHeader>
   <div className="flex gap-1 border-b px-3 pt-2">
    <button className={'rounded-t-lg border-b-2 px-3 py-2 text-sm '+(aiTab==='agent'?'border-primary font-semibold':'border-transparent text-muted-foreground hover:bg-secondary')} onClick={()=>setAiTab('agent')}>外部 Agent 出题</button>
    <button className={'rounded-t-lg border-b-2 px-3 py-2 text-sm '+(aiTab==='api'?'border-primary font-semibold':'border-transparent text-muted-foreground hover:bg-secondary')} onClick={()=>setAiTab('api')}>使用模型 API</button>
   </div>
   <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
    {aiTab==='api'?<>
     <div className="mb-4 flex items-center gap-2 rounded-lg border px-3 py-2 text-xs">
      <span className={'size-1.5 shrink-0 rounded-full '+(configured?'bg-green-500':'bg-muted-foreground/40')}/>
      <span className="truncate">{configured?`${aiProvider} · ${grading.data?.model}`:'尚未配置模型 API'}</span>
      <Button size="sm" variant="ghost" className="ml-auto shrink-0" onClick={()=>{setAiOpen(false);location.hash='/settings/ai';}}>去设置</Button>
     </div>
     {aiItems.length===0?<>
      <div className="grid gap-3 sm:grid-cols-2">
       <div className="sm:col-span-2"><Label htmlFor="ai-topic">出题范围</Label><Input id="ai-topic" className="mt-1.5" placeholder="例如：五年级数学 分数加减法" value={aiForm.topic} onChange={e=>setAiForm({...aiForm,topic:e.target.value})}/><p className="mt-1 text-xs text-muted-foreground">越具体越好，可以写教材版本、单元或薄弱点。</p></div>
       <div className="sm:col-span-2"><Label>题型</Label><div className="mt-1.5 flex flex-wrap gap-2">{Object.entries(labels).map(([k,v])=><label key={k} className={'flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-sm '+(aiForm.types[k]?'bg-primary/5 border-primary/40':'text-muted-foreground')}><input type="checkbox" className="size-4 accent-[var(--primary)]" checked={!!aiForm.types[k]} onChange={e=>setAiForm({...aiForm,types:{...aiForm.types,[k]:e.target.checked}})}/>{v}</label>)}</div></div>
       <div><Label>数量</Label><select className="mt-1.5 h-9 w-full rounded-lg border bg-background px-3 text-sm" value={aiForm.count} onChange={e=>setAiForm({...aiForm,count:Number(e.target.value)})}>{[5,10,15,20].map(n=><option key={n} value={n}>{n} 题</option>)}</select></div>
       <div><Label>难度</Label><select className="mt-1.5 h-9 w-full rounded-lg border bg-background px-3 text-sm" value={aiForm.difficulty} onChange={e=>setAiForm({...aiForm,difficulty:e.target.value})}><option value="mixed">混合（由易到难）</option>{[1,2,3,4,5].map(n=><option key={n} value={n}>{'全部 '+ '★'.repeat(n)}</option>)}</select></div>
       <div className="sm:col-span-2"><Label htmlFor="ai-extra">额外要求（可选）</Label><Textarea id="ai-extra" className="mt-1.5" rows={2} placeholder="例如：结合生活场景；不要出现负数；每题附一句解析。" value={aiForm.extra} onChange={e=>setAiForm({...aiForm,extra:e.target.value})}/></div>
       <div className="sm:col-span-2 flex flex-wrap gap-3"><label className="flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-[var(--primary)]" checked={aiForm.withTip} onChange={e=>setAiForm({...aiForm,withTip:e.target.checked})}/>自动生成解析</label><label className="flex items-center gap-2 text-sm"><input type="checkbox" className="size-4 accent-[var(--primary)]" checked={aiForm.skipDuplicates} onChange={e=>setAiForm({...aiForm,skipDuplicates:e.target.checked})}/>跳过题库里已有的题干</label></div>
      </div>
      <div className="mt-4 flex items-start gap-2 rounded-lg bg-secondary/60 p-3 text-xs leading-6 text-muted-foreground"><RefreshCw className="mt-1 size-3.5 shrink-0"/>生成的题目会先预览；一次最多 20 题。API Key 只保存在设备上。</div>
      {aiError&&<p role="alert" className="mt-3 text-sm text-destructive">{aiError}</p>}
     </>:<>
      <div className="mb-3 flex flex-wrap items-center gap-3 text-sm text-muted-foreground">已生成 {aiItems.length} 题，请勾选要保存的题目。<Button size="sm" variant="ghost" onClick={()=>setAiItems([])}>重新生成</Button></div>
      <div className="space-y-2.5">{aiItems.map((q:any,i:number)=><label key={i} className="flex gap-2.5 rounded-xl border p-3">
       <input type="checkbox" className="mt-1 size-4 accent-[var(--primary)]" checked={q.include} onChange={e=>setAiItems(list=>list.map((x,j)=>j===i?{...x,include:e.target.checked}:x))}/>
       <div className="min-w-0 flex-1"><div className="mb-1 flex items-center gap-2"><Badge variant="secondary">{labels[q.type]||'单选'}</Badge><Stars n={q.difficulty||2}/></div><MathText text={q.q} className="whitespace-pre-wrap text-sm font-medium leading-6"/><MathText text={"答案："+answerOf(q)} className="mt-1 truncate text-xs text-muted-foreground"/></div>
      </label>)}</div>
     </>}
    </>:<div className="space-y-3 text-sm">
     <div className="rounded-xl border p-4"><h3 className="mb-1 flex items-center gap-2 font-semibold"><ClipboardCopy className="size-4 text-primary"/>方式一：让 Agent 直接写入题库（推荐）</h3><p className="mb-2 text-xs leading-6 text-muted-foreground">把出题指令发给能访问局域网、会调用工具的 Agent，它完成出题后直接写入题库，回到这里刷新即可。</p><div className="mb-2 flex flex-wrap gap-1.5">{['WorkBuddy','Codex','千问办公','豆包','Kimi','智谱清言','文心一言','腾讯元宝'].map(name=><span key={name} className="rounded-md border bg-secondary/40 px-2 py-0.5 text-[11px] text-muted-foreground">{name}</span>)}</div><p className="mb-3 rounded-lg bg-secondary/50 p-2.5 text-[11px] leading-5 text-muted-foreground">以上指桌面客户端或命令行工具（如 Codex CLI、千问办公桌面版），<b className="text-foreground">不是网页对话版</b>。网页版通常无法访问本机局域网或写入题库。</p><ol className="mb-3 list-decimal space-y-1 pl-4 text-xs leading-6 text-muted-foreground"><li>确认 Agent 和被控设备在<b className="text-foreground">同一局域网</b></li><li>复制<b className="text-foreground">出题指令</b>，连同管理令牌一起发给 Agent</li><li>Agent 写入后回到这里刷新题库</li></ol><div className="flex flex-wrap gap-2"><Button size="sm" onClick={()=>copy(jsonPrompt)}><Copy/>复制出题指令</Button><Button size="sm" variant="outline" onClick={()=>copy('https://github.com/chadyi/StudyOnTV/blob/main/docs/ai-api.md')}><ExternalLink/>接口说明</Button></div></div>
     <div className="rounded-xl border p-4"><h3 className="mb-1 flex items-center gap-2 font-semibold"><FileJson className="size-4 text-primary"/>方式二：只返回 JSON，再由我导入</h3><p className="mb-3 text-xs leading-6 text-muted-foreground">不把管理令牌交给 Agent。让它按格式输出 JSON，再粘贴或选择文件导入。</p><div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" onClick={()=>copy(agentPrompt)}><Copy/>复制 JSON 指令</Button><Button size="sm" variant="outline" onClick={()=>{setAiOpen(false);setText('');setFileName('');setImporting(true);}}>打开导入</Button></div></div>
     <div className="rounded-lg bg-secondary/60 p-3 text-xs leading-6 text-muted-foreground">管理令牌等同于家长权限，只发给可信的 Agent，不要发到公开群或截图里。修改管理密码后旧令牌立即失效。</div>
    </div>}
   </div>
   <DialogFooter className="border-t px-5 py-3">
    {aiTab==='api'&&aiItems.length===0&&<><span className="mr-auto hidden text-xs text-muted-foreground sm:block">{aiLoading?'正在调用模型…':'生成结果会先预览'}</span><Button variant="outline" onClick={()=>setAiOpen(false)} disabled={aiLoading}>取消</Button><Button onClick={generate} disabled={aiLoading}><Sparkles/>{aiLoading?'生成中…':'生成题目'}</Button></>}
    {aiTab==='api'&&aiItems.length>0&&<><Button variant="outline" className="mr-auto" onClick={()=>setAiItems([])} disabled={saveGenerated.isPending}>重新生成</Button><Button disabled={!aiSelected||saveGenerated.isPending} onClick={()=>saveGenerated.mutate(aiItems.filter((q:any)=>q.include).map(({include,...q}:any)=>q))}>{saveGenerated.isPending?'保存中…':`保存 ${aiSelected} 题到题库`}</Button></>}
    {aiTab==='agent'&&<Button variant="outline" onClick={()=>setAiOpen(false)}>关闭</Button>}
   </DialogFooter>
  </DialogContent></Dialog>
 </>;
}
export function Field({label,children}:{label:string,children:any}){return <div className="space-y-2 mb-5"><Label>{label}</Label>{children}</div>}
