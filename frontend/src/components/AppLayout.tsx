import { useEffect } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { DriverLocationTracker } from './DriverLocationTracker'

export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => {
    // 라우트가 바뀌어도 스크롤 위치는 남는다. 목록 아래쪽에서 상세로 들어가면
    // 새 화면의 중간부터 보이므로 맨 위로 되돌린다.
    window.scrollTo?.({ top: 0, behavior: 'auto' })
  }, [location.pathname])

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/plans" className="brand" aria-label="Delivery Insight 홈">
          <span className="brand-mark">DI</span>
          <span>
            <strong>Delivery Insight</strong>
          </span>
        </NavLink>

        <nav className="main-nav" aria-label="주요 메뉴">
          <NavLink to="/plans" end>
            {user?.role === 'ROLE_ADMIN' ? '전체 배송 계획' : '내 배송 계획'}
          </NavLink>
          {user?.role === 'ROLE_DELIVERY_DRIVER' && (
            <NavLink to="/market">업무 가져가기</NavLink>
          )}
          {user?.role === 'ROLE_ADMIN' && (
            <NavLink to="/plans/new">업무 등록</NavLink>
          )}
        </nav>

        <div className="user-menu">
          {user?.role === 'ROLE_DELIVERY_DRIVER' && <DriverLocationTracker />}
          <NavLink to="/account" className="user-copy" aria-label="계정 설정">
            <strong>{user?.name}</strong>
            <span>{user?.loginId}</span>
          </NavLink>
          <button type="button" className="button button-ghost button-small" onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      </header>

      <main className="page-container">
        {/*
          key 로 강제 리마운트해서 진입 애니메이션이 매 전환마다 다시 재생되게 한다.
          같은 경로 안에서 파라미터만 바뀌는 경우(/plans/1 -> /plans/2)에도 새 화면으로 보이는 게 맞다.
        */}
        <div key={location.pathname} className="page-transition">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
