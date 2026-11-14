import http from '@/utils/http'

export interface TrainingDocument {
  documentId: string
  version: number
  fileName: string
  fileSize: number
  indexState: 'ready' | 'pending' | 'failed'
  indexError?: string
  graphState: 'ready' | 'pending' | 'failed'
  graphError?: string
  updatedAt: string
}

const base = '/training/documents'

export interface DocumentSource {
  documentId: string
  version: number
  fileName: string
  chunk: number
  page?: number
  text: string
}

export interface DocumentAnswer {
  workflow?: { route: 'answered' | 'unverified' | 'no_match', documents: number, candidates: number, citations: number }
  answer: string
  sources: DocumentSource[]
}

export interface GraphNode {
  id: string
  name: string
  kind: string
}

export interface GraphEdge {
  id: number
  source: string
  target: string
  predicate: string
  chunk?: number
  page?: number
  evidence: string
  documentId?: string
  version?: number
  fileName?: string
}

export interface GraphPath {
  from: string
  to: string
  predicate: string
}

export interface GraphAnswer {
  answer: string
  sources: DocumentSource[]
  nodes: GraphNode[]
  edges: GraphEdge[]
  path: GraphPath[]
  workflow?: { route: 'answered' | 'unverified' | 'no_match', documents: number, nodes: number, edges: number }
}

export interface DocumentGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export const askDocument = (doc: TrainingDocument, question: string) =>
  http.post<DocumentAnswer>(`${base}/${doc.documentId}/ask`, { version: doc.version, question }, { timeout: 225000 })

export const askLibrary = (question: string) =>
  http.post<DocumentAnswer>(`${base}/ask`, { question }, { timeout: 225000 })

export const askGraph = (question: string) =>
  http.post<GraphAnswer>('/training/graph/ask', { question }, { timeout: 240000 })

export const fetchDocumentGraph = (doc: TrainingDocument) =>
  http.get<DocumentGraph>(`/training/graph/${doc.documentId}`, { version: doc.version }, { timeout: 60000 })

export const retryIndex = (doc: TrainingDocument) =>
  http.post(`${base}/${doc.documentId}/index`, undefined, { params: { version: doc.version } })

export const listDocuments = () => http.get<TrainingDocument[]>(base)

export function saveDocument(file: File, original?: TrainingDocument) {
  const form = new FormData()
  form.append('file', file)
  if (original) {
    return http.put(`${base}/${original.documentId}`, form, { params: { version: original.version } })
  }
  return http.post<string>(base, form)
}

export const downloadDocument = (doc: TrainingDocument) =>
  http.download(`${base}/${doc.documentId}/content`, { version: doc.version })

export const deleteDocument = (doc: TrainingDocument) =>
  http.del(`${base}/${doc.documentId}`, undefined, { params: { version: doc.version } })
