'use strict'

const fs = require('node:fs')
const path = require('node:path')

const SCHEMA_VERSION = 1
const MAX_RESUME_AGE_MS = 24 * 60 * 60 * 1000
const RESUMABLE_TYPES = new Set(['goto', 'goto_waypoint'])

class TaskStore {
  constructor(file, options = {}) {
    this.file = path.resolve(file)
    this.enabled = options.resumeEnabled !== false
    this.lastError = ''
  }

  restore(validate = () => null) {
    let parsed
    try { parsed = JSON.parse(fs.readFileSync(this.file, 'utf8')) } catch (_) {
      return { activeTask: null, activeTaskArgs: null, recentTasks: [] }
    }
    if (parsed?.schema_version !== SCHEMA_VERSION) {
      this.lastError = '不支持的任务存储版本'
      return { activeTask: null, activeTaskArgs: null, recentTasks: [] }
    }
    const recentTasks = Array.isArray(parsed.recent_tasks) ? parsed.recent_tasks.slice(-100) : []
    const stored = parsed.active_task
    const args = parsed.active_task_args
    if (!stored || !['waiting_for_connection', 'running', 'suspended'].includes(stored.state)) {
      return { activeTask: null, activeTaskArgs: null, recentTasks }
    }
    const age = Date.now() - Number(stored.updated_at_ms || stored.accepted_at_ms || 0)
    let reason = null
    if (!this.enabled) reason = '服务端已关闭导航任务自动恢复'
    else if (!RESUMABLE_TYPES.has(String(stored.task_type))) reason = '该任务类型不允许在重启后自动恢复'
    else if (!Number.isFinite(age) || age < 0 || age > MAX_RESUME_AGE_MS) reason = '未完成导航任务已超过 24 小时'
    else reason = validate(stored, args) || null
    if (reason) {
      recentTasks.push({ ...stored, state: 'failed', finished_at_ms: Date.now(), message: reason, resumed: false })
      return { activeTask: null, activeTaskArgs: null, recentTasks: recentTasks.slice(-100) }
    }
    return {
      activeTask: {
        ...stored,
        state: 'waiting_for_connection',
        resumed: true,
        resumed_at_ms: Date.now(),
        updated_at_ms: Date.now()
      },
      activeTaskArgs: args && typeof args === 'object' ? args : {},
      recentTasks
    }
  }

  save(activeTask, activeTaskArgs, recentTasks) {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 })
      const temporary = `${this.file}.tmp`
      const payload = {
        schema_version: SCHEMA_VERSION,
        saved_at_ms: Date.now(),
        active_task: activeTask ? persistedTask(activeTask) : null,
        active_task_args: activeTask ? sanitizeArgs(activeTaskArgs) : null,
        recent_tasks: Array.from(recentTasks || []).slice(-100).map(persistedTask)
      }
      fs.writeFileSync(temporary, JSON.stringify(payload, null, 2), { mode: 0o600 })
      fs.renameSync(temporary, this.file)
      this.lastError = ''
      return true
    } catch (error) {
      this.lastError = safeMessage(error)
      return false
    }
  }

  status() {
    return { enabled: this.enabled, file: path.basename(this.file), last_error: this.lastError || null }
  }
}

function persistedTask(task) {
  if (!task || typeof task !== 'object') return null
  const result = { ...task, updated_at_ms: Number(task.updated_at_ms || task.accepted_at_ms || Date.now()) }
  delete result.data
  return result
}

function sanitizeArgs(args) {
  if (!args || typeof args !== 'object') return {}
  const encoded = JSON.stringify(args)
  if (Buffer.byteLength(encoded) > 64 * 1024) throw new Error('任务参数超过持久化上限')
  return JSON.parse(encoded)
}

function safeMessage(error) {
  return String(error?.message || error || 'unknown error').replace(/[\r\n\t]+/g, ' ').slice(0, 300)
}

module.exports = { MAX_RESUME_AGE_MS, RESUMABLE_TYPES, TaskStore }
