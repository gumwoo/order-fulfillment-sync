// 코드 값을 운영자가 읽는 말로 바꾼다. 원래 코드는 마우스를 올리면 보이게 title로 둔다.

export const ORDER_STATUSES = [
  'DEPOSIT_WAIT',
  'PAY_DONE',
  'PRODUCT_PREPARE',
  'DELIVERY_PREPARE',
  'DELIVERY_ING',
  'DELIVERY_DONE',
  'BUY_CONFIRM',
] as const;

export const ORDER_STATUS_LABEL: Record<string, string> = {
  DEPOSIT_WAIT: '입금대기',
  PAY_DONE: '결제완료',
  PRODUCT_PREPARE: '상품준비중',
  DELIVERY_PREPARE: '배송준비중',
  DELIVERY_ING: '배송중',
  DELIVERY_DONE: '배송완료',
  BUY_CONFIRM: '구매확정',
};

export const TPL_STATUSES = ['REQUESTED', 'WORKING', 'PICKED', 'RELEASED', 'CANCELED'] as const;

export const TPL_STATUS_LABEL: Record<string, string> = {
  REQUESTED: '출고요청',
  WORKING: '출고작업',
  PICKED: '피킹',
  RELEASED: '출고완료',
  CANCELED: '취소',
};

/** 사람이 처리할 건의 사유. 운영자가 바로 알아듣는 말로 */
export const REASON_LABEL: Record<string, string> = {
  OPTION_NOT_MAPPED: '창고에 없는 옵션',
  BACKWARD_TRANSITION: '주문 상태가 거꾸로 바뀜',
  HOLD_DELIVERY_FAILED: '배송 보류 실패',
  CLAIM_APPROVE_REJECTED: '취소 승인이 거절됨',
  ALREADY_DELIVERY_REJECTED: '이미 출고 처리가 거절됨',
  RELEASE_WITHOUT_INVOICE: '출고됐는데 송장이 없음',
  INVOICE_SYNC_FAILED: '송장을 쇼핑몰에 못 올림',
  INVOICE_MISMATCH_IN_DELIVERY: '한 배송에 송장이 여러 개',
  PREPARE_PRODUCT_REJECTED: '상품준비중 변경이 거절됨',
  CANCEL_AFTER_PICKING: '피킹 뒤 취소',
};

/** 사유마다 운영자가 할 일 (확인 필요 목록의 "내용" 칸) */
export const REASON_HELP: Record<string, string> = {
  BACKWARD_TRANSITION: '쇼핑몰 관리자 화면에서 주문 상태를 확인하세요',
  HOLD_DELIVERY_FAILED: '창고에 출고를 멈춰 달라고 직접 요청하세요',
  CLAIM_APPROVE_REJECTED: '쇼핑몰 관리자 화면에서 취소 상태를 확인하세요',
  ALREADY_DELIVERY_REJECTED: '쇼핑몰 관리자 화면에서 클레임 상태를 확인하세요',
  RELEASE_WITHOUT_INVOICE: '창고에 송장 번호를 문의하세요',
  INVOICE_SYNC_FAILED: '쇼핑몰 관리자 화면에서 송장을 직접 입력하세요',
  INVOICE_MISMATCH_IN_DELIVERY: '창고에 어느 송장이 맞는지 문의하세요',
  PREPARE_PRODUCT_REJECTED: '쇼핑몰 관리자 화면에서 주문 상태를 확인하세요',
};

export const RESULT_LABEL: Record<string, string> = {
  RESOLVED: '해결',
  ALREADY_RESOLVED: '이미 해결됨',
  IN_PROGRESS_BY_OTHER: '다른 사람이 처리 중',
  STILL_UNLINKED: '아직 창고에 등록 안 됨',
  CLOSED_CANCELED: '주문 취소로 종료',
  SKIPPED: '처리할 필요 없음',
  NOT_FOUND: '찾을 수 없음',
  RETRY_LATER: '잠시 후 다시 시도',
};

/** 다시 처리 결과에 붙일 설명. 서버가 주는 상세 문구 대신 쓴다 */
export const RESULT_HELP: Record<string, string> = {
  RESOLVED: '창고 등록과 상품준비중 변경을 마쳤습니다',
  ALREADY_RESOLVED: '자동 재시도가 먼저 처리했습니다',
  STILL_UNLINKED: '창고 옵션 설정을 먼저 고쳐 주세요',
  RETRY_LATER: '창고나 쇼핑몰이 응답하지 않습니다',
};

/** 누가 처리했는지. 시스템이 스스로 해결한 경우 */
export function who(resolver: string | null | undefined): string {
  if (!resolver) return '';
  return resolver === 'SYSTEM' ? '자동 처리' : resolver;
}

export function sum(counts: Record<string, number> | undefined): number {
  return Object.values(counts ?? {}).reduce((a, b) => a + b, 0);
}

export function timeOf(iso: string | null | undefined): string {
  if (!iso) return '-';
  // Jackson은 LocalDateTime의 초가 0이면 "HH:MM"까지만 쓴다
  const t = (iso.split('T')[1] ?? iso).slice(0, 8);
  return t.length === 5 ? `${t}:00` : t;
}

/** "12초 전", "3분 전". 서버 시각(LocalDateTime)은 같은 PC의 현지 시각이다 */
export function ago(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '-';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return `${s}초 전`;
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  if (s < 86400) return `${Math.floor(s / 3600)}시간 전`;
  return `${Math.floor(s / 86400)}일 전`;
}
