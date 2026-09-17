import type { TokenResponse, UserInfo } from '../types/api'
import { apiRequest } from './client'

export function login(loginId: string, password: string) {
  return apiRequest<TokenResponse>('/api/users/login', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ loginId, password }),
  })
}

export function logout() {
  return apiRequest<void>('/api/users/logout', { method: 'POST' })
}

/** 현재 로그인한 회원을 탈퇴 처리한다. 서버는 soft delete 후 refresh 쿠키를 지운다. */
export function withdraw() {
  return apiRequest<void>('/api/users/me', { method: 'DELETE' })
}

export function getMyInfo() {
  return apiRequest<UserInfo>('/api/users/info')
}
