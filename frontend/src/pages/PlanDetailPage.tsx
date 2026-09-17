import {
  DndContext,
  KeyboardSensor,
  MouseSensor,
  TouchSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import {
  completeDeliveryPlan,
  completeDeliveryStop,
  getAdminDeliveryPlan,
  getDeliveryPlan,
  getDriverRecommendations,
  getNextStopRecommendation,
  releaseDeliveryPlan,
  reorderDeliveryStops,
  startDeliveryPlan,
} from '../api/deliveryPlans'
import { useAuth } from '../auth/AuthContext'
import { DeliveryRouteMap } from '../components/DeliveryRouteMap'
import { SortableStopCard } from '../components/SortableStopCard'
import { RiskBadge, StatusBadge } from '../components/StatusBadge'
import type {
  DeliveryPlanDetail,
  DeliveryStop,
  DriverRecommendation,
  NextStopRecommendation,
  ProductType,
} from '../types/api'
import {
  errorMessage,
  formatDateTime,
  maskLoginId,
  maskName,
} from '../utils/format'

const PRODUCT_LABEL: Record<ProductType, string> = {
  NORMAL: '상온',
  REFRIGERATED: '냉장',
  FROZEN: '냉동',
  FRAGILE: '파손 주의',
}

type StopView = 'active' | 'completed'

export function PlanDetailPage() {
  const { planId: planIdParam } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const isAdmin = user?.role === 'ROLE_ADMIN'
  const planId = Number(planIdParam)
  const [plan, setPlan] = useState<DeliveryPlanDetail | null>(null)
  const [assignedDriver, setAssignedDriver] = useState<{
    driverId: number
    loginId: string
    name: string
  } | null>(null)
  const [orderedStops, setOrderedStops] = useState<DeliveryStop[]>([])
  const [recommendation, setRecommendation] = useState<NextStopRecommendation | null>(null)
  const [recommendationError, setRecommendationError] = useState('')
  const [driverRecommendation, setDriverRecommendation] = useState<DriverRecommendation | null>(null)
  const [driverRecommendationError, setDriverRecommendationError] = useState('')
  const [isRecommendingDrivers, setIsRecommendingDrivers] = useState(false)
  const [stopView, setStopView] = useState<StopView>('active')
  const [isLoading, setIsLoading] = useState(true)
  const [busyAction, setBusyAction] = useState('')
  const [error, setError] = useState('')
  const [recentlyMovedStopId, setRecentlyMovedStopId] = useState<number | null>(null)
  const movedAnimationTimer = useRef<number | null>(null)
  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: { distance: 8 },
    }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 180, tolerance: 8 },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  )

  const loadPlan = useCallback(async () => {
    if (!Number.isInteger(planId) || planId <= 0) {
      setError('올바르지 않은 배송 계획 번호입니다.')
      setIsLoading(false)
      return
    }

    setError('')
    setRecommendationError('')
    setIsLoading(true)
    try {
      const adminResponse = isAdmin ? await getAdminDeliveryPlan(planId) : null
      const response = adminResponse?.deliveryPlan ?? await getDeliveryPlan(planId)
      setPlan(response)
      setOrderedStops(response.deliveryStops)
      // 미배정(OPEN) 계획은 기사 정보가 모두 null 이므로 담당 기사 블록 자체를 만들지 않는다.
      setAssignedDriver(adminResponse && adminResponse.driverId !== null ? {
        driverId: adminResponse.driverId,
        loginId: adminResponse.driverLoginId ?? '',
        name: adminResponse.driverName ?? '',
      } : null)

      if (response.status === 'COMPLETED') {
        setStopView('completed')
      }

      if (!isAdmin && response.status === 'DELIVERING') {
        try {
          setRecommendation(await getNextStopRecommendation(planId))
        } catch (caughtError) {
          setRecommendation(null)
          setRecommendationError(errorMessage(caughtError))
        }
      } else {
        setRecommendation(null)
      }
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setIsLoading(false)
    }
  }, [isAdmin, planId])

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- 상세 데이터는 화면 진입 시 불러온다.
    void loadPlan()
  }, [loadPlan])

  useEffect(() => () => {
    if (movedAnimationTimer.current !== null) {
      window.clearTimeout(movedAnimationTimer.current)
    }
  }, [])

  /** 반납하면 이 계획은 더 이상 내 것이 아니므로 상세에 머무르지 않고 목록으로 돌아간다. */
  const handleRelease = async () => {
    setError('')
    setBusyAction('release')
    try {
      await releaseDeliveryPlan(planId)
      navigate('/market')
    } catch (caughtError) {
      setError(errorMessage(caughtError))
      setBusyAction('')
    }
  }

  const loadDriverRecommendations = async () => {
    setDriverRecommendationError('')
    setIsRecommendingDrivers(true)
    try {
      setDriverRecommendation(await getDriverRecommendations(planId))
    } catch (caughtError) {
      setDriverRecommendation(null)
      setDriverRecommendationError(errorMessage(caughtError))
    } finally {
      setIsRecommendingDrivers(false)
    }
  }

  const runAction = async (actionName: string, action: () => Promise<void>) => {
    setError('')
    setBusyAction(actionName)
    try {
      await action()
      await loadPlan()
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setBusyAction('')
    }
  }

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return

    const oldIndex = orderedStops.findIndex((stop) => stop.stopId === active.id)
    const newIndex = orderedStops.findIndex((stop) => stop.stopId === over.id)
    if (oldIndex < 0 || newIndex < 0) return

    setOrderedStops((current) => arrayMove(current, oldIndex, newIndex))
    setRecentlyMovedStopId(Number(active.id))

    if (movedAnimationTimer.current !== null) {
      window.clearTimeout(movedAnimationTimer.current)
    }
    movedAnimationTimer.current = window.setTimeout(
      () => setRecentlyMovedStopId(null),
      720,
    )
  }

  const orderChanged = useMemo(() => {
    if (!plan) return false
    return orderedStops.some((stop, index) => stop.stopId !== plan.deliveryStops[index]?.stopId)
  }, [orderedStops, plan])

  const allStopsCompleted = plan?.deliveryStops.every((stop) => stop.status === 'COMPLETED') ?? false
  const completedStopCount = plan?.deliveryStops.filter((stop) => stop.status === 'COMPLETED').length ?? 0
  const remainingStopCount = (plan?.deliveryStops.length ?? 0) - completedStopCount
  const hasKakaoTravelTime = typeof recommendation?.kakaoTravelSeconds === 'number'
  const displayedTravelSeconds = recommendation?.kakaoTravelSeconds
    ?? recommendation?.estimatedTravelSeconds
    ?? 0
  const visibleStops = useMemo(
    () => orderedStops.filter((stop) => (
      stopView === 'completed'
        ? stop.status === 'COMPLETED'
        : stop.status !== 'COMPLETED'
    )),
    [orderedStops, stopView],
  )

  if (isLoading) return <div className="content-state">배송 계획을 불러오고 있어요.</div>

  if (!plan) {
    return (
      <section className="empty-state">
        <div className="empty-icon">!</div>
        <h2>배송 계획을 표시할 수 없어요</h2>
        <p>{error}</p>
        <Link to="/plans" className="button button-primary">목록으로 돌아가기</Link>
      </section>
    )
  }

  return (
    <div className="page-stack">
      <section className="detail-hero">
        <div>
          <Link to="/plans" className="back-link">← 배송 계획 목록</Link>
          <div className="detail-title-row">
            <div>
              <span className="eyebrow">PLAN #{String(plan.planId).padStart(3, '0')}</span>
              <h1>{plan.departureLocation}</h1>
            </div>
            <StatusBadge status={plan.status} />
          </div>
          <p>예정 출발 · {formatDateTime(plan.scheduledDepartureAt)}</p>
          <p className="actual-departure-copy">
            실제 출발 · {formatDateTime(plan.actualDepartureAt)}
          </p>
          {assignedDriver && (
            <p className="assigned-driver-copy">
              담당 기사 · <strong>{maskName(assignedDriver.name)}</strong> ({maskLoginId(assignedDriver.loginId)}, #{assignedDriver.driverId})
            </p>
          )}
        </div>

        <div className="detail-actions">
          {!isAdmin && plan.status === 'READY' && (
            <button
              type="button"
              className="button button-primary button-large"
              disabled={Boolean(busyAction)}
              onClick={() => void runAction('start', () => startDeliveryPlan(planId))}
            >
              {busyAction === 'start' ? '배송 시작 중...' : '배송 시작'}
            </button>
          )}
          {!isAdmin && plan.status === 'READY' && (
            <button
              type="button"
              className="button button-ghost button-large"
              disabled={Boolean(busyAction)}
              onClick={() => void handleRelease()}
            >
              {busyAction === 'release' ? '반납 중...' : '업무 반납'}
            </button>
          )}
          {!isAdmin && plan.status === 'DELIVERING' && (
            <button
              type="button"
              className="button button-primary button-large"
              disabled={!allStopsCompleted || Boolean(busyAction)}
              onClick={() => void runAction('complete-plan', () => completeDeliveryPlan(planId))}
            >
              {busyAction === 'complete-plan' ? '완료 처리 중...' : '전체 배송 완료'}
            </button>
          )}
        </div>
      </section>

      {error && <div className="alert alert-error">{error}</div>}

      {isAdmin && (plan.status === 'OPEN' || plan.status === 'READY') && (
        <section className="form-card driver-recommendation-card">
          <div className="section-title section-title-action">
            <div>
              <h2>배송 기사 추천</h2>
              <p>
                거리·업무량·위험 배송지·위치 신선도 기준으로 적격 후보를 고르고,
                AI가 최종 적합도와 근거를 제안합니다. 실제 수령은 기사가 직접 합니다.
              </p>
            </div>
            <button
              type="button"
              className="button button-ghost button-small"
              disabled={isRecommendingDrivers}
              onClick={() => void loadDriverRecommendations()}
            >
              {isRecommendingDrivers ? '계산 중...' : driverRecommendation ? '다시 계산' : '추천 보기'}
            </button>
          </div>

          {driverRecommendationError && (
            <div className="alert alert-error">{driverRecommendationError}</div>
          )}

          {driverRecommendation && (
            driverRecommendation.recommendations.length === 0 ? (
              <p className="field-help">
                추천할 수 있는 기사가 없습니다.
                {driverRecommendation.excludedByClaimLimit > 0
                  && ` (보유 한도 초과로 제외 ${driverRecommendation.excludedByClaimLimit}명)`}
              </p>
            ) : (
              <>
                <p className="field-help">
                  후보 {driverRecommendation.candidateCount}명 중 상위 {driverRecommendation.recommendations.length}명
                  {driverRecommendation.excludedByClaimLimit > 0
                    && ` · 보유 한도 초과로 제외 ${driverRecommendation.excludedByClaimLimit}명`}
                </p>
                <ol className="recommendation-list">
                  {driverRecommendation.recommendations.map((driver) => (
                    <li key={driver.driverId} className="recommendation-item">
                      <div className="recommendation-head">
                        <div>
                          <span className="recommendation-rank">{driver.rank}순위</span>
                          <strong>{maskName(driver.driverName)}</strong>
                          <span className="recommendation-login">({maskLoginId(driver.driverLoginId)})</span>
                        </div>
                        <span className="recommendation-score">
                          {driver.score}점
                          {driver.baseScore !== null && ` · 기준 ${driver.baseScore}점`}
                        </span>
                      </div>
                      <div className="recommendation-meter" aria-hidden="true">
                        <span style={{ width: `${driver.score}%` }} />
                      </div>
                      <ul className="recommendation-reasons">
                        {driver.reasons.map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                      <dl className="recommendation-features">
                        {driver.featureScores.map((feature) => (
                          <div key={feature.feature}>
                            <dt>{feature.label}</dt>
                            <dd>
                              {feature.rawValue}
                              <small>+{feature.contribution.toFixed(1)}점</small>
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </li>
                  ))}
                </ol>
              </>
            )
          )}
        </section>
      )}

      <section className="detail-summary-grid">
        <article>
          <span>전체 배송지</span>
          <strong>{plan.deliveryStops.length}</strong>
        </article>
        <article>
          <span>완료</span>
          <strong>{completedStopCount}</strong>
        </article>
        <article>
          <span>남은 배송지</span>
          <strong>{remainingStopCount}</strong>
        </article>
        <article>
          <span>위험 배송지</span>
          <strong className="danger-text">
            {plan.deliveryStops.filter((stop) => (
              stop.status !== 'COMPLETED'
              && stop.riskAssessment?.level === 'DANGER'
            )).length}
          </strong>
        </article>
      </section>

      <DeliveryRouteMap
        departureAddress={plan.departureLocation}
        departureLatitude={plan.departureLatitude}
        departureLongitude={plan.departureLongitude}
        stops={orderedStops}
        recommendedStopId={recommendation?.recommendedStopId}
      />

      {!isAdmin && plan.status === 'DELIVERING' && (
        <section className="next-stop-recommendation" aria-live="polite">
          <div className="recommendation-icon" aria-hidden="true">↗</div>
          {recommendation?.available ? (
            <>
              <div className="recommendation-copy">
                <span className="eyebrow">NEXT STOP RECOMMENDATION</span>
                <div className="recommendation-title-row">
                  <h2>{recommendation.address}</h2>
                  <RiskBadge level={recommendation.riskLevel ?? 'UNKNOWN'} />
                </div>
                <p>
                  {recommendation.currentStopId
                    ? `배송지 #${recommendation.currentStopId} 완료 위치에서 `
                    : '출발지에서 '}
                  앞으로 {recommendation.candidateCount}곳을 비교한 결과예요.
                  위험도가 가장 낮은 후보를 먼저 고르고, 동률이면 다익스트라 경로를 적용합니다.
                </p>
              </div>
              <div className="recommendation-metrics">
                <span>
                  위험 점수
                  <strong>{recommendation.riskScore}</strong>
                </span>
                <span>
                  {hasKakaoTravelTime ? '카카오 예상' : '예상 이동'}
                  <strong>
                    약 {Math.max(1, Math.ceil(displayedTravelSeconds / 60))}분
                  </strong>
                </span>
              </div>
            </>
          ) : (
            <div className="recommendation-copy">
              <span className="eyebrow">NEXT STOP RECOMMENDATION</span>
              <h2>{recommendationError ? '추천 정보를 불러오지 못했어요' : '남은 배송지가 없습니다'}</h2>
              <p>{recommendationError || '모든 배송지를 완료했습니다. 전체 배송 완료 처리를 진행하세요.'}</p>
            </div>
          )}
        </section>
      )}

      {!isAdmin && plan.status === 'READY' && (
        <section className="settings-row settings-row-single">
          <div className="inline-setting order-save">
            <div>
              <strong>배송 순서 편집</strong>
              <span>이동 핸들을 마우스로 끌거나 손가락으로 길게 눌러 순서를 바꾸세요.</span>
            </div>
            <button
              type="button"
              className="button button-secondary"
              disabled={!orderChanged || Boolean(busyAction)}
              onClick={() => void runAction(
                'order',
                () => reorderDeliveryStops(planId, orderedStops.map((stop) => stop.stopId)),
              )}
            >
              {busyAction === 'order' ? '저장 중...' : '순서 저장'}
            </button>
          </div>
        </section>
      )}

      <section className="route-section">
        <div className="section-heading-row">
          <div>
            <span className="eyebrow">DELIVERY ROUTE</span>
            <h2>배송 경로</h2>
          </div>
          <span className="muted">위험도는 최신 기상 데이터 기준입니다.</span>
        </div>

        <div className="route-view-tabs" role="tablist" aria-label="배송지 진행 상태">
          <button
            type="button"
            role="tab"
            aria-selected={stopView === 'active'}
            className={stopView === 'active' ? 'is-active' : ''}
            onClick={() => setStopView('active')}
          >
            현재 업무
            <strong>{remainingStopCount}</strong>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={stopView === 'completed'}
            className={stopView === 'completed' ? 'is-active' : ''}
            onClick={() => setStopView('completed')}
          >
            완료
            <strong>{completedStopCount}</strong>
          </button>
        </div>

        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={visibleStops.map((stop) => stop.stopId)}
            strategy={verticalListSortingStrategy}
          >
            <div className="route-list">
              {visibleStops.map((stop, visibleIndex) => {
                const risk = stop.riskAssessment
                const routeIndex = orderedStops.findIndex(
                  (orderedStop) => orderedStop.stopId === stop.stopId,
                )
                return (
                  <SortableStopCard
                    key={stop.stopId}
                    stopId={stop.stopId}
                    disabled={isAdmin || plan.status !== 'READY'}
                    recentlyMoved={recentlyMovedStopId === stop.stopId}
                    className={`stop-card risk-border-${risk?.level.toLowerCase() ?? 'unknown'}${recommendation?.recommendedStopId === stop.stopId ? ' recommended-stop-card' : ''}`}
                  >
                    {(dragHandle) => (
                      <>
                <div className="route-index-column">
                  <span className="route-index">{routeIndex + 1}</span>
                  {visibleIndex < visibleStops.length - 1 && <span className="route-line" />}
                </div>

                <div className="stop-card-content">
                  <div className="stop-card-header">
                    <div>
                      <div className="stop-badges">
                        <StatusBadge status={stop.status} />
                        <RiskBadge level={risk?.level ?? 'UNKNOWN'} />
                        {recommendation?.recommendedStopId === stop.stopId && (
                          <span className="badge badge-recommended">다음 추천</span>
                        )}
                      </div>
                      <h3>{stop.address}</h3>
                      <small>배송지 #{stop.stopId}</small>
                    </div>
                    <div className="risk-score">
                      <span>위험 점수</span>
                      <strong>
                        {risk?.score == null || risk.score === -1
                            ? "정보 없음"
                            : risk.score}
                      </strong>
                    </div>
                  </div>

                  {risk && risk.factors.length > 0 && (
                    <div className="risk-factor-list">
                      {risk.factors.map((factor) => (
                        <span key={`${stop.stopId}-${factor.type}`}>
                          {factor.description} <strong>+{factor.score}</strong>
                        </span>
                      ))}
                    </div>
                  )}

                  <div className="item-summary-list">
                    {stop.deliveryItems.map((item) => (
                      <span key={item.itemId}>
                        <em>{PRODUCT_LABEL[item.productType]}</em>
                        {item.productName} · {item.quantity}개
                      </span>
                    ))}
                  </div>

                  <div className="stop-card-footer">
                    <span className="muted">분석 시각 · {formatDateTime(risk?.analyzedAt)}</span>
                    <div className="stop-actions">
                      {dragHandle}
                      {!isAdmin && plan.status === 'DELIVERING' && stop.status === 'DELIVERING' && (
                        <button
                          type="button"
                          className="button button-secondary"
                          disabled={Boolean(busyAction)}
                          onClick={() => void runAction(
                            `stop-${stop.stopId}`,
                            () => completeDeliveryStop(planId, stop.stopId),
                          )}
                        >
                          {busyAction === `stop-${stop.stopId}` ? '처리 중...' : '배송 완료'}
                        </button>
                      )}
                    </div>
                  </div>
                </div>
                      </>
                    )}
                  </SortableStopCard>
                )
              })}
              {visibleStops.length === 0 && (
                <div className="route-tab-empty">
                  <strong>
                    {stopView === 'active'
                      ? '현재 처리할 배송지가 없습니다.'
                      : '아직 완료된 배송지가 없습니다.'}
                  </strong>
                  <span>
                    {stopView === 'active'
                      ? '모든 배송지를 완료했다면 전체 배송 완료 처리를 진행하세요.'
                      : '배송 완료 처리한 배송지가 여기에 모입니다.'}
                  </span>
                </div>
              )}
            </div>
          </SortableContext>
        </DndContext>
      </section>
    </div>
  )
}
