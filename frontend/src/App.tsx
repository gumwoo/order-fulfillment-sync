import { useState } from 'react';
import { api } from './api';
import { Dashboard } from './pages/Dashboard';
import { AdminPages, type AdminTab } from './pages/AdminPages';
import { sum, timeOf } from './labels';
import { usePolling } from './usePolling';

type Page = 'dashboard' | AdminTab;

export const ADMIN: { key: AdminTab; label: string }[] = [
  { key: 'unlinked', label: '창고 등록 실패' },
  { key: 'conflicts', label: '확인 필요' },
  { key: 'recalls', label: '회수 안내' },
  { key: 'history', label: '처리 이력' },
];

function pageFromHash(): Page {
  const h = location.hash.replace('#', '');
  return ADMIN.some((a) => a.key === h) ? (h as AdminTab) : 'dashboard';
}

export function App() {
  const [page, setPage] = useState<Page>(pageFromHash);
  const overview = usePolling(api.overview, 2000);
  const o = overview.data;

  const open: Record<AdminTab, number | null> = {
    unlinked: o ? o.openUnlinked : null,
    conflicts: o ? sum(o.openConflicts) : null,
    recalls: o ? o.openRecalls : null,
    history: null,
  };

  const go = (p: Page) => {
    setPage(p);
    history.replaceState(null, '', p === 'dashboard' ? '#' : `#${p}`);
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="product">
          <div className="name">주문 동기화</div>
          <div className="env">연습용 데모, 가상의 쇼핑몰과 창고</div>
        </div>
        <nav aria-label="화면">
          <div className="navgroup">
            <button className="navitem" aria-current={page === 'dashboard' ? 'page' : undefined} onClick={() => go('dashboard')}>
              동기화 현황
            </button>
          </div>
          <div className="navgroup" style={{ marginTop: 18 }}>
            <div className="title">사람이 처리할 일</div>
            {ADMIN.map((a) => {
              const n = open[a.key];
              return (
                <button key={a.key} className="navitem" aria-current={page === a.key ? 'page' : undefined} onClick={() => go(a.key)}>
                  {a.label}
                  {n !== null && (
                    <span className={`count num${n > 0 ? ' alert' : ''}`} aria-label={`${a.label} ${n}건 남음`}>
                      {n}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </nav>
        <div className={`connection${overview.error ? ' down' : ''}`} role="status">
          <span className="dot" aria-hidden />
          {overview.error ? '서버에 연결할 수 없음' : o ? `${timeOf(o.at)}에 새로 고침` : '연결하는 중'}
        </div>
      </aside>
      <main className="content">
        {page === 'dashboard' ? <Dashboard overview={overview} onOpenAdmin={() => go('unlinked')} /> : <AdminPages tab={page} />}
      </main>
    </div>
  );
}
