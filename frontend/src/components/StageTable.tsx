import type { Counts } from '../api';
import { ORDER_STATUS_LABEL, TPL_STATUS_LABEL, TPL_STATUSES } from '../labels';

const STAGES = ['PAY_DONE', 'PRODUCT_PREPARE', 'DELIVERY_PREPARE', 'DELIVERY_ING', 'DELIVERY_DONE'] as const;

interface Props {
  shopby: Counts | undefined;
  ours: Counts | undefined;
  tpl: Counts | undefined;
}

/** 같은 주문의 단계별 건수. 우리 DB가 쇼핑몰 원장과 다르면 빨간 숫자와 차이를 붙인다. */
export function StageTable({ shopby, ours, tpl }: Props) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th>구분</th>
            {STAGES.map((s) => (
              <th key={s} className="n">
                {ORDER_STATUS_LABEL[s]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td title="원장: 쇼핑몰 주문 조회 결과">쇼핑몰 기준</td>
            {STAGES.map((s) => (
              <td key={s} className="n num">
                {shopby?.[s] ?? 0}
              </td>
            ))}
          </tr>
          <tr>
            <td>우리 시스템</td>
            {STAGES.map((s) => {
              const n = ours?.[s] ?? 0;
              const d = shopby && ours ? n - (shopby[s] ?? 0) : 0;
              return (
                <td key={s} className={`n num${d !== 0 ? ' diff' : ''}`} title={d !== 0 ? '쇼핑몰과 다름' : undefined}>
                  {n}
                  {d !== 0 && <span className="delta">({d > 0 ? `+${d}` : d})</span>}
                </td>
              );
            })}
          </tr>
        </tbody>
      </table>
      <table className="data" style={{ marginTop: 10 }}>
        <thead>
          <tr>
            <th>구분</th>
            {TPL_STATUSES.map((s) => (
              <th key={s} className="n">
                {TPL_STATUS_LABEL[s]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td title="3PL">물류 창고</td>
            {TPL_STATUSES.map((s) => (
              <td key={s} className="n num">
                {tpl?.[s] ?? 0}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}
