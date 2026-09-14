import { useEffect, useRef, useState } from 'react'
import { updateMyDriverLocation } from '../api/deliveryPlans'
import type { UpdateDriverLocationRequest } from '../types/api'
import { formatDateTime } from '../utils/format'

type TrackingState = 'waiting' | 'syncing' | 'synced' | 'blocked' | 'failed'

const MIN_UPDATE_INTERVAL_MS = 10_000

export function DriverLocationTracker() {
  const [trackingState, setTrackingState] = useState<TrackingState>('waiting')
  const [updatedAt, setUpdatedAt] = useState<string | null>(null)
  const lastAttemptAtRef = useRef(0)
  const requestInFlightRef = useRef(false)
  const latestCoordinatesRef = useRef<UpdateDriverLocationRequest | null>(null)

  useEffect(() => {
    if (!navigator.geolocation) {
      // oxlint-disable-next-line react/set-state-in-effect -- 브라우저 기능 지원 여부를 최초 렌더 후 반영한다.
      setTrackingState('failed')
      return
    }

    let active = true
    const syncLatestLocation = () => {
      const coordinates = latestCoordinatesRef.current
      const now = Date.now()
      if (
        !coordinates
        || requestInFlightRef.current
        || now - lastAttemptAtRef.current < MIN_UPDATE_INTERVAL_MS
      ) return

      lastAttemptAtRef.current = now
      requestInFlightRef.current = true
      setTrackingState('syncing')
      void updateMyDriverLocation(coordinates).then((location) => {
        if (!active) return
        setUpdatedAt(location.updatedAt)
        setTrackingState('synced')
      }).catch(() => {
        if (active) setTrackingState('failed')
      }).finally(() => {
        requestInFlightRef.current = false
      })
    }

    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        latestCoordinatesRef.current = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        }
        syncLatestLocation()
      },
      (error) => {
        if (!active) return
        setTrackingState(error.code === error.PERMISSION_DENIED ? 'blocked' : 'failed')
      },
      {
        enableHighAccuracy: true,
        maximumAge: 5_000,
        timeout: 15_000,
      },
    )
    const intervalId = window.setInterval(syncLatestLocation, MIN_UPDATE_INTERVAL_MS)

    return () => {
      active = false
      navigator.geolocation.clearWatch(watchId)
      window.clearInterval(intervalId)
    }
  }, [])

  const label = trackingState === 'synced'
    ? '위치 공유 중'
    : trackingState === 'syncing'
      ? '위치 갱신 중'
      : trackingState === 'blocked'
        ? '위치 권한 필요'
        : trackingState === 'failed'
          ? '위치 공유 실패'
          : '현재 위치 확인 중'

  return (
    <div
      className={`location-tracker location-tracker-${trackingState}`}
      title={updatedAt ? `마지막 갱신 ${formatDateTime(updatedAt)}` : label}
      aria-live="polite"
    >
      <i aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}
