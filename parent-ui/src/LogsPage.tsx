import {useEffect,useState} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Download,RefreshCw,Trash2} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Switch} from '@/components/ui/switch';
import {api,downloadLogs} from './api';
import {Panel,Row} from './RulesPage';

export default function LogsPage({sessionId}:{sessionId:number}){
 const client=useQueryClient();
 const [auto,setAuto]=useState(true),[query,setQuery]=useState(''),[busy,setBusy]=useState(false);
 const logs=useQuery({queryKey:['logs',sessionId],queryFn:()=>api('/api/logs?limit=1200'),refetchInterval:auto?3000:false});
 useEffect(()=>{if(auto)void logs.refetch();},[auto]);
 const lines:string[]=logs.data?.lines||[];
 const shown=query.trim()?lines.filter(l=>l.toLowerCase().includes(query.trim().toLowerCase())):lines;
 async function clear(){if(!window.confirm('清空本机诊断日志？导出的旧文件不受影响。'))return;try{await api('/api/logs','DELETE');client.invalidateQueries({queryKey:['logs',sessionId]});toast.success('日志已清空');}catch(e:any){toast.error(e.message);}}
 async function exportLogs(){setBusy(true);try{await downloadLogs();toast.success('日志包已开始下载');}catch(e:any){toast.error(e.message);}finally{setBusy(false);}}

 return <Panel title="诊断日志" icon={RefreshCw} description="记录权限、计时、音频、更新和异常；只保存在本机，不会自动上传">
  <Row title="自动刷新" note="每 3 秒刷新，方便边操作边看"><Switch aria-label="自动刷新日志" checked={auto} onCheckedChange={setAuto}/></Row>
  <div className="flex flex-wrap items-center gap-2 py-4">
   <Button variant="outline" onClick={()=>logs.refetch()} disabled={logs.isFetching}><RefreshCw className={logs.isFetching?'animate-spin':''}/>刷新</Button>
   <Button variant="outline" onClick={exportLogs} disabled={busy}><Download/>导出日志包</Button>
   <Button variant="ghost" className="text-destructive" onClick={clear}><Trash2/>清空</Button>
   <Input className="ml-auto w-full sm:w-64" placeholder="筛选关键字，例如 Gate、Audio、403" value={query} onChange={e=>setQuery(e.target.value)}/>
  </div>
  <p className="text-xs leading-6 text-muted-foreground">导出包含最近的运行日志、设备型号 / 系统版本、权限与服务状态，不包含管理密码、API Key 或令牌。遇到问题时把 zip 发给开发者即可。</p>
  <pre className="mt-3 max-h-[55svh] overflow-auto rounded-xl border bg-secondary/40 p-3 font-mono text-[11px] leading-5 whitespace-pre-wrap break-all">{logs.isLoading?'正在读取日志…':shown.length?shown.join('\n'):'暂时没有日志。'}</pre>
  <p className="mt-2 text-xs text-muted-foreground">共 {lines.length} 行{query?`，筛选出 ${shown.length} 行`:''}。</p>
 </Panel>;
}
