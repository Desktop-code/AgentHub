<template>
  <div class="page">
    <PageHeader title="操作日志" description="查看控制台关键操作摘要，不包含密码和密钥。" />
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="模块">
          <el-input v-model="query.module" class="filter-input" clearable placeholder="user/role/menu" />
        </el-form-item>
        <el-form-item label="用户">
          <el-input v-model="query.username" class="filter-input" clearable />
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="onSearch">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="onReset">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="没有操作日志">
        <el-table-column prop="username" label="操作者" min-width="120" align="center" />
        <el-table-column prop="module" label="模块" min-width="100" align="center" />
        <el-table-column prop="action" label="动作" min-width="110" align="center" />
        <el-table-column prop="targetType" label="资源" min-width="100" align="center" />
        <el-table-column prop="targetId" label="资源ID" min-width="100" align="center" />
        <el-table-column prop="detailJson" label="摘要" min-width="220" align="center" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" min-width="140" align="center" />
        <el-table-column prop="createTime" label="时间" min-width="180" align="center" />
      </el-table>
      <TablePager
        v-model:current="query.current"
        v-model:size="query.size"
        :total="total"
        @change="load"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemOperLog' })
/**
 * 操作日志只读页。所属域：audit。
 * 无新建；有筛选则提供查询 + 重置。摘要来自 detail_json，不含密码和密钥。
 */
import { onMounted, reactive, ref } from 'vue'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { pageOperLogs } from '@/api/log'
import type { OperLogRow } from '@/types'

const loading = ref(false)
const rows = ref<OperLogRow[]>([])
const total = ref(0)
/** 默认每页 10 条；可选 10/20/50/100 由 TablePager 统一提供 */
const query = reactive({ current: 1, size: 10, module: '', username: '' })

async function load() {
  loading.value = true
  try {
    const { data } = await pageOperLogs({
      current: query.current,
      size: query.size,
      module: query.module || undefined,
      username: query.username || undefined
    })
    rows.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.current = 1
  void load()
}

function onReset() {
  query.module = ''
  query.username = ''
  query.current = 1
  void load()
}

onMounted(load)
</script>

