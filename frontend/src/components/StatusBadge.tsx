import type { DeliveryPlanStatus, DeliveryStopStatus, RiskLevel } from '../types/api'

const STATUS_LABEL: Record<DeliveryPlanStatus | DeliveryStopStatus, string> = {
  OPEN: '수령 대기',
  READY: '배송 준비',
  DELIVERING: '배송 중',
  COMPLETED: '배송 완료',
}

const RISK_LABEL: Record<RiskLevel, string> = {
  UNKNOWN: '정보 없음',
  SAFE: '안전',
  CAUTION: '주의',
  DANGER: '위험',
}

export function StatusBadge({ status }: { status: DeliveryPlanStatus | DeliveryStopStatus }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
}

export function RiskBadge({ level }: { level: RiskLevel }) {
  return <span className={`badge risk-${level.toLowerCase()}`}>{RISK_LABEL[level]}</span>
}
