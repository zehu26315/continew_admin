<template>
  <a-card title="我的资料" class="general-card">
    <template #extra>
      <a-space>
        <a-button size="small" :disabled="!documents.some((doc) => doc.indexState === 'ready')" @click="libraryOpen = true">资料库问答</a-button>
        <a-button size="small" :disabled="busy" @click="refresh()">刷新</a-button>
        <a-button type="primary" size="small" :disabled="busy" @click="chooseFile()">上传资料</a-button>
      </a-space>
    </template>
    <input ref="picker" type="file" hidden accept=".txt,.pdf,.docx" @change="onFile" />
    <p class="hint">
      仅自己可见 · PDF、DOCX 或 TXT · 每份最多 5 MB。上传后会自动建立索引。
      <a :href="sampleUrl" download="工程实训器材借用指南.txt">下载示例 TXT</a>
    </p>
    <a-table
      :data="documents" row-key="documentId" :loading="busy" :scroll="{ x: 740 }"
      :pagination="{ pageSize: 8, hideOnSinglePage: true }"
    >
      <template #columns>
        <a-table-column title="资料名称" data-index="fileName" ellipsis tooltip />
        <a-table-column title="版本" :width="62">
          <template #cell="{ record }">v{{ record.version }}</template>
        </a-table-column>
        <a-table-column title="问答索引" :width="145">
          <template #cell="{ record }">
            <a-space size="mini">
              <a-tooltip :content="record.indexError || statusText[record.indexState as TrainingDocument['indexState']]">
                <a-tag :color="record.indexState === 'failed' ? 'red' : record.indexState === 'ready' ? 'green' : 'blue'">
                  {{ statusText[record.indexState as TrainingDocument['indexState']] }}
                </a-tag>
              </a-tooltip>
              <a-link v-if="record.indexState !== 'pending'" :disabled="busy" @click="reindex(record)">
                {{ record.indexState === 'failed' ? '重试' : '重建' }}
              </a-link>
            </a-space>
          </template>
        </a-table-column>
        <a-table-column title="图谱" :width="92">
          <template #cell="{ record }">
            <a-tooltip :content="record.graphError || statusText[record.graphState as TrainingDocument['graphState']]">
              <a-tag :color="record.graphState === 'failed' ? 'red' : record.graphState === 'ready' ? 'green' : 'blue'">
                {{ statusText[record.graphState as TrainingDocument['graphState']] }}
              </a-tag>
            </a-tooltip>
          </template>
        </a-table-column>
        <a-table-column title="更新时间" :width="148">
          <template #cell="{ record }">{{ dayjs(record.updatedAt).format('YYYY-MM-DD HH:mm') }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="280">
          <template #cell="{ record }">
            <a-space size="mini">
              <a-link :disabled="busy" @click="preview(record)">预览</a-link>
              <a-link :disabled="busy || record.indexState !== 'ready'" @click="questionDoc = record">问答</a-link>
              <a-link :disabled="busy || record.graphState !== 'ready'" @click="openGraph(record)">图谱</a-link>
              <a-link :disabled="busy" @click="useDownload(() => downloadDocument(record), record.fileName)">下载</a-link>
              <a-link :disabled="busy" @click="chooseFile(record)">替换</a-link>
              <a-popconfirm :content="`删除《${record.fileName}》？`" @ok="remove(record)">
                <a-link status="danger" :disabled="busy">删除</a-link>
              </a-popconfirm>
            </a-space>
          </template>
        </a-table-column>
      </template>
      <template #empty>
        <a-empty :description="loadFailed ? '资料加载失败，请刷新重试' : '还没有资料，点击「上传资料」开始'" />
      </template>
    </a-table>
    <component :is="officePreviewComponent" v-if="officePreviewComponent" ref="officePreview" />
    <DocumentQuestion v-if="libraryOpen" @close="libraryOpen = false" />
    <DocumentQuestion v-if="questionDoc" :doc="questionDoc" @close="questionDoc = undefined" />
    <a-modal v-model:visible="graphOpen" :title="graphTitle" :width="720" :footer="false" @cancel="closeGraph">
      <GraphCanvas :nodes="graphNodes" :edges="graphEdges" height="380px" @pick="graphPicked = $event" />
      <p v-if="graphPicked" class="hint">点选关系：{{ graphPicked.predicate }} · 依据：{{ graphPicked.evidence }}</p>
      <p v-else class="hint">点击图中的边可以查看支持该关系的原文片段。</p>
    </a-modal>
    <a-modal v-model:visible="showText" :title="textTitle" :footer="false" @close="text = ''">
      <pre class="text-preview">{{ text }}</pre>
    </a-modal>
  </a-card>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import { Message } from '@arco-design/web-vue'
