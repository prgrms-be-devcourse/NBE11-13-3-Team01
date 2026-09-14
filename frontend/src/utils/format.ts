export function formatDateTime(value: string | null | undefined) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function toDateTimeLocal(value: string) {
  return value.slice(0, 16)
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.'
}

export function maskName(value: string | null | undefined) {
  if (!value) return '-'
  const characters = Array.from(value.trim())
  if (characters.length === 0) return '-'

  return `${characters[0]}${'*'.repeat(Math.max(2, characters.length - 1))}`
}

export function maskLoginId(value: string | null | undefined) {
  if (!value) return '-'
  const characters = Array.from(value.trim())
  if (characters.length === 0) return '-'

  return `**${characters.slice(-3).join('')}`
}
