import { Navigate, Outlet } from 'react-router'
import { useAuth } from '../auth/AuthContext'

export function DriverRoute() {
  const { user } = useAuth()

  if (user?.role !== 'ROLE_DELIVERY_DRIVER') {
    return <Navigate to="/plans" replace />
  }

  return <Outlet />
}
