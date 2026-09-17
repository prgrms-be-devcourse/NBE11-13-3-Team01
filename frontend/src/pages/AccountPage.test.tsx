import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDeliveryPlans } from '../api/deliveryPlans'
import { apiError, planSummary } from '../test/fixtures'
import type { DeliveryPlanSummary, UserInfo } from '../types/api'
import { AccountPage } from './AccountPage'

const navigateMock = vi.fn()
const withdrawMock = vi.fn()

const DRIVER: UserInfo = { id: 7, loginId: 'driver01', name: '김기사', role: 'ROLE_DELIVERY_DRIVER' }
const ADMIN: UserInfo = { id: 1, loginId: 'admin', name: '박총괄', role: 'ROLE_ADMIN' }

let currentUser: UserInfo = DRIVER

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigateMock,
}))

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: currentUser,
    isAuthenticated: true,
    isBootstrapping: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: withdrawMock,
  }),
}))

vi.mock('../api/deliveryPlans', () => ({
  getDeliveryPlans: vi.fn(),
}))

const getDeliveryPlansMock = vi.mocked(getDeliveryPlans)

/** 진행 중인 업무 확인이 끝날 때까지 기다린 뒤 화면을 돌려준다. */
async function renderPage(plans: DeliveryPlanSummary[] = []) {
  getDeliveryPlansMock.mockResolvedValue(plans)
  render(
    <MemoryRouter>
      <AccountPage />
    </MemoryRouter>,
  )
  return screen.findByRole('button', { name: '회원 탈퇴' })
}

async function openDialog() {
  fireEvent.click(await screen.findByRole('button', { name: '회원 탈퇴' }))
  return screen.getByRole('dialog')
}

beforeEach(() => {
  sessionStorage.clear()
  currentUser = DRIVER
})

afterEach(() => {
  cleanup()
})

describe('AccountPage 계정 정보', () => {
  it('이름·아이디·권한을 사람이 읽는 말로 보여준다', async () => {
    await renderPage()

    expect(screen.getByText('김기사')).toBeInTheDocument()
    expect(screen.getByText('driver01')).toBeInTheDocument()
    expect(screen.getByText('배송 기사')).toBeInTheDocument()
  })

  it('관리자는 기사로서 업무를 들 수 없으므로 업무 조회를 하지 않는다', async () => {
    currentUser = ADMIN
    await renderPage()

    expect(getDeliveryPlansMock).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeEnabled()
  })
})

describe('AccountPage 진행 중인 배송 확인', () => {
  it('확인이 끝나기 전에는 탈퇴 버튼을 막는다', () => {
    getDeliveryPlansMock.mockReturnValue(new Promise(() => {}))
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('button', { name: '배송 업무 확인 중...' })).toBeDisabled()
  })

  it('배송 준비·배송 중 업무가 있으면 탈퇴를 막고 어떤 업무인지 보여준다', async () => {
    await renderPage([
      planSummary({ planId: 21, status: 'READY' }),
      planSummary({ planId: 22, status: 'DELIVERING', departureLocation: '인천 센터' }),
    ])

    expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeDisabled()
    expect(screen.getByText(/진행 중인 배송 업무가 2건 있어/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /#021/ })).toHaveAttribute('href', '/plans/21')
    expect(screen.getByRole('link', { name: /인천 센터/ })).toHaveAttribute('href', '/plans/22')
  })

  it('완료·미배정 업무만 있으면 탈퇴할 수 있다', async () => {
    await renderPage([
      planSummary({ planId: 31, status: 'COMPLETED' }),
      planSummary({ planId: 32, status: 'OPEN' }),
    ])

    expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeEnabled()
    expect(screen.queryByText(/진행 중인 배송 업무가/)).not.toBeInTheDocument()
  })

  it('업무 조회가 실패해도 탈퇴를 막지 않는다', async () => {
    // 최종 판단은 서버가 한다. 조회 실패로 화면이 잠기면 탈퇴할 방법이 없어진다.
    getDeliveryPlansMock.mockRejectedValue(new Error('network'))
    render(
      <MemoryRouter>
        <AccountPage />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('button', { name: '회원 탈퇴' })).toBeEnabled()
  })
})

describe('AccountPage 회원 탈퇴', () => {
  it('탈퇴 버튼만 눌러서는 탈퇴되지 않고 확인 모달만 열린다', async () => {
    await renderPage()

    expect(await openDialog()).toBeInTheDocument()
    expect(withdrawMock).not.toHaveBeenCalled()
  })

  it('모달이 열리면 포커스가 취소 버튼에 간다', async () => {
    // Enter 를 잘못 눌러 되돌릴 수 없는 작업이 실행되면 안 된다.
    await renderPage()
    await openDialog()

    expect(screen.getByRole('button', { name: '취소' })).toHaveFocus()
  })

  it('취소하면 모달만 닫히고 탈퇴하지 않는다', async () => {
    await renderPage()
    await openDialog()

    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(withdrawMock).not.toHaveBeenCalled()
  })

  it('Esc 로도 모달을 닫을 수 있다', async () => {
    await renderPage()
    await openDialog()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(withdrawMock).not.toHaveBeenCalled()
  })

  it('확인하면 탈퇴하고 로그인 화면으로 보내며 안내 문구를 남긴다', async () => {
    withdrawMock.mockResolvedValue(undefined)
    await renderPage()
    await openDialog()

    fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }))

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/login', { replace: true }))
    expect(withdrawMock).toHaveBeenCalledTimes(1)
    // 라우터 state 는 ProtectedRoute 의 리다이렉트에 덮이므로 sessionStorage 로 전달한다.
    expect(sessionStorage.getItem('delivery-insight.auth-notice')).toContain('회원 탈퇴가 완료')
  })

  it('서버가 진행 중인 배송을 이유로 거절하면 오류를 보여주고 목록을 다시 읽는다', async () => {
    // 화면을 연 뒤 다른 탭에서 업무를 수령했을 수 있다. 서버 판정이 최종이다.
    withdrawMock.mockRejectedValue(
      apiError(409, 'WITHDRAW_BLOCKED_BY_ACTIVE_DELIVERY', '진행 중인 배송 업무가 있어 탈퇴할 수 없습니다'),
    )
    await renderPage()
    await openDialog()

    getDeliveryPlansMock.mockResolvedValue([planSummary({ planId: 21, status: 'DELIVERING' })])
    fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }))

    expect(await screen.findByText(/진행 중인 배송 업무가 있어 탈퇴할 수 없습니다/)).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('delivery-insight.auth-notice')).toBeNull()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    // 다시 읽은 목록이 화면에 반영돼야 한다.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeDisabled(),
    )
  })

  it('처리 중에는 확인·취소 버튼을 모두 막는다', async () => {
    // 응답이 오기 전에 두 번 누르면 두 번 호출된다.
    let resolveWithdraw: () => void = () => {}
    withdrawMock.mockReturnValue(new Promise<void>((resolve) => { resolveWithdraw = resolve }))

    await renderPage()
    await openDialog()
    fireEvent.click(screen.getByRole('button', { name: '탈퇴하기' }))

    const confirmButton = await screen.findByRole('button', { name: '탈퇴 처리 중...' })
    expect(confirmButton).toBeDisabled()
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()

    fireEvent.click(confirmButton)
    expect(withdrawMock).toHaveBeenCalledTimes(1)

    resolveWithdraw()
    await waitFor(() => expect(navigateMock).toHaveBeenCalled())
  })
})
