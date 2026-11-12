import { useEffect, useRef, useState } from 'react';
import { api } from '../api';

/** fault: 복구 과정 그래프에 세로선으로 남길 장애 이름. 없으면 일반 작업 */
export type Run = (action: string, fn: () => Promise<string | void>, fault?: string) => Promise<void>;

interface Props {
  open: boolean;
  busy: boolean;
  run: Run;
  onClose: () => void;
}

/**
 * 자주 쓰는 장애 조합. 화면 문구는 운영자 말로 쓰고, 개발 용어는 tech에 남겨 마우스를 올리면 보이게 한다.
 */
const PRESETS: { name: string; what: string; tech: string; fault: string; go: () => Promise<string> }[] = [
  {
    name: '주문 알림이 사라짐',
    what: '주문 50건을 넣고, 쇼핑몰이 보내는 알림 30%를 중간에 잃어버립니다',
    tech: '웹훅 30% 유실, 나머지는 2번씩 중복 전송',
    fault: '주문 알림 30% 누락',
    go: async () => {
      const r = await api.createOrders({ count: 50, dropRatio: 0.3, duplicates: 2, unmappedRatio: 0 });
      return `주문 ${r.created}건을 넣었고 알림 ${r.webhooksDropped}건이 사라졌습니다`;
    },
  },
  {
    name: '쇼핑몰 요청 과다',
    what: '쇼핑몰이 "요청이 너무 많다"며 30번을 막은 상태에서 주문 50건을 넣습니다',
    tech: '샵바이 API 429 응답 30회',
    fault: '쇼핑몰 요청 제한 30회',
    go: async () => {
      await api.injectRateLimit(30, 0);
      const r = await api.createOrders({ count: 50, dropRatio: 0, duplicates: 0, unmappedRatio: 0 });
      return `요청 제한 30회를 걸고 주문 ${r.created}건을 넣었습니다`;
    },
  },
  {
    name: '창고에 없는 옵션',
    what: '주문 30건 중 20%가 창고에 등록되지 않은 옵션입니다. 사람이 처리할 일로 넘어갑니다',
    tech: '3PL 옵션 매핑 없음, 미연동',
    fault: '창고에 없는 옵션 20%',
    go: async () => {
      const r = await api.createOrders({ count: 30, dropRatio: 0, duplicates: 0, unmappedRatio: 0.2 });
      return `주문 ${r.created}건 중 ${r.unmapped}건이 창고에 없는 옵션입니다`;
    },
  },
  {
    name: '출고 뒤 고객 취소',
    what: '창고에서 모두 출고한 뒤 고객 3명이 주문을 취소합니다',
    tech: '3PL 출고완료 후 취소 요청, 이미출고 처리',
    fault: '출고 뒤 취소 3건',
    go: async () => {
      const a = await api.advanceTpl('REQUESTED', 'RELEASED');
      const c = await api.cancel(3, 'RELEASED');
      return `${a.advanced}건을 출고했고 ${c.cancelRequested}건이 취소를 요청했습니다`;
    },
  },
];

