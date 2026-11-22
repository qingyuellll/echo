import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import './styles/variables.css'
import './styles/global.css'

const app = createApp(App)

app.config.errorHandler = (err, instance, info) => {
  console.error('[错误] [Vue 全局错误]', err)
  console.error('[位置] 组件:', instance?.$options?.name || '未知')
  console.error('[信息] 错误信息:', info)
}

app.use(router)
app.mount('#app')

console.log('[成功] [应用启动] Vue 应用已挂载')
console.log('[路由] [当前路由]:', window.location.hash || window.location.pathname)
