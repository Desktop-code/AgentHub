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
          <!-- 核心能力按「主 → 次」排列，文案固定，不在此处追加第四条或装饰性符号 -->
          <ul class="feature-list">
            <li><span class="dot" aria-hidden="true" />智能体编排与协同运行</li>
            <li><span class="dot" aria-hidden="true" />知识库检索与工具调用</li>
            <li><span class="dot" aria-hidden="true" />多模型接入与全链路追踪</li>
          </ul>
        </div>
      </aside>

      <!-- 右侧：玻璃拟态登录卡 -->
      <main class="form-panel">
        <div class="glass-card">
          <h2 class="card-title">欢迎回来</h2>
          <p class="card-desc">请输入账号密码</p>
          <el-form
            ref="formRef"
            class="login-form"
            :model="form"
            :rules="rules"
            @submit.prevent="onSubmit"
          >
            <el-form-item prop="username">
              <el-input
                ref="usernameRef"
                v-model="form.username"
                placeholder="用户名"
                :prefix-icon="User"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                ref="passwordRef"
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
 * 所属域：前端 auth / 壳。成功后写入 token 并拉取 /me，再进入目标页。
 * 用户名默认留空，不在构建产物里预置任何账号名。
 * 宽度小于 1024px 时隐藏左侧品牌区。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type FormInstance, type FormRules, type InputInstance } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import AppLogo from '@/components/AppLogo.vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const loading = ref(false)
const formRef = ref<FormInstance>()
const usernameRef = ref<InputInstance>()
const passwordRef = ref<InputInstance>()
// 用户名默认空串：预填 admin 等于向所有访问者公开默认管理员账号名
const form = reactive({ username: '', password: '' })

/**
 * 必填校验。用 transform 先 trim 再判空，纯空格与空值同等处理，
 * 避免把 "   " 这类输入发到后端再报错。
 */
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', transform: (v: string) => (v ?? '').trim() }],
  password: [{ required: true, message: '请输入密码' }]
}

/**
 * 校验登录后的回跳地址：只接受站内单斜杠路径。
 * 排除 `//evil.com`（协议相对外链）与 `/\evil.com`（部分浏览器会归一成 `//`），
 * 防开放重定向；非法值返回 null，由调用方回落到工作台。
 */
function safeRedirect(raw: unknown): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || value === '') {
    return null
  }
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null
  }
  return value
}

/**
 * 提交登录：trim + 校验 → 换 token → loadMe → 跳转目标页。
 * 失败时错误提示由 request 拦截器统一弹窗，这里只做状态收口。
 */
