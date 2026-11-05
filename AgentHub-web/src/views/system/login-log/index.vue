<template>
  <div class="page">
    <PageHeader title="登录日志" description="查看控制台登录成功与失败记录，不包含密码。" />
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="用户名">
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
      <el-table v-loading="loading" :data="rows" empty-text="没有登录日志">
        <!-- 最左序号列：跨页连续，纯展示，不参与接口排序 -->
        <el-table-column v-bind="indexColumnProps" />
        <el-table-column prop="username" label="用户名" min-width="140" align="center" />
        <el-table-column prop="ip" label="IP" min-width="160" align="center" />
        <el-table-column label="结果" min-width="90" align="center">
          <template #default="{ row }">{{ row.success === 1 ? '成功' : '失败' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="180" align="center" />
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
defineOptions({ name: 'SystemLoginLog' })
/**
 * 登录日志只读页。所属域：audit。
 * 无新建；有筛选则提供查询 + 重置。不展示密码。
 */
import { onMounted, reactive, ref } from 'vue'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { pageLoginLogs } from '@/api/log'
import type { LoginLogRow } from '@/types'

const loading = ref(false)
const rows = ref<LoginLogRow[]>([])
const total = ref(0)
/** 默认每页 10 条；可选 10/20/50/100 由 TablePager 统一提供 */
const query = reactive({ current: 1, size: 10, username: '' })
/** 表格最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)

async function load() {
  loading.value = true
  try {
    const { data } = await pageLoginLogs({
      current: query.current,
      size: query.size,
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
  query.username = ''
  query.current = 1
  void load()
}

onMounted(load)
</script>

