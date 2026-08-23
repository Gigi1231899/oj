<template>
  <div class="assistant-page">
    <div class="assistant-header">
      <el-button text @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        返回题目
      </el-button>
      <span class="header-title">🤖 编码助手 · 题目 #{{ pid }}</span>
      <span></span>
    </div>

    <div class="chat-container">
      <div class="chat-messages" ref="chatMessagesRef">
        <div v-for="(msg, idx) in chatMessages" :key="idx" class="chat-msg" :class="msg.role">
          <template v-if="msg.role === 'user'">
            <div class="msg-bubble user-bubble">{{ msg.text }}</div>
          </template>
          <template v-else>
            <div class="msg-bubble agent-bubble">
              <div v-if="msg.toolCall" class="tool-card">
                <details>
                  <summary>🔧 调用工具: {{ msg.toolCall }}</summary>
                  <pre class="tool-output">{{ msg.toolResult }}</pre>
                </details>
              </div>
              <div class="msg-text" v-html="renderMd(msg.text)"></div>
              <span v-if="msg.streaming" class="cursor-blink">|</span>
            </div>
          </template>
        </div>
        <div v-if="chatLoading && chatMessages.length === 0" class="chat-placeholder">
          <p class="placeholder-title">AI 正在分析你的代码…</p>
          <p class="placeholder-sub">首次分析会自动获取题目信息和判题结果，请稍候</p>
        </div>
      </div>

      <div class="chat-input-row">
        <el-input
          v-model="chatInput"
          placeholder="输入问题，如：这段代码哪里有问题？复杂度是多少？"
          @keyup.enter="sendChat"
          :disabled="chatLoading"
          size="large"
        />
        <el-button type="primary" size="large" @click="sendChat" :disabled="chatLoading" round>
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElInput, ElButton, ElIcon, ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/userStore'
import { renderMarkdown } from '@/utils/markdown'
import { reqProblemDetail } from '@/api/problem'
import { reqSubmissionDetail } from '@/api/submission'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const pid = Number(route.params.id)

const renderMd = (text: string) => renderMarkdown(text)

const chatOpen = ref(false)
const chatLoading = ref(false)
const chatInput = ref('')
const chatMessages = ref<Array<{
  role: string; text: string; streaming?: boolean
  toolCall?: string; toolResult?: string
}>>([])
const chatMessagesRef = ref<HTMLElement | null>(null)
// 当前题目下的编辑器代码，随每次提问一起传给 Agent（首次由提交详情取到）
let currentCode = ''
// sessionId 按题目持久化：同一道题反复进入/刷新页面，Redis 里的会话历史能续上
const sessionId = ref(
  localStorage.getItem(`ai_session_${pid}`) || Date.now().toString(36)
)
watch(sessionId, (v) => localStorage.setItem(`ai_session_${pid}`, v), { immediate: true })

const scrollChat = async () => {
  await nextTick()
  const el = chatMessagesRef.value
  if (el) el.scrollTop = el.scrollHeight
}

const goBack = () => {
  router.push(`/problem/${pid}`)
}

