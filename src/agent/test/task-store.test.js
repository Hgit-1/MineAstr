'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { MAX_RESUME_AGE_MS, TaskStore } = require('../task-store')

function storeFixture(resumeEnabled = true) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mineastr-task-test-'))
  return { root, store: new TaskStore(path.join(root, 'tasks.json'), { resumeEnabled }) }
}

test('atomically restores an interrupted goto with its checkpoint', t => {
  const state = storeFixture()
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  const task = {
    task_id: 'task-1', task_type: 'goto', state: 'running', accepted_at_ms: Date.now(),
    navigation_checkpoint: { position: { x: 24, y: 65, z: 0 }, corridor_index: 3 }
  }
  assert.equal(state.store.save(task, { x: 100, y: 65, z: 0 }, []), true)
  const restored = state.store.restore()
  assert.equal(restored.activeTask.state, 'waiting_for_connection')
  assert.equal(restored.activeTask.resumed, true)
  assert.deepEqual(restored.activeTaskArgs, { x: 100, y: 65, z: 0 })
  assert.equal(restored.activeTask.navigation_checkpoint.corridor_index, 3)
})

test('does not resume unsafe action tasks or expired navigation', t => {
  const state = storeFixture()
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  state.store.save({ task_id: 'task-2', task_type: 'use_item', state: 'running', accepted_at_ms: Date.now() }, {}, [])
  let restored = state.store.restore()
  assert.equal(restored.activeTask, null)
  assert.match(restored.recentTasks.at(-1).message, /不允许/)

  state.store.save({
    task_id: 'task-3', task_type: 'goto', state: 'suspended',
    accepted_at_ms: Date.now() - MAX_RESUME_AGE_MS - 1000,
    updated_at_ms: Date.now() - MAX_RESUME_AGE_MS - 1000
  }, { x: 100, y: 64, z: 0 }, [])
  restored = state.store.restore()
  assert.equal(restored.activeTask, null)
  assert.match(restored.recentTasks.at(-1).message, /24/)
})

test('server policy can disable automatic resume', t => {
  const state = storeFixture(false)
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  state.store.save({ task_id: 'task-4', task_type: 'goto', state: 'running', accepted_at_ms: Date.now() },
    { x: 10, y: 64, z: 0 }, [])
  const restored = state.store.restore()
  assert.equal(restored.activeTask, null)
  assert.match(restored.recentTasks.at(-1).message, /关闭/)
})
