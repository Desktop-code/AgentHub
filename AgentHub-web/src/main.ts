/**
 * 控制台入口。挂上路由与 Pinia。
 *
 * Element Plus 已改为**按需引入**（见 vite.config.ts 的 Components / ElementPlus 插件）：
 * 这里不再 `app.use(ElementPlus)`、也不再引整包 `element-plus/dist/index.css`。
 * 中文语言包由 App.vue 的 `el-config-provider` 提供，避免丢失日期/分页等组件的中文文案。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/index.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
