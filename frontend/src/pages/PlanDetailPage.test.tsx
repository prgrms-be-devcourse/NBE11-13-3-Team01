import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { getDeliveryPlan, releaseDeliveryPlan } from '../api/deliveryPlans'
import type { DeliveryPlanDetail } from '../types/api'
import { PlanDetailPage } from './PlanDetailPage'

const navigateMock = vi.fn()

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
  useParams: () => ({ planId: '10' }),
}))

vi.mock('../api/deliveryPlans', () => ({
  getDeliveryPlan: vi.fn(),
  getAdminDeliveryPlan: vi.fn(),
  getNextStopRecommendation: vi.fn(),
  getDriverRecommendations: vi.fn(),
  releaseDeliveryPlan: vi.fn(),
  reorderDeliveryStops: vi.fn(),
  startDeliveryPlan: vi.fn(),
  completeDeliveryPlan: vi.fn(),
  completeDeliveryStop: vi.fn(),
}))

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 7, loginId: 'user1', name: '기사1', role: 'ROLE_DELIVERY_DRIVER' },
  }),
}))

// Leaflet 은 jsdom 에서 동작하지 않으므로 지도는 렌더 대상에서 제외한다.
vi.mock('../components/DeliveryRouteMap', () => ({
  DeliveryRouteMap: () => null,
}))

const getDeliveryPlanMock = vi.mocked(getDeliveryPlan)
const releaseDeliveryPlanMock = vi.mocked(releaseDeliveryPlan)

function readyPlan(): DeliveryPlanDetail {
  return {
    planId: 10,
    departureLocation: '서울 물류센터',
    departureLatitude: 37.5,
    departureLongitude: 126.9,
    scheduledDepartureAt: '2026-09-14T13:00:00',
    assignedAt: '2026-09-14T12:00:00',
    actualDepartureAt: null,
    status: 'READY',
    completedAt: null,
    deliveryStops: [
      {
        stopId: 100,
        status: 'READY',
        address: '서울시청',
        latitude: 37.5663,
        longitude: 126.9779,
        completedAt: null,
        riskAssessment: { score: 0, level: 'SAFE', analyzedAt: null, factors: [] },
        deliveryItems: [
          { itemId: 1000, productName: '생수1L', productType: 'NORMAL', quantity: 3 },
        ],
      },
    ],
  }
}

async function renderDetail() {
  getDeliveryPlanMock.mockResolvedValue(readyPlan())
  render(
    <MemoryRouter>
      <PlanDetailPage />
    </MemoryRouter>,
  )
  await screen.findByRole('heading', { name: '서울 물류센터' })
}

describe('PlanDetailPage 업무 반납', () => {
  it('배송 시작 전에는 반납 버튼을 보여준다', async () => {
    await renderDetail()

    expect(screen.getByRole('button', { name: '업무 반납' })).toBeEnabled()
  })

  it('반납에 성공하면 업무 마켓으로 돌아간다', async () => {
    await renderDetail()
    releaseDeliveryPlanMock.mockResolvedValue(undefined)

    fireEvent.click(screen.getByRole('button', { name: '업무 반납' }))

    await waitFor(() => expect(releaseDeliveryPlanMock).toHaveBeenCalledWith(10))
    expect(navigateMock).toHaveBeenCalledWith('/market')
  })

  it('반납에 실패하면 화면에 남아 오류를 보여준다', async () => {
    await renderDetail()
    releaseDeliveryPlanMock.mockRejectedValue(new Error('이미 배송을 시작했습니다'))

    fireEvent.click(screen.getByRole('button', { name: '업무 반납' }))

    expect(await screen.findByText('이미 배송을 시작했습니다')).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
