import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {MonitorSmartphone,RefreshCw,CloudDownload,KeyRound,RadioTower,Copy} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {Badge} from '@/components/ui/badge';
import {Switch} from '@/components/ui/switch';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription,DialogFooter} from '@/components/ui/dialog';
import {api} from './api';
import {copyText} from './lib/clipboard';
import {Panel,Row} from './RulesPage';
import {Field} from './QuestionBank';

const VIA:Record<string,string>={boot:'开机广播',accessibility:'无障碍服务',job:'系统定时任务',update:'应用更新',app:'打开应用'};
function autostartNote(a:any){if(a.pending)return '刚安装或更新，重启一次设备后可以判断开机自启是否生效';if(!a.known)return '这台设备不提供开机次数，无法自动判断；建议开启无障碍服务';
 const how=a.via?`本次开机后 ${a.startedAfterSeconds>=0?Math.round(a.startedAfterSeconds/60)+' 分钟由':'由'}${VIA[a.via]||a.via}启动`:'本次开机后尚未记录';
 return a.ok?how+(a.accessibility?' · 无障碍服务已开启，可在开机后自动恢复':''):how+'。系统可能拦截了开机自启：请在电视 / 手机的「自启动管理」「应用启动管理」里允许本应用，并开启无障碍服务（最可靠）';}
const time=(ms:number)=>ms?new Date(ms).toLocaleString('zh-CN',{hour12:false}):'尚未检查';
function monitorNote(m:any){if(!m?.service)return '管控服务没有运行：重新打开应用，或在系统设置里允许自启动 / 后台运行';const missing:string[]=[];if(!m.overlay)missing.push('悬浮窗');if(!m.usage&&!m.accessibility)missing.push('使用情况访问或无障碍');return missing.length?`缺少${missing.join('、')}，到时间可能无法自动弹出学习页`:'权限齐全，计时和拦截可以正常工作';}

