'use strict'

const fs = require('node:fs')
const path = require('node:path')

class CompanionController {
  constructor(file, options = {}) {
    this.file = file
    this.enabled = Boolean(options.enabled)
    this.defaultLingerSeconds = boundedInteger(options.lingerSeconds, 600, 60, 3600)
    this.emit = typeof options.emit === 'function' ? options.emit : () => {}
    this.getBot = typeof options.getBot === 'function' ? options.getBot : () => null
    this.shouldPause = typeof options.shouldPause === 'function' ? options.shouldPause : () => true
    this.session = this._load()
    this.motionTimer = null
    this.motionBusy = false
  }

  operate(input = {}) {
    const action = String(input.action || 'status').toLowerCase()
    if (action === 'status') return this.status()
    if (!this.enabled) throw new Error('服务端未开启 Agent 陪伴模式')
    if (action === 'stop') {
      const previous = this.session
      this.session = null
      this._save()
      this.emit({ type: 'companion_session_stopped', session_id: previous?.session_id || null,
        reason: text(input.reason || 'requested', 100) })
      return this.status()
    }
    if (!['start', 'update'].includes(action)) throw new Error(`不支持的陪伴操作：${action}`)
    if (action === 'update' && !this.session) throw new Error('当前没有可更新的陪伴会话')
    const now = Date.now()
    const sessionId = text(input.session_id || this.session?.session_id || `companion-${now}`, 80)
    const focusPlayer = minecraftName(input.focus_player || this.session?.focus_player)
    if (!focusPlayer) throw new Error('陪伴会话需要有效的关注玩家名')
    const goal = text(input.goal ?? this.session?.goal ?? '', 1000)
    const goalCompleted = Boolean(input.goal_completed)
    const lingerSeconds = boundedInteger(input.linger_seconds, this.defaultLingerSeconds, 60, 3600)
    this.session = {
      session_id: sessionId,
      focus_player: focusPlayer,
      goal,
      state: goalCompleted ? 'lingering' : 'active',
      started_at_ms: this.session?.started_at_ms || now,
      updated_at_ms: now,
      goal_completed_at_ms: goalCompleted ? now : null,
      linger_until_ms: goalCompleted ? now + lingerSeconds * 1000 : null,
      last_action_summary: text(input.last_action_summary || this.session?.last_action_summary || '', 300) || null
    }
    this._save()
    this.emit({ type: action === 'start' ? 'companion_session_started' : 'companion_session_updated',
      companion: this.status().companion })
    return this.status()
  }

  needsSession(humanPlayerCount = 0, humanPlayers = null) {
    this._expire()
    if (!this.enabled || !this.session || Number(humanPlayerCount) <= 0) return false
    if (!Array.isArray(humanPlayers)) return true
    const focus = String(this.session.focus_player || '').toLowerCase()
    return humanPlayers.some(name => String(name || '').toLowerCase() === focus)
  }

  status() {
    this._expire()
    return {
      ok: true,
      enabled: this.enabled,
      companion: this.session ? { ...this.session } : null,
      human_motion: { running: Boolean(this.motionTimer), busy: this.motionBusy }
    }
  }

  startMotion() {
    if (!this.enabled || this.motionTimer) return
    this._scheduleMotion(1200)
  }

  stopMotion() {
    if (this.motionTimer) clearTimeout(this.motionTimer)
    this.motionTimer = null
    this.motionBusy = false
  }

  _scheduleMotion(delay = randomInteger(3000, 8000)) {
    if (!this.enabled) return
    this.motionTimer = setTimeout(() => {
      this.motionTimer = null
      void this._motionTick().finally(() => this._scheduleMotion())
    }, delay)
    this.motionTimer.unref?.()
  }

  async _motionTick() {
    this._expire()
    const bot = this.getBot()
    if (!this.session || !bot?.entity || this.shouldPause()) return
    const target = bot.players?.[this.session.focus_player]?.entity
    if (!target?.position || target.position.distanceTo(bot.entity.position) > 16) return
    this.motionBusy = true
    try {
      const roll = Math.random()
      if (roll < 0.68 && typeof bot.lookAt === 'function') {
        await bot.lookAt(target.position.offset(0, Math.max(0.8, target.height || 1.6) * 0.8, 0), false)
        this.emit({ type: 'companion_human_motion', motion: 'look_at_focus', focus_player: this.session.focus_player })
      } else if (roll < 0.84 && typeof bot.swingArm === 'function') {
        bot.swingArm('right')
        this.emit({ type: 'companion_human_motion', motion: 'wave', focus_player: this.session.focus_player })
      } else if (typeof bot.setControlState === 'function') {
        bot.setControlState('sneak', true)
        await delay(220)
        bot.setControlState('sneak', false)
        this.emit({ type: 'companion_human_motion', motion: 'brief_crouch', focus_player: this.session.focus_player })
      }
    } catch (_) {
      try { bot.setControlState?.('sneak', false) } catch (_) {}
    } finally {
      this.motionBusy = false
    }
  }

  _expire() {
    if (!this.session || this.session.state !== 'lingering') return
    if (Date.now() < Number(this.session.linger_until_ms || 0)) return
    const expired = this.session
    this.session = null
    this._save()
    this.emit({ type: 'companion_session_expired', session_id: expired.session_id })
  }

  _load() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.file, 'utf8'))
      if (!parsed || typeof parsed !== 'object' || !parsed.session_id || !minecraftName(parsed.focus_player)) return null
      if (parsed.state === 'lingering' && Date.now() >= Number(parsed.linger_until_ms || 0)) return null
      return parsed
    } catch (_) { return null }
  }

  _save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    const temporary = `${this.file}.tmp`
    fs.writeFileSync(temporary, JSON.stringify({ schema_version: 1, ...(this.session || {}) }, null, 2), { mode: 0o600 })
    fs.renameSync(temporary, this.file)
  }
}

function minecraftName(value) {
  const selected = String(value || '').trim()
  return /^[A-Za-z0-9_]{3,16}$/.test(selected) ? selected : null
}

function text(value, limit) { return String(value || '').replace(/[\r\n\t]+/g, ' ').trim().slice(0, limit) }
function boundedInteger(value, fallback, min, max) {
  const number = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback
}
function randomInteger(min, max) { return Math.floor(min + Math.random() * (max - min + 1)) }
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }

module.exports = { CompanionController, minecraftName }
