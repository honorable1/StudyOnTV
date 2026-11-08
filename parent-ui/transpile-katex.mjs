import {readFileSync,writeFileSync} from 'node:fs';
import {transformSync} from '@babel/core';
import presetEnv from '@babel/preset-env';
for(const file of ['../shared/katex/katex.min.js','../shared/katex/contrib/auto-render.min.js']){
 const source=readFileSync(file,'utf8');
 const result=transformSync(source,{presets:[[presetEnv,{targets:{chrome:'37'},modules:false,bugfixes:true,useBuiltIns:false}]],comments:false,compact:true,minified:true,sourceMaps:false});
 writeFileSync(file,result.code+'\n');
 console.log(file,'transpiled for Android 5 WebView');
}
