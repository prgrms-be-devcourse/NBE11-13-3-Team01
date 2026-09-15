import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  createAssignedDeliveryPlan,
  createOpenDeliveryPlan,
  getDrivers,
} from '../api/deliveryPlans'
import { AddressSearch } from '../components/AddressSearch'
import type {
  CreateDeliveryItemRequest,
  CreateDeliveryStopRequest,
  DriverSummary,
  ProductType,
} from '../types/api'
import { errorMessage } from '../utils/format'

const PRODUCT_TYPES: { value: ProductType; label: string }[] = [
  { value: 'NORMAL', label: '상온' },
  { value: 'REFRIGERATED', label: '냉장' },
  { value: 'FROZEN', label: '냉동' },
  { value: 'FRAGILE', label: '파손 주의' },
]

function initialDepartureTime() {
  const date = new Date(Date.now() + 60 * 60 * 1000)
  const localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return localDate.toISOString().slice(0, 16)
}

function emptyItem(): CreateDeliveryItemRequest {
  return { productName: '', productType: 'NORMAL', quantity: 1 }
}

function emptyStop(): CreateDeliveryStopRequest {
  return { address: '', items: [emptyItem()] }
}

type AssignMode = 'open' | 'direct'

export function CreatePlanPage() {
  const navigate = useNavigate()
  const [assignMode, setAssignMode] = useState<AssignMode>('open')
  const [drivers, setDrivers] = useState<DriverSummary[]>([])
  const [selectedDriverId, setSelectedDriverId] = useState<number | ''>('')
  const [isDriverLoading, setIsDriverLoading] = useState(true)
  const [departureAddress, setDepartureAddress] = useState('')
  const [scheduledDepartureAt, setScheduledDepartureAt] = useState(initialDepartureTime)
  const [stops, setStops] = useState<CreateDeliveryStopRequest[]>([emptyStop()])
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- 할당 대상 기사 목록은 화면 진입 시 불러온다.
    void getDrivers()
      .then(setDrivers)
      .catch((caughtError: unknown) => setError(errorMessage(caughtError)))
      .finally(() => setIsDriverLoading(false))
  }, [])

  const updateStopAddress = (stopIndex: number, address: string) => {
    setStops((current) => current.map((stop, index) => (
      index === stopIndex ? { ...stop, address } : stop
    )))
  }

  const updateItem = (
    stopIndex: number,
    itemIndex: number,
    item: CreateDeliveryItemRequest,
  ) => {
    setStops((current) => current.map((stop, index) => {
      if (index !== stopIndex) return stop
      return {
        ...stop,
        items: stop.items.map((currentItem, currentItemIndex) => (
          currentItemIndex === itemIndex ? item : currentItem
        )),
      }
    }))
  }

  const addItem = (stopIndex: number) => {
    setStops((current) => current.map((stop, index) => (
      index === stopIndex ? { ...stop, items: [...stop.items, emptyItem()] } : stop
    )))
  }

  const removeItem = (stopIndex: number, itemIndex: number) => {
    setStops((current) => current.map((stop, index) => {
      if (index !== stopIndex || stop.items.length === 1) return stop
      return { ...stop, items: stop.items.filter((_, currentIndex) => currentIndex !== itemIndex) }
    }))
  }

  const removeStop = (stopIndex: number) => {
    if (stops.length === 1) return
    setStops((current) => current.filter((_, index) => index !== stopIndex))
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError('')
    setIsSubmitting(true)

    try {
      if (assignMode === 'direct' && selectedDriverId === '') {
        throw new Error('배송 계획을 할당할 기사를 선택하세요.')
      }
      if (!departureAddress) {
        throw new Error('출발지를 주소 찾기에서 선택하세요.')
      }
      if (stops.some((stop) => !stop.address)) {
        throw new Error('모든 배송지를 주소 찾기에서 선택하세요.')
      }

      const request = { departureAddress, scheduledDepartureAt, stops }
      const response = assignMode === 'open'
        ? await createOpenDeliveryPlan(request)
        : await createAssignedDeliveryPlan(selectedDriverId as number, request)
      navigate(`/plans/${response.planId}`)
    } catch (caughtError) {
      setError(errorMessage(caughtError))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="page-stack page-narrow">
      <section className="page-heading">
        <Link to="/plans" className="back-link">← 배송 계획 목록</Link>
        <span className="eyebrow">POST DELIVERY JOB</span>
        <h1>새 배송 업무 등록</h1>
        <p>
          기사를 지정하지 않고 올리면 기사들이 직접 가져갑니다.
          추천 상위 기사에게는 잠시 우선 수령 권한이 주어집니다.
        </p>
      </section>

      <form className="create-form" onSubmit={handleSubmit}>
        <section className="form-card">
          <div className="section-title">
            <span className="section-number">01</span>
            <div>
              <h2>등록 방식</h2>
              <p>기사들이 경쟁해서 가져가게 할지, 특정 기사에게 바로 맡길지 선택하세요.</p>
            </div>
          </div>
          <div className="assign-mode-options" role="radiogroup" aria-label="등록 방식">
            <label className={`assign-mode-option${assignMode === 'open' ? ' is-active' : ''}`}>
              <input
                type="radio"
                name="assign-mode"
                value="open"
                checked={assignMode === 'open'}
                onChange={() => setAssignMode('open')}
              />
              <span>
                <strong>미배정으로 공개</strong>
                <small>기사들이 목록에서 확인하고 선착순으로 가져갑니다. (권장)</small>
              </span>
            </label>
            <label className={`assign-mode-option${assignMode === 'direct' ? ' is-active' : ''}`}>
              <input
                type="radio"
                name="assign-mode"
                value="direct"
                checked={assignMode === 'direct'}
                onChange={() => setAssignMode('direct')}
              />
              <span>
                <strong>기사 직접 할당</strong>
                <small>지정한 기사에게 바로 배정합니다. 동시 보유 한도는 동일하게 적용됩니다.</small>
              </span>
            </label>
          </div>

          {assignMode === 'direct' && (
            <>
              <div className="form-grid single-field-grid">
                <label className="field field-wide">
                  <span>배송 기사</span>
                  <select
                    value={selectedDriverId}
                    onChange={(event) => setSelectedDriverId(
                      event.target.value ? Number(event.target.value) : '',
                    )}
                    disabled={isDriverLoading}
                    required
                  >
                    <option value="">
                      {isDriverLoading ? '배송 기사 불러오는 중...' : '배송 기사를 선택하세요'}
                    </option>
                    {drivers.map((driver) => (
                      <option value={driver.driverId} key={driver.driverId}>
                        {driver.name} · {driver.loginId} (#{driver.driverId})
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              {!isDriverLoading && drivers.length === 0 && (
                <p className="field-help danger-text">할당 가능한 배송 기사가 없습니다.</p>
              )}
            </>
          )}
        </section>

        <section className="form-card">
          <div className="section-title">
            <span className="section-number">02</span>
            <div>
              <h2>출발 정보</h2>
              <p>서울 지역의 출발 위치를 검색하고 시간을 입력하세요.</p>
            </div>
          </div>
          <div className="form-grid">
            <div className="field-wide">
              <AddressSearch
                label="출발지 주소"
                placeholder="주소 찾기 버튼을 눌러 선택하세요"
                value={departureAddress}
                onSelect={setDepartureAddress}
              />
            </div>
            <label className="field">
              <span>예정 출발 시각</span>
              <input
                type="datetime-local"
                value={scheduledDepartureAt}
                onChange={(event) => setScheduledDepartureAt(event.target.value)}
                required
              />
            </label>
          </div>
        </section>

        <section className="form-card">
          <div className="section-title section-title-action">
            <div className="section-title-copy">
              <span className="section-number">03</span>
              <div>
                <h2>배송지 및 상품</h2>
                <p>서울 지역에서 방문할 배송지를 검색하고 순서대로 추가하세요.</p>
              </div>
            </div>
            <button type="button" className="button button-secondary" onClick={() => setStops([...stops, emptyStop()])}>
              + 배송지 추가
            </button>
          </div>

          <div className="stop-form-list">
            {stops.map((stop, stopIndex) => (
              <article className="stop-form" key={`stop-${stopIndex + 1}`}>
                <div className="stop-form-header">
                  <strong><span>{stopIndex + 1}</span>번째 배송지</strong>
                  {stops.length > 1 && (
                    <button type="button" className="text-button danger-text" onClick={() => removeStop(stopIndex)}>
                      배송지 삭제
                    </button>
                  )}
                </div>
                <AddressSearch
                  label="배송지 주소"
                  placeholder="주소 찾기 버튼을 눌러 선택하세요"
                  value={stop.address}
                  onSelect={(address) => updateStopAddress(stopIndex, address)}
                />

                <div className="item-list">
                  {stop.items.map((item, itemIndex) => (
                    <div className="item-row" key={`item-${itemIndex + 1}`}>
                      <label className="field item-name">
                        <span>상품명</span>
                        <input
                          value={item.productName}
                          onChange={(event) => updateItem(stopIndex, itemIndex, {
                            ...item,
                            productName: event.target.value,
                          })}
                          placeholder="상품명"
                          required
                        />
                      </label>
                      <label className="field">
                        <span>상품 유형</span>
                        <select
                          value={item.productType}
                          onChange={(event) => updateItem(stopIndex, itemIndex, {
                            ...item,
                            productType: event.target.value as ProductType,
                          })}
                        >
                          {PRODUCT_TYPES.map((type) => (
                            <option value={type.value} key={type.value}>{type.label}</option>
                          ))}
                        </select>
                      </label>
                      <label className="field quantity-field">
                        <span>수량</span>
                        <input
                          type="number"
                          min="1"
                          value={item.quantity}
                          onChange={(event) => updateItem(stopIndex, itemIndex, {
                            ...item,
                            quantity: Number(event.target.value),
                          })}
                          required
                        />
                      </label>
                      <button
                        type="button"
                        className="icon-button"
                        aria-label="상품 삭제"
                        disabled={stop.items.length === 1}
                        onClick={() => removeItem(stopIndex, itemIndex)}
                      >
                        ×
                      </button>
                    </div>
                  ))}
                </div>
                <button type="button" className="text-button" onClick={() => addItem(stopIndex)}>
                  + 상품 추가
                </button>
              </article>
            ))}
          </div>
        </section>

        {error && <div className="alert alert-error">{error}</div>}

        <div className="form-actions">
          <Link to="/plans" className="button button-ghost">취소</Link>
          <button type="submit" className="button button-primary button-large" disabled={isSubmitting}>
            {isSubmitting
              ? '등록 중...'
              : assignMode === 'open' ? '미배정 업무로 등록' : '기사에게 할당'}
          </button>
        </div>
      </form>
    </div>
  )
}
