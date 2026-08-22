'use strict'

const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const net = require('node:net')
const path = require('node:path')
const test = require('node:test')

test('on-demand control stays in standby and an offline task wakes the bot', async () => {
  const child = spawn(process.execPath, [path.resolve(__dirname, '..', 'index.js')], {
    env: {
      ...process.env,
      MINEASTR_AGENT_TOKEN: 'test-token',
      MINEASTR_AGENT_DATA_DIR: path.resolve(__dirname, '.tmp'),
      MINEASTR_MC_HOST: '127.0.0.1',
      MINEASTR_MC_PORT: '9',
      MINEASTR_AGENT_USERNAME: 'MineAstrTest',
      MINEASTR_AGENT_AUTH: 'offline',
      MINEASTR_MC_VERSION: '1.21.1',
      MINEASTR_AGENT_JOIN_COMMANDS: '/login runtime-secret-must-not-leak'
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
    assert.equal(status.state, 'standby')
    assert.equal(status.connection_attempts, 0)
    assert.equal(status.session_policy, 'on_demand')
    assert.equal(status.join_commands.configured_count, 1)
    assert.equal(status.join_commands.phase, 'pending')
    assert.equal(JSON.stringify(status).includes('runtime-secret-must-not-leak'), false)

    const presence = await postJson(port, '/session', {
      human_player_count: 1, preferred_username: 'Aria'
    }, 'test-token')
    assert.equal(presence.status, 200)
    await delay(100)
    const presentButIdle = await (await postJson(port, '/status', {}, 'test-token')).json()
    assert.equal(presentButIdle.human_player_count, 1)
    assert.equal(presentButIdle.preferred_username, 'Aria')
    assert.equal(presentButIdle.username, 'Aria')
    assert.equal(presentButIdle.state, 'standby')
    assert.equal(presentButIdle.connection_attempts, 0)

    const accepted = await postJson(port, '/task', {
      task_id: 'offline-chat', task_type: 'chat', args: { message: 'hello' }
    }, 'test-token')
    assert.equal(accepted.status, 202)
    const task = await accepted.json()
    assert.equal(task.accepted, true)
    assert.equal(task.task.state, 'waiting_for_connection')
    await waitFor(async () => (await (await postJson(port, '/status', {}, 'test-token')).json()).connection_attempts > 0)

    const canceled = await postJson(port, '/cancel', {}, 'test-token')
    assert.equal((await canceled.json()).canceled, true)
    const afterCancel = await (await postJson(port, '/status', {}, 'test-token')).json()
    assert.equal(afterCancel.wake_reason, null)
  } finally {
    await stopChild(child)
  }
})

test('proxy protocol mode sends a v1 header before the Minecraft handshake', async () => {
  let resolveHeader
  const headerPromise = new Promise(resolve => { resolveHeader = resolve })
  const server = net.createServer(socket => {
    socket.once('data', data => {
      resolveHeader(data.toString('latin1'))
      socket.destroy()
    })
  })
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject))
  const child = spawn(process.execPath, [path.resolve(__dirname, '..', 'index.js')], {
    env: {
      ...process.env,
      MINEASTR_AGENT_TOKEN: 'proxy-test-token',
      MINEASTR_AGENT_DATA_DIR: path.resolve(__dirname, '.tmp-proxy'),
      MINEASTR_MC_HOST: '127.0.0.1',
      MINEASTR_MC_PORT: String(server.address().port),
      MINEASTR_AGENT_USERNAME: 'MineAstrProxy',
      MINEASTR_AGENT_AUTH: 'offline',
      MINEASTR_MC_VERSION: '1.21.1',
      MINEASTR_PROXY_PROTOCOL: 'true',
      MINEASTR_AGENT_SESSION_POLICY: 'always'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  try {
    await readyPort(child)
    const firstWrite = await Promise.race([
      headerPromise,
      delay(5000).then(() => { throw new Error('proxy header timeout') })
    ])
    assert.match(firstWrite, /^PROXY TCP4 127\.0\.0\.1 127\.0\.0\.1 \d+ \d+\r\n/)
  } finally {
    server.close()
    await stopChild(child)
  }
})

test('players-online policy connects only after human presence is synchronized', async () => {
  let resolveConnection
  const connectionPromise = new Promise(resolve => { resolveConnection = resolve })
  const server = net.createServer(socket => {
    resolveConnection()
    socket.destroy()
  })
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject))
  const child = spawn(process.execPath, [path.resolve(__dirname, '..', 'index.js')], {
    env: {
      ...process.env,
      MINEASTR_AGENT_TOKEN: 'presence-token',
      MINEASTR_AGENT_DATA_DIR: path.resolve(__dirname, '.tmp-presence'),
      MINEASTR_MC_HOST: '127.0.0.1',
      MINEASTR_MC_PORT: String(server.address().port),
      MINEASTR_AGENT_USERNAME: 'MineAstrHuman',
      MINEASTR_AGENT_AUTH: 'offline',
      MINEASTR_MC_VERSION: '1.21.1',
      MINEASTR_AGENT_SESSION_POLICY: 'players_online'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  try {
    const port = await readyPort(child)
    await delay(200)
    const standby = await (await postJson(port, '/status', {}, 'presence-token')).json()
    assert.equal(standby.state, 'standby')
    assert.equal(standby.connection_attempts, 0)

    const session = await postJson(port, '/session', { human_player_count: 1 }, 'presence-token')
    assert.equal(session.status, 200)
    await Promise.race([
      connectionPromise,
      delay(5000).then(() => { throw new Error('presence wake timeout') })
    ])
  } finally {
    server.close()
    await stopChild(child)
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

function postJson(port, endpoint, body, authorization) {
  return fetch(`http://127.0.0.1:${port}${endpoint}`, {
    method: 'POST',
    headers: { authorization: `Bearer ${authorization}`, 'content-type': 'application/json' },
    body: JSON.stringify(body)
  })
}

async function waitFor(predicate, timeoutMilliseconds = 5000) {
  const deadline = Date.now() + timeoutMilliseconds
  while (Date.now() < deadline) {
    if (await predicate()) return
    await delay(50)
  }
  throw new Error('condition timeout')
}

async function stopChild(child) {
  if (child.exitCode != null) return
  const exited = new Promise(resolve => child.once('exit', () => resolve(true)))
  child.kill('SIGTERM')
  if (await Promise.race([exited, delay(4000).then(() => false)])) return
  child.kill('SIGKILL')
  await exited
}
