'use strict'

const ALWAYS_HOSTILE = new Set([
  'blaze', 'bogged', 'breeze', 'cave_spider', 'drowned', 'elder_guardian', 'endermite',
  'evoker', 'guardian', 'hoglin', 'husk', 'magma_cube', 'phantom', 'piglin_brute',
  'pillager', 'ravager', 'shulker', 'silverfish', 'skeleton', 'slime', 'stray', 'vex',
  'vindicator', 'witch', 'wither_skeleton', 'zoglin', 'zombie', 'zombie_villager'
])

// These entities are hostile or can become hostile, but initiating melee against
// them is more dangerous than disengaging. Neutral mobs are also kept here so the
// Agent never starts a fight merely because minecraft-data labels the category hostile.
const NEVER_AUTO_ENGAGE = new Set([
  'bee', 'creeper', 'ender_dragon', 'enderman', 'ghast', 'goat', 'iron_golem',
  'piglin', 'polar_bear', 'spider', 'trader_llama', 'warden', 'wither',
  'wolf', 'zombified_piglin'
])

const RETREAT_ON_SIGHT = new Set(['creeper', 'ender_dragon', 'warden', 'wither'])

function normalizedEntityName(entity) {
  return String(entity?.name || entity?.mobType || '').toLowerCase().replace(/^minecraft:/, '')
}

function registryEntity(bot, entity) {
  const id = Number(entity?.entityType)
  return Number.isInteger(id) ? bot?.registry?.entities?.[id] : null
}

function isAttackableHostile(bot, entity) {
  if (!entity?.position || entity === bot?.entity || entity.type === 'player' || entity.username) return false
  const name = normalizedEntityName(entity)
  if (!name || NEVER_AUTO_ENGAGE.has(name)) return false
  if (ALWAYS_HOSTILE.has(name)) return true
  const registered = registryEntity(bot, entity)
  return String(registered?.category || '').toLowerCase() === 'hostile mobs'
}

function isRetreatThreat(entity) {
  return RETREAT_ON_SIGHT.has(normalizedEntityName(entity))
}

function distanceToBot(bot, entity) {
  try { return Number(entity.position.distanceTo(bot.entity.position)) } catch (_) { return Infinity }
}

function selectCombatTarget(bot, options = {}) {
  const radius = finiteNumber(options.radius, 6)
  const forbidden = typeof options.isForbidden === 'function' ? options.isForbidden : () => false
  return Object.values(bot?.entities || {})
    .filter(entity => isAttackableHostile(bot, entity) && !forbidden(entity.position))
    .map(entity => ({ entity, distance: distanceToBot(bot, entity) }))
    .filter(candidate => candidate.distance <= radius)
    .sort((left, right) => left.distance - right.distance)[0] || null
}

function selectRetreatThreat(bot, options = {}) {
  const radius = finiteNumber(options.radius, 8)
  return Object.values(bot?.entities || {})
    .filter(entity => entity?.position && isRetreatThreat(entity))
    .map(entity => ({ entity, distance: distanceToBot(bot, entity) }))
    .filter(candidate => candidate.distance <= radius)
    .sort((left, right) => left.distance - right.distance)[0] || null
}

function weaponScore(item) {
  const name = String(item?.name || '').toLowerCase().replace(/^minecraft:/, '')
  if (!name) return 0
  const material = name.includes('netherite') ? 50 : name.includes('diamond') ? 40
    : name.includes('iron') ? 30 : name.includes('stone') ? 20
      : name.includes('golden') ? 12 : name.includes('wooden') ? 10 : 5
  if (/(?:^|_)(?:sword|katana|saber|rapier)$/.test(name)) return 100 + material
  if (/(?:^|_)(?:mace|trident)$/.test(name)) return 90 + material
  if (/(?:^|_)(?:axe|battleaxe|warhammer)$/.test(name)) return 75 + material
  return 0
}

function selectBestWeapon(bot) {
  return (bot?.inventory?.items?.() || [])
    .map(item => ({ item, score: weaponScore(item) }))
    .filter(candidate => candidate.score > 0)
    .sort((left, right) => right.score - left.score)[0]?.item || null
}

