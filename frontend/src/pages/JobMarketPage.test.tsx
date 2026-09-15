import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { claimDeliveryPlan, getOpenDeliveryPlans } from '../api/deliveryPlans'
import { apiError, claimResponse, openPlan, secondsFromNow } from '../test/fixtures'
import { JobMarketPage } from './JobMarketPage'

const navigateMock = vi.fn()

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}))

vi.mock('../api/deliveryPlans', () => ({
  getOpenDeliveryPlans: vi.fn(),
  claimDeliveryPlan: vi.fn(),
}))

const getOpenDeliveryPlansMock = vi.mocked(getOpenDeliveryPlans)
const claimDeliveryPlanMock = vi.mocked(claimDeliveryPlan)

async function renderMarket(plans = [openPlan()]) {
  getOpenDeliveryPlansMock.mockResolvedValue(plans)
  render(<JobMarketPage />)
  await screen.findByLabelText('수령 대기 업무 목록')
}

function cardOf(planId: number) {
  return screen.getByText(`#${String(planId).padStart(3, '0')}`).closest('article') as HTMLElement
}

afterEach(() => {
  cleanup()
})

describe('JobMarketPage 상태별 렌더링', () => {
  it('우선권이 있으면 순위 배지와 추천 기사 전용 안내를 보여주고 버튼을 활성화한다', async () => {
    await renderMarket([
      openPlan({ planId: 1, priorityRank: 1, claimableNow: true, publicAt: secondsFromNow(120) }),
    ])

    const card = cardOf(1)
    expect(within(card).getByText('우선 수령 1순위')).toBeInTheDocument()
    expect(within(card).getByText(/추천 기사끼리만 가져갈 수 있어요/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: '가져오기' })).toBeEnabled()
  })

  it('우선권이 없고 아직 공개 전이면 배지 없이 버튼을 비활성화한다', async () => {
    await renderMarket([
      openPlan({ planId: 2, priorityRank: null, claimableNow: false, publicAt: secondsFromNow(120) }),
    ])

    const card = cardOf(2)
    expect(within(card).queryByText(/우선 수령 \d+순위/)).not.toBeInTheDocument()
    expect(within(card).getByText(/뒤 전체 공개/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: '가져오기' })).toBeDisabled()
  })

  it('전체 공개된 업무는 선착순 안내와 함께 바로 가져갈 수 있다', async () => {
    await renderMarket([openPlan({ planId: 3, publicAt: null, claimableNow: true })])

    const card = cardOf(3)
    expect(within(card).getByText(/먼저 누르는 기사가 담당합니다/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: '가져오기' })).toBeEnabled()
  })

  /**
   * 반납은 public_at 만 비우고 우선권 행은 남기므로 priorityRank 가 계속 내려온다.
   * 윈도우가 끝난 뒤에는 우선권 표시가 사라져야 한다.
   */
  it('반납되어 공개 시각이 사라지면 우선권 순위가 남아 있어도 배지를 숨긴다', async () => {
    await renderMarket([
      openPlan({ planId: 4, priorityRank: 2, publicAt: null, claimableNow: true }),
    ])

    const card = cardOf(4)
    expect(within(card).queryByText('우선 수령 2순위')).not.toBeInTheDocument()
    expect(within(card).getByText(/먼저 누르는 기사가 담당합니다/)).toBeInTheDocument()
  })
})

describe('JobMarketPage 공개 카운트다운', () => {
  /**
   * 컴포넌트의 1초 타이머가 로컬 시계를 밀어 카운트다운이 0이 되면 잠금이 풀려야 한다.
   * 가짜 타이머와 React act 조합은 초기 로딩 promise 플러시가 불안정해 실제 타이머로 검증한다.
   */
  it('공개 시각이 지나면 잠겨 있던 버튼이 활성화된다', async () => {
    await renderMarket([
      openPlan({ planId: 5, priorityRank: null, claimableNow: false, publicAt: secondsFromNow(1) }),
    ])

    expect(screen.getByRole('button', { name: '가져오기' })).toBeDisabled()

    await waitFor(
      () => expect(screen.getByRole('button', { name: '가져오기' })).toBeEnabled(),
      { timeout: 4_000 },
    )
  })
})

describe('JobMarketPage 수령 결과 처리', () => {
  it('수령에 성공하면 배송 계획 상세로 이동한다', async () => {
    await renderMarket([openPlan({ planId: 11 })])
    claimDeliveryPlanMock.mockResolvedValue(claimResponse({ planId: 11, alreadyOwned: false }))

    fireEvent.click(screen.getByRole('button', { name: '가져오기' }))

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/plans/11'))
  })

  it('이미 본인이 가져간 업무(멱등 응답)도 상세로 이동한다', async () => {
    await renderMarket([openPlan({ planId: 12 })])
    claimDeliveryPlanMock.mockResolvedValue(claimResponse({ planId: 12, alreadyOwned: true }))

    fireEvent.click(screen.getByRole('button', { name: '가져오기' }))

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/plans/12'))
  })

  it.each([
    ['DELIVERY_PLAN_ALREADY_CLAIMED', '방금 다른 기사가 가져갔어요'],
    ['DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED', '동시에 가질 수 있는 업무 수를 넘었어요'],
    ['DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE', '아직 추천 기사 우선 수령 시간이에요'],
    ['DELIVERY_CLAIM_LOCK_CONFLICT', '다른 요청을 처리하고 있어요'],
  ])('409 %s 응답이면 코드에 맞는 안내를 보여준다', async (code, expectedMessage) => {
    await renderMarket([openPlan({ planId: 13 })])
    claimDeliveryPlanMock.mockRejectedValue(apiError(409, code))

    fireEvent.click(screen.getByRole('button', { name: '가져오기' }))

    expect(await screen.findByText(new RegExp(expectedMessage))).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('수령에 실패하면 목록을 즉시 다시 불러와 화면을 최신 상태로 맞춘다', async () => {
    await renderMarket([openPlan({ planId: 14 })])
    claimDeliveryPlanMock.mockRejectedValue(apiError(409, 'DELIVERY_PLAN_ALREADY_CLAIMED'))
    const callsBeforeClaim = getOpenDeliveryPlansMock.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '가져오기' }))

    await waitFor(() => {
      expect(getOpenDeliveryPlansMock.mock.calls.length).toBeGreaterThan(callsBeforeClaim)
    })
  })
})
