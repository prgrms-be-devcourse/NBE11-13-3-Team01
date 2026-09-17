import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

interface ConfirmDialogProps {
  open: boolean
  title: string
  description: ReactNode
  confirmLabel: string
  cancelLabel?: string
  /** danger 는 확인 버튼을 빨간색으로 바꾼다. 되돌릴 수 없는 작업에 쓴다. */
  tone?: 'default' | 'danger'
  /** 처리 중에는 닫기·확인을 모두 막는다. 중복 요청과 어정쩡한 중단을 피한다. */
  isBusy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/**
 * 되돌릴 수 없는 작업에 한 번 더 물어보는 모달.
 *
 * `window.confirm` 대신 직접 만든 이유는 디자인 일관성 때문만이 아니다.
 * `confirm` 은 렌더 스레드를 멈춰서 "처리 중" 상태를 보여줄 수 없고,
 * jsdom 에서는 아예 동작하지 않아 테스트에서 전역 모킹이 필요하다.
 *
 * 접근성에서 챙긴 것:
 * - 열릴 때 포커스를 **취소 버튼**에 둔다. Enter 를 잘못 눌러 파괴적 작업이 실행되면 안 된다.
 * - Esc 와 배경 클릭으로 닫힌다. 단, 처리 중에는 둘 다 막는다.
 * - 닫힐 때 원래 있던 곳으로 포커스를 돌려준다.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = '취소',
  tone = 'default',
  isBusy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const titleId = useId()
  const descriptionId = useId()
  const cancelRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return

    const previouslyFocused = document.activeElement as HTMLElement | null
    cancelRef.current?.focus()

    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    return () => {
      document.body.style.overflow = overflow
      previouslyFocused?.focus?.()
    }
  }, [open])

  useEffect(() => {
    if (!open) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !isBusy) {
        onCancel()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isBusy, onCancel, open])

  if (!open) return null

  /*
   * body 로 포털한다. 페이지 전환 애니메이션이 화면 컨테이너에 transform 을 거는데,
   * transform 이 걸린 조상은 position: fixed 의 기준이 되어 버린다. 그러면 전환 직후
   * 모달을 열었을 때 배경이 뷰포트가 아니라 그 컨테이너에 맞춰져 어긋난다.
   * 쌓임 맥락(z-index) 문제도 같은 이유로 사라진다.
   */
  return createPortal(
    <div
      className="dialog-backdrop"
      onMouseDown={(event) => {
        // 배경을 직접 누른 경우에만 닫는다. 카드 안에서 드래그하다 배경에서 손을 떼도 닫히면 안 된다.
        if (event.target === event.currentTarget && !isBusy) onCancel()
      }}
    >
      <div className="dialog-card" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId}>
        <h2 id={titleId}>{title}</h2>
        <div id={descriptionId} className="dialog-body">
          {description}
        </div>
        <div className="dialog-actions">
          <button
            ref={cancelRef}
            type="button"
            className="button button-secondary"
            onClick={onCancel}
            disabled={isBusy}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={tone === 'danger' ? 'button button-danger' : 'button button-primary'}
            onClick={onConfirm}
            disabled={isBusy}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
