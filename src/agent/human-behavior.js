'use strict'

class HumanBehaviorController {
  constructor(options = {}) {
    this.enabled = Boolean(options.enabled)
    this.intensity = boundedInteger(options.intensity, 50, 0, 100)
    this.socialDistance = boundedInteger(options.socialDistance, 3, 2, 8)
    this.getBot = typeof options.getBot === 'function' ? options.getBot : () => null
    this.getCompanion = typeof options.getCompanion === 'function' ? options.getCompanion : () => null
    this.shouldPause = typeof options.shouldPause === 'function' ? options.shouldPause : () => true
    this.emit = typeof options.emit === 'function' ? options.emit : () => {}
    this.schedule = typeof options.schedule === 'function' ? options.schedule : setTimeout
    this.cancel = typeof options.cancel === 'function' ? options.cancel : clearTimeout
    this.random = typeof options.random === 'function' ? options.random : Math.random
    this.lastSequence = 0
    this.pending = []
    this.timer = null
    this.state = 'idle'
    this.attentionTarget = null
    this.lastReaction = null
    this.lastGestureAt = 0
  }

  ingest(events) {
    if (!this.enabled || !Array.isArray(events)) return 0
    let accepted = 0
    for (const raw of events) {
      const event = sanitizeSocialEvent(raw)
      if (!event || event.sequence <= this.lastSequence) continue
      this.lastSequence = event.sequence
      this.pending.push(event)
      accepted++
    }
    if (this.pending.length > 16) this.pending.splice(0, this.pending.length - 16)
    this._schedule()
    return accepted
  }

  status() {
    return {
      enabled: this.enabled,
      state: this.state,
      intensity: this.intensity,
      social_distance: this.socialDistance,
      attention_target: this.attentionTarget,
      last_sequence: this.lastSequence,
      queued_events: this.pending.length,
      last_reaction: this.lastReaction
    }
  }

  reset() {
    if (this.timer) this.cancel(this.timer)
    this.timer = null
    this.pending = []
    this.state = 'idle'
    this.attentionTarget = null
  }

  async reactNext() {
    if (!this.enabled || this.shouldPause()) return false
    const companion = this.getCompanion()?.companion
    if (!companion) return false
    const focus = String(companion.focus_player || '').toLowerCase()
    let index = this.pending.findIndex(event => String(event.actor).toLowerCase() === focus)
    if (index < 0) index = this.pending.length - 1
    if (index < 0) return false
    const [event] = this.pending.splice(index, 1)
    if (event.type === 'player_leave') return false
    const bot = this.getBot()
    const target = findPlayer(bot, event.actor)
    if (!bot?.entity || !target?.position || target.position.distanceTo(bot.entity.position) > 24) return false
    this.state = 'reacting'
    this.attentionTarget = { actor: event.actor, event_type: event.type, sequence: event.sequence }
    try {
      const height = Math.max(0.8, Number(target.height) || 1.6)
      await bot.lookAt?.(target.position.offset(0, height * 0.82, 0), false)
      let motion = 'look_at_actor'
      const now = Date.now()
      const gestureAllowed = this.intensity >= 35 && now - this.lastGestureAt >= 10_000
        && ['player_join', 'player_chat', 'player_advancement'].includes(event.type)
      if (gestureAllowed && this.random() < this.intensity / 140) {
        bot.swingArm?.('right')
        motion = 'acknowledge_actor'
        this.lastGestureAt = now
      }
      this.lastReaction = { sequence: event.sequence, actor: event.actor, event_type: event.type, motion, time_ms: now }
      this.emit({ type: 'human_attention_reaction', ...this.lastReaction })
      return true
    } catch (_) {
      return false
    } finally {
      this.state = 'idle'
    }
  }

  _schedule() {
    if (this.timer || !this.pending.length) return
    const delay = Math.floor(250 + this.random() * (this.intensity >= 60 ? 650 : 1100))
    this.timer = this.schedule(() => {
      this.timer = null
      void this.reactNext().finally(() => this._schedule())
    }, delay)
    this.timer?.unref?.()
  }
}

function sanitizeSocialEvent(value) {
  if (!value || typeof value !== 'object') return null
  const sequence = Number(value.sequence)
  const actor = String(value.actor || '').trim()
  const type = String(value.type || '').trim().toLowerCase()
  if (!Number.isSafeInteger(sequence) || sequence < 1 || !/^[A-Za-z0-9_]{3,16}$/.test(actor)) return null
  if (!['player_chat', 'player_join', 'player_leave', 'player_death', 'player_advancement',
    'player_interact', 'player_hurt'].includes(type)) return null
  return { sequence, actor, type, time_ms: Number(value.time_ms) || 0 }
}

function findPlayer(bot, name) {
  if (!bot?.players) return null
  const wanted = String(name || '').toLowerCase()
  for (const [key, player] of Object.entries(bot.players)) {
    if (key.toLowerCase() === wanted) return player?.entity || null
  }
  return null
}

function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}

module.exports = { HumanBehaviorController, sanitizeSocialEvent }
