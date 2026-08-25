'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { AgentEventBuffer } = require('../agent-events')
const { CompanionController } = require('../companion')

test('persists a focused companion session and keeps it online only while humans are present', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mineastr-companion-'))
  const file = path.join(directory, 'companion.json')
  const events = []
  try {
    const controller = new CompanionController(file, { enabled: true, emit: event => events.push(event) })
    const started = controller.operate({ action: 'start', session_id: 'session-1', focus_player: 'Halpha1st', goal: '一起看看车站' })
    assert.equal(started.companion.focus_player, 'Halpha1st')
    assert.equal(started.companion.state, 'active')
    assert.equal(controller.needsSession(1), true)
    assert.equal(controller.needsSession(1, ['Halpha1st']), true)
    assert.equal(controller.needsSession(1, ['SomeoneElse']), false)
    assert.equal(controller.needsSession(0), false)

    const restored = new CompanionController(file, { enabled: true })
    assert.equal(restored.status().companion.goal, '一起看看车站')
    const lingering = restored.operate({ action: 'update', goal_completed: true, linger_seconds: 600 })
    assert.equal(lingering.companion.state, 'lingering')
    restored.operate({ action: 'stop', reason: 'player_goodbye' })
    assert.equal(restored.status().companion, null)
    assert.equal(events[0].type, 'companion_session_started')
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

test('rejects companion activation when the authoritative server switch is disabled', () => {
  const controller = new CompanionController(path.join(os.tmpdir(), `mineastr-disabled-${Date.now()}.json`), { enabled: false })
  assert.throws(() => controller.operate({ action: 'start', focus_player: 'Halpha1st' }), /未开启/)
})

test('agent event buffer exposes a bounded cursor stream without raw NBT-like payloads', () => {
  const buffer = new AgentEventBuffer(16)
  buffer.append({ type: 'task_started', task: { task_id: 'one', data: { secret: true }, nbt: '{unsafe}' } })
  buffer.append({ type: 'container_opened', position: { x: 1, y: 2, z: 3 } })
  const result = buffer.since(1, 10)
  assert.equal(result.events.length, 1)
  assert.equal(result.events[0].sequence, 2)
  assert.equal(JSON.stringify(buffer.since(0)).includes('unsafe'), false)
})
