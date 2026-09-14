import { ApiError } from '../api/client'
import type { ClaimDeliveryPlanResponse, ErrorResponse, OpenDeliveryPlan } from '../types/api'

export function openPlan(overrides: Partial<OpenDeliveryPlan> = {}): OpenDeliveryPlan {
  return {
    planId: 10,
    departureLocation: '서울 물류센터',
    scheduledDepartureAt: '2026-09-14T13:00:00',
    status: 'OPEN',
    totalStops: 3,
    remainingStops: 3,
    totalBoxes: 12,
    remainingBoxes: 12,
    dangerStops: 0,
    publicAt: null,
    priorityRank: null,
    claimableNow: true,
    ...overrides,
  }
}

export function claimResponse(
  overrides: Partial<ClaimDeliveryPlanResponse> = {},
): ClaimDeliveryPlanResponse {
  return {
    planId: 10,
    driverId: 7,
    status: 'READY',
    assignedAt: '2026-09-14T12:00:00',
    alreadyOwned: false,
    activePlanCount: 1,
    ...overrides,
  }
}

/** 서버가 내려주는 409 응답을 그대로 흉내 낸다. */
export function apiError(status: number, code: string, message = '요청을 처리하지 못했습니다.') {
  const response: ErrorResponse = {
    status: String(status),
    code,
    message,
    errors: [],
    reason: null,
  }
  return new ApiError(status, response)
}

/** publicAt 을 "지금부터 n초 뒤"로 만든다. */
export function secondsFromNow(seconds: number) {
  return new Date(Date.now() + seconds * 1000).toISOString()
}
