export type DeliveryPlanStatus = 'READY' | 'DELIVERING' | 'COMPLETED'
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
  driverId: number
  driverLoginId: string
  driverName: string
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
  driverId: number
  driverLoginId: string
  driverName: string
  deliveryPlan: DeliveryPlanDetail
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
