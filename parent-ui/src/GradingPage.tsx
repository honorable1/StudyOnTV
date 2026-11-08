import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {ClipboardCheck,RefreshCw,Bot,PlugZap} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {Switch} from '@/components/ui/switch';
import {Badge} from '@/components/ui/badge';
import {api} from './api';
import {Choice,Panel,Row} from './RulesPage';
import {answerOf,Field,MathText} from './QuestionBank';

type Provider={id:string,name:string,baseUrl:string,model:string,visionModel:string,note:string,keyUrl?:string};
export default function GradingPage({sessionId}:{sessionId:number}){
 const settings=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const pending=useQuery({queryKey:['reviews',sessionId],queryFn:()=>api('/api/reviews'),refetchInterval:5000});
 const client=useQueryClient();
 const [config,setConfig]=useState<any>({mode:'manual',provider:'deepseek',endpoint:'https://api.deepseek.com/v1',model:'deepseek-chat',vision:false,passScore:60,apiKey:'',hasKey:false});
 const [dirty,setDirty]=useState(false);
 const [models,setModels]=useState<string[]>([]);
 const [result,setResult]=useState<{ok:boolean,message:string}|null>(null);
 const providers:Provider[]=settings.data?.providers||[];
 const provider=providers.find(p=>p.id===config.provider);
 useEffect(()=>{if(settings.data&&!dirty)setConfig({...settings.data,apiKey:''});},[settings.data]);
 function change(key:string,value:any){setDirty(true);setResult(null);setConfig((c:any)=>({...c,[key]:value}));}
 function pick(id:string){const p=providers.find(x=>x.id===id);setDirty(true);setResult(null);setModels([]);setConfig((c:any)=>({...c,provider:id,...(p&&p.id!=='custom'?{endpoint:p.baseUrl,model:c.vision&&p.visionModel?p.visionModel:p.model}:{})}));}
 const payload=()=>({endpoint:config.endpoint,model:config.model,vision:config.vision,apiKey:config.apiKey});
 const save=useMutation({mutationFn:(value:any)=>api('/api/grading','PUT',value),onSuccess:r=>{setConfig({...r,apiKey:''});setDirty(false);client.setQueryData(['grading',sessionId],r);toast.success('批改设置已保存');},onError:(e:any)=>toast.error(e.message)});
 const test=useMutation({mutationFn:()=>api('/api/grading/test','POST',payload()),onSuccess:r=>setResult({ok:r.ok,message:r.message+(r.ms?` · ${(r.ms/1000).toFixed(1)} 秒`:'')}),onError:(e:any)=>setResult({ok:false,message:e.message})});
 const list=useMutation({mutationFn:()=>api('/api/grading/models','POST',payload()),onSuccess:r=>{if(!r.ok)return toast.error(r.message);setModels(r.items);toast.success(`获取到 ${r.items.length} 个模型`);},onError:(e:any)=>toast.error(e.message)});
 function submit(){if(!Number.isFinite(Number(config.passScore))||Number(config.passScore)<1||Number(config.passScore)>100)return toast.error('及格分需要为 1–100');if(config.mode==='ai'){try{const url=new URL(config.endpoint);if(!/^https?:$/.test(url.protocol))throw 0;}catch{return toast.error('请填写完整的接口地址，例如 https://api.deepseek.com/v1');}if(!config.model.trim())return toast.error('请填写模型名称');if(!config.hasKey&&!config.apiKey.trim()&&config.provider!=='ollama')return toast.error('请填写 API Key');}save.mutate(config);}
 return <div className="sections"><Panel title="批改设置" icon={Bot}>
  <Row title="批改方式" note="默认家长手动批改；AI 自动批改需要先在设置里配置模型 API"><Choice label="批改方式" value={config.mode} onChange={v=>change('mode',v)} options={[["manual","家长手动批改"],["ai","AI 自动批改"]]}/></Row>
  <Row title="及格分"><Input className="w-24 text-center" aria-label="及格分" type="number" min={1} max={100} value={config.passScore} onChange={e=>change('passScore',e.target.value)}/></Row>
  {config.mode==='ai'&&<div className="mt-4 rounded-xl border p-4 text-sm"><div className="flex flex-wrap items-center gap-3"><Badge variant="secondary">{settings.data?.hasKey?'AI 服务已配置':'AI 服务未配置'}</Badge><span className="text-muted-foreground">模型：{settings.data?.model||'未选择'}</span><Button variant="outline" size="sm" className="ml-auto" onClick={()=>{location.hash='/settings/ai';}}>去设置</Button></div><p className="mt-3 text-xs leading-6 text-muted-foreground">AI 失败或超时时，孩子可以在等待页选择「先跳过这题」，家长也可以手动批改。</p></div>}
  <div className="flex justify-end mt-5"><Button disabled={save.isPending} onClick={submit}>保存批改设置</Button></div>
</Panel><Panel title="待批作答" description="家长可以直接评分，也可以让 Agent 通过已验证的 API 批改" icon={ClipboardCheck}><div className="flex items-center justify-between mb-4"><Badge variant="secondary">{pending.data?.items?.length||0} 份待批</Badge><Button variant="ghost" size="sm" onClick={()=>pending.refetch()}><RefreshCw/>刷新</Button></div>{pending.isLoading?<p className="empty-state">正在读取作答…</p>:!pending.data?.items?.length?<p className="empty-state">暂时没有待批作答</p>:pending.data.items.map((r:any)=><Review key={r.id} review={r} sessionId={sessionId} ai={config.mode==='ai'}/>)}</Panel></div>
}
function Review({review:r,sessionId,ai}:{review:any,sessionId:number,ai:boolean}){
 const [score,setScore]=useState('60'),[feedback,setFeedback]=useState('');const client=useQueryClient();
 const submit=useMutation({mutationFn:(body:any)=>api('/api/reviews/'+r.id,'POST',body),onSuccess:()=>{client.invalidateQueries({queryKey:['reviews',sessionId]});client.invalidateQueries({queryKey:['state',sessionId]});toast.success('评分已提交');},onError:(e:any)=>toast.error(e.message)});
 const retry=useMutation({mutationFn:()=>api('/api/reviews/'+r.id+'/retry','POST',{}),onSuccess:()=>toast.success('已提交 AI 重试'),onError:(e:any)=>toast.error(e.message)});
 return <article className="review-card"><MathText text={r.question.q} className="font-medium text-base leading-7"/><p className="text-xs text-muted-foreground mt-5 mb-2">孩子的答案</p><MathText className="student-answer" text={r.input.text||'手写作答'}/>{r.input.drawing&&<img className="review-image" alt="孩子的手写答案" src={r.input.drawing}/>}<details className="mt-4 text-sm"><summary className="cursor-pointer text-muted-foreground">参考答案与评分标准</summary><MathText className="mt-3 leading-7" text={r.question.modelAnswer||answerOf(r.question)}/><MathText className="leading-7 text-muted-foreground" text={r.question.rubric||''}/></details>{r.feedback&&<p className="text-xs text-muted-foreground my-4">{r.feedback}</p>}<Row title="分数"><Input aria-label="分数" className="w-24 text-center" type="number" min={0} max={100} value={score} onChange={e=>setScore(e.target.value)}/></Row><Field label="批改意见"><Textarea value={feedback} onChange={e=>setFeedback(e.target.value)} rows={2}/></Field><div className="flex gap-2 justify-end">{ai&&<Button variant="outline" disabled={retry.isPending} onClick={()=>retry.mutate()}>AI 重试</Button>}<Button disabled={submit.isPending} onClick={()=>{const value=Number(score);if(score===''||!Number.isFinite(value)||value<0||value>100)return toast.error('请输入 0–100 分');submit.mutate({score:value,feedback});}}>提交评分</Button></div></article>
}
