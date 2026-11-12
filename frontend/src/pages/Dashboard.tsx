import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type Overview, type ReconciliationRun } from '../api';
import { LineChart, type Point } from '../components/LineChart';
import { ScenarioPanel, type Run } from '../components/ScenarioPanel';
import { StageTable } from '../components/StageTable';
import { TimelineChart, type Marker } from '../components/TimelineChart';
import { sum, timeOf } from '../labels';
import { usePolling } from '../usePolling';

interface LogEntry {
  at: number;
  action: string;
  result: string;
  error?: boolean;
}

interface Polled<T> {
  data: T | null;
  error: string | null;
  refresh: () => void;
}

const QUEUE_SERIES = [
  { key: 'visible', label: '기다림', color: 'var(--series-1)' },
  { key: 'inFlight', label: '처리 중', color: 'var(--series-2)' },
  { key: 'delayed', label: '잠시 뒤 다시 시도', color: 'var(--series-3)' },
];
const QUEUE_HISTORY = 150;
/** 주문 상태를 어떤 길로 받았는지 (웹훅, 재조회, 실패 웹훅 조회) */
const SOURCE_LABEL: Record<string, string> = { WEBHOOK: '쇼핑몰 알림', POLLING: '쇼핑몰 재확인', FAILED_WEBHOOK: '놓친 알림 다시 받기' };

const ms = (iso: string) => new Date(iso).getTime();
/** 이 시간 안에 연달아 넣은 장애는 한 사건으로 묶어 요약한다 */
const GROUP_MS = 10_000;

/** 타임라인 위에 붙일 한 줄 요약. 마지막 장애 이후의 대사 기록으로 만든다 */
export function story(runs: ReconciliationRun[], markers: Marker[]): { text: React.ReactNode; tone: 'good' | 'bad' | 'none' } {
  const last = runs[runs.length - 1];
  const lastMarker = markers[markers.length - 1];
  const group = lastMarker ? markers.filter((m) => m.at >= lastMarker.at - GROUP_MS) : [];
  const fault = lastMarker ? { at: group[0].at, label: group.map((m) => m.label).join(', ') } : undefined;
  if (!last) return { text: '장애 테스트를 시작하면 다시 맞춰지는 과정이 여기에 그려집니다', tone: 'none' };
  if (!fault) {
    if (last.compared === 0) return { text: '주문이 아직 없습니다. "장애 테스트"에서 주문을 넣어 보세요', tone: 'none' };
    return last.mismatches === 0
      ? { text: <>쇼핑몰과 우리 시스템의 주문 상태가 <span className="good">모두 같습니다</span></>, tone: 'good' }
      : { text: <>상태가 다른 주문 <span className="bad">{last.mismatches}건</span></>, tone: 'bad' };
  }
  const after = runs.filter((r) => ms(r.ranAt) >= fault.at);
  const peak = Math.max(0, ...after.map((r) => r.mismatches));
  if (peak === 0) return { text: <>{fault.label}, 차이를 찾는 중입니다</>, tone: 'none' };
  const peakIdx = after.findIndex((r) => r.mismatches === peak);
  const recovered = after.slice(peakIdx).find((r) => r.mismatches === 0);
  if (recovered) {
    const secs = Math.round((ms(recovered.ranAt) - fault.at) / 1000);
    return {
      text: (
        <>
          {fault.label} → 상태가 다른 주문 최대 {peak}건 → <span className="good">{secs}초 만에 모두 맞춰짐</span>
        </>
      ),
      tone: 'good',
    };
  }
  return {
    text: (
      <>
        {fault.label} → 상태가 다른 주문 <span className="bad">{last.mismatches}건</span> 맞추는 중
      </>
    ),
    tone: 'bad',
  };
}

