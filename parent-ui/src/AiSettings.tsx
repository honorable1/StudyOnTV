import {useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {Bot,Settings2} from 'lucide-react';
import {Button} from '@/components/ui/button';
import {Badge} from '@/components/ui/badge';
import {api} from './api';
import {Panel,Row} from './RulesPage';
import AiConfig from './AiConfig';

export default function AiSettings({sessionId}:{sessionId:number}){
 const [open,setOpen]=useState(false);
 const settings=useQuery({queryKey:['grading',sessionId],queryFn:()=>api('/api/grading')});
 const d=settings.data;
 const ready=!!(d?.endpoint&&d?.model&&(d?.hasKey||d?.provider==='ollama'));
 return <><Panel title="模型 API（高级，可选）" icon={Bot} description="只给需要自动批改或自动出题的家长；不会配置可以继续用外部 Agent 或手动批改">
  <Row title="当前状态" note={d?.hasKey?'API Key 已保存在本机':'还没有保存 API Key'}>
   <Badge variant={ready?'secondary':'outline'} className={ready?'text-green-700 dark:text-green-400':'text-muted-foreground'}>{ready?'已配置':'未配置'}</Badge>
  </Row>
  <Row title="服务商 / 模型" note={d?.model?`${d?.provider||'自定义'} · ${d.model}`:'支持 DeepSeek、通义千问、智谱、Kimi、豆包、Ollama 等'}>
   <Button variant="outline" onClick={()=>setOpen(true)}><Settings2/>配置</Button>
  </Row>
  <p className="text-sm leading-6 text-muted-foreground pt-3">这里只配置一次，批改和 AI 出题共用。新手建议先不配置：题库可以导入，或者把出题指令发给桌面版 Agent；批改默认由家长手动完成。</p>
 </Panel>
 {open&&<AiConfig sessionId={sessionId} onClose={()=>setOpen(false)}/>}</>;
}
