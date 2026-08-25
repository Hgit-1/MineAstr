'use strict'

class AgentEventBuffer {
  constructor(limit = 128) {
    this.limit = Math.max(16, Math.min(512, Number(limit) || 128))
    this.sequence = 0
    this.events = []
  }

  append(record) {
    const sanitized = sanitize(record)
    const event = { sequence: ++this.sequence, ...sanitized }
    this.events.push(event)
    if (this.events.length > this.limit) this.events.splice(0, this.events.length - this.limit)
    return event
  }

  since(sequence = 0, limit = 64) {
    const cursor = Math.max(0, Number(sequence) || 0)
    const selected = this.events.filter(event => event.sequence > cursor)
      .slice(0, Math.max(1, Math.min(128, Number(limit) || 64)))
    return {
      ok: true,
      first_sequence: this.events[0]?.sequence || this.sequence,
      last_sequence: this.sequence,
      events: selected
    }
  }
}

function sanitize(value, depth = 0) {
  if (depth > 5) return null
  if (value == null || typeof value === 'boolean') return value
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string') return value.replace(/[\r\n\t]+/g, ' ').slice(0, 500)
  if (Array.isArray(value)) return value.slice(0, 32).map(item => sanitize(item, depth + 1))
  if (typeof value !== 'object') return String(value).slice(0, 100)
  const result = {}
  for (const [key, item] of Object.entries(value).slice(0, 64)) {
    if (['data', 'before_data', 'after_data', 'nbt', 'components'].includes(key)) continue
    result[String(key).slice(0, 80)] = sanitize(item, depth + 1)
  }
  return result
}

module.exports = { AgentEventBuffer, sanitize }
