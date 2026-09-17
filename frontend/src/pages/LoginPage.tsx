import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { consumeAuthNotice } from '../auth/notice'
import { errorMessage } from '../utils/format'

interface LoginLocationState {
  from?: string
}

export function LoginPage() {
  const { isAuthenticated, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  // 마운트할 때 한 번만 읽고 지운다. 새로고침하면 다시 보이면 안 된다.
  const [notice] = useState(consumeAuthNotice)
  const [isSubmitting, setIsSubmitting] = useState(false)

  if (isAuthenticated) return <Navigate to="/plans" replace />

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError('')
    setIsSubmitting(true)

    try {
      await login(loginId, password)
      const state = location.state as LoginLocationState | null
      navigate(state?.from || '/plans', { replace: true })
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-visual" aria-label="서비스 소개">
        <div className="login-brand">
          <span className="brand-mark brand-mark-light">DI</span>
          <strong>Delivery Insight</strong>
        </div>
        <div className="login-message">
          <span className="eyebrow eyebrow-light">WEATHER-AWARE DELIVERY</span>
          <h1>날씨를 먼저 보고,<br />더 안전하게 배송하세요.</h1>
          <p>배송지별 위험도를 한눈에 확인하고 오늘의 배송 흐름을 관리합니다.</p>
        </div>
        <div className="weather-orbit" aria-hidden="true">
          <span className="orbit-ring orbit-ring-large" />
          <span className="orbit-ring orbit-ring-small" />
          <span className="weather-core">☂</span>
          <span className="weather-dot dot-one" />
          <span className="weather-dot dot-two" />
          <span className="weather-dot dot-three" />
        </div>
      </section>

      <section className="login-panel">
        <form className="login-form" onSubmit={handleSubmit}>
          <div>
            <span className="eyebrow">DRIVER ACCESS</span>
            <h2>다시 만나서 반가워요</h2>
            <p className="muted">오늘의 배송 계획을 확인하려면 로그인하세요.</p>
          </div>

          <label className="field">
            <span>아이디</span>
            <input
              type="text"
              value={loginId}
              onChange={(event) => setLoginId(event.target.value)}
              placeholder="아이디를 입력하세요"
              autoComplete="username"
              required
            />
          </label>

          <label className="field">
            <span>비밀번호</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="비밀번호를 입력하세요"
              autoComplete="current-password"
              required
            />
          </label>

          {notice && <div className="alert alert-notice">{notice}</div>}
          {error && <div className="alert alert-error">{error}</div>}

          <button className="button button-primary button-large" type="submit" disabled={isSubmitting}>
            {isSubmitting ? '로그인 중...' : '로그인'}
          </button>
          <p className="login-help">개발 중에는 백엔드에 등록된 배송 기사 계정을 사용하세요.</p>
        </form>
      </section>
    </main>
  )
}
