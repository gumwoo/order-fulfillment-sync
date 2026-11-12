import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { App } from '../App';
import { story } from './Dashboard';
import { fakeFetch } from '../test-fetch';

function overview(mismatches: number, dlq = 0) {
  return {
    at: '2026-11-12T10:00:10',
    orderLines: { PAY_DONE: 10, PRODUCT_PREPARE: 30 },
    openClaims: {},
    statusApplied: 30,
    processedEvents: { WEBHOOK: 28, POLLING: 2 },
    openConflicts: { BACKWARD_TRANSITION: 1 },
    openUnlinked: 2,
    openRecalls: 0,
    cancelCases: {},
    shipments: { REQUESTED: 30 },
    invoicePending: 0,
    invoiceSynced: 0,
    alertsUnnotified: 0,
    notifications: 0,
    queue: { visible: 4, inFlight: 1, delayed: 6 },
    dlq: { visible: dlq, inFlight: 0, delayed: 0 },
    lastReconciliation: { ranAt: '2026-11-12T10:00:05', compared: 40, mismatches },
  };
}

const mockState = {
  shopby: { orderOptions: 40, byStatus: { PRODUCT_PREPARE: 40 }, claims: {}, holdDelivery: 0 },
  tpl: { REQUESTED: 40 },
  webhooks: { sent: 42, dropped: 12 },
  shopbyApi: { maxCallsInAnySecond: 12, rejected429: 3 },
};

const runs = [
  { ranAt: '2026-11-12T10:00:00', compared: 40, mismatches: 12 },
  { ranAt: '2026-11-12T10:00:05', compared: 40, mismatches: 10 },
];

const metric = (label: string) => screen.getByRole('group', { name: label });

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('동기화 현황 화면 (F-16)', () => {
  it('복구 요약, 지표, 단계별 차이, 사이드바 건수를 보여 준다', async () => {
    fakeFetch({
      'GET /admin/overview': () => overview(10, 2),
      'GET /admin/reconciliation/runs': () => runs,
      'GET /mock/demo/state': () => mockState,
    });
    render(<App />);

    expect(await screen.findByText('10:00:10에 새로 고침')).toBeInTheDocument();
    expect(await screen.findByText('10건')).toBeInTheDocument(); // 장애 기록 없이 불일치만 있을 때 요약
    expect(within(metric('쇼핑몰과 같은 주문')).getByText('30')).toBeInTheDocument();
    expect(within(metric('자동 처리 실패')).getByText('확인 필요')).toBeInTheDocument();
    expect(within(metric('자동 처리 대기')).getByText('11')).toBeInTheDocument();
    expect(await screen.findByText('(-10)')).toBeInTheDocument();
    expect(await screen.findByText(/주문 알림 42건 중 12건이 사라졌습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText('창고 등록 실패 2건 남음')).toHaveClass('alert');
    expect(screen.getByRole('img', { name: /복구 과정: 마지막 비교에서 상태가 다른 주문 10건/ })).toBeInTheDocument();
  });

  it('다른 주문과 자동 처리 실패가 없으면 모두 같음과 없음으로 보인다', async () => {
    fakeFetch({
      'GET /admin/overview': () => overview(0),
      'GET /admin/reconciliation/runs': () => [{ ranAt: '2026-11-12T10:00:05', compared: 40, mismatches: 0 }],
      'GET /mock/demo/state': () => mockState,
    });
    render(<App />);
    expect(await screen.findByText('모두 같습니다')).toBeInTheDocument();
    expect(within(metric('자동 처리 실패')).getByText('없음')).toBeInTheDocument();
  });

  it('자주 쓰는 상황을 시작하면 정한 설정으로 가상 쇼핑몰에 요청하고 테스트 기록에 남긴다', async () => {
    const { calls } = fakeFetch({
      'GET /admin/overview': () => overview(0),
      'GET /admin/reconciliation/runs': () => [],
      'GET /mock/demo/state': () => mockState,
      'POST /mock/demo/orders': () => ({ created: 50, unmapped: 0, webhooksDropped: 15 }),
    });
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: '장애 테스트' }));
    const dialog = screen.getByRole('dialog', { name: '장애 테스트' });
    const preset = within(dialog).getByText('주문 알림이 사라짐', { selector: 'strong' }).closest('.preset') as HTMLElement;
    fireEvent.click(within(preset).getByRole('button', { name: '시작' }));

    expect(await screen.findByText('주문 50건을 넣었고 알림 15건이 사라졌습니다')).toBeInTheDocument();
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toMatchObject({ count: 50, dropRatio: 0.3, duplicates: 2, recordDroppedAsFailed: true });

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('현황 API가 503이면 서버에 연결할 수 없다고 보인다', async () => {
    fakeFetch({
      'GET /admin/overview': () => ({ status: 503, body: { message: 'down' } }),
      'GET /admin/reconciliation/runs': () => [],
      'GET /mock/demo/state': () => mockState,
    });
    render(<App />);
    expect(await screen.findByText('서버에 연결할 수 없음')).toBeInTheDocument();
  });
});

describe('복구 요약 문장', () => {
  const at = new Date('2026-11-12T10:00:00').getTime();
  const fault = [{ at, label: '웹훅 30% 유실' }];
  const text = (n: React.ReactNode) => {
    const { container } = render(<p>{n}</p>);
    const t = container.textContent;
    cleanup();
    return t;
  };

  it('불일치가 생겼다가 0이 되면 걸린 시간을 말한다', () => {
    const s = story(
      [
        { ranAt: '2026-11-12T10:00:05', compared: 50, mismatches: 37 },
        { ranAt: '2026-11-12T10:00:10', compared: 50, mismatches: 20 },
        { ranAt: '2026-11-12T10:00:21', compared: 50, mismatches: 0 },
      ],
      fault,
    );
    expect(s.tone).toBe('good');
    expect(text(s.text)).toBe('웹훅 30% 유실 → 상태가 다른 주문 최대 37건 → 21초 만에 모두 맞춰짐');
  });

  it('10초 안에 연달아 넣은 장애는 한 사건으로 묶는다', () => {
    const s = story(
      [
        { ranAt: '2026-11-12T10:00:15', compared: 50, mismatches: 40 },
        { ranAt: '2026-11-12T10:01:00', compared: 50, mismatches: 0 },
      ],
      [
        { at, label: '429 30회' },
        { at: at + 1000, label: '웹훅 30% 유실' },
      ],
    );
    expect(text(s.text)).toBe('429 30회, 웹훅 30% 유실 → 상태가 다른 주문 최대 40건 → 60초 만에 모두 맞춰짐');
  });

  it('아직 남아 있으면 복구 중이라고 말한다', () => {
    const s = story([{ ranAt: '2026-11-12T10:00:05', compared: 50, mismatches: 37 }], fault);
    expect(s.tone).toBe('bad');
    expect(text(s.text)).toBe('웹훅 30% 유실 → 상태가 다른 주문 37건 맞추는 중');
  });

  it('장애 뒤 대사가 아직 차이를 못 찾았으면 찾는 중이라고 말한다', () => {
    const s = story([{ ranAt: '2026-11-12T09:59:55', compared: 0, mismatches: 0 }], fault);
    expect(text(s.text)).toBe('웹훅 30% 유실, 차이를 찾는 중입니다');
  });
});
