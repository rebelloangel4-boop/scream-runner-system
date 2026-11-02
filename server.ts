import express from 'express';
import { createServer as createViteServer } from 'vite';
import { createProxyMiddleware } from 'http-proxy-middleware';
import path from 'path';
import { fileURLToPath } from 'url';
import { spawn, ChildProcess } from 'child_process';
import http from 'http';
import fs from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const PYTHON_PORT = 8001;
const isProd = process.env.NODE_ENV === 'production';

let pythonProcess: ChildProcess | null = null;

function startPythonBackend() {
  const backendScript = path.resolve(__dirname, 'backend', 'main.py');
  console.log(`[Backend Bridge] Starting FastAPI backend on port ${PYTHON_PORT}...`);

  pythonProcess = spawn('python3', [backendScript], {
    env: { ...process.env, PORT: String(PYTHON_PORT) },
    stdio: 'inherit',
  });

  pythonProcess.on('error', (err) => {
    console.error('[Backend Bridge] Failed to spawn Python backend:', err);
  });

  pythonProcess.on('exit', (code, signal) => {
    console.log(`[Backend Bridge] Python backend exited with code ${code}, signal ${signal}`);
  });
}

// Cleanup on exit
process.on('SIGTERM', () => {
  if (pythonProcess) pythonProcess.kill('SIGTERM');
  process.exit(0);
});
process.on('SIGINT', () => {
  if (pythonProcess) pythonProcess.kill('SIGINT');
  process.exit(0);
});

async function main() {
  startPythonBackend();

  const app = express();
  const server = http.createServer(app);

  // Serve direct APK downloads with full Android Chrome headers
  const downloadsDir = path.resolve(__dirname, 'public', 'downloads');
  app.get(['/downloads/:filename', '/api/v1/downloads/:filename'], (req, res) => {
    const filename = req.params.filename;
    if (filename !== 'scream-runner.apk' && filename !== 'sams-dashboard.apk') {
      return res.status(404).send('APK file not found');
    }
    const filePath = path.join(downloadsDir, filename);

    if (fs.existsSync(filePath)) {
      const stat = fs.statSync(filePath);
      res.setHeader('Content-Type', 'application/vnd.android.package-archive');
      res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
      res.setHeader('Content-Length', stat.size);
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Expose-Headers', 'Content-Disposition, Content-Length');
      res.setHeader('X-Content-Type-Options', 'nosniff');
      res.setHeader('Cache-Control', 'public, max-age=3600');
      res.setHeader('Accept-Ranges', 'bytes');
      return res.sendFile(filePath);
    }
    return res.status(404).send('APK download not found');
  });

  // Proxy API & WebSockets to FastAPI backend preserving full route path
  app.use(
    createProxyMiddleware({
      target: `http://127.0.0.1:${PYTHON_PORT}`,
      changeOrigin: true,
      ws: true,
      pathFilter: ['/api', '/ws'],
    })
  );

  // Vite integration
  if (!isProd) {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.resolve(__dirname, 'dist')));
    app.get('*', (_req, res) => {
      res.sendFile(path.resolve(__dirname, 'dist', 'index.html'));
    });
  }

  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Server] Unified full-stack server running at http://0.0.0.0:${PORT}`);
  });
}

main().catch((err) => {
  console.error('[Server Error]', err);
});
