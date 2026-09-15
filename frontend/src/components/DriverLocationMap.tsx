import L from 'leaflet'
import { useEffect, useRef } from 'react'
import 'leaflet/dist/leaflet.css'
import type { DriverLocation } from '../types/api'
import { formatDateTime } from '../utils/format'

interface DriverLocationMapProps {
  locations: DriverLocation[]
}

const SEOUL_CENTER: L.LatLngExpression = [37.5665, 126.978]

export function DriverLocationMap({ locations }: DriverLocationMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markerLayerRef = useRef<L.LayerGroup | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const map = L.map(containerRef.current, {
      center: SEOUL_CENTER,
      zoom: 11,
      zoomControl: false,
      scrollWheelZoom: false,
    })

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(map)
    L.control.zoom({ position: 'bottomright' }).addTo(map)

    mapRef.current = map
    markerLayerRef.current = L.layerGroup().addTo(map)

    return () => {
      map.remove()
      mapRef.current = null
      markerLayerRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const markerLayer = markerLayerRef.current
    if (!map || !markerLayer) return

    markerLayer.clearLayers()
    if (locations.length === 0) {
      map.setView(SEOUL_CENTER, 11)
      return
    }

    const coordinates: L.LatLngExpression[] = []
    locations.forEach((location) => {
      const coordinate: L.LatLngExpression = [location.latitude, location.longitude]
      coordinates.push(coordinate)

      const icon = L.divIcon({
        className: 'driver-location-marker-shell',
        html: '<span class="driver-location-marker"><i></i></span>',
        iconSize: [34, 42],
        iconAnchor: [17, 40],
      })
      const popup = document.createElement('div')
      popup.className = 'driver-location-popup'
      const name = document.createElement('strong')
      name.textContent = location.driverName
      const loginId = document.createElement('span')
      loginId.textContent = location.driverLoginId
      const updatedAt = document.createElement('small')
      updatedAt.textContent = `마지막 갱신 ${formatDateTime(location.updatedAt)}`
      popup.append(name, loginId, updatedAt)

      L.marker(coordinate, {
        icon,
        title: `${location.driverName} 기사 위치`,
        alt: `${location.driverName} 기사 현재 위치`,
        keyboard: true,
      }).bindPopup(popup).addTo(markerLayer)
    })

    if (coordinates.length === 1) {
      map.setView(coordinates[0], 14, { animate: true })
      return
    }

    map.fitBounds(L.latLngBounds(coordinates), {
      animate: true,
      duration: 0.4,
      maxZoom: 14,
      padding: [42, 42],
    })
  }, [locations])

  return (
    <section className="driver-location-section" aria-label="배송 기사 실시간 위치">
      <div className="driver-location-heading">
        <div>
          <span className="eyebrow">LIVE DRIVER MAP</span>
          <h2>배송 기사 실시간 위치</h2>
        </div>
        <span className="live-refresh-label"><i />10초마다 자동 갱신</span>
      </div>

      <div className="driver-location-map-wrap">
        <div ref={containerRef} className="driver-location-map" />
        {locations.length === 0 && (
          <div className="driver-location-empty">
            <strong>공유된 기사 위치가 아직 없어요.</strong>
            <span>기사가 위치 권한을 허용하면 지도에 표시됩니다.</span>
          </div>
        )}
      </div>

      {locations.length > 0 && (
        <div className="driver-location-list" aria-label="위치가 확인된 기사 목록">
          {locations.map((location) => (
            <article key={location.driverId}>
              <i aria-hidden="true" />
              <div>
                <strong>{location.driverName}</strong>
                <span>{location.driverLoginId}</span>
              </div>
              <small>{formatDateTime(location.updatedAt)}</small>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
