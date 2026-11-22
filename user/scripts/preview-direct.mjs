import { preview } from 'vite'
import { fileURLToPath } from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url))
const server=await preview({configFile:false,root,preview:{host:'127.0.0.1',port:3002,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:8081',changeOrigin:true,rewrite:path=>path.replace(/^\/api/,'')},'/ssn':{target:'http://127.0.0.1:8081',changeOrigin:true}}}})
server.printUrls()
