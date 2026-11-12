// admin-api(/admin)와 mock-servers 데모 API(/mock → /_mock)를 부른다. 개발 서버 프록시가 각 서버로 보낸다.

export type Counts = Record<string, number>;

export interface Overview {
  at: string;
  orderLines: Counts;
  openClaims: Counts;
  statusApplied: number;
  processedEvents: Counts;
  openConflicts: Counts;
  openUnlinked: number;
  openRecalls: number;
  cancelCases: Counts;
  shipments: Counts;
  invoicePending: number;
  invoiceSynced: number;
  alertsUnnotified: number;
  notifications: number;
  queue: { visible: number; inFlight: number; delayed: number };
  dlq: { visible: number; inFlight: number; delayed: number };
  lastReconciliation: { ranAt: string; compared: number; mismatches: number } | null;
}

export interface ReconciliationRun {
  ranAt: string;
  compared: number;
  mismatches: number;
}

export interface MockState {
  shopby: { orderOptions: number; byStatus: Counts; claims: Counts; holdDelivery: number };
  tpl: Counts;
  webhooks: { sent: number; dropped: number };
  shopbyApi: { maxCallsInAnySecond: number; rejected429: number };
}

export interface UnlinkedRow {
  id: number;
  orderProductOptionNo: number;
  orderNo: string | null;
  productName: string | null;
  optionName: string | null;
  optionValue: string | null;
  reasonCode: string;
  detail: string | null;
  detectedAt: string;
  resolvedAt: string | null;
  resolver: string | null;
}

export interface ConflictRow {
  id: number;
  targetKey: string;
  reasonCode: string;
  detail: string | null;
  detectedAt: string;
  resolvedAt: string | null;
}

export interface RecallRow {
  id: number;
  orderProductOptionNo: number;
  claimNo: number;
  invoiceNo: string | null;
  reason: string;
  createdAt: string;
  resolvedAt: string | null;
}

export interface HistoryRow {
  requestId: string;
  actor: string;
  action: string;
  targetKey: string;
  status: string;
  result: string | null;
  detail: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface ReprocessOutcome {
  duplicateRequest: boolean;
  status: string;
  result: string | null;
  detail: string | null;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** okStatuses: 2xx가 아니어도 본문을 결과로 읽을 상태 코드 (재처리 FAILED는 503 + 결과 본문) */
async function request<T>(url: string, init?: RequestInit, okStatuses: number[] = []): Promise<T> {
  const res = await fetch(url, init);
  const text = await res.text();
  if (!res.ok && !(okStatuses.includes(res.status) && text)) {
    throw new ApiError(res.status, text || res.statusText);
  }
  return (text ? JSON.parse(text) : {}) as T;
}

const post = <T>(url: string, body?: unknown, headers?: Record<string, string>) =>
  request<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

export const api = {
  overview: () => request<Overview>('/admin/overview'),
  reconciliationRuns: (limit = 120) => request<ReconciliationRun[]>(`/admin/reconciliation/runs?limit=${limit}`),
  unlinked: (status?: string) => request<UnlinkedRow[]>(`/admin/unlinked${status ? `?status=${status}` : ''}`),
  conflicts: (status?: string) => request<ConflictRow[]>(`/admin/conflicts${status ? `?status=${status}` : ''}`),
  recalls: (status?: string) => request<RecallRow[]>(`/admin/recalls${status ? `?status=${status}` : ''}`),
  history: (targetKey?: string) =>
    request<HistoryRow[]>(`/admin/history${targetKey ? `?targetKey=${encodeURIComponent(targetKey)}` : ''}`),
  reprocess: (id: number, requestId: string, actor: string) =>
    request<ReprocessOutcome>(
      `/admin/unlinked/${id}/reprocess`,
      { method: 'POST', headers: { 'Idempotency-Key': requestId, 'X-Actor': actor } },
      [503],
    ),
  // 모의 쇼핑몰을 먼저 비운다. 반대로 하면 그 사이 대사가 "쇼핑몰에만 있는 주문"을 불일치로 센다
  resetDemo: async () => {
    await post('/mock/demo/reset', {});
    await post('/admin/demo/reset', {});
  },
  mockState: () => request<MockState>('/mock/demo/state'),
  createOrders: (p: { count: number; dropRatio: number; duplicates: number; unmappedRatio: number }) =>
    post<{ created: number; unmapped: number; webhooksDropped: number }>('/mock/demo/orders', {
      ...p,
      recordDroppedAsFailed: true,
    }),
  injectRateLimit: (next429: number, nextExhausted: number) => post('/mock/demo/faults', { next429, nextExhausted }),
  advanceTpl: (from: string, to: string) => post<{ advanced: number }>('/mock/demo/tpl/advance', { from, to }),
  cancel: (count: number, tplStatus: string) =>
    post<{ cancelRequested: number }>('/mock/demo/cancel', { count, tplStatus }),
  fixMapping: () => post('/mock/demo/mapping/fix'),
};
