'use strict'

function suspendNavigationRecord(task, options = {}) {
  if (!task || task.state !== 'running') throw new Error('只能挂起正在执行的导航任务')
  const now = finiteTimestamp(options.now)
  return {
    ...task,
    run_id: options.runId,
    state: 'suspended',
    suspended_at_ms: now,
    updated_at_ms: now,
    suspension_reason: String(options.message || options.reason || 'survival').slice(0, 300),
    navigation_checkpoint: options.checkpoint || task.navigation_checkpoint || null
  }
}

function resumeNavigationRecord(task, options = {}) {
  if (!task || task.state !== 'suspended') throw new Error('只能恢复已挂起的导航任务')
  const now = finiteTimestamp(options.now)
  return {
    ...task,
    run_id: options.runId,
    state: 'waiting_for_connection',
    resumed: true,
    resumed_at_ms: now,
    updated_at_ms: now
  }
}

function finiteTimestamp(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : Date.now()
}

module.exports = { resumeNavigationRecord, suspendNavigationRecord }
