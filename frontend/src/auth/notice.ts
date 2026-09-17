const NOTICE_KEY = 'delivery-insight.auth-notice'

/**
 * 로그인 화면에 한 번만 보여줄 안내 문구를 남긴다.
 *
 * navigate 의 state 를 쓸 수 없다. 탈퇴가 성공하면 AuthContext 가 먼저 사용자 상태를 비우고,
 * 그 리렌더에서 ProtectedRoute 가 `state: { from }` 을 실은 자기 Navigate 로 /login 에 보내버린다.
 * 우리가 넣으려던 state 는 그 과정에서 덮여 사라진다. 그래서 라우터 밖에 남긴다.
 */
export function setAuthNotice(message: string) {
  try {
    sessionStorage.setItem(NOTICE_KEY, message)
  } catch {
    // 사파리 프라이빗 모드처럼 저장이 막힌 환경에서는 안내를 포기한다. 탈퇴 자체는 이미 끝났다.
  }
}

/** 남아 있는 안내 문구를 읽고 곧바로 지운다. 새로고침하면 다시 보이면 안 된다. */
export function consumeAuthNotice(): string {
  try {
    const message = sessionStorage.getItem(NOTICE_KEY)
    if (message) sessionStorage.removeItem(NOTICE_KEY)
    return message ?? ''
  } catch {
    return ''
  }
}
