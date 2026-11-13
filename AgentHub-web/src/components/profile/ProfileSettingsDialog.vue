<template>
  <!--
    个人设置弹窗：上传头像 + 改显示名。
    从侧栏左下角用户菜单进入；与 Agent 形象配置彻底分开。
  -->
  <el-dialog
    :model-value="modelValue"
    title="个人设置"
    width="480px"
    destroy-on-close
    @close="onClose"
  >
    <el-form label-width="88px" class="profile-form" @submit.prevent>
      <el-form-item label="头像">
        <div class="avatar-row">
          <div class="avatar-preview" :style="previewStyle">
            <img v-if="previewUrl" :src="previewUrl" alt="头像" />
            <span v-else>{{ previewInitial }}</span>
          </div>
          <div class="avatar-actions">
            <el-upload
              :show-file-list="false"
              :auto-upload="false"
              accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
              :disabled="uploading"
              @change="onFileChange"
            >
              <el-button type="primary" plain :loading="uploading">上传头像</el-button>
            </el-upload>
            <el-button
              v-if="previewUrl"
              plain
              :disabled="uploading || saving"
              @click="onClearAvatar"
            >
              清除
            </el-button>
            <p class="hint">支持 jpg / png / webp，不超过 2MB</p>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="显示名" required>
        <el-input v-model="nickname" maxlength="64" placeholder="侧栏与聊天气泡展示的名称" />
      </el-form-item>
      <el-form-item label="登录名">
        <el-input :model-value="username" disabled />
      </el-form-item>
      <el-form-item v-if="email" label="邮箱">
        <el-input :model-value="email" disabled />
      </el-form-item>
      <el-form-item v-if="phone" label="手机">
        <el-input :model-value="phone" disabled />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="onClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存显示名</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 个人设置弹窗。所属域：auth（前端）。
 * 上传头像即时生效；显示名点保存后写库并刷新 /me。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { clearAvatar, fetchProfile, updateProfile, uploadAvatar } from '@/api/profile'
import {
  USER_AVATAR_FALLBACK_COLOR,
  initialOf,
  resolveAvatarUrl
} from '@/constants/agentLooks'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const user = useUserStore()
const nickname = ref('')
const username = ref('')
const email = ref('')
const phone = ref('')
const avatarUrl = ref<string | null>(null)
const uploading = ref(false)
const saving = ref(false)

const previewUrl = computed(() => resolveAvatarUrl(avatarUrl.value))
const previewInitial = computed(() => initialOf(nickname.value || username.value || '用户'))
const previewStyle = computed(() =>
  previewUrl.value
    ? undefined
    : { background: USER_AVATAR_FALLBACK_COLOR, color: '#fff' }
)

/**
 * 打开时拉本人资料；关闭不自动清 store。
 */
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) {
      return
    }
    try {
      const { data } = await fetchProfile()
      const profile = data.data
      nickname.value = profile.nickname || profile.username || ''
      username.value = profile.username || ''
      email.value = profile.email || ''
      phone.value = profile.phone || ''
      avatarUrl.value = profile.avatarUrl || null
    } catch {
      // 全局拦截已提示；用 /me 兜底，避免弹窗空白
      nickname.value = user.profile?.nickname || user.profile?.username || ''
      username.value = user.profile?.username || ''
      avatarUrl.value = user.profile?.avatarUrl || null
    }
  }
)

/** 关闭弹窗 */
function onClose() {
  emit('update:modelValue', false)
}

/**
 * 本地选图后立刻上传；超限 / 类型错误给中文提示。
 *
 * @param file 上传组件文件包装
 */
async function onFileChange(file: UploadFile) {
  const raw = file.raw
  if (!raw) {
    return
  }
  const name = (raw.name || '').toLowerCase()
  const okExt = /\.(jpe?g|png|webp)$/.test(name)
  const okType = /^image\/(jpeg|jpg|png|webp)$/i.test(raw.type || '')
  if (!okExt && !okType) {
    ElMessage.error('仅支持 jpg / png / webp 图片')
    return
  }
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.error('头像不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const { data } = await uploadAvatar(raw)
    avatarUrl.value = data.data.avatarUrl
    // 立刻刷新 /me，侧栏与工作台用户气泡同步
    await user.loadMe()
    ElMessage.success('头像已更新')
  } finally {
    uploading.value = false
  }
}

/** 清除头像并刷新会话资料 */
async function onClearAvatar() {
  uploading.value = true
  try {
    const { data } = await clearAvatar()
    avatarUrl.value = data.data.avatarUrl || null
    await user.loadMe()
    ElMessage.success('已清除头像')
  } finally {
    uploading.value = false
  }
}

/** 保存显示名 */
async function onSave() {
  const next = nickname.value.trim()
  if (!next) {
    ElMessage.error('显示名不能为空')
    return
  }
  saving.value = true
  try {
    const { data } = await updateProfile(next)
    nickname.value = data.data.nickname || next
    await user.loadMe()
    ElMessage.success('显示名已保存')
    onClose()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-form {
  padding-top: 4px;
}

.avatar-row {
  display: flex;
  gap: 16px;
  align-items: center;
}

.avatar-preview {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  overflow: hidden;
  border-radius: 50%;
  background: #e4e2ff;
  color: #635bff;
  font-size: 22px;
  font-weight: 650;
  flex: none;
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.hint {
  margin: 0;
  width: 100%;
  color: var(--ah-text-secondary, #909399);
  font-size: 12px;
  line-height: 1.4;
}
</style>
