import type {
  AdminDeliveryPlanDetail,
  AdminDeliveryPlanSummary,
  CreateDeliveryPlanRequest,
  CreateDeliveryPlanResponse,
  DeliveryPlanDetail,
  DeliveryPlanSummary,
  DriverLocation,
  DriverSummary,
  AdminDeliveryStatistics,
  NextStopRecommendation,
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