function createCombatController(bot, options = {}) {
  const enabled = options.enabled !== false
  const radius = finiteNumber(options.radius, 6)
  const attackRange = Math.min(radius, finiteNumber(options.attackRange, 3.1))
  const minimumHealth = finiteNumber(options.minimumHealth, 10)
  const attackCooldownMilliseconds = finiteNumber(options.attackCooldownMilliseconds, 650)
  const tickMilliseconds = finiteNumber(options.tickMilliseconds, 200)
  const emit = typeof options.emit === 'function' ? options.emit : () => {}
  const shouldPause = typeof options.shouldPause === 'function' ? options.shouldPause : () => false
  const onDanger = typeof options.onDanger === 'function' ? options.onDanger : () => {}
  let timer = null
  let busy = false
  let currentTarget = null
  let lastAttackAt = 0
  let attacks = 0
  let dangerEvents = 0
  let lastDanger = null
  let lastError = null

  function disengage(reason) {
    if (currentTarget) emit({ type: 'combat_ended', target: currentTarget, reason })
    currentTarget = null
  }

  function requestRetreat(candidate, reason, now) {
    dangerEvents += 1
    lastDanger = {
      id: candidate.entity.id,
      name: normalizedEntityName(candidate.entity),
      reason,
      distance: round(candidate.distance),
      time_ms: now
    }
    onDanger(candidate.entity, reason)
  }

  async function tick(now = Date.now()) {
    if (!enabled || busy || !bot?.entity?.position || shouldPause()) return false
    const retreat = selectRetreatThreat(bot, { radius })
    if (retreat) {
      disengage('dangerous_target')
      requestRetreat(retreat, 'dangerous_target', now)
      return false
    }
    const candidate = selectCombatTarget(bot, { radius, isForbidden: options.isForbidden })
    if (!candidate) {
      disengage('target_gone')
      return false
    }
    if (Number(bot.health) <= minimumHealth) {
      disengage('low_health')
      requestRetreat(candidate, 'low_health', now)
      return false
    }
    const target = candidate.entity
    const targetName = normalizedEntityName(target)
    if (!currentTarget || currentTarget.id !== target.id) {
      currentTarget = { id: target.id, name: targetName }
      emit({ type: 'combat_started', target: currentTarget, distance: round(candidate.distance) })
    }
    if (candidate.distance > attackRange || now - lastAttackAt < attackCooldownMilliseconds) return false

    busy = true
    try {
      const weapon = selectBestWeapon(bot)
      if (weapon && bot.heldItem?.type !== weapon.type) {
        try { await bot.equip(weapon, 'hand') } catch (_) {}
      }
      const liveTarget = bot.entities?.[target.id]
      if (!liveTarget?.position || distanceToBot(bot, liveTarget) > attackRange) return false
      const aimHeight = Math.max(0.5, Math.min(2, Number(liveTarget.height) || 1.6)) * 0.6
      await bot.lookAt(liveTarget.position.offset(0, aimHeight, 0), true)
      bot.attack(liveTarget, true)
      lastAttackAt = now
      attacks += 1
      emit({ type: 'combat_attack', target: currentTarget, weapon: weapon?.name || null, attack_count: attacks })
      return true
    } catch (error) {
      lastError = String(error?.message || error || 'unknown error').replace(/[\r\n\t]+/g, ' ').slice(0, 200)
      emit({ type: 'combat_error', target: currentTarget, error: lastError })
      return false
    } finally {
      busy = false
    }
  }

  return {
    start() {
      if (!enabled || timer) return
      timer = setInterval(() => void tick(), tickMilliseconds)
      timer.unref?.()
    },
    stop(reason = 'stopped') {
      if (timer) clearInterval(timer)
      timer = null
      disengage(reason)
    },
    tick,
    status() {
      return {
        enabled,
        state: currentTarget ? 'engaged' : 'idle',
        target: currentTarget,
        radius,
        attack_range: attackRange,
        minimum_health: minimumHealth,
        attack_cooldown_ms: attackCooldownMilliseconds,
        attacks,
        danger_events: dangerEvents,
        last_danger: lastDanger,
        last_attack_at_ms: lastAttackAt || null,
        last_error: lastError
      }
    }
  }
}

function finiteNumber(value, fallback) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

function round(value) {
  return Math.round(Number(value) * 100) / 100
}

module.exports = {
  ALWAYS_HOSTILE,
  NEVER_AUTO_ENGAGE,
  createCombatController,
  isAttackableHostile,
  isRetreatThreat,
  selectBestWeapon,
  selectCombatTarget,
  selectRetreatThreat,
  weaponScore
}
