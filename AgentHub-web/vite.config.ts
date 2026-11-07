import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'
import { fileURLToPath, URL } from 'node:url'

/**
 * 前端构建配置。所属域：前端壳（工程配置）。
 *
 * 两件事：
 * 1. Element Plus 按需：模板里的 `el-*` 与 `v-loading` 由 Components 插件自动引入组件与其样式；
 *    业务代码里显式 `import { ElMessage } from 'element-plus'` 的样式由 unplugin-element-plus 补上。
 *    入口不再 `app.use(ElementPlus)`、也不再引 `element-plus/dist/index.css`。
 * 2. 拆包：vue 运行时 / 重图表库 / 流程图库各成一块，避免全部挤进入口 chunk。
 */
export default defineConfig({
  plugins: [
    vue(),
    // 模板内 el-* 组件与 v-loading 指令按需引入，样式随组件一起引入（css 预编译产物）
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })]
    }),
    // 显式 import 的 ElMessage / ElMessageBox 等补样式，避免按需后弹层没样式
    ElementPlus({})
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    /**
     * 产物目录不参与监听。
     * <p>不忽略的话，开发中在另一个终端跑 `npm run build` 会写几十个 dist 文件，
     * Vite 视作工程变更直接整页 reload —— 表现就是「放着不动也会突然刷新」。
     */
    watch: {
      ignored: ['**/dist/**']
    }
  },
  build: {
    rollupOptions: {
      output: {
        /**
         * 手动分包：把长期不变的第三方库与业务代码分开，首屏与二次访问都能命中缓存。
         * 只按 node_modules 路径分类，未命中的交给 Rollup 默认策略。
         *
         * <p>Element Plus **刻意不在这里合成一块**：按需引入后它由 Rollup 自然拆成
         * `el-table-column` / `el-select` / `el-date-picker` 等按组件的小块，只有真正用到的页面
         * 才拉对应的块（实测首屏 1519KB → 339KB）。强行把 element-plus 归到一块会让
         * 全站用到的组件提前在入口加载（实测 971KB 常驻），反而比改前更大。
         *
         * @param id 模块绝对路径
         * @return chunk 名；返回 undefined 表示交给默认分包
         */
        manualChunks(id: string) {
          if (!id.includes('node_modules')) {
            return undefined
          }
          // 图表库只在 Trace / 评测等页用到，单独成块，不拖首屏
          if (id.includes('echarts') || id.includes('zrender')) {
            return 'echarts'
          }
          // 流程图库只在工作流编辑器用到
          if (id.includes('@vue-flow')) {
            return 'vue-flow'
          }
          if (id.includes('marked')) {
            return 'marked'
          }
          // Vue 运行时 + 路由 + 状态管理：入口必需且版本最稳定，单独缓存
          if (id.includes('vue-router') || id.includes('pinia') || id.includes('/@vue/') || id.includes('/vue/')) {
            return 'vue'
          }
          return undefined
        }
      }
    }
  }
})
