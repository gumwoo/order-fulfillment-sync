import { useMemo, useRef, useState } from 'react';

export interface Series {
  key: string;
  label: string;
  color: string;
}

export interface Point {
  t: string;
  values: Record<string, number>;
}

interface Props {
  title: string;
  aside?: string;
  series: Series[];
  points: Point[];
  height?: number;
  emptyText?: string;
}

const W = 600;
const PAD = { top: 8, right: 8, bottom: 22, left: 32 };

/**
 * 한 축 선 그래프 (작은 크기용). 2px 선, 옅은 격자, 2개 이상이면 범례.
 * 마우스를 올리면 십자선과 그 시점 값. "표로 보기"로 같은 데이터를 표로 볼 수 있다.
 */
export function LineChart({ title, aside, series, points, height = 200, emptyText = '데이터가 아직 없습니다' }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);
  const ref = useRef<SVGSVGElement>(null);
  const H = height;

  const maxY = useMemo(() => {
    let m = 0;
    points.forEach((p) => series.forEach((s) => (m = Math.max(m, p.values[s.key] ?? 0))));
    return niceMax(m);
  }, [points, series]);

  const x = (i: number) => PAD.left + (points.length <= 1 ? 0 : (i / (points.length - 1)) * (W - PAD.left - PAD.right));
  const y = (v: number) => PAD.top + (1 - v / maxY) * (H - PAD.top - PAD.bottom);

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    if (!ref.current || points.length === 0) return;
    const rect = ref.current.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * W;
    const i = Math.round(((px - PAD.left) / (W - PAD.left - PAD.right)) * (points.length - 1));
    setHover(Math.max(0, Math.min(points.length - 1, i)));
  };

  const last = points[points.length - 1];

  return (
    <div className="chart">
      <div className="blockhead">
        <h2>{title}</h2>
        <span className="aside">
          {aside && <span style={{ marginRight: 12 }}>{aside}</span>}
          <button className="link" onClick={() => setAsTable((v) => !v)}>
            {asTable ? '그래프로 보기' : '표로 보기'}
          </button>
        </span>
      </div>
      {series.length >= 2 && !asTable && (
        <div className="legend" style={{ marginBottom: 8 }}>
          {series.map((s) => (
            <span key={s.key}>
              <span className="sw" style={{ background: s.color }} />
              {s.label} <span className="num" style={{ color: 'var(--ink)' }}>{last ? last.values[s.key] ?? 0 : 0}</span>
            </span>
          ))}
        </div>
      )}
      {points.length === 0 ? (
        <div className="empty">{emptyText}</div>
      ) : asTable ? (
        <div className="table-wrap" style={{ maxHeight: H, overflow: 'auto' }}>
          <table className="data">
            <thead>
              <tr>
                <th>시각</th>
                {series.map((s) => (
                  <th key={s.key} className="n">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[...points].reverse().map((p, i) => (
                <tr key={i}>
                  <td className="num">{p.t}</td>
                  {series.map((s) => (
                    <td key={s.key} className="n num">
                      {p.values[s.key] ?? 0}
                    </td>
                  ))}
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
            aria-label={`${title}: 최근 값 ${series.map((s) => `${s.label} ${last.values[s.key] ?? 0}`).join(', ')}`}
            onMouseMove={onMove}
            onMouseLeave={() => setHover(null)}
          >
            {[0, maxY / 2, maxY].map((t) => (
              <g key={t}>
                <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--axis)' : 'var(--grid)'} />
                <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" fontSize={11} fill="var(--muted)">
                  {t}
                </text>
              </g>
            ))}
            <text x={PAD.left} y={H - 4} fontSize={11} fill="var(--muted)">
              {points[0].t}
            </text>
            <text x={W - PAD.right} y={H - 4} fontSize={11} fill="var(--muted)" textAnchor="end">
              {last.t}
            </text>
            {series.map((s) => (
              <path
                key={s.key}
                d={points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p.values[s.key] ?? 0).toFixed(1)}`).join(' ')}
                fill="none"
                stroke={s.color}
                strokeWidth={2}
                strokeLinejoin="round"
                strokeLinecap="round"
              />
            ))}
            {hover !== null && (
              <g>
                <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={H - PAD.bottom} stroke="var(--axis)" />
                {series.map((s) => (
                  <circle key={s.key} cx={x(hover)} cy={y(points[hover].values[s.key] ?? 0)} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />
                ))}
              </g>
            )}
            <rect x={PAD.left} y={PAD.top} width={W - PAD.left - PAD.right} height={H - PAD.top - PAD.bottom} fill="transparent" />
          </svg>
          {hover !== null && (
            <div
              className="tip"
              style={{ left: `calc(${(x(hover) / W) * 100}% + 10px)`, top: 60, transform: x(hover) > W * 0.6 ? 'translateX(calc(-100% - 20px))' : undefined }}
            >
              <div className="muted num" style={{ marginBottom: 4 }}>
                {points[hover].t}
              </div>
              {series.map((s) => (
                <div className="row" key={s.key}>
                  <span className="sw" style={{ background: s.color }} />
                  <span>{s.label}</span>
                  <b className="num" style={{ marginLeft: 'auto', paddingLeft: 12 }}>
                    {points[hover].values[s.key] ?? 0}
                  </b>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export function niceMax(m: number): number {
  if (m <= 4) return 4;
  const pow = Math.pow(10, Math.floor(Math.log10(m)));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * pow >= m) return step * pow;
  }
  return 10 * pow;
}
