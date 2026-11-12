import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 개발 서버(기본 5173, FRONT_PORT로 변경)가 admin-api(8083)와 mock-servers(기본 8090, MOCK_PORT로 변경)로 프록시한다.
// 브라우저는 같은 출처로만 부른다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', ['MOCK_', 'FRONT_']);
  const mockPort = env.MOCK_PORT ?? '8090';
  return {
    plugins: [react()],
    server: {
      port: Number(env.FRONT_PORT ?? 5173),
      strictPort: true,
      proxy: {
        '/admin': 'http://localhost:8083',
        '/mock': {
          target: `http://localhost:${mockPort}`,
          rewrite: (path) => path.replace(/^\/mock/, '/_mock'),
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test-setup.ts'],
    },
  };
});
