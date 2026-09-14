import { NavLink, Outlet, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { DriverLocationTracker } from './DriverLocationTracker'

export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

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
          {user?.role === 'ROLE_ADMIN' && (
            <NavLink to="/plans/new">계획 할당</NavLink>
          )}
        </nav>

        <div className="user-menu">
          {user?.role === 'ROLE_DELIVERY_DRIVER' && <DriverLocationTracker />}
          <div className="user-copy">
            <strong>{user?.name}</strong>
            <span>{user?.loginId}</span>
          </div>
          <button type="button" className="button button-ghost button-small" onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      </header>

      <main className="page-container">
        <Outlet />
      </main>
    </div>
  )
}
