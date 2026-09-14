import type {
  AdminDeliveryPlanDetail,
  AdminDeliveryPlanSummary,
  ClaimDeliveryPlanResponse,
  CreateDeliveryPlanRequest,
  CreateDeliveryPlanResponse,
  DeliveryPlanDetail,
  DeliveryPlanSummary,
  DriverLocation,
  DriverRecommendation,
  DriverSummary,
  AdminDeliveryStatistics,
  NextStopRecommendation,
  OpenDeliveryPlan,
  UpdateDriverLocationRequest,
} from '../types/api'
import { apiRequest } from './client'

const PLAN_PATH = '/api/delivery-plans'

export function getDeliveryPlans() {
  return apiRequest<DeliveryPlanSummary[]>(PLAN_PATH)
}

export function getDeliveryPlan(planId: number) {
  return apiRequest<DeliveryPlanDetail>(`${PLAN_PATH}/${planId}`)
}

export function getNextStopRecommendation(planId: number) {
  return apiRequest<NextStopRecommendation>(
    `${PLAN_PATH}/${planId}/next-stop-recommendation`,
  )
}

/** 아직 아무도 가져가지 않은 배송 업무 목록 (우선권 정보 포함) */
export function getOpenDeliveryPlans() {
  return apiRequest<OpenDeliveryPlan[]>(`${PLAN_PATH}/open`)
}

/** 배송 업무를 선착순으로 수령한다. */
export function claimDeliveryPlan(planId: number) {
  return apiRequest<ClaimDeliveryPlanResponse>(`${PLAN_PATH}/${planId}/claim`, {
    method: 'POST',
  })
}

/** 배송 시작 전 수령한 업무를 반납한다. */
export function releaseDeliveryPlan(planId: number) {
  return apiRequest<void>(`${PLAN_PATH}/${planId}/claim`, { method: 'DELETE' })
}

export function getAllDeliveryPlans() {
  return apiRequest<AdminDeliveryPlanSummary[]>('/api/admin/delivery-plans')
}

export function getAdminDeliveryPlan(planId: number) {
  return apiRequest<AdminDeliveryPlanDetail>(`/api/admin/delivery-plans/${planId}`)
}

export function getDrivers() {
  return apiRequest<DriverSummary[]>('/api/admin/drivers')
}

export function updateMyDriverLocation(request: UpdateDriverLocationRequest) {
  return apiRequest<DriverLocation>('/api/drivers/me/location', {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

export function getMyDriverLocation() {
  return apiRequest<DriverLocation>('/api/drivers/me/location')
}

export function getDriverLocations() {
  return apiRequest<DriverLocation[]>('/api/admin/drivers/locations')
}

export function getDeliveryStatistics() {
  return apiRequest<AdminDeliveryStatistics>('/api/admin/delivery-plans/statistics')
}

export function getDriverRecommendations(planId: number, limit?: number) {
  const query = limit ? `?limit=${limit}` : ''
  return apiRequest<DriverRecommendation>(
    `/api/admin/delivery-plans/${planId}/driver-recommendations${query}`,
  )
}

/** 기사를 지정하지 않고 미배정(OPEN) 업무로 등록한다. */
export function createOpenDeliveryPlan(request: CreateDeliveryPlanRequest) {
  return apiRequest<CreateDeliveryPlanResponse>('/api/admin/delivery-plans', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function createAssignedDeliveryPlan(
  driverId: number,
  request: CreateDeliveryPlanRequest,
) {
  return apiRequest<CreateDeliveryPlanResponse>(
    `/api/admin/drivers/${driverId}/delivery-plans`,
    {
      method: 'POST',
      body: JSON.stringify(request),
    },
  )
}

export function reorderDeliveryStops(planId: number, stopIds: number[]) {
  return apiRequest<void>(`${PLAN_PATH}/${planId}/stops/order`, {
    method: 'PUT',
    body: JSON.stringify({ stopIds }),
  })
}

export function startDeliveryPlan(planId: number) {
  return apiRequest<void>(`${PLAN_PATH}/${planId}/start`, { method: 'POST' })
}

export function completeDeliveryStop(planId: number, stopId: number) {
  return apiRequest<void>(`${PLAN_PATH}/${planId}/stops/${stopId}/complete`, {
    method: 'POST',
  })
}

export function completeDeliveryPlan(planId: number) {
  return apiRequest<void>(`${PLAN_PATH}/${planId}/complete`, { method: 'POST' })
}
