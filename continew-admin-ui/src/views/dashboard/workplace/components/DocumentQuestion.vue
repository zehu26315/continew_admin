<template>
  <a-modal visible :title="mode === 'graph' ? '图谱问答' : '资料问答'" :width="720" :footer="false" @cancel="emit('close')">
    <a-radio-group v-model="mode" type="button" size="small" @change="reset">
      <a-radio value="rag">向量检索（RAG）</a-radio>
      <a-radio value="graph">图谱检索（GraphRAG）</a-radio>
    </a-radio-group>
    <p v-if="doc" class="scope">《{{ doc.fileName }}》 · v{{ doc.version }} · 仅搜索这份资料</p>
    <p v-else class="scope">{{ mode === 'graph' ? '检索我的全部已就绪资料图谱' : '搜索我的全部已就绪资料' }}</p>
    <a-textarea v-model="question" placeholder="这份资料里，你想了解什么？" :max-length="500" show-word-limit :auto-size="{ minRows: 2, maxRows: 4 }" />
    <a-button class="send" type="primary" :loading="busy" :disabled="!question.trim()" @click="ask">提问</a-button>
    <p v-if="busy" role="status">{{ mode === 'graph' ? '正在沿实体与关系检索并生成回答…' : '正在检索原文并生成回答…' }}</p>
    <a-alert v-else-if="failed" type="error">本次未取得答案，请查看提示后重试。</a-alert>
    <section v-else-if="answer" aria-live="polite">
      <strong>回答</strong>
      <p>{{ answer.answer }}</p>
      <a-alert v-if="answer.workflow && answer.workflow.route !== 'answered'" type="info">
        {{ answer.workflow.route === 'no_match' ? '没有可用的图谱路径。可以换用资料里的设备名、编号或关键词再问。' : '命中关系，但无法从中核实答案。请补充写明所问事项的资料。' }}
      </a-alert>
      <template v-if="graph">
        <GraphCanvas :nodes="graph.nodes" :edges="graph.edges" height="300px" @pick="picked = $event" />
        <p v-if="picked" class="picked">点选关系：{{ picked.predicate }} · 依据：{{ picked.evidence }}</p>
        <p v-else class="picked">点击图中的边可以查看支持该关系的原文。</p>
      </template>
      <blockquote v-for="source in answer.sources" :key="`${source.documentId}-${source.chunk}`">
        <small>《{{ source.fileName }}》 · v{{ source.version }} · <template v-if="source.page">第 {{ source.page }} 页 · </template>第 {{ source.chunk }} 段</small>
        <p>{{ source.text }}</p>
      </blockquote>
      <details v-if="answer.workflow">
        <summary>查看本次问答流程</summary>
        <ol v-if="mode === 'rag'">
          <li>在当前账号有权查阅的 {{ answer.workflow.documents }} 份已就绪资料中检索。</li>
          <li>保留 {{ answer.workflow.candidates }} 个相关片段。</li>
          <li v-if="answer.workflow.route === 'no_match'">检索结束，跳过答案生成。</li>
          <li v-else>调用本地模型生成答案，核对原文后返回 {{ answer.workflow.citations }} 条引用。</li>
        </ol>
        <ol v-else>
          <li>在 {{ answer.workflow.documents }} 份资料图谱中按实体名命中起点。</li>
          <li>沿关系扩展两跳，收集 {{ answer.workflow.nodes }} 个节点、{{ answer.workflow.edges }} 条关系。</li>
          <li v-if="answer.workflow.route === 'no_match'">未命中任何关系，跳过答案生成。</li>
          <li v-else>用关系证据生成答案，点击边可核对原文。</li>
        </ol>
      </details>
    </section>
  </a-modal>
</template>

<script setup lang="ts">
import GraphCanvas from './GraphCanvas.vue'
import { type DocumentAnswer, type GraphAnswer, type GraphEdge, type TrainingDocument, askDocument, askGraph, askLibrary } from '@/apis/training/documents'

defineOptions({ name: 'DocumentQuestion' })
const props = defineProps<{ doc?: TrainingDocument }>()
const emit = defineEmits<{ close: [] }>()
const mode = ref<'rag' | 'graph'>('rag')
const question = ref('')
const busy = ref(false)
const failed = ref(false)
const answer = ref<DocumentAnswer | GraphAnswer>()
const graph = computed(() => (mode.value === 'graph' ? (answer.value as GraphAnswer | undefined) : undefined))
const picked = ref<GraphEdge>()

function reset() {
  answer.value = undefined
  failed.value = false
  picked.value = undefined
}

async function ask() {
  if (busy.value || !question.value.trim()) return
  busy.value = true
  failed.value = false
  answer.value = undefined
  picked.value = undefined
  try {
    const input = question.value.trim()
    if (mode.value === 'graph') {
      answer.value = (await askGraph(input)).data
    } else {
      answer.value = props.doc ? (await askDocument(props.doc, input)).data : (await askLibrary(input)).data
    }
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.scope, small { color: var(--color-text-3); }
.send { margin: 12px 0; }
details { margin-top: 12px; color: var(--color-text-2); }
summary { cursor: pointer; }
blockquote { margin: 10px 0; padding: 10px 14px; background: var(--color-fill-2); border-left: 3px solid rgb(var(--primary-6)); }
section p { white-space: pre-wrap; overflow-wrap: anywhere; }
.picked { color: var(--color-text-3); }
</style>