export default function DevicePanel({sessionId,onPasswordChanged,only}:{sessionId:number,onPasswordChanged:()=>void,only:'device'|'update'|'security'|'integration'}){
 const client=useQueryClient();
 const device=useQuery({queryKey:['device',sessionId],queryFn:()=>api('/api/device')});
 const [polling,setPolling]=useState(false);
 const update=useQuery({queryKey:['update',sessionId],queryFn:()=>api('/api/update'),refetchInterval:polling?2000:false});
 const [name,setName]=useState(''),[mirrors,setMirrors]=useState(''),[custom,setCustom]=useState(''),[dirty,setDirty]=useState(false);
 useEffect(()=>{if(device.data)setName(device.data.name);},[device.data]);
 useEffect(()=>{if(update.data&&!dirty){setMirrors((update.data.mirrors||[]).join('\n'));setCustom(update.data.api||'');}},[update.data]);
 const startedAt=update.data?.logAt||update.data?.checkedAt;
 useEffect(()=>{if(polling&&update.data&&!update.data.checking){setPolling(false);if(update.data.hasUpdate&&update.data.latest)toast.message(`发现新版本 v${update.data.latest}，请升级`);else if(update.data.log&&update.data.log.length&&update.data.log.every((x:any)=>!x.ok))toast.error('检查更新失败，请稍后再试');else toast.success('当前已是最新版本');}},[polling,update.data?.checking,update.data?.hasUpdate]);
 const bugly=useMutation({mutationFn:(enabled:boolean)=>api('/api/device/bugly','PUT',{enabled}),onSuccess:()=>{client.invalidateQueries({queryKey:['device',sessionId]});toast.success(device.data?.buglyConsent?'崩溃报告已关闭，下次启动生效':'崩溃报告已开启');},onError:(e:any)=>toast.error(e.message)});
 const disableGuard=useMutation({mutationFn:()=>api('/api/device/uninstall-guard','DELETE'),onSuccess:()=>{client.invalidateQueries({queryKey:['device',sessionId]});toast.success('防卸载已关闭');},onError:(e:any)=>toast.error(e.message)});
 const rename=useMutation({mutationFn:()=>api('/api/device','PUT',{name}),onSuccess:()=>{toast.success('设备名称已保存');client.invalidateQueries({queryKey:['device',sessionId]});},onError:(e:any)=>toast.error(e.message)});
 const check=useMutation({mutationFn:()=>api('/api/update/check','POST',{}),onSuccess:r=>{client.setQueryData(['update',sessionId],r);setPolling(true);toast.message('正在检查更新…');window.setTimeout(()=>setPolling(false),70000);},onError:(e:any)=>toast.error(e.message)});
 const save=useMutation({mutationFn:(reset:boolean)=>api('/api/update','PUT',{api:custom,mirrors:mirrors.split('\n'),resetMirrors:reset}),onSuccess:r=>{setDirty(false);client.setQueryData(['update',sessionId],r);setMirrors(r.mirrors.join('\n'));toast.success('更新来源已保存');},onError:(e:any)=>toast.error(e.message)});
 const [pw,setPw]=useState({current:'',next:'',confirm:''});
 const integration=useQuery({queryKey:['integration',sessionId],queryFn:()=>api('/api/integration')});
 const [scope,setScope]=useState({read:true,control:false});
 const [issued,setIssued]=useState('');
 useEffect(()=>{if(integration.data)setScope({read:!!integration.data.read,control:!!integration.data.control});},[integration.data?.read,integration.data?.control,integration.data?.hasToken]);
 const issue=useMutation({mutationFn:()=>api('/api/integration','POST',scope),onSuccess:(r:any)=>{setIssued(r.token);client.setQueryData(['integration',sessionId],r.integration);toast.success('集成令牌已生成');},onError:(e:any)=>toast.error(e.message)});
 const patch=useMutation({mutationFn:(body:any)=>api('/api/integration','PUT',body),onSuccess:(r:any)=>{client.setQueryData(['integration',sessionId],r);toast.success('已保存');},onError:(e:any)=>toast.error(e.message)});
 const revoke=useMutation({mutationFn:()=>api('/api/integration','DELETE'),onSuccess:(r:any)=>{client.setQueryData(['integration',sessionId],r);setIssued('');toast.success('集成令牌已吊销');},onError:(e:any)=>toast.error(e.message)});
 const copy=async(value:string)=>{const r=await copyText(value);if(r==='ok')toast.success('已复制');else toast.error('复制失败，请手动选中文字复制');};
 const integrationBase=`http://${device.data?.ip||'设备IP'}:${device.data?.port||8099}`;
 const displayToken=issued||'<集成令牌>';
 const stateCurl=`curl "${integrationBase}/api/state" -H "Authorization: Bearer ${displayToken}"`;
 const unlockCurl=`curl -X POST "${integrationBase}/api/remote" -H "Authorization: Bearer ${displayToken}" -H "Content-Type: application/json" -d '{"action":"unlock","minutes":30}'`;
 const haYaml=`sensor:\n  - platform: rest\n    name: 题了个题\n    resource: ${integrationBase}/api/state\n    headers:\n      Authorization: Bearer <集成令牌>\n    value_template: "{{ value_json.learning.remainingSeconds }}"\n    json_attributes:\n      - learning\n      - today\n    scan_interval: 60`;
 const password=useMutation({mutationFn:()=>api('/api/password','POST',{current:pw.current,password:pw.next}),onSuccess:()=>{toast.success('管理密码已修改，请用新密码重新登录');setPw({current:'',next:'',confirm:''});onPasswordChanged();},onError:(e:any)=>toast.error(e.message)});
 function changePassword(){if(pw.next.length<6||pw.next.length>64)return toast.error('新密码需要 6–64 位');if(pw.next!==pw.confirm)return toast.error('两次输入的新密码不一致');password.mutate();}
 const u=update.data;
 return <>
 <div className="sections">
  {only==='device'&&<Panel title="设备" icon={MonitorSmartphone} description={device.data?`${device.data.flavor==='tv'?'电视版':'手机 / 平板版'} v${device.data.version} · ${device.data.ip||'未连接 WiFi'}:${device.data.port}`:undefined}>
   {device.data?.autostart&&<Row title="开机自启" note={autostartNote(device.data.autostart)}><span className={'text-sm '+(device.data.autostart.ok?'text-green-600 dark:text-green-400':device.data.autostart.pending?'text-muted-foreground':'text-amber-600 dark:text-amber-400')}>{!device.data.autostart.known?'无法判断':device.data.autostart.ok?'正常':device.data.autostart.pending?'待重启确认':'可能被拦截'}</span></Row>}
   {device.data?.monitor&&<Row title="管控能力" note={monitorNote(device.data.monitor)}><div className="flex flex-wrap gap-1.5">{[['服务',device.data.monitor.service],['悬浮窗',device.data.monitor.overlay],['使用情况',device.data.monitor.usage],['无障碍',device.data.monitor.accessibility]].map(([label,ok]:any)=><Badge key={label} variant="outline" className={ok?'text-green-700 dark:text-green-400 border-green-600/30':'text-amber-700 dark:text-amber-400 border-amber-600/30'}>{ok?'✓':'✗'} {label}</Badge>)}</div></Row>}
   {!!device.data?.monitor?.lastError&&<Row title="最近一次弹窗失败" note={device.data.monitor.lastError}><span className="text-xs text-muted-foreground">可反馈给开发者</span></Row>}
   <Row title="防卸载" note="开启后孩子无法直接删除应用">{device.data?.uninstallProtected
    ?<Button size="sm" variant="outline" className="text-destructive border-destructive/40 hover:bg-destructive/10" onClick={()=>{if(confirm('关闭后孩子可以直接卸载应用，确认吗？'))disableGuard.mutate()}}>关闭防卸载</Button>
    :<span className="text-sm text-muted-foreground">未开启（在被控设备权限设置里开启）</span>}
   </Row>
   <Row title="崩溃报告" note="崩溃时自动上传调用堆栈、设备型号、应用版本；不含题库、密码、答题记录"><Switch aria-label="崩溃报告" checked={!!device.data?.buglyConsent} onCheckedChange={v=>bugly.mutate(v)}/></Row>
   <Row title="设备名称" note="多台设备时便于区分"><div className="flex gap-2"><Input aria-label="设备名称" className="w-44" maxLength={32} value={name} onChange={e=>setName(e.target.value)}/><Button variant="outline" disabled={rename.isPending||!name.trim()||name===device.data?.name} onClick={()=>rename.mutate()}>保存</Button></div></Row>
  </Panel>}
  {only==='security'&&<Panel title="管理密码" icon={KeyRound} description="修改后所有已登录的家长设备需要用新密码重新连接">
   <div className="grid sm:grid-cols-3 gap-3"><Input type="password" aria-label="当前密码" autoComplete="current-password" placeholder="当前密码" value={pw.current} onChange={e=>setPw({...pw,current:e.target.value})}/><Input type="password" aria-label="新密码" autoComplete="new-password" placeholder="新密码（6–64 位）" value={pw.next} onChange={e=>setPw({...pw,next:e.target.value})}/><Input type="password" aria-label="确认新密码" autoComplete="new-password" placeholder="再次输入新密码" value={pw.confirm} onChange={e=>setPw({...pw,confirm:e.target.value})}/></div>
   <div className="flex justify-end mt-4"><Button disabled={password.isPending||!pw.current||!pw.next} onClick={changePassword}>修改密码</Button></div>
  </Panel>}
  {only==='integration'&&<Panel title="家庭自动化" icon={RadioTower} description="给 Home Assistant 等局域网系统一个独立令牌；默认关闭，可随时吊销">
   <Row title="启用接口" note="只接受局域网请求；权限由令牌单独控制，不用把家长密码交给其他系统"><Switch aria-label="启用家庭自动化接口" checked={!!integration.data?.enabled} onCheckedChange={v=>patch.mutate({enabled:v})}/></Row>
   <Row title="集成令牌" note={integration.data?.hasToken?`${integration.data.hint} · 创建于 ${new Date(integration.data.createdAt).toLocaleString('zh-CN',{hour12:false})}`:'尚未生成。生成后可随时吊销或重新生成。'}><div className="flex gap-2"><Button variant="outline" onClick={()=>issue.mutate()} disabled={issue.isPending||(!scope.read&&!scope.control)}>{integration.data?.hasToken?'重新生成':'生成令牌'}</Button>{integration.data?.hasToken&&<Button variant="ghost" onClick={()=>{if(window.confirm('吊销后使用该令牌的系统会立即断开，确定吗？'))revoke.mutate();}} disabled={revoke.isPending}>吊销</Button>}</div></Row>
   <Row title="权限" note="只读：学习状态和设备信息；控制：家长解锁、暂停或取消"><div className="flex flex-wrap gap-x-5 gap-y-2 text-sm"><label className="flex items-center gap-2"><input type="checkbox" className="size-4 accent-[var(--primary)]" checked={scope.read} onChange={e=>{const read=e.target.checked;if(!read&&!scope.control)return toast.error('至少保留一种权限');setScope({...scope,read});patch.mutate({read});}}/>只读</label><label className="flex items-center gap-2"><input type="checkbox" className="size-4 accent-[var(--primary)]" checked={scope.control} onChange={e=>{const control=e.target.checked;if(!control&&!scope.read)return toast.error('至少保留一种权限');setScope({...scope,control});patch.mutate({control});}}/>控制（解锁 / 暂停）</label></div></Row>
   <p className="text-xs leading-6 text-muted-foreground mt-2">请求头 <code className="rounded bg-secondary px-1.5 py-0.5">Authorization: Bearer &lt;集成令牌&gt;</code>，限流 240 次/分钟。</p>
   <details className="text-sm mt-3"><summary className="cursor-pointer text-muted-foreground">接口示例与 Home Assistant 配置</summary><div className="mt-3 space-y-3">
    <div><div className="flex items-center gap-2 mb-1"><b className="text-xs">读取状态</b><Button size="sm" variant="ghost" onClick={()=>copy(stateCurl)}><Copy/>复制</Button></div><pre className="text-xs bg-secondary/50 border rounded-lg p-3 overflow-x-auto">{stateCurl}</pre></div>
    <div><div className="flex items-center gap-2 mb-1"><b className="text-xs">临时解锁 30 分钟</b><Button size="sm" variant="ghost" onClick={()=>copy(unlockCurl)}><Copy/>复制</Button></div><pre className="text-xs bg-secondary/50 border rounded-lg p-3 overflow-x-auto">{unlockCurl}</pre></div>
    <div><div className="flex items-center gap-2 mb-1"><b className="text-xs">Home Assistant REST sensor</b><Button size="sm" variant="ghost" onClick={()=>copy(haYaml)}><Copy/>复制</Button></div><pre className="text-xs bg-secondary/50 border rounded-lg p-3 overflow-x-auto">{haYaml}</pre></div>
    <p className="text-xs text-muted-foreground">只开放给同一局域网，不要做端口转发。桌面 / 电视上的 HDMI、机顶盒画面不是 Android 前台应用，仍需由 Home Assistant 侧处理。</p>
   </div></details>
  </Panel>}
  {only==='update'&&<Panel title="版本更新" icon={CloudDownload}>
   <Row title={u?.hasUpdate?`有新版本 v${u.latest}`:'当前已是最新版本'} note={`上次检查：${time(startedAt)}`}>
    <Button variant="outline" disabled={check.isPending||polling} onClick={()=>check.mutate()}><RefreshCw className={polling?'animate-spin':''}/>{polling?'检查中…':'检查更新'}</Button></Row>
   {u?.hasUpdate&&<p className="text-sm leading-6 text-muted-foreground whitespace-pre-line mb-4">{u.notes?.replace(/\[force\]/gi,'').trim()}<br/><b className="text-foreground">在被控设备的学习页右上角选择“更新”即可安装。</b></p>}
     </Panel>}
 </div>
 <Dialog open={!!issued} onOpenChange={v=>{if(!v)setIssued('');}}><DialogContent><DialogHeader><DialogTitle>复制集成令牌</DialogTitle><DialogDescription>令牌只显示这一次，请保存到 Home Assistant 的密钥或密码管理器里。</DialogDescription></DialogHeader><div className="rounded-lg border bg-secondary/40 p-3 font-mono text-xs break-all select-all">{issued}</div><DialogFooter><Button variant="outline" onClick={()=>setIssued('')}>我已保存</Button><Button onClick={()=>copy(issued)}><Copy/>复制令牌</Button></DialogFooter></DialogContent></Dialog>
 </>
}
