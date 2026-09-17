import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { getDeliveryPlans } from '../api/deliveryPlans'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { StatusBadge } from '../components/StatusBadge'
import { useAuth } from '../auth/AuthContext'
import { setAuthNotice } from '../auth/notice'
import type { DeliveryPlanSummary } from '../types/api'
import { errorMessage, formatDateTime } from '../utils/format'

const ROLE_LABEL: Record<string, string> = {
  ROLE_ADMIN: '관리자',
  ROLE_DELIVERY_DRIVER: '배송 기사',
}

/** 탈퇴를 막는 상태. 서버의 DeliveryPlanStatus.ACTIVE_STATUSES 와 같은 값이어야 한다. */
const ACTIVE_STATUSES = ['READY', 'DELIVERING'] as const

function isActive(plan: DeliveryPlanSummary) {
  return (ACTIVE_STATUSES as readonly string[]).includes(plan.status)
}

export function AccountPage() {
  const { user, withdraw } = useAuth()
  const navigate = useNavigate()
  const [isDialogOpen, setIsDialogOpen] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')

  const isDriver = user?.role === 'ROLE_DELIVERY_DRIVER'
  const [activePlans, setActivePlans] = useState<DeliveryPlanSummary[]>([])
  // 관리자는 기사로서 업무를 들 수 없으므로 조회 자체를 하지 않는다.
  const [isCheckingPlans, setIsCheckingPlans] = useState(isDriver)

  /**
   * 진행 중인 업무를 확인한다.
   *
   * 이 조회는 **안내용**이다. 진짜 차단은 서버가 한다. 화면이 막아도 다른 탭에서
   * 업무를 수령하면 그 사이에 상태가 바뀌기 때문에, 서버가 409 를 주면 목록을 다시 읽는다.
   */
  const loadActivePlans = useCallback(async () => {
    if (!isDriver) return

    setIsCheckingPlans(true)
    try {
      const plans = await getDeliveryPlans()
      setActivePlans(plans.filter(isActive))
    } catch {
      // 조회에 실패하면 차단하지 않는다. 최종 판단은 어차피 서버가 한다.
      setActivePlans([])
    } finally {
      setIsCheckingPlans(false)
    }
  }, [isDriver])

  useEffect(() => {
    loadActivePlans()
  }, [loadActivePlans])

  const hasActivePlan = activePlans.length > 0
  const canWithdraw = !isCheckingPlans && !hasActivePlan && !isSubmitting

  const handleWithdraw = async () => {
    setError('')
    setIsSubmitting(true)

    try {
      await withdraw()
      setAuthNotice('회원 탈퇴가 완료되었습니다. 그동안 이용해 주셔서 감사합니다.')
      navigate('/login', { replace: true })
      // 성공 경로에서는 상태를 되돌리지 않는다. 곧 언마운트될 컴포넌트에 setState 하면 경고가 난다.
    } catch (caughtError) {
      setError(errorMessage(caughtError))
      setIsSubmitting(false)
      setIsDialogOpen(false)
      // 화면을 연 뒤에 업무를 수령했을 수 있다. 서버가 거절했으면 목록부터 다시 맞춘다.
      loadActivePlans()
    }
  }

  return (
    <div className="page-stack page-narrow">
      <div className="page-heading">
        <span className="eyebrow">ACCOUNT</span>
        <h1>계정 설정</h1>
        <p>로그인 정보를 확인하고 계정을 관리합니다.</p>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <section className="form-card">
        <div className="section-title">
          <span className="section-number">01</span>
          <div>
            <h2>계정 정보</h2>
            <p>서버에 등록된 내 정보입니다.</p>
          </div>
        </div>

        <dl className="account-facts">
          <div>
            <dt>이름</dt>
            <dd>{user?.name ?? '-'}</dd>
          </div>
          <div>
            <dt>아이디</dt>
            <dd>{user?.loginId ?? '-'}</dd>
          </div>
          <div>
            <dt>권한</dt>
            <dd>{user ? (ROLE_LABEL[user.role] ?? user.role) : '-'}</dd>
          </div>
        </dl>
      </section>

      <section className="form-card danger-zone" aria-labelledby="danger-zone-title">
        <div className="section-title">
          <span className="section-number section-number-danger">!</span>
          <div>
            <h2 id="danger-zone-title">회원 탈퇴</h2>
            <p>
              탈퇴하면 즉시 로그아웃되고 다시 로그인할 수 없습니다. 같은 아이디로 새로 가입할 수 없으니
              신중하게 결정해 주세요.
            </p>
          </div>
        </div>

        {hasActivePlan ? (
          <div className="withdraw-blocked" role="status">
            <strong>진행 중인 배송 업무가 {activePlans.length}건 있어 탈퇴할 수 없습니다.</strong>
            <p>배송을 완료하거나, 아직 시작하지 않았다면 반납한 뒤에 다시 시도해 주세요.</p>
            <ul className="withdraw-blocked-list">
              {activePlans.map((plan) => (
                <li key={plan.planId}>
                  <Link to={`/plans/${plan.planId}`}>
                    <span>#{String(plan.planId).padStart(3, '0')}</span>
                    <span className="muted">{plan.departureLocation}</span>
                  </Link>
                  <span className="withdraw-blocked-meta">
                    <StatusBadge status={plan.status} />
                    <span className="muted">{formatDateTime(plan.scheduledDepartureAt)}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <ul className="danger-notes">
            <li>탈퇴 후에는 같은 아이디로 다시 로그인할 수 없습니다.</li>
            <li>지금까지의 배송 기록은 통계 목적으로 서버에 남습니다.</li>
          </ul>
        )}

        <div className="form-actions">
          <button
            type="button"
            className="button button-danger"
            onClick={() => setIsDialogOpen(true)}
            disabled={!canWithdraw}
          >
            {isCheckingPlans ? '배송 업무 확인 중...' : '회원 탈퇴'}
          </button>
        </div>
      </section>

      <ConfirmDialog
        open={isDialogOpen}
        tone="danger"
        title="정말 탈퇴하시겠어요?"
        description={
          <>
            <p>
              <strong>{user?.loginId}</strong> 계정이 탈퇴 처리됩니다. 이 작업은 되돌릴 수 없습니다.
            </p>
            <p className="muted">탈퇴 후에는 이 아이디로 다시 로그인할 수 없습니다.</p>
          </>
        }
        confirmLabel={isSubmitting ? '탈퇴 처리 중...' : '탈퇴하기'}
        isBusy={isSubmitting}
        onConfirm={handleWithdraw}
        onCancel={() => setIsDialogOpen(false)}
      />
    </div>
  )
}