/** 오른쪽에서 열리는 장애 패널. 자주 쓰는 조합과 직접 설정 */
export function ScenarioPanel({ open, busy, run, onClose }: Props) {
  const [count, setCount] = useState(50);
  const [drop, setDrop] = useState(0.3);
  const [dups, setDups] = useState(2);
  const [unmapped, setUnmapped] = useState(0);
  const [cancelAt, setCancelAt] = useState('RELEASED');
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className="drawer" role="dialog" aria-modal="true" aria-labelledby="scenario-title">
        <header>
          <h2 id="scenario-title">장애 테스트</h2>
          <button ref={closeRef} className="btn quiet sm" onClick={onClose}>
            닫기
          </button>
        </header>
        <div className="body">
          <section>
            <h3>자주 쓰는 상황</h3>
            {PRESETS.map((p) => (
              <div className="preset" key={p.name} title={p.tech}>
                <div className="what">
                  <strong>{p.name}</strong>
                  <span>{p.what}</span>
                </div>
                <button className="btn sm" disabled={busy} onClick={() => run(p.name, p.go, p.fault)}>
                  시작
                </button>
              </div>
            ))}
          </section>

          <section>
            <h3>주문 직접 넣기</h3>
            <div className="form">
              <label>
                주문 수
                <input type="number" min={1} max={1000} value={count} onChange={(e) => setCount(Number(e.target.value))} />
              </label>
              <label title="웹훅 유실">
                사라지는 주문 알림
                <select value={drop} onChange={(e) => setDrop(Number(e.target.value))}>
                  <option value={0}>없음</option>
                  <option value={0.1}>10%</option>
                  <option value={0.3}>30%</option>
                  <option value={0.5}>50%</option>
                </select>
              </label>
              <label title="웹훅 중복 전송">
                같은 알림 반복
                <select value={dups} onChange={(e) => setDups(Number(e.target.value))}>
                  <option value={0}>없음</option>
                  <option value={1}>1번 더</option>
                  <option value={2}>2번 더</option>
                  <option value={3}>3번 더</option>
                </select>
              </label>
              <label title="3PL 옵션 매핑 없음">
                창고에 없는 옵션
                <select value={unmapped} onChange={(e) => setUnmapped(Number(e.target.value))}>
                  <option value={0}>없음</option>
                  <option value={0.1}>10%</option>
                  <option value={0.2}>20%</option>
                </select>
              </label>
            </div>
            <button
              className="btn primary"
              style={{ marginTop: 12, width: '100%' }}
              disabled={busy}
              onClick={() =>
                run(
                  '주문 넣기',
                  async () => {
                    const r = await api.createOrders({ count, dropRatio: drop, duplicates: dups, unmappedRatio: unmapped });
                    return `주문 ${r.created}건을 넣었고 알림 ${r.webhooksDropped}건이 사라졌습니다. 창고에 없는 옵션 ${r.unmapped}건`;
                  },
                  drop > 0 ? `주문 알림 ${Math.round(drop * 100)}% 누락` : `주문 ${count}건`,
                )
              }
            >
              주문 넣기
            </button>
          </section>

          <section>
            <h3>쇼핑몰 요청 제한</h3>
            <p className="hint">다음 요청 30번을 쇼핑몰이 막거나, 곧 막겠다고 경고합니다</p>
            <div className="row">
              <button
                className="btn sm"
                disabled={busy}
                title="샵바이 API 429 응답"
                onClick={() => run('쇼핑몰 요청 제한 30회', () => api.injectRateLimit(30, 0).then(() => undefined), '쇼핑몰 요청 제한 30회')}
              >
                막기 30회
              </button>
              <button
                className="btn sm"
                disabled={busy}
                title="응답 헤더 ratelimit-available-level: EXHAUSTED"
                onClick={() => run('쇼핑몰 한도 임박 경고 30회', () => api.injectRateLimit(0, 30).then(() => undefined), '한도 임박 경고 30회')}
              >
                경고만 30회
              </button>
            </div>
          </section>

          <section>
            <h3>물류 창고</h3>
            <p className="hint">창고에 있는 주문을 다음 단계로 넘기거나, 고객 취소를 넣습니다</p>
            <div className="row">
              <Advance run={run} busy={busy} from="REQUESTED" to="WORKING" label="출고작업으로" />
              <Advance run={run} busy={busy} from="WORKING" to="PICKED" label="피킹으로" />
              <Advance run={run} busy={busy} from="PICKED" to="RELEASED" label="출고완료로" />
              <Advance run={run} busy={busy} from="REQUESTED" to="RELEASED" label="전부 출고완료로" />
            </div>
            <div className="row" style={{ marginTop: 12, alignItems: 'center' }}>
              <select value={cancelAt} onChange={(e) => setCancelAt(e.target.value)} aria-label="취소할 창고 단계">
                <option value="REQUESTED">출고요청 단계에서</option>
                <option value="WORKING">출고작업 단계에서</option>
                <option value="PICKED">피킹 단계에서</option>
                <option value="RELEASED">출고완료 단계에서</option>
              </select>
              <button
                className="btn sm"
                disabled={busy}
                onClick={() => run('고객 취소 3건', async () => `${(await api.cancel(3, cancelAt)).cancelRequested}건이 취소를 요청했습니다`, '고객 취소 3건')}
              >
                고객 3명 취소
              </button>
            </div>
            <button
              className="btn sm"
              style={{ marginTop: 12 }}
              disabled={busy}
              title="3PL 옵션 매핑 추가"
              onClick={() => run('창고 옵션 설정 고치기', () => api.fixMapping().then(() => undefined))}
            >
              창고 옵션 설정 고치기
            </button>
          </section>
        </div>
      </div>
    </>
  );
}

function Advance({ run, busy, from, to, label }: { run: Run; busy: boolean; from: string; to: string; label: string }) {
  return (
    <button className="btn sm" disabled={busy} onClick={() => run(`창고 ${label}`, async () => `${(await api.advanceTpl(from, to)).advanced}건 옮겼습니다`)}>
      {label}
    </button>
  );
}
