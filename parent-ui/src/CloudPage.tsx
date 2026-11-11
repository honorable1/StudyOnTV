import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Cloud,Link2,RefreshCw,Unlink,CircleCheck,CircleAlert} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Badge} from '@/components/ui/badge';
import {Switch} from '@/components/ui/switch';
import {api} from './api';
import {Panel,Row} from './RulesPage';
import {Field} from './QuestionBank';

const DEFAULT_BASE='https://www.wangziran0828.cn/api/v1';
const clock=(ms:number)=>ms?new Date(ms).toLocaleString('zh-CN',{hour12:false}):'还没同步过';
const minutes=(seconds:number)=>seconds>0?Math.floor(seconds/60)+' 分钟':'0 分钟';

/**
 * 阅读联动（云端）——接「阅读时长记录」小程序。
 *
 * 默认关闭：关着的时候设备完全独立，不产生任何外部请求，规则、题库、面板都不受影响。
 * 打开并填配对码之后，电视每 60 秒拉一次家长兑换出来的时长，入账进「已购额度」池。
 */
export default function CloudPage({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const cloud=useQuery({queryKey:['cloud',sessionId],queryFn:()=>api('/api/cloud'),refetchInterval:30000});
 const [base,setBase]=useState(DEFAULT_BASE),[code,setCode]=useState(''),[enabled,setEnabled]=useState(false),[dirtyBase,setDirtyBase]=useState(false);

 useEffect(()=>{if(cloud.data&&!dirtyBase){setBase(cloud.data.base||DEFAULT_BASE);setEnabled(!!cloud.data.enabled);}},[cloud.data?.base,cloud.data?.enabled,cloud.data?.configured]);

 const refresh=()=>client.setQueryData(['cloud',sessionId],undefined);
 const save=useMutation({mutationFn:(body:any)=>api('/api/cloud','PUT',body),onSuccess:(r:any)=>{setDirtyBase(false);client.setQueryData(['cloud',sessionId],r);toast.success(enabled?'已开启阅读联动':'已关闭阅读联动');},onError:(e:any)=>{setEnabled(!!cloud.data?.enabled);toast.error(e.message)}});
 const bind=useMutation({mutationFn:()=>api('/api/cloud/bind','POST',{code,base}),onSuccess:(r:any)=>{setCode('');client.setQueryData(['cloud',sessionId],r);toast.success('配对成功，已开始接收阅读兑换');},onError:(e:any)=>toast.error(e.message)});
 const unbind=useMutation({mutationFn:()=>api('/api/cloud','DELETE'),onSuccess:(r:any)=>{client.setQueryData(['cloud',sessionId],r);toast.success('已解除绑定，电视恢复独立使用');},onError:(e:any)=>toast.error(e.message)});
 const pull=useMutation({mutationFn:()=>api('/api/cloud/pull','POST',{}),onSuccess:(r:any)=>{client.setQueryData(['cloud',sessionId],r.cloud);const n=r.pulled?.applied||0;toast.success(n?`拉到 ${n} 笔兑换`:'没有新的兑换');},onError:(e:any)=>toast.error(e.message)});

 const data=cloud.data||{};
 const configured=!!data.configured;
 const lastError=data.lastError||'';

 return <div className="space-y-6">
  <Panel title="阅读联动" icon={Cloud} description="把「阅读时长记录」小程序里兑换出来的电视时长送到本机。不开启时，设备完全独立工作。" hint={configured?<Badge variant="secondary">已配对</Badge>:null}>
   <Row title="启用阅读联动" note="关闭后不再联网，已入账的时长仍然有效">
    <Switch checked={enabled} onCheckedChange={v=>{setEnabled(v);save.mutate({enabled:v});}}/>
   </Row>
  </Panel>

  <Panel title="配对" icon={Link2} description="在小程序「我的 → 电视联动」里生成 6 位配对码，5 分钟内有效">
   <div className="px-4 py-4 space-y-4">
    <Field label="服务地址">
     <Input value={base} onChange={e=>{setBase(e.target.value);setDirtyBase(true);}} placeholder={DEFAULT_BASE} spellCheck={false}/>
    </Field>
    {dirtyBase&&<Button size="sm" variant="outline" onClick={()=>save.mutate({base})} disabled={save.isPending}>保存地址</Button>}
    <Field label="配对码">
     <div className="flex gap-2">
      <Input value={code} inputMode="numeric" maxLength={6} onChange={e=>setCode(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="六位数字" className="font-mono tracking-[0.3em]"/>
      <Button onClick={()=>bind.mutate()} disabled={bind.isPending||code.length!==6}><Link2/>{configured?'重新配对':'配对'}</Button>
     </div>
    </Field>
    <p className="text-xs text-muted-foreground">配对只交换一个设备令牌，不涉及微信账号；令牌保存在本机，随时可以解除。</p>
   </div>
  </Panel>

  <Panel title="状态" icon={configured?CircleCheck:CircleAlert} description={configured?'电视每 60 秒自动拉取一次':'还没有配对'}>
   <div className="px-4 py-4 space-y-3 text-sm">
    <div className="flex items-center justify-between"><span className="text-muted-foreground">孩子</span><span className="font-medium">{data.childName||'—'}</span></div>
    <div className="flex items-center justify-between"><span className="text-muted-foreground">已购额度（可跨天）</span><span className="font-medium">{minutes(data.extSeconds||0)}</span></div>
    <div className="flex items-center justify-between"><span className="text-muted-foreground">已拉到第几笔</span><span className="font-mono text-sm">#{data.seq||0}</span></div>
    <div className="flex items-center justify-between"><span className="text-muted-foreground">上次同步</span><span>{clock(data.lastSyncAt||0)}</span></div>
    {lastError&&<div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">{lastError}</div>}
    <div className="flex flex-wrap gap-2 pt-1">
     <Button size="sm" variant="outline" onClick={()=>pull.mutate()} disabled={!configured||pull.isPending}><RefreshCw/>立即同步</Button>
     <Button size="sm" variant="outline" onClick={()=>cloud.refetch()} disabled={cloud.isFetching}>刷新状态</Button>
     {configured&&<Button size="sm" variant="ghost" className="text-destructive" onClick={()=>{if(confirm('解除后电视不再接收兑换，已入账的时长保留。继续？'))unbind.mutate();}}><Unlink/>解除绑定</Button>}
    </div>
   </div>
  </Panel>
 </div>;
}
