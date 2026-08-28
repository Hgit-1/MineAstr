'use strict'

const { Vec3 } = require('vec3')

async function executeWorkAction(bot, type, args = {}, options = {}) {
  if (!bot?.entity) throw new Error('Bot 尚未进入服务器')
  if (type === 'farm_tend') return tendFarm(bot, args, options)
  if (type === 'collect_items') return collectItems(bot, args, options)
  if (type === 'interact_entity') return interactEntity(bot, args, options)
  throw new Error(`不支持的工作流动作：${type}`)
}

async function tendFarm(bot, args, options) {
  requireAuthority(options)
  const center = coordinate(args)
  const radius = boundedInteger(args.radius, 6, 1, 8)
  const maximum = boundedInteger(args.max_count, 32, 1, 64)
  options.assertAllowed?.(center, args.dimension)
  if (bot.entity.position.distanceTo(center) > 5) {
    await options.navigate(center, { ...args, tolerance: 4 })
  }
  options.assertActive?.()
  const dimension = String(args.dimension || bot.game?.dimension || 'minecraft:overworld')
  const scan = await options.serverAuthority('farm_scan', {
    x: Math.floor(center.x), y: Math.floor(center.y), z: Math.floor(center.z),
    dimension, radius, max_count: maximum
  })
  const crops = Array.isArray(scan?.crops) ? scan.crops.slice(0, maximum) : []
  const harvested = []
  const failures = []
  for (const crop of crops) {
    options.assertActive?.()
    const target = coordinate(crop)
    options.assertAllowed?.(target, dimension)
    try {
      if (bot.entity.position.distanceTo(target) > 5) {
        await options.navigate(target, { x: target.x, y: target.y, z: target.z, dimension, tolerance: 4 })
      }
      options.assertActive?.()
      bot.swingArm?.('right')
      const result = await options.serverAuthority('farm_harvest', {
        x: Math.floor(target.x), y: Math.floor(target.y), z: Math.floor(target.z), dimension
      })
      harvested.push(result)
      options.emit?.({
        type: 'crop_harvested', block_id: result.block_id || crop.block_id || null,
        position: vector(target), replanted: Boolean(result.replanted), authority: 'minecraft_server'
      })
    } catch (error) {
      failures.push({ position: vector(target), error: safeError(error) })
    }
  }
  if (crops.length > 0 && harvested.length === 0) {
    throw new Error(`检测到 ${crops.length} 株成熟作物，但全部收割失败：${failures[0]?.error || 'unknown'}`)
  }
  if (harvested.length) await delay(250)
  const pickup = harvested.length ? await collectItems(bot, {
    radius: Math.min(12, radius + 4), max_count: maximum * 2,
    timeout_seconds: boundedInteger(args.pickup_timeout_seconds, 20, 2, 120), dimension
  }, options) : { operation: 'collect_items', collected_entities: 0, entity_ids: [] }
  return {
    operation: 'farm_tend', authority: 'minecraft_server', center: vector(center), radius,
    mature_detected: crops.length, harvested_count: harvested.length, replanted_count: harvested.length,
    failed_count: failures.length, failures, pickup
  }
}

async function collectItems(bot, args, options) {
  const radius = boundedInteger(args.radius, 12, 1, 16)
  const maximum = boundedInteger(args.max_count, 32, 1, 128)
  const deadline = Date.now() + boundedInteger(args.timeout_seconds, 20, 2, 120) * 1000
  const attempted = new Set()
  const collected = []
  while (Date.now() < deadline && collected.length < maximum) {
    options.assertActive?.()
    const candidate = Object.values(bot.entities || {})
      .filter(entity => isDroppedItem(entity) && !attempted.has(entity.id)
        && entity.position.distanceTo(bot.entity.position) <= radius
        && !options.isForbidden?.(entity.position))
      .sort((left, right) => left.position.distanceTo(bot.entity.position)
        - right.position.distanceTo(bot.entity.position))[0]
    if (!candidate) break
    attempted.add(candidate.id)
    options.assertAllowed?.(candidate.position, args.dimension)
    await options.navigate(candidate.position, { ...args, tolerance: 1 })
    options.assertActive?.()
    const pickupDeadline = Math.min(deadline, Date.now() + 2500)
    while (Date.now() < pickupDeadline && bot.entities?.[candidate.id]) await delay(100)
    if (!bot.entities?.[candidate.id]) {
      collected.push(candidate.id)
      options.emit?.({ type: 'item_pickup_completed', entity_id: candidate.id, workflow: 'collect_items' })
    }
  }
  return {
    operation: 'collect_items', collected_entities: collected.length,
    attempted_entities: attempted.size, entity_ids: collected
  }
}

