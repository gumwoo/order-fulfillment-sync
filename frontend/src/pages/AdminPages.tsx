import { useCallback, useEffect, useState } from 'react';
import { api, type ConflictRow, type HistoryRow, type RecallRow, type UnlinkedRow } from '../api';
import { REASON_HELP, REASON_LABEL, RESULT_HELP, RESULT_LABEL, ago, who } from '../labels';

export type AdminTab = 'unlinked' | 'conflicts' | 'recalls' | 'history';
type StatusFilter = 'OPEN' | 'RESOLVED' | '';

const META: Record<AdminTab, { title: string; desc: string; tech: string }> = {
  unlinked: {
    title: '창고 등록 실패',
    desc: '창고가 출고 요청을 받지 못한 주문입니다. 창고의 옵션 설정을 고친 뒤 다시 처리하세요.',
    tech: '3PL 미연동 (unlinked)',
  },
  conflicts: {
    title: '확인 필요',
    desc: '시스템이 스스로 판단하지 못해 멈춘 주문입니다. 다시 시도해도 결과가 같아 사람이 확인해야 합니다.',
    tech: 'conflict',
  },
  recalls: {
    title: '회수 안내',
    desc: '창고에서 이미 꺼낸 뒤 취소된 주문입니다. 고객에게 상품 회수를 안내하세요.',
    tech: 'recall_task, 피킹 이후 취소',
  },
  history: { title: '처리 이력', desc: '누가 언제 어떤 주문을 다시 처리했는지 남긴 기록입니다.', tech: 'admin_action' },
};

const STATUS_OPTIONS: { value: StatusFilter; label: string }[] = [
  { value: 'OPEN', label: '미처리' },
  { value: 'RESOLVED', label: '처리됨' },
  { value: '', label: '전체' },
];

const ACTOR_KEY = 'ofs.actor';
const ASCII = /^[\x21-\x7e]+$/;

function loadActor(): string {
  try {
    return localStorage.getItem(ACTOR_KEY) ?? 'cs-demo';
  } catch {
    return 'cs-demo';
  }
}

function saveActor(v: string) {
  try {
    localStorage.setItem(ACTOR_KEY, v);
  } catch {
    // 저장하지 못해도 화면 동작에는 영향이 없다
  }
}

function newRequestId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function errorText(e: unknown): string {
  if (!(e instanceof Error)) return String(e);
  try {
    const body = JSON.parse(e.message) as { message?: string };
    return body.message ?? e.message;
  } catch {
    return e.message;
  }
}

const matches = (q: string, ...fields: (string | number | null | undefined)[]) =>
  q === '' || fields.some((f) => f !== null && f !== undefined && String(f).toLowerCase().includes(q.toLowerCase()));

/**
 * CS 처리 화면. 자동으로 풀지 못한 건(미연동·충돌·회수)과 처리 이력을 본다.
 * 미연동은 골라서 다시 처리하고, 건마다 Idempotency-Key를 새로 만들어 보낸다.
 */
