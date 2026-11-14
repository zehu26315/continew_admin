<template>
  <div ref="host" class="graph-canvas" :style="{ height }" />
</template>

<script setup lang="ts">
import * as echarts from 'echarts'
import type { GraphEdge, GraphNode } from '@/apis/training/documents'

defineOptions({ name: 'GraphCanvas' })
const props = withDefaults(defineProps<{ nodes: GraphNode[], edges: GraphEdge[], height?: string }>(), {
  height: '320px',
})
const emit = defineEmits<{ pick: [edge: GraphEdge] }>()

const host = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!chart) return
  const kinds = [...new Set(props.nodes.map((node) => node.kind))]
  chart.setOption({
    tooltip: {
      formatter: (info: any) => {
        if (info.dataType === 'edge') return `${info.data.predicate}<br/>${info.data.evidence ?? ''}`
        return `${info.data.kind}：${info.data.name}`
      },
    },
    legend: { data: kinds, bottom: 0 },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      label: { show: true, fontSize: 11 },
      force: { repulsion: 220, edgeLength: 90 },
      categories: kinds.map((name) => ({ name })),
      edgeSymbol: ['none', 'arrow'],
      edgeLabel: { show: true, fontSize: 10, formatter: (info: any) => info.data.predicate },
      data: props.nodes.map((node) => ({ id: node.id, name: node.name, kind: node.kind, category: kinds.indexOf(node.kind) })),
      links: props.edges.map((edge) => ({
        source: edge.source,
        target: edge.target,
        predicate: edge.predicate,
        evidence: edge.evidence,
      })),
    }],
  }, true)
}

onMounted(() => {
  if (!host.value) return
  chart = echarts.init(host.value)
  render()
  chart.on('click', (params: any) => {
    if (params.dataType === 'edge') {
      const hit = props.edges.find((edge) => edge.predicate === params.data.predicate
        && edge.evidence === params.data.evidence)
      if (hit) emit('pick', hit)
    }
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(host.value)
})

watch(() => [props.nodes, props.edges], render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = undefined
})
</script>

<style scoped>
.graph-canvas { width: 100%; border: 1px solid var(--color-border-2); border-radius: 4px; }
</style>