async function onSubmit() {
  // 双击/回车连打时直接挡回：loading 期间不再发第二次登录请求
  if (loading.value) {
    return
  }
  // trim 放在校验前，空白用户名按空值处理
  form.username = form.username.trim()
  try {
    await formRef.value?.validate()
  } catch {
    // 校验失败：按「先用户名后密码」定位首个空项，避免用户不知道点没点上
    if (!form.username) {
      usernameRef.value?.focus()
    } else {
      passwordRef.value?.focus()
    }
    return
  }
  loading.value = true
  try {
    const { data } = await login(form.username, form.password)
    user.setToken(data.data.token)
    /*
     * /me 失败说明刚拿到的 token 不可用：立即清掉本地登录态，
     * 不留「有 token、没用户信息」的半截态；用户面提示由拦截器给出，避免二次弹窗。
     */
    try {
      await user.loadMe()
    } catch {
      await user.logout()
      return
    }
    // 优先回原深链，没有合法 redirect 再进工作台
    await router.replace(safeRedirect(route.query.redirect) ?? '/workspace')
  } catch {
    // 登录失败（含上面的 loadMe 失败）已在各分支处理，这里只收口未处理的 rejection
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/*
 * 登录页是产品里唯一的暗色世界：暗底、光晕、玻璃卡只在此页生效，不向控制台扩散。
 * 颜色/圆角统一收在本页局部变量里，避免任何一个值漏成全局令牌。
 * 圆角除玻璃卡 20px（DESIGN.md 明确划给登录）外，其余控件只取 8 / 6 / 4。
 */
.login-page {
  --signin-bg: #0b0f19;
  --signin-surface: rgba(255, 255, 255, 0.08);
  --signin-border: rgba(255, 255, 255, 0.1);
  --signin-text: #f8fafc;
  /* 暗底上的次级文字：合成后约 #A6ABB4，对 #0B0F19 实测 8.3:1；
     比控制台的 #6B7280 更亮，是因为暗底需要更高亮度才不塌进背景 */
  --signin-text-secondary: rgba(226, 232, 240, 0.72);
  --signin-text-faint: rgba(148, 163, 184, 0.86);
  --signin-primary: #635bff;
  --signin-cyan: #00d4ff;
  --signin-radius: 8px;
  --signin-radius-card: 20px;

  position: relative;
  /* 100vh 在移动端浏览器会算进地址栏高度，导致底部被裁；dvh 兜住，老内核退回 vh */
  height: 100vh;
  height: 100dvh;
  width: 100%;
  overflow: hidden;
  background: var(--signin-bg);
  color: var(--signin-text);
}

/*
 * 暗底上的选区也属于设计系统：默认浅蓝选区在玻璃卡上像浏览器原样。
 * 必须走 :deep —— scoped 会给 `::selection` 后缀加 data 属性，规则会整体失效。
 */
.login-page :deep(::selection) {
  background: rgba(99, 91, 255, 0.45);
  color: #fff;
}

/* 唯一一处入场动效：整块自下浮起，避免暗色首屏硬切；位移幅度刻意小于 12px */
@keyframes signin-enter {
  from {
    opacity: 0;
    transform: translate3d(0, 10px, 0);
  }
  to {
    opacity: 1;
    transform: none;
  }
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
  background: var(--signin-primary);
}

.orb-cyan {
  right: -6%;
  top: -10%;
  width: 480px;
  height: 480px;
  background: var(--signin-cyan);
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
  animation: signin-enter 420ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.brand-panel {
  flex: 0 0 50%;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  /*
   * 横向留白随视口收放：1024px 时 6vw≈61px，左栏内容区仍有约 390px，
   * 48px 角标 + 16px 间距 + 56px 字标约 314px，不会顶出左栏压到玻璃卡；宽屏最多留 96px。
   */
  padding: 48px clamp(32px, 6vw, 96px);
}

.brand-content {
  max-width: 460px;
}

.brand-heading {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

/* 角标 SVG 自带 8px 圆角，这里只把 32px 放大到 48px；不额外加描边环，避免多余装饰 */
.brand-logo :deep(.mark) {
  width: 48px;
  height: 48px;
  border-radius: var(--signin-radius);
}

/* Display 只属于登录页字标：800 / clamp(42px, 5.5vw, 64px) / 1.1，与控制台字号体系隔离 */
.brand-title {
  margin: 0;
  font-size: clamp(42px, 5.5vw, 64px);
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: 0.02em;
  color: var(--signin-text);
}

/*
 * 副标题回到字号表 16px。层级靠「字重 + 明度」而不是字号拉开：
 * 800 白字标 → 500 近白 16px 定位语 → 400 次级灰 14px 能力点，
 * 三档不靠 24px 那种越级尺寸也能读出主次。
 */
.brand-subtitle {
  margin: 0 0 32px;
  font-size: 16px;
  font-weight: 500;
  line-height: 1.4;
  letter-spacing: 0.02em;
  /* 近白 86%：与字标同色族但明度略低，叠在 #0B0F19 上约 14:1，仍稳在正文可读范围 */
  color: rgba(248, 250, 252, 0.86);
}

.feature-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feature-list li {
  position: relative;
  padding-left: 18px;
  font-size: 14px;
  font-weight: 400;
  line-height: 1.5;
  color: var(--signin-text-secondary);
}

/*
 * 能力点前的指示灯：6px 青点 + 1px 偏移的柔光（不带零偏移彩色光晕），
 * 呼应「设备通电」语义。用绝对定位锁在第一行行高中心，
 * 文案换行时点仍只贴着首行，不会跟着整块居中飘走。
 */
.feature-list .dot {
  position: absolute;
  left: 0;
  /* 行高 1.5 × 14px ≈ 21px，点径 6px，偏移 7px 即压在第一行视觉中线 */
  top: 7px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--signin-cyan);
  box-shadow: 0 1px 6px rgba(0, 212, 255, 0.55);
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

/*
 * DESIGN.md 的 Sign-in 签名：420px 宽、20px 圆角、8% 白底、24px 模糊 + 深投影。
 * 它不参与控制台任何布局规则，也不得被业务页复用。
 */
.glass-card {
  width: 420px;
  max-width: 100%;
  padding: 40px 36px 36px;
  /* 20px 是 DESIGN.md 明确划给登录玻璃卡的圆角，普通业务卡不得借用 */
  border-radius: var(--signin-radius-card);
  background: var(--signin-surface);
  border: 1px solid var(--signin-border);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 24px 64px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

/* 卡片内只用三级字号：16 标题 / 13 说明 / 14 正文，与控制台字号表一致 */
.card-title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--signin-text);
}

/* 卡片说明压到 13px 次级灰：它是引导语，不与 16px 标题竞争 */
.card-desc {
  margin: 0 0 28px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--signin-text-secondary);
}

/*
 * 表单项间距 22px：Element Plus 的错误提示是绝对定位在项底部的，
 * 12px 字 + 4px 内边距约需 21px，间距小于它就会压到下一个输入框上。
 */
.login-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

/*
 * 表单校验/登录失败提示的红色在暗底上默认偏暗，这里只提亮字色，
 * 不改任何校验规则与提交逻辑：错误提示文案始终由原逻辑给出。
 */
.login-form :deep(.el-form-item__error) {
  padding-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: #fca5a5;
}

/*
 * 全局 index.css 用 !important 把输入框刷成浅色底；
 * 暗色登录卡必须同等权重压回去，否则白字叠白底，看起来像「输不进去」。
 * 高度 44px、8px 圆角沿用 DESIGN.md 的 Dark variant 描述。
 */
.login-form :deep(.el-input__wrapper),
.login-form :deep(.el-input__wrapper:hover) {
  height: 44px;
  padding: 0 14px;
  background-color: rgba(0, 0, 0, 0.35) !important;
  /* 静止无描边（与全站轮廓语言一致），聚焦才出现紫描边 */
  border: 1px solid transparent !important;
  box-shadow: none !important;
  border-radius: var(--signin-radius);
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background-color: rgba(0, 0, 0, 0.45) !important;
  border-color: rgba(99, 91, 255, 0.55) !important;
  box-shadow: 0 0 0 2px rgba(99, 91, 255, 0.25) !important;
}

/*
 * 校验失败态：上面的基础规则把描边与阴影清成了透明，必须在这里补回红色描边，
 * 否则只剩一行小字、输入框看起来毫无异常。放在基础规则之后才压得住。
 */
.login-form :deep(.el-form-item.is-error .el-input__wrapper) {
  border-color: rgba(248, 113, 113, 0.72) !important;
  box-shadow: 0 0 0 2px rgba(248, 113, 113, 0.18) !important;
}

.login-form :deep(.el-input__inner) {
  height: 44px;
  font-size: 14px;
  color: var(--signin-text) !important;
  -webkit-text-fill-color: var(--signin-text);
  caret-color: var(--signin-text);
}

/* 占位符在 35% 黑输入底上实测约 5.5:1，仍算可读正文灰，不压到 3:1 一带 */
.login-form :deep(.el-input__inner::placeholder) {
  color: var(--signin-text-faint);
  -webkit-text-fill-color: var(--signin-text-faint);
}

.login-form :deep(.el-input__inner:-webkit-autofill),
.login-form :deep(.el-input__inner:-webkit-autofill:hover),
.login-form :deep(.el-input__inner:-webkit-autofill:focus) {
  /* 浏览器自动填充会用内阴影刷白底，这里用超长 transition 压住，并保持白字可读 */
  -webkit-text-fill-color: var(--signin-text);
  caret-color: var(--signin-text);
  transition: background-color 99999s ease-out;
  box-shadow: 0 0 0 1000px rgba(0, 0, 0, 0.45) inset;
}

/* 输入框内前后缀图标沿用占位符的同一档灰，避免图标比文字还亮 */
.login-form :deep(.el-input__prefix),
.login-form :deep(.el-input__suffix),
.login-form :deep(.el-input__prefix .el-icon),
.login-form :deep(.el-input__suffix .el-icon),
.login-form :deep(.el-input__password) {
  color: var(--signin-text-faint);
}

/*
 * 主按钮沿用 DESIGN.md 为登录页签名的 135deg 紫→青渐变 + 紫投影（这是唯一允许渐变的位置，
 * 不向控制台扩散）。hover / active 不再叠第二套渐变，只改滤镜明度，避免两处渐变值漂移。
 */
.submit {
  width: 100%;
  height: 44px;
  margin-top: 6px;
  border: none;
  border-radius: var(--signin-radius);
  /* 14px / 600 仍在字号表的正文档内，靠字重而不是加大字号撑主操作 */
  font-size: 14px;
  font-weight: 600;
  color: #fff !important;
  background: linear-gradient(135deg, #635bff 0%, #00d4ff 100%) !important;
  box-shadow: 0 8px 24px rgba(99, 91, 255, 0.32);
  transition: filter 0.15s ease, box-shadow 0.15s ease;
}

.submit:hover {
  color: #fff !important;
  filter: brightness(1.08);
  box-shadow: 0 10px 28px rgba(99, 91, 255, 0.42);
}

.submit:active {
  filter: brightness(0.94);
  box-shadow: 0 4px 14px rgba(99, 91, 255, 0.32);
}

/*
 * 键盘聚焦：先给与 hover 同档的提亮，再压一圈 2px 白描边 ——
 * 渐变底上紫环看不见，白色才是暗色世界里唯一可靠的焦点信号。
 * 不用裸 outline: none，否则键盘用户完全找不到当前按钮。
 */
.submit:focus-visible {
  color: #fff !important;
  filter: brightness(1.08);
  box-shadow: 0 10px 28px rgba(99, 91, 255, 0.42);
  outline: 2px solid rgba(255, 255, 255, 0.9);
  outline-offset: 2px;
}

/*
 * loading / 双击禁用期间仍保持渐变底与白字：Element Plus 的 is-disabled 会把按钮刷成
 * 浅灰底，在暗色玻璃卡上等于凭空多出一块白斑。只降不透明度表示不可点。
 */
.submit.is-loading,
.submit.is-loading:hover,
.submit.is-disabled,
.submit.is-disabled:hover {
  color: #fff !important;
  background: linear-gradient(135deg, #635bff 0%, #00d4ff 100%) !important;
  opacity: 0.78;
  filter: none;
  border-color: transparent !important;
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

/* 尊重系统减弱动效：入场位移直接取消，只留静态终态 */
@media (prefers-reduced-motion: reduce) {
  .login-shell {
    animation: none;
  }

  .submit {
    transition: none;
  }
}
</style>
