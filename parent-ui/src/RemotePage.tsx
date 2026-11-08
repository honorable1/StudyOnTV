import {useEffect,useState,type CSSProperties} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {LockKeyhole,LockKeyholeOpen,RotateCcw,Clock3,ArrowUpRight,BookOpen,ClipboardCheck,Pause,Play,Sparkles,ChevronRight} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Label} from '@/components/ui/label';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription,DialogFooter} from '@/components/ui/dialog';
import {api} from './api';
import tablerCss from '@tabler/core/dist/css/tabler.css?raw';
import {BarChart,Bar,ResponsiveContainer,XAxis,YAxis,Tooltip,CartesianGrid} from 'recharts';
const scopedTabler=`@scope (.tabler-home) { ${tablerCss.replace(/@charset[^;]+;/g,'').replace(/:root/g,':scope').replace(/\nbody \{/g,'\n:scope {')} }`;


/* 遥控：像电视遥控器一样，随时临时解锁（大人自己用）或立即锁定（孩子回来了），1 秒内生效 */
const fmt=(s:number)=>{const m=Math.ceil(s/60);if(m>=60)return `${Math.floor(m/60)} 小时${m%60?` ${m%60} 分`:''}`;return `${m} 分钟`;};
const minutesToMidnight=()=>{const d=new Date(),end=new Date(d);end.setHours(24,0,0,0);return Math.max(1,Math.round((+end-+d)/60000));};
const reasonText:Record<string,string>={quota:'今天的使用时间已用完',rounds:'今天的任务已全部完成',schedule:'不在可用时段',timeup:'需要先完成学习任务','':'可以使用'};