import DocumentQuestion from './DocumentQuestion.vue'
import GraphCanvas from './GraphCanvas.vue'
import type FilePreview from '@/components/FilePreview/index.vue'
import { useDownload } from '@/hooks'
import { type GraphEdge, type GraphNode, type TrainingDocument, deleteDocument, downloadDocument, fetchDocumentGraph, listDocuments, retryIndex, saveDocument } from '@/apis/training/documents'

const documents = ref<TrainingDocument[]>([])
const libraryOpen = ref(false)
const statusText = { ready: '可问答', pending: '建立中', failed: '失败' }
const questionDoc = ref<TrainingDocument>()
const graphOpen = ref(false)
const graphTitle = ref('')
const graphNodes = ref<GraphNode[]>([])
const graphEdges = ref<GraphEdge[]>([])
const graphPicked = ref<GraphEdge>()
const sampleUrl = `${import.meta.env.BASE_URL}training/lab-guide.txt`
const picker = ref<HTMLInputElement>()
const replacement = ref<TrainingDocument>()
const busy = ref(false)
const loadFailed = ref(false)
const officePreview = ref<InstanceType<typeof FilePreview>>()
const officePreviewComponent = shallowRef<typeof FilePreview>()
const showText = ref(false)
const textTitle = ref('')
const text = ref('')

let poll: ReturnType<typeof setTimeout> | undefined
let disposed = false
let requestNumber = 0

async function refresh(silent = false) {
  const sequence = ++requestNumber
  clearTimeout(poll)
  if (!silent) busy.value = true
  try {
    const rows = (await listDocuments()).data
    if (disposed || sequence !== requestNumber) return
    documents.value = rows
    if (rows.some((doc) => doc.indexState === 'pending' || doc.graphState === 'pending')) poll = setTimeout(() => refresh(true), 2500)
    loadFailed.value = false
  } catch {
    if (disposed || sequence !== requestNumber) return
    documents.value = []
    loadFailed.value = true
  } finally {
    if (sequence === requestNumber) busy.value = false
  }
}

async function reindex(doc: TrainingDocument) {
  await retryIndex(doc)
  await refresh()
}

function chooseFile(doc?: TrainingDocument) {
  replacement.value = doc
  picker.value?.click()
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.(pdf|docx|txt)$/i.test(file.name) || !file.size || file.size > 5 * 1024 * 1024) {
    Message.warning('请选择不超过 5 MB 的非空 PDF、DOCX 或 TXT 文件')
    return
  }
  busy.value = true
  try {
    await saveDocument(file, replacement.value)
    Message.success(replacement.value ? '资料已更新' : '资料已上传')
  } finally {
    await refresh()
  }
}

async function remove(doc: TrainingDocument) {
  busy.value = true
  try {
    await deleteDocument(doc)
    Message.success('资料已删除')
  } finally {
    await refresh()
  }
}

async function preview(doc: TrainingDocument) {
  busy.value = true
  try {
    const blob: Blob = (await downloadDocument(doc)).data
    const fileType = doc.fileName.split('.').pop()?.toLowerCase()
    if (fileType === 'txt') {
      text.value = await blob.text()
      textTitle.value = doc.fileName
      showText.value = true
    } else {
      officePreviewComponent.value ??= (await import('@/components/FilePreview/index.vue')).default
      await nextTick()
      officePreview.value?.onPreview({ fileInfo: { fileType, fileName: doc.fileName, data: blob } })
    }
  } finally {
    busy.value = false
  }
}

async function openGraph(doc: TrainingDocument) {
  graphTitle.value = `《${doc.fileName}》 · v${doc.version} 图谱`
  graphNodes.value = []
  graphEdges.value = []
  graphPicked.value = undefined
  graphOpen.value = true
  try {
    const data = (await fetchDocumentGraph(doc)).data
    graphNodes.value = data.nodes
    graphEdges.value = data.edges
  } catch {
    Message.warning('图谱暂不可用，请稍后重试')
  }
}

function closeGraph() {
  graphOpen.value = false
  graphNodes.value = []
  graphEdges.value = []
  graphPicked.value = undefined
}

onMounted(() => refresh())
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(poll)
})
</script>

<style scoped>
.hint { margin: 0 0 16px; color: var(--color-text-3); }
.text-preview { max-height: 60vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
