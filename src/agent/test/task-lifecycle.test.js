'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { resumeNavigationRecord, suspendNavigationRecord } = require('../task-lifecycle')

test('survival suspension preserves navigation identity and checkpoint', () => {
  const running = {
    task_id: 'survival-goto', task_type: 'goto_waypoint', run_id: 4, state: 'running',
    accepted_at_ms: 100, navigation_checkpoint: { position: { x: 12, y: 70, z: -3 }, corridor_index: 2 }
  }
  const suspended = suspendNavigationRecord(running, {
    runId: 5, now: 200, reason: 'low_health', message: '生命值过低'
  })
  assert.equal(suspended.task_id, running.task_id)
  assert.equal(suspended.task_type, running.task_type)
  assert.equal(suspended.state, 'suspended')
  assert.equal(suspended.run_id, 5)
  assert.deepEqual(suspended.navigation_checkpoint, running.navigation_checkpoint)
  assert.equal(suspended.suspension_reason, '生命值过低')
})

test('survival resume allocates a fresh run and keeps the suspended record', () => {
  const suspended = suspendNavigationRecord({
    task_id: 'survival-goto', task_type: 'goto', run_id: 9, state: 'running'
  }, {
    runId: 10, now: 300, checkpoint: { position: { x: 20, y: 64, z: 20 } }
  })
  const resumed = resumeNavigationRecord(suspended, { runId: 11, now: 400 })
  assert.equal(resumed.state, 'waiting_for_connection')
  assert.equal(resumed.run_id, 11)
  assert.equal(resumed.resumed, true)
  assert.equal(resumed.resumed_at_ms, 400)
  assert.deepEqual(resumed.navigation_checkpoint, suspended.navigation_checkpoint)
})

test('invalid lifecycle transitions are rejected', () => {
  assert.throws(() => suspendNavigationRecord({ state: 'failed' }), /正在执行/)
  assert.throws(() => resumeNavigationRecord({ state: 'running' }), /已挂起/)
})
