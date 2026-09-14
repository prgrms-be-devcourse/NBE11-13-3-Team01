import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// 테스트는 vite.config.ts 의 dev 서버·프록시 설정이 필요 없으므로 별도 설정으로 분리한다.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    clearMocks: true,
    restoreMocks: true,
  },
})
