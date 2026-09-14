import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import {
  getAllDeliveryPlans,
  getDeliveryPlans,
  getDeliveryStatistics,
  getDriverLocations,
} from '../api/deliveryPlans'
import { useAuth } from '../auth/AuthContext'
import { DriverLocationMap } from '../components/DriverLocationMap'
import { StatusBadge } from '../components/StatusBadge'
import type {
  AdminDeliveryPlanSummary,
  AdminDeliveryStatistics,
  DeliveryPlanSummary,
  DriverLocation,
} from '../types/api'
import {
  errorMessage,
  formatDateTime,
  maskLoginId,
  maskName,
} from '../utils/format'

type PlanRow = DeliveryPlanSummary | AdminDeliveryPlanSummary
type PlanView = 'current' | 'completed'

interface DriverPlanGroup {
  driverId: number
  driverLoginId: string
  driverName: string
  plans: AdminDeliveryPlanSummary[]
}

interface PlanDateGroup {
  dateKey: string
  label: string
  plans: PlanRow[]
}

function isAdminPlan(plan: PlanRow): plan is AdminDeliveryPlanSummary {
  return 'driverId' in plan
}

function safeCount(value: unknown) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : 0
}

function toLocalDateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatDateHeading(dateKey: string) {
  const [year, month, day] = dateKey.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  const today = new Date()
  const tomorrow = new Date(today)
  tomorrow.setDate(today.getDate() + 1)

  const prefix = dateKey === toLocalDateKey(today)
    ? '오늘'
    : dateKey === toLocalDateKey(tomorrow)
      ? '내일'
      : ''
  const formatted = new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(date)

  return prefix ? `${prefix} · ${formatted}` : formatted
}

function planViewDate(plan: PlanRow, view: PlanView) {
  if (view === 'completed') {
    return plan.completedAt ?? plan.actualDepartureAt ?? plan.scheduledDepartureAt
  }

  return plan.scheduledDepartureAt
}

function groupPlansByDate(plans: PlanRow[], view: PlanView): PlanDateGroup[] {
  const sortedPlans = [...plans].sort((left, right) => {
    const difference = new Date(planViewDate(left, view)).getTime()
      - new Date(planViewDate(right, view)).getTime()
    return view === 'completed' ? -difference : difference
  })
  const groups = new Map<string, PlanRow[]>()

  sortedPlans.forEach((plan) => {
    const dateKey = planViewDate(plan, view).slice(0, 10)
    const group = groups.get(dateKey)
    if (group) {
      group.push(plan)
    } else {
      groups.set(dateKey, [plan])
    }
  })

  return [...groups.entries()].map(([dateKey, groupedPlans]) => ({
    dateKey,
    label: formatDateHeading(dateKey),
    plans: groupedPlans,
  }))
}

