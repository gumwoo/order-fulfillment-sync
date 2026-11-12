import { useRef, useState } from 'react';
import type { ReconciliationRun } from '../api';
import { niceMax } from './LineChart';

export interface Marker {
  at: number; // epoch ms
  label: string;
}

interface Props {
  runs: ReconciliationRun[];
  markers: Marker[];
  height?: number;
  /** 그래프 위 머리말 (요약 문장 등). 오른쪽에 표로 보기 전환이 붙는다 */
  head?: React.ReactNode;
}

const W = 1200;
const H_DEFAULT = 220;
const PAD_BASE = { top: 26, right: 16, bottom: 24, left: 36 };
const LABEL_ROW = 16;
const LABEL_MIN_GAP = 170; // viewBox 단위. 이보다 가까운 장애 이름은 한 줄 위로 올린다

const ms = (iso: string) => new Date(iso).getTime();
/** 장애가 있으면 첫 장애 조금 전부터 보여 줘서 사건이 그래프 가운데를 차지하게 한다 */
const LEAD_MS = 20_000;
const hhmmss = (t: number) => new Date(t).toTimeString().slice(0, 8);

/**
 * 복구 타임라인: 대사 실행마다 잰 불일치 수를 시간축에 그린다.
 * 장애를 넣은 시점은 세로 점선과 이름으로, 불일치가 있던 구간은 옅은 빨강으로 칠한다 (Vercel Observability 방식).
 */