export function Dashboard({ overview, onOpenAdmin }: { overview: Polled<Overview>; onOpenAdmin: () => void }) {
  const runs = usePolling(() => api.reconciliationRuns(120), 3000);
  const mock = usePolling(api.mockState, 2000);
  const [queueHistory, setQueueHistory] = useState<Point[]>([]);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [markers, setMarkers] = useState<Marker[]>([]);
  const [busy, setBusy] = useState(false);
  const [panel, setPanel] = useState(false);
  const lastAt = useRef<string | null>(null);

  const o = overview.data;
  useEffect(() => {
    if (!o || o.at === lastAt.current) return;
    lastAt.current = o.at;
    setQueueHistory((h) =>
      [...h, { t: timeOf(o.at), values: { visible: o.queue.visible, inFlight: o.queue.inFlight, delayed: o.queue.delayed } }].slice(-QUEUE_HISTORY),
    );
  }, [o]);

  const run: Run = async (action, fn, fault) => {
    setBusy(true);
    const at = Date.now();
    // 장애는 넣기 시작한 순간부터 효과가 있으므로 요청이 끝나기 전에 표시한다
    if (fault) setMarkers((m) => [...m, { at, label: fault }]);
    try {
      const result = (await fn()) ?? '완료';
      setLog((l) => [{ at, action, result }, ...l].slice(0, 30));
      overview.refresh();
      mock.refresh();
      runs.refresh();
    } catch (e) {
      if (fault) setMarkers((m) => m.filter((x) => x.at !== at));
      setLog((l) => [{ at, action, result: e instanceof Error ? e.message : String(e), error: true }, ...l].slice(0, 30));
    } finally {
      setBusy(false);
    }
  };

  const reset = () => {
    if (!window.confirm('데모로 넣은 주문과 기록을 모두 지웁니다. 계속할까요?')) return;
    return run('데모 데이터 지우기', async () => {
      await api.resetDemo();
      setQueueHistory([]);
      setMarkers([]);
    });
  };

  const closePanel = useCallback(() => setPanel(false), []);

  const m = mock.data;
  const r = runs.data ?? [];
  const lastRun = o?.lastReconciliation;
  const last = lastRun && lastRun.compared > 0 ? lastRun : null;
  const matched = last ? last.compared - last.mismatches : 0;
  const people = o ? sum(o.openConflicts) + o.openUnlinked + o.openRecalls : 0;
  const s = story(r, markers);
  const sources = o ? Object.entries(o.processedEvents) : [];

  return (
    <>
      <div className="pagehead">
        <div>
          <h1>동기화 현황</h1>
          <p className="desc">쇼핑몰과 창고에 문제가 생겨도 주문 상태가 저절로 다시 맞춰지는지 봅니다</p>
        </div>
        <div className="actions">
          <button className="btn" disabled={busy} onClick={reset}>
            데모 데이터 지우기
          </button>
          <button className="btn primary" onClick={() => setPanel(true)}>
            장애 테스트
          </button>
        </div>
      </div>

      {mock.error && (
        <p className="err" role="alert">
          가상 쇼핑몰에 연결할 수 없습니다. 데모를 scripts/demo.sh로 띄웠는지 확인하세요
        </p>
      )}

      <section className="timeline" aria-labelledby="story">
        <TimelineChart
          runs={r}
          markers={markers}
          head={
            <>
            <p id="story" className="story" role="status">
              {s.text}
            </p>
            <p className="sub">
              {m && m.webhooks.sent > 0 ? `쇼핑몰이 보낸 주문 알림 ${m.webhooks.sent}건 중 ${m.webhooks.dropped}건이 사라졌습니다. ` : ''}
              {sources.length > 0
                ? `받은 상태 변경은 ${sources.map(([k, n]) => `${SOURCE_LABEL[k] ?? k} ${n}건`).join(', ')}입니다.`
                : '아직 받은 상태 변경이 없습니다.'}
            </p>
            </>
          }
        />
      </section>

      <div className="metrics">
        <Metric
          label="쇼핑몰과 같은 주문"
          title="대사(reconciliation): 쇼핑몰 주문 조회 결과와 우리 DB를 주기적으로 비교"
          note={last ? `${timeOf(last.ranAt)}에 쇼핑몰과 비교` : '비교할 주문이 아직 없습니다'}
        >
          <div className="value num">
            {last ? matched : '-'}
            {last && <span className="of">/ {last.compared}</span>}
          </div>
          <div className={`bar${last && last.mismatches > 0 ? ' bad' : ''}`} aria-hidden>
            <span style={{ width: `${last && last.compared > 0 ? (matched / last.compared) * 100 : 0}%` }} />
          </div>
        </Metric>
        <Metric
          label="자동 처리 대기"
          title="SQS 큐 메시지 수"
          note={o ? `기다림 ${o.queue.visible}, 처리 중 ${o.queue.inFlight}, 잠시 뒤 다시 시도 ${o.queue.delayed}` : undefined}
        >
          <div className="value num">{o ? o.queue.visible + o.queue.inFlight + o.queue.delayed : '-'}</div>
        </Metric>
        <Metric
          label="자동 처리 실패"
          title="DLQ: 여러 번 실패해 따로 빼 둔 메시지"
          badge={o ? (o.dlq.visible === 0 ? <span className="badge good">없음</span> : <span className="badge bad">확인 필요</span>) : undefined}
          note={m ? `쇼핑몰이 "요청이 너무 많다"며 막은 ${m.shopbyApi.rejected429}번은 잠시 뒤 다시 시도했습니다` : undefined}
        >
          <div className="value num">{o ? o.dlq.visible : '-'}</div>
        </Metric>
        <Metric
          label="사람이 처리할 일"
          badge={o && people > 0 ? <span className="badge warn">남아 있음</span> : undefined}
          note={
            o ? (
              <>
                창고 등록 실패 {o.openUnlinked}, 확인 필요 {sum(o.openConflicts)}, 회수 안내 {o.openRecalls}{' '}
                {people > 0 && (
                  <button className="link" onClick={onOpenAdmin}>
                    처리하러 가기
                  </button>
                )}
              </>
            ) : undefined
          }
        >
          <div className="value num">{o ? people : '-'}</div>
        </Metric>
      </div>

      <div className="pair block">
        <LineChart title="자동 처리 대기 추이" aside="2초마다 새로 고침" series={QUEUE_SERIES} points={queueHistory} />
        <div>
          <div className="blockhead">
            <h2>단계별 주문 수</h2>
            <span className="aside">쇼핑몰과 다르면 빨간 숫자</span>
          </div>
          <StageTable shopby={m?.shopby.byStatus} ours={o?.orderLines} tpl={m?.tpl} />
        </div>
      </div>

      <section className="block">
        <div className="blockhead">
          <h2>테스트 기록</h2>
        </div>
        {log.length === 0 ? (
          <div className="empty">아직 실행한 테스트가 없습니다</div>
        ) : (
          <table className="data">
            <thead>
              <tr>
                <th style={{ width: 110 }}>시각</th>
                <th style={{ width: 220 }}>테스트</th>
                <th>결과</th>
              </tr>
            </thead>
            <tbody>
              {log.map((e, i) => (
                <tr key={i}>
                  <td className="num muted">{new Date(e.at).toTimeString().slice(0, 8)}</td>
                  <td>{e.action}</td>
                  <td className={e.error ? 'err' : undefined}>{e.result}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <ScenarioPanel open={panel} busy={busy} run={run} onClose={closePanel} />
    </>
  );
}

function Metric({
  label,
  title,
  badge,
  note,
  children,
}: {
  label: string;
  /** 개발자용 원래 용어. 마우스를 올리면 보인다 */
  title?: string;
  badge?: React.ReactNode;
  note?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="metric" role="group" aria-label={label}>
      <div className="label" title={title}>
        {label}
        {badge}
      </div>
      {children}
      {note && <div className="note">{note}</div>}
    </div>
  );
}
