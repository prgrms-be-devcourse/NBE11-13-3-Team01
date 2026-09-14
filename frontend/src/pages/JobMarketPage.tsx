import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { claimDeliveryPlan, getOpenDeliveryPlans } from '../api/deliveryPlans'
import { ApiError } from '../api/client'
import type { OpenDeliveryPlan } from '../types/api'
import { errorMessage, formatDateTime } from '../utils/format'

const REFRESH_INTERVAL_MS = 5_000

/**
 * 수령 실패는 "왜 실패했고 다시 시도하면 되는지"가 전부 다르므로
 * 서버 에러 코드를 그대로 문구에 대응시킨다.
 */
const CLAIM_ERROR_MESSAGE: Record<string, string> = {
  DELIVERY_PLAN_ALREADY_CLAIMED: '방금 다른 기사가 가져갔어요. 목록을 새로 불러옵니다.',
  DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED: '동시에 가질 수 있는 업무 수를 넘었어요. 진행 중인 업무를 먼저 마쳐 주세요.',
  DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE: '아직 추천 기사 우선 수령 시간이에요. 공개 시각 이후에 다시 시도해 주세요.',
  DELIVERY_CLAIM_LOCK_CONFLICT: '다른 요청을 처리하고 있어요. 잠시 후 다시 시도해 주세요.',
}

function claimErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const code = error.response?.code
    if (code && CLAIM_ERROR_MESSAGE[code]) return CLAIM_ERROR_MESSAGE[code]
  }
  return errorMessage(error)
}

function remainingSeconds(publicAt: string | null, now: number) {
  if (!publicAt) return 0
  return Math.max(0, Math.ceil((new Date(publicAt).getTime() - now) / 1000))
}

function formatCountdown(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes}분 ${rest}초` : `${rest}초`
}

export function JobMarketPage() {
  const navigate = useNavigate()
  const [plans, setPlans] = useState<OpenDeliveryPlan[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [claimingPlanId, setClaimingPlanId] = useState<number | null>(null)
  // 공개까지 남은 시간을 1초마다 다시 그리기 위한 로컬 시계
  const [now, setNow] = useState(() => Date.now())

  const loadPlans = useCallback(async () => {
    try {
      setPlans(await getOpenDeliveryPlans())
      setError('')
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- 수령 대기 업무는 화면 진입 후 주기적으로 동기화한다.
    void loadPlans()
    const intervalId = window.setInterval(() => void loadPlans(), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(intervalId)
  }, [loadPlans])

  useEffect(() => {
    const tickId = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(tickId)
  }, [])

  const handleClaim = async (planId: number) => {
    setClaimingPlanId(planId)
    setError('')
    try {
      const response = await claimDeliveryPlan(planId)
      // alreadyOwned 는 중복 클릭·재시도로 이미 내 것이 된 경우다. 둘 다 상세로 보낸다.
      navigate(`/plans/${response.planId}`)
    } catch (caughtError) {
      setError(claimErrorMessage(caughtError))
      // 경합에서 밀렸을 수 있으니 최신 목록으로 맞춘다.
      void loadPlans()
    } finally {
      setClaimingPlanId(null)
    }
  }

  return (
    <div className="page-stack">
      <section className="page-heading">
        <span className="eyebrow">JOB MARKET</span>
        <h1>배송 업무 가져가기</h1>
        <p>
          등록된 업무를 먼저 가져가는 기사가 담당합니다.
          추천 상위 기사에게는 잠시 동안 우선 수령 권한이 주어집니다.
        </p>
      </section>

      {error && <div className="alert alert-error">{error}</div>}

      {isLoading ? (
        <section className="empty-state"><p>수령 대기 중인 업무를 불러오는 중...</p></section>
      ) : plans.length === 0 ? (
        <section className="empty-state">
          <h2>지금 가져갈 수 있는 업무가 없어요</h2>
          <p>새 업무가 등록되면 이 화면에 바로 나타납니다.</p>
        </section>
      ) : (
        <section className="market-grid" aria-label="수령 대기 업무 목록">
          {plans.map((plan) => {
            const countdown = remainingSeconds(plan.publicAt, now)
            const isLocked = !plan.claimableNow && countdown > 0
            // 반납된 업무는 public_at 만 비워지고 우선권 행은 남는다.
            // 따라서 순위가 있어도 윈도우가 끝났으면 우선권 표시를 하지 않는다.
            const hasActivePriority = plan.priorityRank !== null && countdown > 0

            return (
              <article
                key={plan.planId}
                className={`market-card${isLocked ? ' is-locked' : ''}`}
              >
                <header className="market-card-head">
                  <div>
                    <span className="plan-number">#{String(plan.planId).padStart(3, '0')}</span>
                    <h2>{plan.departureLocation}</h2>
                    <p className="plan-time">예정 · {formatDateTime(plan.scheduledDepartureAt)}</p>
                  </div>
                  {hasActivePriority && (
                    <span className="badge priority-badge">
                      우선 수령 {plan.priorityRank}순위
                    </span>
                  )}
                </header>

                <dl className="market-card-stats">
                  <div>
                    <dt>배송지</dt>
                    <dd>{plan.totalStops}곳</dd>
                  </div>
                  <div>
                    <dt>박스</dt>
                    <dd>{plan.totalBoxes}개</dd>
                  </div>
                  <div>
                    <dt>위험 배송지</dt>
                    <dd className={plan.dangerStops > 0 ? 'danger-text' : ''}>
                      {plan.dangerStops}곳
                    </dd>
                  </div>
                </dl>

                <footer className="market-card-foot">
                  {isLocked ? (
                    <p className="market-card-hint">
                      추천 기사 우선 수령 중 · <strong>{formatCountdown(countdown)}</strong> 뒤 전체 공개
                    </p>
                  ) : hasActivePriority ? (
                    <p className="market-card-hint priority-hint">
                      <strong>{formatCountdown(countdown)}</strong> 동안 추천 기사끼리만 가져갈 수 있어요
                    </p>
                  ) : (
                    <p className="market-card-hint">전체 공개 · 먼저 누르는 기사가 담당합니다</p>
                  )}
                  <button
                    type="button"
                    className="button button-primary"
                    disabled={isLocked || claimingPlanId !== null}
                    onClick={() => void handleClaim(plan.planId)}
                  >
                    {claimingPlanId === plan.planId ? '가져오는 중...' : '가져오기'}
                  </button>
                </footer>
              </article>
            )
          })}
        </section>
      )}
    </div>
  )
}