export default function RemotePage({state,sessionId}:{state:any,sessionId:number}){
 const client=useQueryClient();
 const reviews=useQuery({queryKey:['reviews',sessionId],queryFn:()=>api('/api/reviews'),refetchInterval:15000});
 const pendingCount=reviews.data?.items?.length||0;
 const history=useQuery({queryKey:['history',sessionId],queryFn:()=>api('/api/history?days=7')});
 const days=history.data?.items||[];
 const learning=state?.learning,ov=learning?.override;
 // 本地每秒倒计时，服务器状态每 5 秒刷新一次
 const [now,setNow]=useState(Date.now());const [base,setBase]=useState({at:Date.now(),left:0});
 useEffect(()=>{setBase({at:Date.now(),left:ov?.remainingSeconds||0});},[ov?.remainingSeconds,ov?.at]);
 useEffect(()=>{const t=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(t);},[]);
 const left=Math.max(0,base.left-Math.max(0,Math.floor((now-base.at)/1000)));
 const run=useMutation({mutationFn:(b:{action:string,minutes?:number,label:string})=>api('/api/remote','POST',{action:b.action,minutes:b.minutes||0}),
  onSuccess:(r,b)=>{client.setQueryData(['state',sessionId],(old:any)=>old?{...old,learning:r.learning}:old);client.invalidateQueries({queryKey:['state',sessionId]});toast.success(b.label);},
  onError:(e:any)=>toast.error(e.message)});
 const go=(action:string,minutes:number,label:string)=>run.mutate({action,minutes,label});
 const mode=ov?.mode as 'unlock'|'lock'|'indefinite'|undefined;
 const title=mode==='indefinite'?'管控已关闭（无限时）':mode==='unlock'?'已临时解锁':mode==='lock'?'已锁定':learning?.free?'自由时段':learning?.reason?'管控中 · '+(reasonText[learning.reason]||'暂不可用'):'管控中 · 可以使用';
 const [custom,setCustom]=useState<{mode:'unlock'|'lock'|'indefinite',minutes:string}|null>(null);
 function submitCustom(){const m=Number(custom?.minutes);if(!custom)return;if(custom.mode!=='indefinite'&&(!Number.isInteger(m)||m<1||m>(custom.mode==='unlock'?1440:10080)))return toast.error(custom.mode==='unlock'?'请输入 1–1440 分钟':'请输入 1–10080 分钟');go(custom.mode,m,custom.mode==='unlock'?`已解锁 ${fmt(m*60)}`:`已锁定 ${fmt(m*60)}`);setCustom(null);}
 if(!learning)return <p className="empty-state">正在连接设备…</p>;
 const used=Number(learning.usedMinutes||0),limit=Number(state.config?.dailyMinutes||40);
 const blocked=mode==='lock'||(!mode&&!learning.free&&!!learning.reason);
 const remaining=mode?left:learning.remainingSeconds||0;
 const open=(action:'unlock'|'lock')=>setCustom({mode:action,minutes:'30'});
 return <div className="tabler-home">
  <style>{scopedTabler}</style>
  <div className="page-header d-print-none"><div className="row align-items-center"><div className="col"><div className="page-pretitle">设备管理</div><h1 className="page-title">今日概览</h1></div><div className="col-auto ms-auto"><div className="btn-list"><button className="btn" onClick={()=>location.hash='/rules'}><BookOpen className="icon"/>学习计划</button><button className="btn btn-primary" disabled={run.isPending} onClick={()=>open('unlock')}><Play className="icon"/>临时解锁</button>
{!mode&&<button className="btn btn-danger" disabled={run.isPending} onClick={()=>{if(window.confirm('关闭管控后孩子可无限使用，直到您手动恢复，确定吗？'))go('indefinite',0,'管控已关闭');}}><LockKeyholeOpen className="icon"/>关闭管控</button>}
{mode==='indefinite'&&<button className="btn btn-primary" disabled={run.isPending} onClick={()=>go('cancel',0,'管控已恢复')}><LockKeyhole className="icon"/>恢复管控</button>}</div></div></div></div>
  <div className="dashboard-overview">
   <section className="card dashboard-device card-gradient"><div className="card-body">
    <div className="dashboard-device-heading"><div><div className="subheader">当前设备</div><h2>设备使用状态</h2><div className="text-secondary"><span className={'status-dot '+(blocked?'bg-red':'bg-green')}/>{title}</div></div><span className="dashboard-device-symbol"><Clock3 aria-hidden="true"/></span></div>
    <div className="dashboard-device-bottom"><div><div className="subheader">{mode==='lock'?'暂停剩余':mode==='unlock'?'临时解锁剩余':'剩余可用时间'}</div><div className="dashboard-balance">{learning.free&&!mode?'自由使用':blocked&&!mode?'暂不可用':Math.ceil(remaining/60)}{!(!mode&&(learning.free||blocked))&&<span>分钟</span>}</div></div><button className="btn btn-sm" disabled={run.isPending} onClick={()=>mode?go('cancel',0,'已恢复学习规则'):open('lock')}>{mode?<RotateCcw className="icon"/>:<Pause className="icon"/>}{mode?'恢复学习规则':'暂停使用'}</button></div>
   </div></section>
   <section className="card dashboard-usage"><div className="card-body"><div className="subheader">今日已用</div><div className="dashboard-number">{Number(used.toFixed(1))}<span>分钟</span></div><div className="text-secondary">每日上限 {limit} 分钟</div><div className="dashboard-mini-chart">{days.length>0&&<ResponsiveContainer width="100%" height="100%"><BarChart data={days.slice().reverse()}><Bar dataKey="watch" fill="var(--tblr-primary)" maxBarSize={16} radius={[2,2,0,0]}/></BarChart></ResponsiveContainer>}</div><div className="dashboard-stat-foot text-secondary">最近 7 天的使用时间</div></div></section>
   <section className="card dashboard-tasks"><div className="card-body"><div className="subheader">今日学习任务</div><div className="dashboard-number">{learning.roundsDoneToday||0}<span>/ {state.config?.roundsPerDay||0} 组</span></div><div className="dashboard-ring" style={{'--completion':Math.min(100,(learning.roundsDoneToday||0)/Math.max(1,state.config?.roundsPerDay||1)*100)+'%'} as CSSProperties}><div><strong>{Math.max(0,(state.config?.roundsPerDay||0)-(learning.roundsDoneToday||0))}</strong><span>组待完成</span></div></div></div></section>
  </div>
  <div className="dashboard-quick-facts"><div><BookOpen/><span>每组题量<strong>{state.config?.questionsPerRound||0} 题</strong></span></div><div><Clock3/><span>答题奖励<strong>{state.config?.difficultyRewardEnabled?'按星级奖励':(state.config?.sessionMinutes||0)+' 分钟'}</strong></span></div><div><ClipboardCheck/><span>待批改<strong>{pendingCount} 份</strong></span><button aria-label="查看待批改" onClick={()=>location.hash='/grading'}><ChevronRight/></button></div><div><Sparkles/><span>待完成加练<strong>{learning.extraPool||0} 题</strong></span><button aria-label="查看成长记录" onClick={()=>location.hash='/records'}><ChevronRight/></button></div></div>
  {pendingCount>0&&<div className="alert alert-warning"><ClipboardCheck className="icon me-2"/>{pendingCount} 份作业待批改<button className="btn btn-sm ms-auto" onClick={()=>location.hash='/grading'}>去批改</button></div>}
  {!state?.questions&&<div className="alert alert-warning">题库还没有题目<button className="btn btn-sm ms-auto" onClick={()=>location.hash='/bank'}>添加题目</button></div>}
  <div className="row row-deck g-3 mb-3 dashboard-details">
   <div className="col-xl-8"><section className="card dashboard-chart-card"><div className="card-header"><h2 className="card-title">使用时间</h2><span className="ms-auto text-secondary">最近 7 天</span></div><div className="card-body"><div className="dashboard-chart">{days.length?<ResponsiveContainer width="100%" height="100%"><BarChart data={days.slice().reverse()} margin={{top:12,right:8,bottom:0,left:-15}}><CartesianGrid vertical={false} stroke="var(--tblr-border-color)" strokeDasharray="3 3"/><XAxis dataKey="label" axisLine={false} tickLine={false} tick={{fontSize:11,fill:'var(--tblr-secondary)'}}/><YAxis axisLine={false} tickLine={false} tick={{fontSize:11,fill:'var(--tblr-secondary)'}}/><Tooltip formatter={(v:any)=>[Number(v).toFixed(1)+' 分钟','使用时间']} contentStyle={{border:'1px solid var(--tblr-border-color)',borderRadius:4,fontSize:12}} cursor={{fill:'var(--tblr-bg-surface-secondary)'}}/><Bar dataKey="watch" fill="var(--tblr-primary)" maxBarSize={28} radius={[2,2,0,0]}/></BarChart></ResponsiveContainer>:<div className="empty"><p className="empty-title">暂无使用记录</p></div>}</div></div></section></div>
   <div className="col-xl-4"><section className="card dashboard-control-card"><div className="card-header"><h2 className="card-title">设备控制</h2></div><div className="list-group list-group-flush"><button className="list-group-item list-group-item-action" disabled={run.isPending} onClick={()=>open('unlock')}><div className="row align-items-center"><div className="col-auto"><span className="avatar bg-blue-lt"><Play className="icon"/></span></div><div className="col"><div>临时解锁</div><div className="text-secondary mt-1">到时自动恢复学习规则</div></div><div className="col-auto"><ChevronRight className="icon text-secondary"/></div></div></button><button className="list-group-item list-group-item-action" disabled={run.isPending} onClick={()=>open('lock')}><div className="row align-items-center"><div className="col-auto"><span className="avatar"><Pause className="icon"/></span></div><div className="col"><div>暂停使用</div><div className="text-secondary mt-1">设置设备暂停时长</div></div><div className="col-auto"><ChevronRight className="icon text-secondary"/></div></div></button></div><div className="card-body"><div className="subheader mb-2">今日计划</div><div className="d-flex justify-content-between mb-2"><span className="text-secondary">每组题量</span><span>{state.config?.questionsPerRound||0} 题</span></div><div className="d-flex justify-content-between"><span className="text-secondary">通过一组解锁</span><span>{state.config?.difficultyRewardEnabled?'按星级奖励':(state.config?.sessionMinutes||0)+' 分钟'}</span></div></div><div className="card-footer"><button className="btn btn-link p-0" onClick={()=>location.hash='/rules'}>调整学习计划<ChevronRight className="icon"/></button></div></section></div>
  </div>
  <section className="card dashboard-records"><div className="card-header"><h2 className="card-title">学习记录</h2><div className="card-actions"><button className="btn btn-sm btn-ghost-secondary" onClick={()=>location.hash='/records'}>查看全部<ChevronRight className="icon ms-1"/></button></div></div><div className="table-responsive"><table className="table card-table table-vcenter text-nowrap datatable"><thead><tr><th>日期</th><th>答题数量</th><th>正确率</th><th>完成任务</th><th className="text-end">使用时间</th></tr></thead><tbody>{days.slice(0,5).map((day:any)=><tr key={day.label}><td className="text-nowrap">{day.label}</td><td className="text-secondary">{day.answered} 题</td><td><div className="d-flex align-items-center"><span className="badge bg-green-lt me-2">{day.accuracy}%</span><div className="progress progress-xs flex-grow-1" style={{width: 48}}><div className="progress-bar bg-green" style={{width: `${day.accuracy}%`}}></div></div></div></td><td className="text-secondary">{day.rounds} 组</td><td className="text-end text-secondary">{Number(day.watch).toFixed(1)} 分钟</td></tr>)}</tbody></table>{!days.length&&<div className="empty text-secondary">暂无学习记录</div>}</div></section>

  <Dialog open={!!custom} onOpenChange={v=>{if(!v)setCustom(null);}}>
   <DialogContent className="dashboard-control-dialog">
    <DialogHeader><DialogTitle>{custom?.mode==='lock'?'暂停使用':'临时解锁'}</DialogTitle><DialogDescription>{custom?.mode==='lock'?'锁定期间孩子不能答题，到时间自动恢复；家长可随时解除。':'期间不需要答题，也不计入使用时间；到时间自动恢复规则。'}</DialogDescription></DialogHeader>
    <div className="home-duration-options">{[[30,'30 分钟'],[60,'1 小时'],[120,'2 小时'],[minutesToMidnight(),'到今晚 24 点']].map(([minutes,label])=><Button key={String(label)} type="button" variant={custom?.minutes===String(minutes)?'secondary':'outline'} onClick={()=>setCustom(c=>c?{...c,minutes:String(minutes)}:c)}>{label}</Button>)}</div>
    <div className="py-2"><Label htmlFor="custom-minutes">分钟</Label><Input id="custom-minutes" className="mt-2" type="number" inputMode="numeric" min={1} max={custom?.mode==='lock'?10080:1440} value={custom?.minutes||''} onChange={e=>setCustom(c=>c?{...c,minutes:e.target.value}:c)} placeholder={custom?.mode==='lock'?'例如 60':'例如 45'}/></div>
    <DialogFooter><Button variant="outline" onClick={()=>setCustom(null)}>取消</Button><Button onClick={submitCustom} disabled={run.isPending}>确定</Button></DialogFooter>
   </DialogContent>
  </Dialog>
 </div>;
}