const sendChatAnalysis = async () => {
  if (chatLoading.value) return
  chatLoading.value = true
  chatMessages.value = []

  // 构建上下文：题目 + 判题结果 + 代码
  let contextMsg = `当前题目 ID: ${pid}`

  // 1. 获取题目详情
  try {
    const p = await reqProblemDetail(String(pid))
    const d = p.data.data
    if (d) {
      contextMsg = [
        `当前题目: #${pid} ${d.name || ''}`,
        `难度: ${d.difficulty || '未知'}`,
        `时间限制: ${d.timeLimit || '?'} ms，内存限制: ${d.memoryLimit || '?'} MB`,
        `题目描述: ${(d.description || '').substring(0, 500)}`,
      ].join('\n')
    }
  } catch { /* ignore */ }

  // 2. 获取提交详情（含代码）
  const submissionId = Number(route.query.submissionId)
  if (submissionId) {
    try {
      const res = await reqSubmissionDetail(submissionId)
      const s = res.data?.data?.list?.[0]
      if (s) {
        currentCode = s.code || ''
        const timeDisplay = s.time != null ? `${(s.time * 1000).toFixed(2)} ms` : 'N/A'
        const memDisplay = s.memory != null ? `${(s.memory).toFixed(2)} KB` : 'N/A'
        contextMsg += [
          `\n提交 #${s.id} 判题结果:`,
          `状态: ${s.status || '未知'}`,
          `耗时: ${timeDisplay}`,
          `内存: ${memDisplay}`,
          `信息: ${s.message || '(无)'}`,
          `语言: ${s.language || '未知'}`,
          `代码:\n\`\`\`\n${s.code || '(未获取到)'}\n\`\`\``,
        ].join('\n')
      }
    } catch { /* ignore */ }
  }

  const userMsg = '请帮我分析这道题的代码，包括正确性、性能和优化建议'
  chatMessages.value.push({ role: 'user', text: userMsg })
  chatMessages.value.push({ role: 'agent', text: '', streaming: true })
  await scrollChat()

  const token = userStore.userInfo?.accessToken || ''
  const lastMsg = chatMessages.value[chatMessages.value.length - 1]

  try {
    const resp = await fetch(`${import.meta.env.VITE_APP_URL}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token,
      },
      body: JSON.stringify({
        session_id: sessionId.value,
        message: `${contextMsg}\n\n${userMsg}`,
        problem_id: pid,
      }),
    })

    const reader = resp.body?.getReader()
    if (!reader) { chatLoading.value = false; return }
    const decoder = new TextDecoder()
    let buf = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const evt = JSON.parse(line.substring(5).trim())
            if (evt.type === 'text') {
              lastMsg.text += evt.content
            } else if (evt.type === 'tool_call') {
              lastMsg.toolCall = `${evt.tool}(${evt.input})`
            } else if (evt.type === 'tool_result') {
              lastMsg.toolResult = evt.output
            } else if (evt.type === 'final') {
              lastMsg.text = evt.text
              lastMsg.streaming = false
            }
          } catch { /* partial */ }
        }
      }
      await scrollChat()
    }
    lastMsg.streaming = false
  } catch (e) {
    lastMsg.text = 'AI 服务暂时不可用，请稍后重试'
    lastMsg.streaming = false
  }
  chatLoading.value = false
  await scrollChat()
}

const sendChat = async () => {
  if (chatLoading.value || !chatInput.value.trim()) return
  const msg = chatInput.value.trim()
  chatInput.value = ''
  chatMessages.value.push({ role: 'user', text: msg })
  chatMessages.value.push({ role: 'agent', text: '', streaming: true })
  chatLoading.value = true
  await scrollChat()

  const token = userStore.userInfo?.accessToken || ''
  const lastMsg = chatMessages.value[chatMessages.value.length - 1]

  try {
    const resp = await fetch(`${import.meta.env.VITE_APP_URL}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token,
      },
      body: JSON.stringify({
        session_id: sessionId.value,
        message: msg,
        problem_id: pid,
        code: currentCode,
      }),
    })

    const reader = resp.body?.getReader()
    if (!reader) { chatLoading.value = false; return }
    const decoder = new TextDecoder()
    let buf = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const evt = JSON.parse(line.substring(5).trim())
            if (evt.type === 'text') {
              lastMsg.text += evt.content
            } else if (evt.type === 'tool_call') {
              lastMsg.toolCall = `${evt.tool}(${evt.input})`
            } else if (evt.type === 'tool_result') {
              lastMsg.toolResult = evt.output
            } else if (evt.type === 'final') {
              lastMsg.text = evt.text
              lastMsg.streaming = false
            }
          } catch { /* partial */ }
        }
      }
      await scrollChat()
    }
    lastMsg.streaming = false
  } catch (e) {
    lastMsg.text = 'AI 服务暂时不可用，请稍后重试'
    lastMsg.streaming = false
  }
  chatLoading.value = false
  await scrollChat()
}

onMounted(() => {
  sendChatAnalysis()
})
</script>

<style scoped lang="scss">
.assistant-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
}

.assistant-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.6em 2em;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-elevated);
  flex-shrink: 0;

  .header-title {
    font-weight: 600;
    font-size: 105%;
  }
}

.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
  padding: 0 2em;
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 1.5em 0;
  display: flex;
  flex-direction: column;
  gap: 1em;
}

.chat-msg {
  display: flex;
  &.user { justify-content: flex-end; }
  &.agent { justify-content: flex-start; }
}

.msg-bubble {
  max-width: 88%;
  padding: 0.75em 1em;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;

  :deep(p) { margin: 0.4em 0; }
  :deep(pre.md-code) {
    background: #1e1e1e;
    color: #d4d4d4;
    padding: 0.75em 1em;
    border-radius: 6px;
    overflow-x: auto;
    font-size: 13px;
    line-height: 1.5;
    margin: 0.5em 0;
  }
  :deep(code.md-inline) {
    background: rgba(127,127,127,0.15);
    padding: 1px 5px;
    border-radius: 3px;
    font-size: 90%;
  }
  :deep(h4), :deep(h5) { margin: 0.6em 0 0.3em; font-size: 108%; }
  :deep(li) { margin-left: 1.4em; }
  :deep(strong) { font-weight: 600; }
  :deep(table) {
    border-collapse: collapse; margin: 0.5em 0; font-size: 92%;
    th, td { border: 1px solid var(--border-color); padding: 3px 10px; text-align: left; }
    th { background: var(--bg-elevated); }
  }
}

.user-bubble {
  background: var(--primary-gradient);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.agent-bubble {
  background: var(--bg-elevated);
  border: 1px solid var(--border-color);
  border-bottom-left-radius: 4px;
  color: var(--text-primary);
}

.tool-card {
  margin-bottom: 0.5em;
  details {
    summary {
      cursor: pointer;
      color: #e6a23c;
      font-size: 85%;
      font-weight: 500;
    }
    .tool-output {
      margin-top: 0.4em;
      padding: 0.5em;
      background: var(--bg-primary);
      border-radius: 4px;
      font-size: 82%;
      white-space: pre-wrap;
      word-break: break-all;
      max-height: 12em;
      overflow-y: auto;
      color: var(--text-secondary);
    }
  }
}

.cursor-blink {
  animation: blink 1s infinite;
  color: var(--text-secondary);
  font-weight: bold;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.chat-placeholder {
  text-align: center;
  padding: 4em 2em;
  color: var(--text-secondary);
  .placeholder-title { font-size: 110%; font-weight: 600; margin-bottom: 0.4em; }
  .placeholder-sub { font-size: 88%; opacity: 0.7; }
}

.chat-input-row {
  display: flex;
  gap: 0.75em;
  padding: 1em 0 1.5em;
  flex-shrink: 0;
}
</style>