export function PlanListPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ROLE_ADMIN'
  const [plans, setPlans] = useState<PlanRow[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [planView, setPlanView] = useState<PlanView>('current')
  const [driverLocations, setDriverLocations] = useState<DriverLocation[]>([])
  const [adminStatistics, setAdminStatistics] = useState<AdminDeliveryStatistics | null>(null)
  const [dashboardError, setDashboardError] = useState('')

  const loadPlans = useCallback(async () => {
    setError('')
    setIsLoading(true)
    try {
      setPlans(isAdmin ? await getAllDeliveryPlans() : await getDeliveryPlans())
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setIsLoading(false)
    }
  }, [isAdmin])

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- 목록 데이터는 화면 진입 시 불러온다.
    void loadPlans()
  }, [loadPlans])

  const loadAdminDashboard = useCallback(async () => {
    if (!isAdmin) return

    const [locationResult, statisticsResult] = await Promise.allSettled([
      getDriverLocations(),
      getDeliveryStatistics(),
    ])
    const errors: string[] = []

    if (locationResult.status === 'fulfilled') {
      setDriverLocations(locationResult.value)
    } else {
      errors.push(errorMessage(locationResult.reason))
    }

    if (statisticsResult.status === 'fulfilled') {
      setAdminStatistics(statisticsResult.value)
    } else {
      errors.push(errorMessage(statisticsResult.reason))
    }

    if (errors.length === 0) {
      setDashboardError('')
    } else {
      setDashboardError([...new Set(errors)].join(' / '))
    }
  }, [isAdmin])

  useEffect(() => {
    if (!isAdmin) return

    // oxlint-disable-next-line react/set-state-in-effect -- 관리자 운영 데이터는 화면 진입 후 API와 동기화한다.
    void loadAdminDashboard()
    const intervalId = window.setInterval(() => {
      void loadAdminDashboard()
    }, 10_000)

    return () => window.clearInterval(intervalId)
  }, [isAdmin, loadAdminDashboard])

  const todayPlans = useMemo(() => {
    const todayKey = toLocalDateKey(new Date())
    return plans.filter((plan) => (
      plan.scheduledDepartureAt.slice(0, 10) === todayKey
    ))
  }, [plans])

  const summary = useMemo(
    () => ({
      active: todayPlans.filter((plan) => plan.status === 'DELIVERING').length,
      ready: todayPlans.filter((plan) => plan.status === 'READY').length,
      danger: todayPlans.reduce((count, plan) => (
        count + safeCount(plan.dangerStops)
      ), 0),
    }),
    [todayPlans],
  )

  const todaySummary = useMemo(
    () => todayPlans.reduce(
      (totals, plan) => ({
        totalStops: totals.totalStops + safeCount(plan.totalStops),
        remainingStops: totals.remainingStops + safeCount(plan.remainingStops),
        totalBoxes: totals.totalBoxes + safeCount(plan.totalBoxes),
        remainingBoxes: totals.remainingBoxes + safeCount(plan.remainingBoxes),
      }),
      { totalStops: 0, remainingStops: 0, totalBoxes: 0, remainingBoxes: 0 },
    ),
    [todayPlans],
  )

  const todayLabel = new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date())

  const driverGroups = useMemo(() => {
    const groups = new Map<number, DriverPlanGroup>()

    plans.forEach((plan) => {
      if (!isAdminPlan(plan)) return

      const group = groups.get(plan.driverId)
      if (group) {
        group.plans.push(plan)
        return
      }

      groups.set(plan.driverId, {
        driverId: plan.driverId,
        driverLoginId: plan.driverLoginId,
        driverName: plan.driverName,
        plans: [plan],
      })
    })

    return [...groups.values()].sort((left, right) => (
      left.driverId - right.driverId
    ))
  }, [plans])

  const [selectedDriverId, setSelectedDriverId] = useState<number | null>(null)
  const selectedDriver = driverGroups.find((group) => (
    group.driverId === selectedDriverId
  )) ?? driverGroups[0] ?? null
  const selectedPlans = isAdmin
    ? selectedDriver?.plans ?? []
    : plans
  const currentPlans = selectedPlans.filter((plan) => plan.status !== 'COMPLETED')
  const completedPlans = selectedPlans.filter((plan) => plan.status === 'COMPLETED')
  const visiblePlans = planView === 'current' ? currentPlans : completedPlans
  const dateGroups = groupPlansByDate(visiblePlans, planView)

  return (
    <div className="page-stack">
      <section className="page-heading heading-with-action">
        <div>
          <span className="eyebrow">DELIVERY OVERVIEW</span>
          <h1>{isAdmin ? '전체 배송 계획' : '내 배송 계획'}</h1>
          <p>
            {isAdmin
              ? '기사별 배송 진행 상태와 기상 위험도를 한눈에 확인하세요.'
              : '오늘 방문할 배송지와 기상 위험도를 확인하세요.'}
          </p>
        </div>
        {isAdmin && (
          <Link to="/plans/new" className="button button-primary">+ 배송 계획 할당</Link>
        )}
      </section>
      {isAdmin && (
        <>
          <section className="admin-statistics" aria-label="전체 누적 배송 통계">
            <div className="admin-statistics-heading">
              <div>
                <span className="eyebrow">OPERATIONS TOTAL</span>
                <h2>전체 누적 배송 통계</h2>
              </div>
              <span>서버 집계 기준</span>
            </div>
            <div className="admin-statistics-grid">
              <article>
                <span>전체 계획</span>
                <strong>{(adminStatistics?.totalPlans ?? 0).toLocaleString()}<em>건</em></strong>
                <small>대기 {adminStatistics?.readyPlans ?? 0} · 진행 {adminStatistics?.deliveringPlans ?? 0} · 완료 {adminStatistics?.completedPlans ?? 0}</small>
              </article>
              <article>
                <span>완료 배송지</span>
                <strong>{(adminStatistics?.completedStops ?? 0).toLocaleString()}<em>곳</em></strong>
                <small>전체 {adminStatistics?.totalStops ?? 0} · 잔여 {adminStatistics?.remainingStops ?? 0}</small>
              </article>
              <article>
                <span>배송 완료 물량</span>
                <strong>{(adminStatistics?.deliveredBoxes ?? 0).toLocaleString()}<em>박스</em></strong>
                <small>전체 {adminStatistics?.totalBoxes ?? 0} · 잔여 {adminStatistics?.remainingBoxes ?? 0}</small>
              </article>
              <article className="is-danger">
                <span>현재 위험 배송지</span>
                <strong>{(adminStatistics?.dangerStops ?? 0).toLocaleString()}<em>곳</em></strong>
                <small>미완료 배송지 기준</small>
              </article>
            </div>
          </section>

          <DriverLocationMap locations={driverLocations} />

          {dashboardError && (
            <div className="alert alert-error alert-with-action">
              <span>실시간 운영 정보를 갱신하지 못했습니다. {dashboardError}</span>
              <button type="button" onClick={() => void loadAdminDashboard()}>다시 시도</button>
            </div>
          )}
        </>
      )}
      {isAdmin && (
        <section className="today-overview" aria-label="오늘 배송 운영 현황">
          <div className="today-overview-heading">
            <div>
              <span className="eyebrow">TODAY'S DASHBOARD</span>
              <h2>오늘 배송 운영 현황</h2>
            </div>
            <p>{todayLabel} 출발 예정 기준</p>
          </div>

          <div className="today-overview-content">
            <section className="today-overview-group" aria-labelledby="today-load-heading">
              <div className="today-overview-group-heading">
                <h3 id="today-load-heading">배송 규모</h3>
                <span>오늘 방문할 집과 적재 물량</span>
              </div>
              <div className="today-overview-grid">
                <article className="today-metric-card">
                  <span className="today-metric-index">오늘 배송할 집 수</span>
                  <div>
                    <strong>{todaySummary.totalStops.toLocaleString()}<em>집</em></strong>
                  </div>
                </article>
                <article className="today-metric-card is-accented">
                  <span className="today-metric-index">진행률</span>
                  <div>
                    <small>
                      {todaySummary.totalStops
                          ? (100 - (todaySummary.remainingStops / todaySummary.totalStops * 100)).toFixed(1)
                          : "0.0"}
                      <em>%</em>
                    </small>
                    <strong>
                      {todaySummary.totalStops - todaySummary.remainingStops}
                      <em>
                        집
                      </em>
                      <small>
                        <span>잔여 {todaySummary.remainingStops.toLocaleString()}</span>(전체 {todaySummary.totalStops.toLocaleString()})
                      </small>
                    </strong>
                  </div>
                </article>
                <article className="today-metric-card">
                  <span className="today-metric-index">전체 물량</span>
                  <div>
                    <strong>
                      {todaySummary.totalBoxes.toLocaleString()}
                      <em>박스</em>
                    </strong>
                  </div>
                </article>
                <article className="today-metric-card is-accented">
                  <span className="today-metric-index">진행률</span>
                  <div>
                    <small>
                      {todaySummary.totalBoxes > 0
                          ? (100 - (todaySummary.remainingBoxes / todaySummary.totalBoxes * 100)).toFixed(1)
                          : "0.0"}
                      <em>%</em>
                    </small>
                    <strong>
                      {todaySummary.totalBoxes - todaySummary.remainingBoxes}
                      <em>
                        박스
                      </em>
                      <small>
                         잔여 {todaySummary.remainingBoxes.toLocaleString()} (전체 {todaySummary.totalBoxes.toLocaleString()})
                      </small>
                    </strong>
                  </div>
                </article>
              </div>
            </section>
            <section className="today-overview-group" aria-labelledby="today-plan-status-heading">
              <div className="today-overview-group-heading">
                <h3 id="today-plan-status-heading">계획 상태</h3>
                <span>오늘 배정된 계획의 진행 현황</span>
              </div>
              <div className="today-status-grid">
                <article className="today-status-card is-live">
                  <span>진행 중</span>
                  <strong>{summary.active}<em>건</em></strong>
                </article>
                <article className="today-status-card is-ready">
                  <span>출발 대기</span>
                  <strong>{summary.ready}<em>건</em></strong>
                </article>
                <article className="today-status-card is-danger">
                  <span>위험 배송지</span>
                  <strong>{summary.danger}<em>곳</em></strong>
                </article>
              </div>
            </section>
          </div>
        </section>
      )}

      {!isAdmin && (
        <section className="summary-grid" aria-label="배송 요약">
          <article className="summary-card summary-primary">
            <span>오늘 진행 중인 계획</span>
            <strong>{summary.active}</strong>
            <small>현재 배송 중</small>
          </article>
          <article className="summary-card">
            <span>오늘 출발 대기</span>
            <strong>{summary.ready}</strong>
            <small>대기중</small>
          </article>
          <article className="summary-card summary-danger">
            <span>오늘 위험 배송지</span>
            <strong>{summary.danger}</strong>
            <small>주의가 필요한 배송지</small>
          </article>
        </section>
      )}

      {error && (
        <div className="alert alert-error alert-with-action">
          <span>{error}</span>
          <button type="button" onClick={() => void loadPlans()}>다시 시도</button>
        </div>
      )}

      {isLoading ? (
        <div className="content-state">배송 계획을 불러오고 있어요.</div>
      ) : plans.length === 0 ? (
        <section className="empty-state">
          <div className="empty-icon">↗</div>
          <h2>아직 배송 계획이 없어요</h2>
          <p>
            {isAdmin
              ? '배송 기사에게 첫 계획을 할당해 보세요.'
              : '관리자가 계획을 할당하면 이곳에서 확인할 수 있어요.'}
          </p>
          {isAdmin && (
            <Link to="/plans/new" className="button button-primary">배송 계획 할당</Link>
          )}
        </section>
      ) : (
        <>
          {isAdmin && selectedDriver && (
            <section className="driver-filter-section" aria-label="기사별 배송 계획 선택">
              <div className="driver-filter-heading">
                <div>
                  <span className="eyebrow">DRIVER VIEW</span>
                  <h2>기사별 배송 계획</h2>
                </div>
              </div>
              <div className="driver-filter-tabs" role="tablist" aria-label="배송 기사 선택">
                {driverGroups.map((group, index) => {
                  const isSelected = group.driverId === selectedDriver.driverId
                  const activeCount = group.plans.filter((plan) => (
                    plan.status !== 'COMPLETED'
                  )).length
                  const completedCount = group.plans.length - activeCount

                  return (
                    <button
                      type="button"
                      role="tab"
                      id={`driver-tab-${group.driverId}`}
                      aria-controls="selected-driver-plans"
                      aria-selected={isSelected}
                      className={`driver-filter-button${isSelected ? ' is-active' : ''}`}
                      key={group.driverId}
                      onClick={() => setSelectedDriverId(group.driverId)}
                      aria-label={`기사 ${index + 1}, 진행 및 예정 ${activeCount}건, 배송 완료 ${completedCount}건`}
                    >
                      <span className="driver-filter-order">{group.driverName}</span>
                      <span className="driver-filter-identity">
                        <strong>{maskLoginId(group.driverLoginId)}</strong>
                        <small>예정 {activeCount}건, 완료 {completedCount}건, 총 {activeCount + completedCount}건</small>
                      </span>
                      <span className="driver-filter-count">
                        <strong>{activeCount}</strong>
                        <small>진행</small>
                      </span>
                    </button>
                  )
                })}
              </div>
            </section>
          )}

          <section
            className="plan-view-section"
            id={isAdmin ? 'selected-driver-plans' : undefined}
            role={isAdmin ? 'tabpanel' : undefined}
            aria-labelledby={isAdmin && selectedDriver
              ? `driver-tab-${selectedDriver.driverId}`
              : undefined}
            aria-label={isAdmin ? undefined : '배송 계획 목록'}
          >
            <div className="plan-view-tabs" role="tablist" aria-label="배송 계획 상태 선택">
              <button
                type="button"
                role="tab"
                id="plan-view-current"
                aria-controls="plan-date-list"
                aria-selected={planView === 'current'}
                className={planView === 'current' ? 'is-active' : ''}
                onClick={() => setPlanView('current')}
              >
                진행·예정 <strong>{currentPlans.length}</strong>
              </button>
              <button
                type="button"
                role="tab"
                id="plan-view-completed"
                aria-controls="plan-date-list"
                aria-selected={planView === 'completed'}
                className={planView === 'completed' ? 'is-active' : ''}
                onClick={() => setPlanView('completed')}
              >
                배송 완료 <strong>{completedPlans.length}</strong>
              </button>
            </div>

            <div
              id="plan-date-list"
              role="tabpanel"
              aria-labelledby={`plan-view-${planView}`}
            >
              {dateGroups.length === 0 ? (
                <div className="plan-tab-empty">
                  <strong>
                    {planView === 'current'
                      ? '진행 중이거나 예정된 배송이 없어요.'
                      : '완료된 배송이 없어요.'}
                  </strong>
                  <span>
                    {planView === 'current'
                      ? '완료된 기록은 배송 완료 탭에서 확인할 수 있습니다.'
                      : '배송이 끝나면 완료 기록이 날짜별로 모입니다.'}
                  </span>
                </div>
              ) : dateGroups.map((group) => (
                <section className="plan-date-group" key={group.dateKey}>
                  <div className="plan-date-heading">
                    <div>
                      <span>{planView === 'current' ? '출발 일정' : '완료일'}</span>
                      <h2>{group.label}</h2>
                    </div>
                    <strong>{group.plans.length}건</strong>
                  </div>

                  <div className="plan-list">
                    {group.plans.map((plan) => (
                      <Link to={`/plans/${plan.planId}`} className="plan-card" key={plan.planId}>
                        <div className="plan-card-main">
                          <div className="plan-number">#{String(plan.planId).padStart(3, '0')}</div>
                          <div>
                            <div className="plan-card-title-row">
                              <h2>{plan.departureLocation}</h2>
                              <StatusBadge status={plan.status} />
                            </div>
                            <p className="plan-time">예정 · {formatDateTime(plan.scheduledDepartureAt)}</p>
                            <p className="plan-time">
                              {plan.status === 'COMPLETED' && plan.completedAt
                                ? `완료 · ${formatDateTime(plan.completedAt)}`
                                : plan.actualDepartureAt
                                  ? `실제 · ${formatDateTime(plan.actualDepartureAt)}`
                                  : '출발 예정'}
                            </p>
                            {isAdminPlan(plan) && (
                              <p className="plan-driver">
                                담당 · <strong>{maskName(plan.driverName)}</strong> ({maskLoginId(plan.driverLoginId)})
                              </p>
                            )}
                          </div>
                        </div>
                        <div className="plan-stats">
                          <span><strong>{plan.remainingStops}</strong> / {plan.totalStops} 남음</span>
                          <span className={plan.dangerStops > 0 ? 'danger-text' : ''}>
                            위험 <strong>{plan.dangerStops}</strong>
                          </span>
                          <span className="arrow-link">→</span>
                        </div>
                      </Link>
                    ))}
                  </div>
                </section>
              ))}
            </div>
          </section>
        </>
      )}
    </div>
  )
}