export function TimelineChart({ runs: allRuns, markers, height = H_DEFAULT, head }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);
  const ref = useRef<SVGSVGElement>(null);
  const H = height;

  const from = markers.length > 0 ? Math.min(...markers.map((m) => m.at)) - LEAD_MS : -Infinity;
  const runs = allRuns.filter((r) => ms(r.ranAt) >= from);

  if (runs.length === 0) {
    return (
      <>
        <div className="tl-head">{head}</div>
        <div className="empty">아직 쇼핑몰과 비교한 기록이 없습니다</div>
      </>
    );
  }

  const t0 = Math.min(ms(runs[0].ranAt), ...markers.map((m) => m.at));
  const t1 = Math.max(ms(runs[runs.length - 1].ranAt), t0 + 1000);
  const visible = markers.filter((m) => m.at >= t0 && m.at <= t1).sort((a, b) => a.at - b.at);
  // 가까운 장애 이름이 겹치지 않게 줄을 나눈다
  const xRaw = (t: number) => PAD_BASE.left + ((t - t0) / (t1 - t0)) * (W - PAD_BASE.left - PAD_BASE.right);
  const rows: number[] = [];
  const rowEnds: number[] = [];
  visible.forEach((m) => {
    const mx = xRaw(m.at);
    let row = rowEnds.findIndex((end) => mx - end >= LABEL_MIN_GAP);
    if (row === -1) row = rowEnds.length;
    rowEnds[row] = mx;
    rows.push(row);
  });
  const PAD = { ...PAD_BASE, top: PAD_BASE.top + Math.max(0, rowEnds.length - 1) * LABEL_ROW };
  const maxY = niceMax(Math.max(...runs.map((r) => r.mismatches)));
  const x = (t: number) => PAD.left + ((t - t0) / (t1 - t0)) * (W - PAD.left - PAD.right);
  const y = (v: number) => PAD.top + (1 - v / maxY) * (H - PAD.top - PAD.bottom);

  // 불일치가 0보다 큰 연속 구간
  const zones: [number, number][] = [];
  let start: number | null = null;
  runs.forEach((r, i) => {
    if (r.mismatches > 0 && start === null) start = i === 0 ? ms(r.ranAt) : ms(runs[i - 1].ranAt);
    if (r.mismatches === 0 && start !== null) {
      zones.push([start, ms(r.ranAt)]);
      start = null;
    }
  });
  if (start !== null) zones.push([start, ms(runs[runs.length - 1].ranAt)]);

  const line = runs.map((r, i) => `${i === 0 ? 'M' : 'L'}${x(ms(r.ranAt)).toFixed(1)},${y(r.mismatches).toFixed(1)}`).join(' ');
  const area = `${line} L${x(ms(runs[runs.length - 1].ranAt)).toFixed(1)},${y(0)} L${x(ms(runs[0].ranAt)).toFixed(1)},${y(0)} Z`;
  const last = runs[runs.length - 1];

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const t = t0 + (((e.clientX - rect.left) / rect.width) * W - PAD.left) / (W - PAD.left - PAD.right) * (t1 - t0);
    let best = 0;
    runs.forEach((r, i) => {
      if (Math.abs(ms(r.ranAt) - t) < Math.abs(ms(runs[best].ranAt) - t)) best = i;
    });
    setHover(best);
  };

  return (
    <div className="chart">
      <div className="tl-head">
        <div>{head}</div>
        <button className="link" onClick={() => setAsTable((v) => !v)}>
          {asTable ? '그래프로 보기' : '표로 보기'}
        </button>
      </div>
      {asTable ? (
        <div className="table-wrap" style={{ maxHeight: H, overflow: 'auto' }}>
          <table className="data">
            <thead>
              <tr>
                <th>비교한 시각</th>
                <th className="n">비교한 주문</th>
                <th className="n">상태가 다른 주문</th>
              </tr>
            </thead>
            <tbody>
              {[...runs].reverse().map((r) => (
                <tr key={r.ranAt}>
                  <td className="num">{hhmmss(ms(r.ranAt))}</td>
                  <td className="n num">{r.compared}</td>
                  <td className={`n num${r.mismatches > 0 ? ' diff' : ''}`}>{r.mismatches}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <>
          <svg
            ref={ref}
            viewBox={`0 0 ${W} ${H}`}
            role="img"
            aria-label={`복구 과정: 마지막 비교에서 상태가 다른 주문 ${last.mismatches}건`}
            onMouseMove={onMove}
            onMouseLeave={() => setHover(null)}
          >
            <defs>
              <linearGradient id="tl-fill" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--series-1)" stopOpacity="0.16" />
                <stop offset="100%" stopColor="var(--series-1)" stopOpacity="0" />
              </linearGradient>
            </defs>
            {zones.map(([a, b]) => (
              <rect key={a} x={x(a)} y={PAD.top} width={Math.max(2, x(b) - x(a))} height={H - PAD.top - PAD.bottom} fill="var(--bad-zone)" />
            ))}
            {[0, maxY / 2, maxY].map((t) => (
              <g key={t}>
                <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} />
                <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" fontSize={12} fill="var(--muted)">
                  {t}
                </text>
              </g>
            ))}
            <path d={area} fill="url(#tl-fill)" />
            <path d={line} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" />
            {visible.map((m, i) => (
              <g key={`${m.at}-${i}`}>
                <line
                  x1={x(m.at)}
                  x2={x(m.at)}
                  y1={PAD.top - 6 - rows[i] * LABEL_ROW}
                  y2={H - PAD.bottom}
                  stroke="var(--ink-2)"
                  strokeDasharray="3 3"
                />
                <text x={x(m.at) + 6} y={PAD.top - 10 - rows[i] * LABEL_ROW} fontSize={12} fill="var(--ink-2)" fontWeight={600}>
                  {m.label}
                </text>
              </g>
            ))}
            <circle cx={x(ms(last.ranAt))} cy={y(last.mismatches)} r={4} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
            <text x={PAD.left} y={H - 4} fontSize={12} fill="var(--muted)">
              {hhmmss(t0)}
            </text>
            <text x={W - PAD.right} y={H - 4} fontSize={12} fill="var(--muted)" textAnchor="end">
              {hhmmss(t1)}
            </text>
            {hover !== null && (
              <g>
                <line x1={x(ms(runs[hover].ranAt))} x2={x(ms(runs[hover].ranAt))} y1={PAD.top} y2={H - PAD.bottom} stroke="var(--axis)" />
                <circle cx={x(ms(runs[hover].ranAt))} cy={y(runs[hover].mismatches)} r={4} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
              </g>
            )}
            <rect x={PAD.left} y={PAD.top} width={W - PAD.left - PAD.right} height={H - PAD.top - PAD.bottom} fill="transparent" />
          </svg>
          {hover !== null && (
            <div
              className="tip"
              style={{
                left: `calc(${(x(ms(runs[hover].ranAt)) / W) * 100}% + 10px)`,
                top: 40,
                transform: x(ms(runs[hover].ranAt)) > W * 0.7 ? 'translateX(calc(-100% - 20px))' : undefined,
              }}
            >
              <div className="muted num" style={{ marginBottom: 4 }}>
                {hhmmss(ms(runs[hover].ranAt))} 비교
              </div>
              <div className="row">
                <span>상태가 다른 주문</span>
                <b className="num" style={{ marginLeft: 'auto', paddingLeft: 16 }}>
                  {runs[hover].mismatches}건
                </b>
              </div>
              <div className="row muted">
                <span>비교한 주문</span>
                <span className="num" style={{ marginLeft: 'auto', paddingLeft: 16 }}>
                  {runs[hover].compared}건
                </span>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
