<template>
  <div class="login-page">
    <!-- 背景层：光晕 + 科技网格 -->
    <div class="bg-layer" aria-hidden="true">
      <span class="orb orb-purple" />
      <span class="orb orb-cyan" />
      <span class="bg-grid" />
    </div>

    <div class="login-shell">
      <!-- 左侧：品牌展示 -->
      <aside class="brand-panel">
        <div class="brand-content">
          <!-- 与顶栏一致的 hub 角标，置于标题前 -->
          <div class="brand-heading">
            <AppLogo class="brand-logo" :show-text="false" />
            <h1 class="brand-title">AgentHub</h1>
          </div>
          <p class="brand-subtitle">企业级智能体编排中台</p>
          <ul class="feature-list">
            <li><span class="dot" />多模型接入与降级</li>
            <li><span class="dot" />全链路执行追踪 (Trace)</li>
            <li><span class="dot" />MCP 工具生态接入</li>
          </ul>
        </div>
      </aside>

      <!-- 右侧：玻璃拟态登录卡 -->
      <main class="form-panel">
        <div class="glass-card">
          <h2 class="card-title">欢迎回来</h2>
          <p class="card-desc">请输入账号密码</p>
          <el-form class="login-form" :model="form" @submit.prevent="onSubmit">
            <el-form-item>
              <el-input
                v-model="form.username"
                placeholder="用户名"
                :prefix-icon="User"
              />
            </el-form-item>
            <el-form-item>
              <el-input
                v-model="form.password"
                placeholder="密码"
                type="password"
                show-password
                :prefix-icon="Lock"
              />
            </el-form-item>
            <el-button
              class="submit"
              :loading="loading"
              native-type="submit"
              @click="onSubmit"
            >
              登录
            </el-button>
          </el-form>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 登录页（暗色全屏 + 左右分栏 + 玻璃拟态卡片）。
 * 所属域：前端 auth / 壳。成功后写入 token 并拉取 /me，再进入工作台。
 * 宽度小于 1024px 时隐藏左侧品牌区。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import AppLogo from '@/components/AppLogo.vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

/**
 * 提交登录：校验非空 → 换 token → loadMe → 跳转工作台。
 */
async function onSubmit() {
  if (!form.username || !form.password) {
    return
  }
  loading.value = true
  try {
    const { data } = await login(form.username.trim(), form.password)
    user.setToken(data.data.token)
    await user.loadMe()
    await router.replace('/workspace')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  background: #0b0f19;
  color: #fff;
}

/* —— 背景层 —— */
.bg-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.3;
}

.orb-purple {
  left: -8%;
  bottom: -12%;
  width: 520px;
  height: 520px;
  background: #635bff;
}

.orb-cyan {
  right: -6%;
  top: -10%;
  width: 480px;
  height: 480px;
  background: #00d4ff;
}

.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 40px 40px;
}

/* —— 左右分栏壳 —— */
.login-shell {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  width: 100%;
}

.brand-panel {
  flex: 0 0 50%;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 48px 10%;
}

.brand-content {
  max-width: 480px;
}

.brand-heading {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

.brand-logo :deep(.mark) {
  width: 48px;
  height: 48px;
}

.brand-title {
  margin: 0;
  font-size: clamp(42px, 5.5vw, 64px);
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: 0.02em;
  color: #fff;
}

.brand-subtitle {
  margin: 0 0 36px;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.4;
  color: rgba(226, 232, 240, 0.78);
}

.feature-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.feature-list li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
  color: rgba(226, 232, 240, 0.88);
}

.feature-list .dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #00d4ff;
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.85), 0 0 22px rgba(99, 91, 255, 0.45);
}

/* —— 右侧玻璃卡 —— */
.form-panel {
  flex: 0 0 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  padding: 32px 24px;
}

.glass-card {
  width: 420px;
  max-width: 100%;
  padding: 40px 36px 36px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 24px 64px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.card-title {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
}

.card-desc {
  margin: 0 0 28px;
  font-size: 14px;
  color: rgba(203, 213, 225, 0.75);
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

/*
 * 全局 index.css 用 !important 把输入框刷成浅色底；
 * 暗色登录卡必须同等权重压回去，否则白字叠白底，看起来像「输不进去」。
 */
.login-form :deep(.el-input__wrapper),
.login-form :deep(.el-input__wrapper:hover) {
  height: 44px;
  padding: 0 14px;
  background-color: rgba(0, 0, 0, 0.35) !important;
  border: 1px solid transparent !important;
  box-shadow: none !important;
  border-radius: 8px;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background-color: rgba(0, 0, 0, 0.45) !important;
  border-color: rgba(99, 91, 255, 0.55) !important;
  box-shadow: 0 0 0 2px rgba(99, 91, 255, 0.25) !important;
}

.login-form :deep(.el-input__inner) {
  height: 44px;
  color: #fff !important;
  -webkit-text-fill-color: #fff;
  caret-color: #fff;
}

.login-form :deep(.el-input__inner::placeholder) {
  color: rgba(148, 163, 184, 0.85);
  -webkit-text-fill-color: rgba(148, 163, 184, 0.85);
}

.login-form :deep(.el-input__inner:-webkit-autofill),
.login-form :deep(.el-input__inner:-webkit-autofill:hover),
.login-form :deep(.el-input__inner:-webkit-autofill:focus) {
  -webkit-text-fill-color: #fff;
  caret-color: #fff;
  transition: background-color 99999s ease-out;
  box-shadow: 0 0 0 1000px rgba(0, 0, 0, 0.45) inset;
}

.login-form :deep(.el-input__prefix),
.login-form :deep(.el-input__suffix),
.login-form :deep(.el-input__prefix .el-icon),
.login-form :deep(.el-input__suffix .el-icon),
.login-form :deep(.el-input__password) {
  color: rgba(148, 163, 184, 0.9);
}

.submit {
  width: 100%;
  height: 44px;
  margin-top: 6px;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
  color: #fff !important;
  background: linear-gradient(135deg, #635bff 0%, #00d4ff 100%) !important;
  box-shadow: 0 8px 24px rgba(99, 91, 255, 0.35);
}

.submit:hover,
.submit:focus {
  filter: brightness(1.06);
  color: #fff !important;
  background: linear-gradient(135deg, #635bff 0%, #00d4ff 100%) !important;
}

.submit:active {
  filter: brightness(0.96);
}

/* 窄屏：隐藏左侧，右侧占满 */
@media (max-width: 1023px) {
  .brand-panel {
    display: none;
  }

  .form-panel {
    flex: 1 1 100%;
  }
}
</style>
