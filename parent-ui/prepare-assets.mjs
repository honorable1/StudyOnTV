import {cpSync,copyFileSync,existsSync,mkdirSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';
mkdirSync('public/assets',{recursive:true});
cpSync('../shared/katex','public/assets/vendor/katex',{recursive:true});
copyFileSync('../shared/math.js','public/assets/math.js');
const analytics='../admin/assets/analytics.js';
if(existsSync(analytics))copyFileSync(analytics,'public/assets/analytics.js');
else if(existsSync('../admin/analytics.js'))copyFileSync('../admin/analytics.js','public/assets/analytics.js');
else if(existsSync('public/analytics.js'))copyFileSync('public/analytics.js','public/assets/analytics.js');
rmSync('public/analytics.js',{force:true});
mkdirSync('public/assets/notices',{recursive:true});
if(existsSync('public/SHADCN-ADMIN-LICENSE.txt')){copyFileSync('public/SHADCN-ADMIN-LICENSE.txt','public/assets/notices/shadcn-admin.txt');rmSync('public/SHADCN-ADMIN-LICENSE.txt');}
for(const name of ['react','react-dom','@radix-ui/react-dialog','@tanstack/react-query','react-hook-form','recharts','lucide-react','sonner','zod','tailwindcss']){
 for(const license of ['LICENSE','LICENSE.md','LICENSE.txt']){
  const file=resolve('node_modules',name,license);
  if(existsSync(file)){copyFileSync(file,resolve('public/assets/notices',name.replace(/[@/]/g,'-')+'.txt'));break;}
 }
}
