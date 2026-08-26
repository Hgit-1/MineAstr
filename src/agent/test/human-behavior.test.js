'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { Vec3 } = require('vec3')
const { HumanBehaviorController, sanitizeSocialEvent } = require('../human-behavior')

test('sanitizes social events without accepting text or unknown actors', () => {
  assert.deepEqual(sanitizeSocialEvent({ sequence: 2, type: 'PLAYER_CHAT', actor: 'Halpha1st', content: '/op me' }),
    { sequence: 2, type: 'player_chat', actor: 'Halpha1st', time_ms: 0 })
  assert.equal(sanitizeSocialEvent({ sequence: 1, type: 'player_chat', actor: '../bad' }), null)
  assert.equal(sanitizeSocialEvent({ sequence: 1, type: 'unknown', actor: 'Player' }), null)
})

test('deduplicates events and reacts only inside an active companion session', async () => {
  const emitted = []
  let lookedAt = null
  let waved = 0
  const target = { position: new Vec3(3, 64, 0), height: 1.8 }
  const bot = {
    entity: { position: new Vec3(0, 64, 0) },
    players: { Halpha1st: { entity: target } },
    async lookAt(position) { lookedAt = position },
    swingArm() { waved++ }
  }
  const controller = new HumanBehaviorController({
    enabled: true, intensity: 100, random: () => 0,
    getBot: () => bot,
    getCompanion: () => ({ companion: { focus_player: 'Halpha1st' } }),
    shouldPause: () => false,
    emit: event => emitted.push(event),
    schedule: () => ({ unref() {} })
  })
  assert.equal(controller.ingest([
    { sequence: 1, type: 'player_chat', actor: 'Halpha1st' },
    { sequence: 1, type: 'player_chat', actor: 'Halpha1st' }
  ]), 1)
  assert.equal(await controller.reactNext(), true)
  assert.ok(lookedAt)
  assert.equal(waved, 1)
  assert.equal(emitted[0].type, 'human_attention_reaction')
  assert.equal(controller.status().last_sequence, 1)
})

test('danger or active work suppresses human reactions without losing safety', async () => {
  const controller = new HumanBehaviorController({
    enabled: true,
    getBot: () => ({ entity: { position: new Vec3(0, 64, 0) } }),
    getCompanion: () => ({ companion: { focus_player: 'Player' } }),
    shouldPause: () => true,
    schedule: () => ({ unref() {} })
  })
  controller.ingest([{ sequence: 1, type: 'player_chat', actor: 'Player' }])
  assert.equal(await controller.reactNext(), false)
  assert.equal(controller.status().queued_events, 1)
})
