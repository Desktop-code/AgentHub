<template>
  <!--
    全局语言包：Element Plus 改为按需引入后不再有 app.use(ElementPlus, { locale })，
    中文文案（分页「共 N 条」、日期选择、表格空态等）统一由这里下发，避免回退成英文。
  -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
/** 根组件只承接路由与全局语言包，不放业务状态。 */
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
