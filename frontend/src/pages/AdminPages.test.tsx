import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AdminPages } from './AdminPages';
import { fakeFetch } from '../test-fetch';

const unlinkedRow = (id: number, optionNo: number) => ({
  id,
  orderProductOptionNo: optionNo,
  orderNo: `DEMO${optionNo}`,
  productName: '데모 티셔츠',
  optionName: '색상',
  optionValue: '빨강',
  reasonCode: 'OPTION_NOT_MAPPED',
  detail: '3PL에 없는 옵션',
  detectedAt: '2026-11-12T10:00:01',
  resolvedAt: null,
  resolver: null,
});

function routes(overrides: Record<string, (c: never) => unknown> = {}) {
  return {
    'GET /admin/unlinked': () => [unlinkedRow(7, 1000003)],
    'GET /admin/conflicts': () => [
      { id: 3, targetKey: '1000009', reasonCode: 'BACKWARD_TRANSITION', detail: 'DELIVERY_ING→PAY_DONE', detectedAt: '2026-11-12T10:00:02', resolvedAt: null },
    ],
    'GET /admin/recalls': () => [
      { id: 1, orderProductOptionNo: 1000011, claimNo: 500001, invoiceNo: 'INV1000011', reason: 'CANCEL_AFTER_PICKING', createdAt: '2026-11-12T10:00:03', resolvedAt: null },
    ],
    'GET /admin/history': () => [],
    ...overrides,
  };
}

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    // 저장소가 없어도 기본 처리자로 진행한다
  }
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('사람이 처리할 일 화면 (F-15)', () => {
  it('창고 등록 실패 목록을 쉬운 사유와 미처리 상태로 보여 주고, 사유 코드는 마우스를 올리면 보인다', async () => {
    const { calls } = fakeFetch(routes());
    render(<AdminPages tab="unlinked" />);
    expect(await screen.findByText('데모 티셔츠')).toBeInTheDocument();
    expect(screen.getByText('창고에 없는 옵션')).toHaveAttribute('title', 'OPTION_NOT_MAPPED');
    expect(screen.getByText('미처리', { selector: '.badge' })).toHaveClass('warn');
    expect(calls.some((c) => c.url === '/admin/unlinked?status=OPEN')).toBe(true);
  });

  it('확인 필요와 회수 안내 목록은 사유와 할 일을 보여 준다', async () => {
    fakeFetch(routes());
    const { unmount } = render(<AdminPages tab="conflicts" />);
    expect(await screen.findByText('주문 상태가 거꾸로 바뀜')).toHaveAttribute('title', 'BACKWARD_TRANSITION');
    expect(screen.getByText('주문 상품 1000009')).toBeInTheDocument();
    expect(screen.getByText('쇼핑몰 관리자 화면에서 주문 상태를 확인하세요')).toBeInTheDocument();
    unmount();
    render(<AdminPages tab="recalls" />);
    expect(await screen.findByText('INV1000011')).toBeInTheDocument();
    expect(screen.getByText('피킹 뒤 취소')).toBeInTheDocument();
  });

  it('찾기는 주문번호로 목록을 좁힌다', async () => {
    fakeFetch(routes({ 'GET /admin/unlinked': () => [unlinkedRow(7, 1000003), unlinkedRow(8, 1000004)] }));
    render(<AdminPages tab="unlinked" />);
    await screen.findAllByText('데모 티셔츠');
    fireEvent.change(screen.getByRole('searchbox', { name: '찾기' }), { target: { value: '1000004' } });
    expect(screen.getAllByText('데모 티셔츠')).toHaveLength(1);
    expect(screen.getByText('DEMO1000004')).toBeInTheDocument();
  });

  it('골라서 다시 처리하면 건마다 다른 Idempotency-Key를 보내고 결과를 보여 준다', async () => {
    let resolved = false;
    const { calls } = fakeFetch(
      routes({
        'GET /admin/unlinked': () => (resolved ? [] : [unlinkedRow(7, 1000003), unlinkedRow(8, 1000004)]),
        'POST /admin/unlinked/7/reprocess': () => ({ duplicateRequest: false, status: 'DONE', result: 'RESOLVED', detail: null }),
        'POST /admin/unlinked/8/reprocess': () => {
          resolved = true;
          return { duplicateRequest: false, status: 'DONE', result: 'STILL_UNLINKED', detail: '매핑 없음' };
        },
      }),
    );
    render(<AdminPages tab="unlinked" />);
    await screen.findAllByText('데모 티셔츠');
    expect(screen.getByRole('button', { name: '선택한 0건 창고에 다시 등록' })).toBeDisabled();

    fireEvent.click(screen.getByRole('checkbox', { name: '전체 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 2건 창고에 다시 등록' }));

    expect(await screen.findByText('DEMO1000003 해결: 창고 등록과 상품준비중 변경을 마쳤습니다')).toBeInTheDocument();
    expect(screen.getByText('DEMO1000004 아직 창고에 등록 안 됨: 창고 옵션 설정을 먼저 고쳐 주세요')).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0].headers['Idempotency-Key']).not.toBe(posts[1].headers['Idempotency-Key']);
    expect(posts.every((p) => p.headers['X-Actor'] === 'cs-demo')).toBe(true);
    await waitFor(() => expect(screen.getByText('창고 등록에 실패한 주문이 없습니다')).toBeInTheDocument());
  });

  it('처리 이력에 처리자와 결과를 보여 준다', async () => {
    fakeFetch(
      routes({
        'GET /admin/history': () => [
          {
            requestId: 'abcdef12-0000',
            actor: 'cs-demo',
            action: 'REPROCESS_UNLINKED',
            targetKey: 'unlinked:7',
            status: 'DONE',
            result: 'RESOLVED',
            detail: null,
            createdAt: '2026-11-12T10:01:00',
            finishedAt: '2026-11-12T10:01:01',
          },
        ],
      }),
    );
    render(<AdminPages tab="history" />);
    const row = (await screen.findByText('창고 등록 실패 #7')).closest('tr')!;
    expect(within(row).getByText('창고 등록 다시 시도')).toBeInTheDocument();
    expect(within(row).getByText('해결')).toHaveClass('badge', 'good');
    expect(within(row).getByText('cs-demo')).toBeInTheDocument();
  });

  it('재처리가 실패(503)하면 결과 본문의 사유를 보여 준다', async () => {
    fakeFetch(
      routes({
        'POST /admin/unlinked/7/reprocess': () => ({
          status: 503,
          body: { duplicateRequest: false, status: 'FAILED', result: 'RETRY_LATER', detail: '3PL 응답 없음' },
        }),
      }),
    );
    render(<AdminPages tab="unlinked" />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'DEMO1000003 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 1건 창고에 다시 등록' }));
    expect(await screen.findByText('DEMO1000003 잠시 후 다시 시도: 창고나 쇼핑몰이 응답하지 않습니다')).toBeInTheDocument();
  });

  it('담당자 이름에 한글이 있으면 다시 처리를 막고 이유를 알려 준다', async () => {
    fakeFetch(routes());
    render(<AdminPages tab="unlinked" />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'DEMO1000003 선택' }));
    fireEvent.change(screen.getByLabelText('담당자 이름'), { target: { value: '김상담' } });
    expect(screen.getByText(/담당자 이름은 영문과 숫자로/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '선택한 1건 창고에 다시 등록' })).toBeDisabled();
  });
});