export function AdminPages({ tab }: { tab: AdminTab }) {
  const [status, setStatus] = useState<StatusFilter>('OPEN');
  const [query, setQuery] = useState('');
  const [actor, setActor] = useState(loadActor);
  const [unlinked, setUnlinked] = useState<UnlinkedRow[]>([]);
  const [conflicts, setConflicts] = useState<ConflictRow[]>([]);
  const [recalls, setRecalls] = useState<RecallRow[]>([]);
  const [history, setHistory] = useState<HistoryRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [results, setResults] = useState<string[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [working, setWorking] = useState(false);

  const load = useCallback(async () => {
    try {
      const s = status || undefined;
      if (tab === 'unlinked') {
        const u = await api.unlinked(s);
        setUnlinked(u);
        setSelected((sel) => new Set([...sel].filter((id) => u.some((row) => row.id === id && !row.resolvedAt))));
      } else if (tab === 'conflicts') setConflicts(await api.conflicts(s));
      else if (tab === 'recalls') setRecalls(await api.recalls(s));
      else setHistory(await api.history());
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [status, tab]);

  useEffect(() => {
    setResults([]);
    setQuery('');
  }, [tab]);

  useEffect(() => {
    load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, [load]);

  const actorOk = ASCII.test(actor);

  const reprocess = async (ids: number[]) => {
    setWorking(true);
    const lines: string[] = [];
    const orderOf = (id: number) => unlinked.find((r) => r.id === id)?.orderNo ?? `#${id}`;
    for (const id of ids) {
      try {
        const out = await api.reprocess(id, newRequestId(), actor);
        const result = out.result ? RESULT_LABEL[out.result] ?? out.result : out.status;
        const help = out.result ? RESULT_HELP[out.result] : undefined;
        lines.push(`${orderOf(id)} ${result}${help ? `: ${help}` : ''}`);
      } catch (e) {
        lines.push(`${orderOf(id)} 실패: ${errorText(e)}`);
      }
    }
    setResults(lines);
    setSelected(new Set());
    setWorking(false);
    load();
  };

  const meta = META[tab];

  return (
    <>
      <div className="pagehead">
        <div>
          <h1 title={meta.tech}>{meta.title}</h1>
          <p className="desc">{meta.desc}</p>
        </div>
      </div>

      <div className="filters">
        {tab !== 'history' && (
          <div className="segmented" role="group" aria-label="처리 상태">
            {STATUS_OPTIONS.map((o) => (
              <button key={o.label} aria-pressed={status === o.value} onClick={() => setStatus(o.value)}>
                {o.label}
              </button>
            ))}
          </div>
        )}
        <input type="search" placeholder="주문번호로 찾기" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="찾기" />
        {tab === 'unlinked' && (
          <div className="right">
            <label className="actor" title="X-Actor 헤더로 보냅니다">
              담당자 이름
              <input type="text" value={actor} aria-invalid={!actorOk} onChange={(e) => (setActor(e.target.value), saveActor(e.target.value))} />
            </label>
            <button className="btn primary" disabled={selected.size === 0 || working || !actorOk} onClick={() => reprocess([...selected])}>
              {working ? '등록하는 중' : `선택한 ${selected.size}건 창고에 다시 등록`}
            </button>
          </div>
        )}
      </div>
      {tab === 'unlinked' && !actorOk && <p className="err">담당자 이름은 영문과 숫자로 적어 주세요. 예: kim</p>}
      {error && (
        <p className="err" role="alert">
          목록을 불러오지 못했습니다. 잠시 뒤 새로 고쳐 주세요
        </p>
      )}
      {results.length > 0 && (
        <div className="result" role="status" style={{ marginTop: 16 }}>
          <strong>처리 결과</strong>
          <ul>
            {results.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
          <button className="link close" onClick={() => setResults([])}>
            닫기
          </button>
        </div>
      )}

      <div className="table-wrap" style={{ marginTop: 12 }}>
        {tab === 'unlinked' && (
          <UnlinkedTable
            rows={unlinked.filter((r) => matches(query, r.orderNo, r.orderProductOptionNo))}
            selected={selected}
            onToggle={(id, on) =>
              setSelected((s) => {
                const n = new Set(s);
                if (on) n.add(id);
                else n.delete(id);
                return n;
              })
            }
            onToggleAll={(rows, on) => setSelected(on ? new Set(rows.filter((r) => !r.resolvedAt).map((r) => r.id)) : new Set())}
          />
        )}
        {tab === 'conflicts' && <ConflictTable rows={conflicts.filter((r) => matches(query, r.targetKey))} />}
        {tab === 'recalls' && <RecallTable rows={recalls.filter((r) => matches(query, r.orderProductOptionNo, r.claimNo, r.invoiceNo))} />}
        {tab === 'history' && <HistoryTable rows={history.filter((r) => matches(query, r.targetKey, r.actor))} />}
      </div>
    </>
  );
}

function When({ iso }: { iso: string | null }) {
  if (!iso) return <span className="muted">-</span>;
  return (
    <time dateTime={iso} title={iso.replace('T', ' ').slice(0, 19)}>
      {ago(iso)}
    </time>
  );
}

function Reason({ code }: { code: string }) {
  return <span title={code}>{REASON_LABEL[code] ?? code}</span>;
}

/** 대상 키(예: 1000038, shipping:9000001, unlinked:7)를 사람이 읽는 말로 */
function target(key: string): string {
  const [kind, no] = key.includes(':') ? key.split(':') : ['option', key];
  if (kind === 'shipping') return `배송 ${no}`;
  if (kind === 'unlinked') return `창고 등록 실패 #${no}`;
  return `주문 상품 ${no}`;
}

function State({ resolvedAt, who: resolver }: { resolvedAt: string | null; who?: string | null }) {
  const by = who(resolver);
  return resolvedAt ? (
    <>
      <span className="badge good">처리됨</span>
      <div className="secondary">
        <When iso={resolvedAt} />
        {by ? `, ${by}` : ''}
      </div>
    </>
  ) : (
    <span className="badge warn">미처리</span>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>;
}

function UnlinkedTable({
  rows,
  selected,
  onToggle,
  onToggleAll,
}: {
  rows: UnlinkedRow[];
  selected: Set<number>;
  onToggle: (id: number, on: boolean) => void;
  onToggleAll: (rows: UnlinkedRow[], on: boolean) => void;
}) {
  if (rows.length === 0) return <Empty text="창고 등록에 실패한 주문이 없습니다" />;
  const open = rows.filter((r) => !r.resolvedAt);
  return (
    <table className="data">
      <thead>
        <tr>
          <th style={{ width: 36 }}>
            <input
              type="checkbox"
              aria-label="전체 선택"
              disabled={open.length === 0}
              checked={open.length > 0 && open.every((r) => selected.has(r.id))}
              onChange={(e) => onToggleAll(rows, e.target.checked)}
            />
          </th>
          <th>주문</th>
          <th>상품과 옵션</th>
          <th>사유</th>
          <th>상태</th>
          <th className="n">발견 시각</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id}>
            <td>
              {!r.resolvedAt && (
                <input type="checkbox" aria-label={`${r.orderNo ?? r.id} 선택`} checked={selected.has(r.id)} onChange={(e) => onToggle(r.id, e.target.checked)} />
              )}
            </td>
            <td>
              <span className="num">{r.orderNo ?? '-'}</span>
              <div className="secondary num">주문 상품 {r.orderProductOptionNo}</div>
            </td>
            <td>
              {r.productName ?? '-'}
              <div className="secondary">
                {r.optionName ?? ''}
                {r.optionValue ? ` ${r.optionValue}` : ''}
              </div>
            </td>
            <td>
              <Reason code={r.reasonCode} />
            </td>
            <td>
              <State resolvedAt={r.resolvedAt} who={r.resolver} />
            </td>
            <td className="n muted">
              <When iso={r.detectedAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ConflictTable({ rows }: { rows: ConflictRow[] }) {
  if (rows.length === 0) return <Empty text="확인이 필요한 주문이 없습니다" />;
  return (
    <table className="data">
      <thead>
        <tr>
          <th>주문</th>
          <th>사유</th>
          <th>할 일</th>
          <th>상태</th>
          <th className="n">발견 시각</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id}>
            <td className="num" title={r.targetKey}>
              {target(r.targetKey)}
            </td>
            <td>
              <Reason code={r.reasonCode} />
            </td>
            <td style={{ maxWidth: 440, color: 'var(--ink-2)' }} title={r.detail ?? undefined}>
              {REASON_HELP[r.reasonCode] ?? '쇼핑몰과 창고의 주문 상태를 직접 확인하세요'}
            </td>
            <td>
              <State resolvedAt={r.resolvedAt} />
            </td>
            <td className="n muted">
              <When iso={r.detectedAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RecallTable({ rows }: { rows: RecallRow[] }) {
  if (rows.length === 0) return <Empty text="회수할 주문이 없습니다" />;
  return (
    <table className="data">
      <thead>
        <tr>
          <th>주문 상품</th>
          <th>취소 요청 번호</th>
          <th>송장 번호</th>
          <th>사유</th>
          <th>상태</th>
          <th className="n">접수 시각</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id}>
            <td className="num">{r.orderProductOptionNo}</td>
            <td className="num">{r.claimNo}</td>
            <td className="num">{r.invoiceNo ?? '-'}</td>
            <td>
              <Reason code={r.reason} />
            </td>
            <td>
              <State resolvedAt={r.resolvedAt} />
            </td>
            <td className="n muted">
              <When iso={r.createdAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const ACTION_LABEL: Record<string, string> = { REPROCESS_UNLINKED: '창고 등록 다시 시도' };

function HistoryTable({ rows }: { rows: HistoryRow[] }) {
  if (rows.length === 0) return <Empty text="처리 이력이 없습니다" />;
  return (
    <table className="data">
      <thead>
        <tr>
          <th>한 일</th>
          <th>대상</th>
          <th>담당자</th>
          <th>결과</th>
          <th className="n">시각</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.requestId}>
            <td>
              <span title={`요청 ${r.requestId}`}>{ACTION_LABEL[r.action] ?? r.action}</span>
            </td>
            <td className="num" title={r.targetKey}>
              {target(r.targetKey)}
            </td>
            <td>{r.actor}</td>
            <td>
              <span className={`badge ${r.status === 'DONE' ? 'good' : r.status === 'FAILED' ? 'bad' : 'warn'}`}>
                {r.result ? RESULT_LABEL[r.result] ?? r.result : r.status}
              </span>
              {r.result && RESULT_HELP[r.result] && <div className="secondary">{RESULT_HELP[r.result]}</div>}
            </td>
            <td className="n muted">
              <When iso={r.createdAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
