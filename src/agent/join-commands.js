'use strict'

const MAX_JOIN_COMMANDS = 5
const MAX_COMMAND_LENGTH = 256

function parseJoinCommands(value) {
  return String(value || '').split(/\r?\n/)
    .map(command => command.trim())
    .filter(command => command.startsWith('/') && command.length <= MAX_COMMAND_LENGTH)
    .slice(0, MAX_JOIN_COMMANDS)
}

function initialJoinCommandState(configuredCount) {
  return {
    configured_count: configuredCount,
    phase: configuredCount > 0 ? 'pending' : 'not_required',
    sent_count: 0,
    current_index: null,
    last_run_at_ms: null,
    completed_at_ms: null,
    last_error_code: null
  }
}

async function executeJoinCommands(options) {
  const commands = Array.isArray(options.commands) ? options.commands.slice(0, MAX_JOIN_COMMANDS) : []
  const wait = options.wait || (milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)))
  const isActive = options.isActive || (() => true)
  const send = options.send
  const onState = options.onState || (() => {})
  const initialDelayMs = normalizeDelay(options.commandDelayMs)
  const intervalDelayMs = initialDelayMs
  const settleDelayMs = normalizeDelay(options.settleDelayMs)
  let state = initialJoinCommandState(commands.length)
  const update = patch => {
    state = { ...state, ...patch }
    onState({ ...state })
  }
  const abortIfInactive = () => {
    if (isActive()) return false
    update({ phase: 'aborted', current_index: null, last_error_code: 'session_ended' })
    return true
  }

  onState({ ...state })
  if (!commands.length) return { ok: true, aborted: false, failed_index: null, state }

  update({ phase: 'waiting', last_run_at_ms: Date.now() })
  if (initialDelayMs > 0) await wait(initialDelayMs)
  if (abortIfInactive()) return { ok: false, aborted: true, failed_index: null, state }

  for (let index = 0; index < commands.length; index++) {
    update({ phase: 'sending', current_index: index })
    try {
      send(commands[index])
    } catch (_) {
      update({ phase: 'failed', current_index: index, last_error_code: 'send_failed' })
      return { ok: false, aborted: false, failed_index: index, state }
    }
    update({ sent_count: index + 1 })
    if (index + 1 < commands.length && intervalDelayMs > 0) await wait(intervalDelayMs)
    if (abortIfInactive()) return { ok: false, aborted: true, failed_index: null, state }
  }

  update({ phase: 'settling', current_index: null })
  if (settleDelayMs > 0) await wait(settleDelayMs)
  if (abortIfInactive()) return { ok: false, aborted: true, failed_index: null, state }
  update({ phase: 'complete', completed_at_ms: Date.now() })
  return { ok: true, aborted: false, failed_index: null, state }
}

function normalizeDelay(value) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(0, Math.min(10_000, parsed)) : 0
}

module.exports = { executeJoinCommands, initialJoinCommandState, parseJoinCommands }
