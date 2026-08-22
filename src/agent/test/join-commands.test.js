'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { executeJoinCommands, parseJoinCommands } = require('../join-commands')

test('parses at most five safe slash-prefixed join commands', () => {
  const commands = parseJoinCommands([
    ' /register first-secret first-secret ',
    '/login second-secret',
    'say not-a-command',
    `/${'x'.repeat(256)}`,
    '/server lobby',
    '/one',
    '/two',
    '/three'
  ].join('\n'))
  assert.deepEqual(commands, [
    '/register first-secret first-secret',
    '/login second-secret',
    '/server lobby',
    '/one',
    '/two'
  ])
})

test('runs join commands before readiness without exposing their contents in state', async () => {
  const sent = []
  const waits = []
  const states = []
  const secret = '/login never-print-this-password'
  const result = await executeJoinCommands({
    commands: [secret, '/server survival'],
    commandDelayMs: 800,
    settleDelayMs: 1200,
    send: command => sent.push(command),
    wait: async milliseconds => { waits.push(milliseconds) },
    isActive: () => true,
    onState: state => states.push(state)
  })

  assert.equal(result.ok, true)
  assert.deepEqual(sent, [secret, '/server survival'])
  assert.deepEqual(waits, [800, 800, 1200])
  assert.equal(result.state.phase, 'complete')
  assert.equal(result.state.sent_count, 2)
  assert.equal(JSON.stringify(states).includes('never-print-this-password'), false)
})

test('reports only a safe index and code when sending fails', async () => {
  const secret = '/login should-stay-secret'
  const states = []
  const result = await executeJoinCommands({
    commands: [secret],
    send: () => { throw new Error(`could not send ${secret}`) },
    wait: async () => {},
    isActive: () => true,
    onState: state => states.push(state)
  })

  assert.equal(result.ok, false)
  assert.equal(result.failed_index, 0)
  assert.equal(result.state.last_error_code, 'send_failed')
  assert.equal(JSON.stringify(states).includes('should-stay-secret'), false)
})
