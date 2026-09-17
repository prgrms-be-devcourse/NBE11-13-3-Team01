import dgram from 'node:dgram'
import http from 'node:http'

const iterations = Number(process.env.ROUTE_BENCH_ITERATIONS ?? 1000)
const payload = JSON.stringify({
  nodeCount: 6,
  costs: [
    -1, 12, 18, 9, 20, 15,
    -1, -1, 10, 14, 8, 16,
    -1, -1, -1, 11, 13, 7,
    -1, -1, -1, -1, 6, 12,
    -1, -1, -1, -1, -1, 9,
    -1, -1, -1, -1, -1, -1,
  ],
})

const percentile = (values, p) => values[Math.min(values.length - 1, Math.floor(values.length * p))]
const stats = (samples, failures, firstError = null) => {
  samples.sort((a, b) => a - b)
  return { count: samples.length, failures, firstError, avgMs: samples.length ? samples.reduce((a, b) => a + b, 0) / samples.length : null, p50Ms: samples.length ? percentile(samples, 0.5) : null, p95Ms: samples.length ? percentile(samples, 0.95) : null, p99Ms: samples.length ? percentile(samples, 0.99) : null, maxMs: samples.at(-1) ?? null }
}

async function benchmarkHttp() {
  const samples = []; let failures = 0; let firstError = null
  for (let i = 0; i < iterations; i++) {
    const started = process.hrtime.bigint()
    try {
      await httpRequest()
      samples.push(Number(process.hrtime.bigint() - started) / 1e6)
    } catch (error) { failures++; if (!firstError) firstError = error.message }
  }
  return stats(samples, failures, firstError)
}

function httpRequest() {
  return new Promise((resolve, reject) => {
    const request = http.request(process.env.ROUTE_BENCH_HTTP ?? 'http://localhost:8091/optimize', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload), connection: 'close' },
      timeout: Number(process.env.ROUTE_BENCH_TIMEOUT_MS ?? 1000),
    }, (response) => {
      response.resume()
      response.on('end', () => response.statusCode >= 200 && response.statusCode < 300 ? resolve() : reject(new Error(`HTTP ${response.statusCode}`)))
    })
    request.on('timeout', () => request.destroy(new Error('timeout')))
    request.on('error', reject)
    request.end(payload)
  })
}

function udpRequest() {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4')
    const timer = setTimeout(() => { socket.close(); reject(new Error('timeout')) }, Number(process.env.ROUTE_BENCH_TIMEOUT_MS ?? 1000))
    socket.on('message', (message) => { clearTimeout(timer); socket.close(); resolve(message) })
    socket.on('error', (error) => { clearTimeout(timer); socket.close(); reject(error) })
    socket.send(Buffer.from(payload), Number(process.env.ROUTE_BENCH_UDP_PORT ?? 8092), process.env.ROUTE_BENCH_UDP_HOST ?? 'localhost')
  })
}

async function benchmarkUdp() {
  const samples = []; let failures = 0; let firstError = null
  for (let i = 0; i < iterations; i++) {
    const started = process.hrtime.bigint()
    try { await udpRequest(); samples.push(Number(process.hrtime.bigint() - started) / 1e6) } catch (error) { failures++; if (!firstError) firstError = error.message }
  }
  return stats(samples, failures, firstError)
}

console.log(JSON.stringify({ iterations, http: await benchmarkHttp(), udp: await benchmarkUdp() }, null, 2))
