'use strict'

const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const path = require('node:path')
const test = require('node:test')

test('control server authenticates and reports disconnected bot safely', async () => {
  const child = spawn(process.execPath, [path.resolve(__dirname, '..', 'index.js')], {
    env: {
      ...process.env,
      MINEASTR_AGENT_TOKEN: 'test-token',
      MINEASTR_AGENT_DATA_DIR: path.resolve(__dirname, '.tmp'),
      MINEASTR_MC_HOST: '127.0.0.1',
      MINEASTR_MC_PORT: '9',
      MINEASTR_AGENT_USERNAME: 'MineAstrTest',
      MINEASTR_AGENT_AUTH: 'offline',
      MINEASTR_MC_VERSION: '1.21.1'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  try {
    const port = await readyPort(child)
    const unauthorized = await fetch(`http://127.0.0.1:${port}/status`, { method: 'POST' })
    assert.equal(unauthorized.status, 401)
    const response = await fetch(`http://127.0.0.1:${port}/status`, {
      method: 'POST',
      headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' },
      body: '{}'
    })
    assert.equal(response.status, 200)
    const status = await response.json()
    assert.equal(status.ok, true)
    assert.equal(status.username, 'MineAstrTest')
    assert.ok(['connecting', 'disconnected', 'error'].includes(status.state))
  } finally {
    child.kill('SIGTERM')
    await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(4000)])
    if (!child.killed) child.kill('SIGKILL')
  }
})

function readyPort(child) {
  return new Promise((resolve, reject) => {
    let buffer = ''
    const timeout = setTimeout(() => reject(new Error('Agent ready timeout')), 5000)
    child.once('exit', code => reject(new Error(`Agent exited before ready: ${code}`)))
    child.stdout.on('data', chunk => {
      buffer += chunk.toString('utf8')
      while (buffer.includes('\n')) {
        const newline = buffer.indexOf('\n')
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 1)
        try {
          const payload = JSON.parse(line)
          if (payload.type === 'ready') {
            clearTimeout(timeout)
            resolve(payload.port)
          }
        } catch (_) {}
      }
    })
  })
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}
