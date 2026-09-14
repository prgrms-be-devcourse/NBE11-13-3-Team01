import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 0.0.0.0 으로 바인딩해 같은 네트워크의 휴대폰에서도 접속할 수 있게 한다.
    host: true,
    port: 5173,
    // cloudflared 터널(https)로 접속할 때 Vite 의 호스트 검사를 통과시킨다.
    // Geolocation API 는 secure context 에서만 동작하므로 폰 실기기 테스트에는 https 터널이 필요하다.
    allowedHosts: ['.trycloudflare.com', '.ngrok-free.app', '.ngrok.io'],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