async function interactEntity(bot, args, options) {
  const entityId = Number(args.entity_id)
  const entityName = String(args.entity_name || '').trim().toLowerCase()
  const candidates = Object.values(bot.entities || {}).filter(entity => entity && entity !== bot.entity && entity.position)
  const entity = candidates.find(candidate => Number.isInteger(entityId) && candidate.id === entityId)
    || candidates.find(candidate => entityName && normalizedEntityName(candidate) === entityName)
  if (!entity) throw new Error('未找到要交互的实体')
  const maximumDistance = boundedInteger(args.distance, 16, 2, 32)
  if (entity.position.distanceTo(bot.entity.position) > maximumDistance) throw new Error('目标实体超出允许交互范围')
  options.assertAllowed?.(entity.position, args.dimension)
  if (entity.position.distanceTo(bot.entity.position) > 3.5) {
    await options.navigate(entity.position, { ...args, tolerance: 2 })
  }
  options.assertActive?.()
  const requested = String(args.item_name || args.item_id || '').trim().toLowerCase()
  let authority = 'mineflayer'
  if (requested) {
    if (options.inventoryDegraded) {
      requireAuthority(options)
      await options.serverAuthority('inventory_select', { item_id: requested })
      authority = 'minecraft_server'
    } else {
      const shortName = requested.includes(':') ? requested.slice(requested.indexOf(':') + 1) : requested
      const item = (bot.inventory?.items?.() || []).find(candidate =>
        String(candidate.name || '').toLowerCase() === requested
        || String(candidate.name || '').toLowerCase() === shortName)
      if (!item) throw new Error(`背包中没有交互物品：${requested}`)
      await bot.equip(item, 'hand')
    }
  }
  bot.swingArm?.('right')
  if (typeof bot.activateEntity !== 'function') throw new Error('当前 Mineflayer 不支持实体交互')
  await bot.activateEntity(entity)
  options.emit?.({
    type: 'entity_interacted', entity_id: entity.id, entity_name: normalizedEntityName(entity),
    item_id: requested || null, inventory_authority: authority
  })
  return {
    operation: 'interact_entity', entity_id: entity.id, entity_name: normalizedEntityName(entity),
    item_id: requested || null, inventory_authority: authority
  }
}

function isDroppedItem(entity) {
  const type = String(entity?.type || '').toLowerCase()
  const name = String(entity?.name || '').toLowerCase()
  return Boolean(entity?.position) && (type === 'item' || name === 'item'
    || (type === 'object' && /item/i.test(String(entity?.displayName || entity?.objectType || ''))))
}

function requireAuthority(options) {
  if (typeof options.serverAuthority !== 'function') throw new Error('服务端权威工作流通道不可用')
}

function coordinate(args) {
  const values = ['x', 'y', 'z'].map(name => Number(args?.[name]))
  if (!values.every(Number.isFinite) || values.some(value => Math.abs(value) > 30_000_000)) {
    throw new Error('工作流坐标无效')
  }
  return new Vec3(Math.floor(values[0]), Math.floor(values[1]), Math.floor(values[2]))
}

function normalizedEntityName(entity) {
  return String(entity?.username || entity?.displayName || entity?.name || entity?.type || '').toLowerCase()
}

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(minimum, Math.min(maximum, parsed)) : fallback
}

function vector(value) { return { x: round(value.x), y: round(value.y), z: round(value.z) } }
function round(value) { return Math.round(Number(value) * 100) / 100 }
function safeError(error) { return String(error?.message || error || 'unknown').replace(/[\r\n\t]+/g, ' ').slice(0, 200) }
function delay(milliseconds) { return new Promise(resolve => setTimeout(resolve, milliseconds)) }

module.exports = { collectItems, executeWorkAction, interactEntity, isDroppedItem, tendFarm }
