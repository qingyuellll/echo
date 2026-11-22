// Compile Vue SFCs before invoking the esbuild executable with inherited streams.
// This is a production fallback for Windows environments that restrict child-process pipes.
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync, readdirSync, copyFileSync, cpSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
const root=fileURLToPath(new URL('../',import.meta.url))
const require=createRequire(path.join(root,'package.json'))
const {parse,compileScript,compileTemplate,compileStyle}=require('@vue/compiler-sfc')
const output=path.join(root,'dist')
const stage=mkdtempSync(path.join(tmpdir(),'echo-vue-build-'))
function run(command,args) {
  const result=spawnSync(command,args,{cwd:root,stdio:'inherit',windowsHide:true,env:{...process.env,NODE_PATH:path.join(root,'node_modules')}})
  if(result.error)throw result.error
  if(result.status!==0)throw new Error(`Build process exited ${result.status}`)
}
function rewrite(code) {return code.replace(/(\.vue)(['"])/g,'$1.js$2')}
function compileDirectory(source,destination) {
  mkdirSync(destination,{recursive:true})
  for(const item of readdirSync(source,{withFileTypes:true})) {
    const input=path.join(source,item.name),out=path.join(destination,item.name)
    if(item.isDirectory()){compileDirectory(input,out);continue}
    if(!item.name.endsWith('.vue')){copyFileSync(input,out);continue}
    const text=readFileSync(input,'utf8'),{descriptor,errors}=parse(text,{filename:input})
    if(errors.length)throw errors[0]
    const scope='data-v-'+createHash('sha256').update(path.relative(root,input)).digest('hex').slice(0,8)
    let code
    if(descriptor.script || descriptor.scriptSetup) {
      code=compileScript(descriptor,{id:scope,inlineTemplate:true,genDefaultAs:'__component'}).content
      if(!descriptor.scriptSetup && descriptor.template) {
        const template=compileTemplate({source:descriptor.template.content,filename:input,id:scope,scoped:descriptor.styles.some(style=>style.scoped)})
        if(template.errors.length)throw template.errors[0]
        code+='\n'+template.code.replace('export function render','function render')+'\n__component.render=render;'
      }
    } else {
      const template=compileTemplate({source:descriptor.template?.content || '',filename:input,id:scope,scoped:descriptor.styles.some(style=>style.scoped)})
      if(template.errors.length)throw template.errors[0]
      code='const __component={};\n'+template.code.replace('export function render','function render')+'\n__component.render=render;'
    }
    let css=''
    for(const style of descriptor.styles) {
      const source=style.src?readFileSync(path.resolve(path.dirname(input),style.src),'utf8'):style.content
      const result=compileStyle({source,filename:input,id:scope,scoped:!!style.scoped})
      if(result.errors.length)throw result.errors[0]
      css+='\n'+result.code
    }
    if(descriptor.styles.some(style=>style.scoped))code+=`\n__component.__scopeId=${JSON.stringify(scope)};`
    if(css.trim()){writeFileSync(out+'.css',css);code+=`\nimport './${item.name}.css';`}
    code+='\nexport default __component;\n'
    writeFileSync(out+'.js',rewrite(code))
  }
  // JS routes and TS modules may import Vue files, too.
  for(const item of readdirSync(destination,{withFileTypes:true}))if(item.isFile() && /\.[cm]?[jt]s$/.test(item.name)) {
    const file=path.join(destination,item.name);writeFileSync(file,rewrite(readFileSync(file,'utf8')))
  }
}
try {
  run(process.execPath,[require.resolve('vue-tsc/bin/vue-tsc.js'),'--noEmit'])
  compileDirectory(path.join(root,'src'),path.join(stage,'src'))
  if(path.dirname(output)!==path.resolve(root))throw new Error('Unexpected output directory')
  rmSync(output,{recursive:true,force:true,maxRetries:6,retryDelay:100});mkdirSync(output,{recursive:true})
  if(existsSync(path.join(root,'public')))cpSync(path.join(root,'public'),output,{recursive:true})
  const esbuildRequire=createRequire(require.resolve('esbuild/package.json'))
  const binary=esbuildRequire.resolve(`@esbuild/${process.platform}-${process.arch}/${process.platform==='win32'?'esbuild.exe':'bin/esbuild'}`)
  run(binary,[path.join(stage,'src/main.js'),'--bundle','--splitting','--format=esm','--minify','--target=es2020','--loader:.js=ts','--loader:.svg=file','--loader:.png=file','--loader:.jpg=file',`--alias:@=${path.join(stage,'src')}`,`--alias:shared=${path.join(root,'../shared')}`,'--alias:vue=vue/dist/vue.esm-bundler.js','--define:process.env.NODE_ENV="production"','--define:import.meta.env={"PROD":true,"DEV":false,"BASE_URL":"/"}','--define:__VUE_OPTIONS_API__=true','--define:__VUE_PROD_DEVTOOLS__=false','--define:__VUE_PROD_HYDRATION_MISMATCH_DETAILS__=false','--outdir=dist/assets','--entry-names=[name]-[hash]','--metafile=dist/build-meta.json'])
  const metadata=JSON.parse(readFileSync(path.join(output,'build-meta.json'),'utf8'))
  const [entry,info]=Object.entries(metadata.outputs).find(([,value])=>value.entryPoint?.replaceAll('\\','/').endsWith('/src/main.js'))
  let html=readFileSync(path.join(root,'index.html'),'utf8').replace('<script type="module" src="/src/main.js"></script>',`<script type="module" src="/${entry.replace(/^dist\//,'')}"></script>`)
  if(info.cssBundle)html=html.replace('</head>',`<link rel="stylesheet" href="/${info.cssBundle.replace(/^dist\//,'')}" /></head>`)
  writeFileSync(path.join(output,'index.html'),html)
  console.log('Echo user frontend compiled, scoped styles processed and typechecked.')
} finally {
  const resolved=path.resolve(stage)
  if(path.dirname(resolved)===path.resolve(tmpdir()) && path.basename(resolved).startsWith('echo-vue-build-'))rmSync(resolved,{recursive:true,force:true})
}
