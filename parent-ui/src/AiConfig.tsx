import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Bot,Check,PlugZap,RefreshCw,X} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Label} from '@/components/ui/label';
import {api} from './api';

type Provider={id:string,name:string,baseUrl:string,model:string,visionModel:string,note:string,keyUrl?:string};

export default function AiConfig({sessionId,onClose}:{sessionId:number;onClose:()=>void}){
 const client=useQueryClient();
 const settings=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const [form,setForm]=useState({provider:'deepseek',endpoint:'',model:'',apiKey:'',hasKey:false});
 const [result,setResult]=useState<{ok:boolean,message:string}|null>(null);
 const providers:Provider[]=settings.data?.providers||[];
 const provider=providers.find(p=>p.id===form.provider);

 useEffect(()=>{if(settings.data)setForm(f=>({...f,provider:settings.data.provider||'deepseek',endpoint:settings.data.endpoint||'',model:settings.data.model||'',hasKey:!!settings.data.hasKey}));},[settings.data]);

 function pick(id:string){const p=providers.find(x=>x.id===id);setResult(null);setForm(f=>({...f,provider:id,...(p&&p.id!=='custom'?{endpoint:p.baseUrl,model:p.model}:{})}));}
 const test=useMutation({mutationFn:()=>api('/api/grading/test','POST',{endpoint:form.endpoint,model:form.model,apiKey:form.apiKey,vision:false}),onSuccess:(r:any)=>setResult({ok:r.ok,message:r.message+(r.ms?` · ${(r.ms/1000).toFixed(1)} 秒`:'')}),onError:(e:any)=>setResult({ok:false,message:e.message})});
 const save=useMutation({mutationFn:()=>api('/api/grading','PUT',{...settings.data,mode:'ai',provider:form.provider,endpoint:form.endpoint.trim(),model:form.model.trim(),apiKey:form.apiKey,vision:!!settings.data?.vision,passScore:settings.data?.passScore||60}),onSuccess:()=>{client.invalidateQueries({queryKey:['grading',sessionId]});toast.success('模型 API 已保存');onClose();},onError:(e:any)=>toast.error(e.message)});

 function submit(){
  try{const url=new URL(form.endpoint);if(!/^https?:$/.test(url.protocol))throw 0;}catch{return toast.error('请填写完整的接口地址，例如 https://api.deepseek.com/v1');}
  if(!form.model.trim())return toast.error('请填写模型名称');
  if(!form.hasKey&&!form.apiKey.trim()&&form.provider!=='ollama')return toast.error('请填写 API Key');
  save.mutate();
 }

 return <div className="fixed inset-0 z-[60] flex flex-col bg-background">
  <header className="flex items-center gap-2 border-b px-4 py-3">
   <Bot className="size-4 text-primary"/><b className="text-sm">模型 API</b>
   <Button size="icon" variant="ghost" className="ml-auto size-8" aria-label="关闭" onClick={onClose}><X/></Button>
  </header>
  <div className="flex-1 overflow-y-auto">
   <div className="mx-auto w-full max-w-lg px-4 py-5">
    <p className="mb-5 text-sm leading-6 text-muted-foreground">AI 出题和批改共用这个服务。选好服务商，填 API Key 即可；本地 Ollama 可以填局域网地址。</p>
    <div className="space-y-4">
     <div><Label>服务商</Label><select aria-label="服务商" className="mt-1.5 h-10 w-full rounded-lg border bg-background px-3 text-sm" value={form.provider} onChange={e=>pick(e.target.value)}>{(providers.length?providers:[{id:'custom',name:'自定义'} as Provider]).map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select>{provider?.note&&<p className="mt-1 text-xs text-muted-foreground">{provider.note}</p>}</div>
     <div><Label htmlFor="ai-endpoint">接口地址</Label><Input id="ai-endpoint" className="mt-1.5" value={form.endpoint} onChange={e=>setForm({...form,endpoint:e.target.value})} placeholder="https://api.deepseek.com/v1"/></div>
     <div><Label htmlFor="ai-key">API Key</Label><div className="relative mt-1.5"><Input id="ai-key" type="password" autoComplete="off" value={form.apiKey} onChange={e=>setForm({...form,apiKey:e.target.value})} placeholder={form.hasKey?'已保存，留空保持原密钥':'请输入 API Key'}/>{provider?.keyUrl&&<a href={provider.keyUrl} target="_blank" rel="noreferrer" className="absolute right-2 top-2 text-xs text-primary underline underline-offset-4">获取</a>}</div></div>
     <div><Label htmlFor="ai-model">模型名称</Label><Input id="ai-model" className="mt-1.5" value={form.model} onChange={e=>setForm({...form,model:e.target.value})} placeholder={provider?.model||'例如 deepseek-chat'}/></div>
    </div>
    <div className="mt-5 flex flex-wrap items-center gap-3">
     <Button type="button" variant="outline" disabled={test.isPending} onClick={()=>test.mutate()}><PlugZap/>{test.isPending?'测试中…':'测试连接'}</Button>
     {result&&<span role="status" className={'text-sm '+(result.ok?'text-green-600 dark:text-green-400':'text-destructive')}>{result.ok?'✓ ':'✗ '}{result.message}</span>}
    </div>
    <p className="mt-5 text-xs leading-6 text-muted-foreground">测试会发一道 1+1 的示例题，不会保存设置。启用后，题目与孩子答案会发送到您配置的服务。</p>
   </div>
  </div>
  <footer className="border-t px-4 py-3">
   <div className="mx-auto flex w-full max-w-lg gap-2"><Button variant="outline" className="flex-1" onClick={onClose}>取消</Button><Button className="flex-1" disabled={save.isPending} onClick={submit}><Check/>{save.isPending?'保存中…':'保存并使用'}</Button></div>
  </footer>
 </div>;
}
