import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // serve the API from the same origin so the session and CSRF cookies just work
    proxy: { '/api': process.env.API_URL || 'http://localhost:8081' },
  },
});
