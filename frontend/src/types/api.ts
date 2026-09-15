export type DeliveryPlanStatus = 'OPEN' | 'READY' | 'DELIVERING' | 'COMPLETED'
export type DeliveryStopStatus = 'READY' | 'DELIVERING' | 'COMPLETED'
export type ProductType = 'NORMAL' | 'REFRIGERATED' | 'FROZEN' | 'FRAGILE'
export type RiskLevel = 'UNKNOWN' | 'SAFE' | 'CAUTION' | 'DANGER'
export type RiskFactorType = 'HEAVY_RAIN' | 'HEAT_WAVE' | 'WEATHER_WARNING'
export type Role = 'ROLE_DELIVERY_DRIVER' | 'ROLE_ADMIN'

export interface TokenResponse {
  accessToken: string
}

export interface UserInfo {
  id: number
  loginId: string
  name: string
  role: Role
}

export interface DriverSummary {
  driverId: number
  loginId: string
  name: string
}

export interface DriverLocation {
  driverId: number
  driverLoginId: string
  driverName: string
  latitude: number
  longitude: number
  updatedAt: string
}

export interface UpdateDriverLocationRequest {
  latitude: number
  longitude: number
}

export interface AdminDeliveryStatistics {
  totalPlans: number
  openPlans: number
  readyPlans: number
  deliveringPlans: number
  completedPlans: number
  totalStops: number
  remainingStops: number
  completedStops: number
  totalBoxes: number
  remainingBoxes: number
  deliveredBoxes: number
  dangerStops: number
}

export interface DeliveryPlanSummary {
  planId: number
  departureLocation: string
  scheduledDepartureAt: string
  assignedAt: string | null
  actualDepartureAt: string | null
  completedAt: string | null
  status: DeliveryPlanStatus
  totalStops: number
  remainingStops: number
  totalBoxes: number
  remainingBoxes: number
  dangerStops: number
}

export interface AdminDeliveryPlanSummary extends DeliveryPlanSummary {
  // 미배정(OPEN) 업무는 수령한 기사가 없으므로 null 이다.
  driverId: number | null
  driverLoginId: string | null
  driverName: string | null
}

export interface RiskFactor {
  type: RiskFactorType
  description: string
  score: number
}

export interface RiskAssessment {
  score: number
  level: RiskLevel
  analyzedAt: string | null
  factors: RiskFactor[]
}

export interface DeliveryItem {
  itemId: number
  productName: string
  productType: ProductType
  quantity: number
}

export interface DeliveryStop {
  stopId: number
  status: DeliveryStopStatus
  address: string
  latitude: number
  longitude: number
  completedAt: string | null
  riskAssessment: RiskAssessment | null
  deliveryItems: DeliveryItem[]
}

export interface DeliveryPlanDetail {
  planId: number
  departureLocation: string
  departureLatitude: number
  departureLongitude: number
  scheduledDepartureAt: string
  assignedAt: string | null
  actualDepartureAt: string | null
  status: DeliveryPlanStatus
  completedAt: string | null
  deliveryStops: DeliveryStop[]
}

export interface NextStopRecommendation {
  available: boolean
  currentStopId: number | null
  recommendedStopId: number | null
  address: string | null
  latitude: number | null
  longitude: number | null
  riskLevel: RiskLevel | null
  riskScore: number | null
  candidateCount: number
  candidateStopIds: number[]
  optimizedSafestRouteStopIds: number[]
  estimatedTravelSeconds: number | null
  kakaoTravelSeconds: number | null
}

export interface AdminDeliveryPlanDetail {
  driverId: number | null
  driverLoginId: string | null
  driverName: string | null
  deliveryPlan: DeliveryPlanDetail
}

export interface OpenDeliveryPlan {
  planId: number
  departureLocation: string
  scheduledDepartureAt: string
  status: DeliveryPlanStatus
  totalStops: number
  remainingStops: number
  totalBoxes: number
  remainingBoxes: number
  dangerStops: number
  /** 전체 공개 시각. null 이면 처음부터 전체 공개된 업무다. */
  publicAt: string | null
  /** 내 추천 우선권 순위. 우선권이 없으면 null. */
  priorityRank: number | null
  /** 내가 지금 수령할 수 있는지 여부 */
  claimableNow: boolean
}

export interface ClaimDeliveryPlanResponse {
  planId: number
  driverId: number | null
  status: DeliveryPlanStatus
  assignedAt: string | null
  alreadyOwned: boolean
  activePlanCount: number
}

export interface FeatureScore {
  feature: string
  label: string
  rawValue: string
  weight: number
  normalized: number
  contribution: number
}

export interface RecommendedDriver {
  rank: number
  driverId: number
  driverLoginId: string
  driverName: string
  score: number
  distanceMeters: number | null
  estimatedTravelSeconds: number | null
  locationFresh: boolean
  locationUpdatedAt: string | null
  activePlans: number
  remainingStops: number
  remainingBoxes: number
  dangerStops: number
  reasons: string[]
  featureScores: FeatureScore[]
}

export interface DriverRecommendation {
  planId: number
  departureLocation: string
  scheduledDepartureAt: string
  evaluatedAt: string
  candidateCount: number
  excludedByClaimLimit: number
  recommendations: RecommendedDriver[]
}

export interface CreateDeliveryItemRequest {
  productName: string
  productType: ProductType
  quantity: number
}

export interface CreateDeliveryStopRequest {
  address: string
  items: CreateDeliveryItemRequest[]
}

export interface CreateDeliveryPlanRequest {
  departureAddress: string
  scheduledDepartureAt: string
  stops: CreateDeliveryStopRequest[]
}

export interface CreateDeliveryPlanResponse {
  planId: number
}

export interface FieldError {
  field: string
  value: string
  reason: string
}

export interface ErrorResponse {
  status: string
  code: string
  message: string
  errors: FieldError[]
  reason: string | null
}
