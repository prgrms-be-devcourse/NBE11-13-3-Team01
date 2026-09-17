import { Navigate, Route, Routes } from 'react-router'
import { AdminRoute } from './components/AdminRoute'
import { AppLayout } from './components/AppLayout'
import { DriverRoute } from './components/DriverRoute'
import { ProtectedRoute } from './components/ProtectedRoute'
import { AccountPage } from './pages/AccountPage'
import { CreatePlanPage } from './pages/CreatePlanPage'
import { JobMarketPage } from './pages/JobMarketPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PlanDetailPage } from './pages/PlanDetailPage'
import { PlanListPage } from './pages/PlanListPage'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/plans" replace />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/plans" element={<PlanListPage />} />
          <Route path="/plans/:planId" element={<PlanDetailPage />} />
          <Route element={<DriverRoute />}>
            <Route path="/market" element={<JobMarketPage />} />
          </Route>
          <Route element={<AdminRoute />}>
            <Route path="/plans/new" element={<CreatePlanPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
