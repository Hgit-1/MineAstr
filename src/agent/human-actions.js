'use strict'

const { Vec3 } = require('vec3')

const ARMOR_MATERIAL = { leather: 1, golden: 2, chainmail: 3, iron: 4, diamond: 5, netherite: 6 }
const ARMOR_SLOT = { helmet: 'head', chestplate: 'torso', leggings: 'legs', boots: 'feet' }
const DIRECTIONS = [new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)]

async function executeHumanAction(bot, type, args = {}, options = {}) {
  if (!bot?.entity) throw new Error('Bot 尚未进入服务器')
  if (type === 'craft' && options.inventoryDegraded) {
    throw new Error('当前 NeoForge 会话无法可靠解码动态配方与合成槽；已拒绝自动合成')
  }
  if (['equip_best', 'place_block'].includes(type) && options.inventoryDegraded
      && typeof options.serverAuthority !== 'function') {
    throw new Error('当前 NeoForge 会话无法可靠解码动态物品，且服务端权威背包通道不可用')
  }
  if (type === 'equip_best') {
    if (options.inventoryDegraded) {
      const result = await options.serverAuthority('inventory_equip_best', {})
      options.emit?.({ type: 'equipment_updated', equipped: result.equipped || [], authority: 'minecraft_server' })
      return result
    }
    return equipBest(bot, options)
  }
  if (type === 'inspect_entity') return inspectEntity(bot, args)
  if (type === 'sleep') return sleepInBed(bot, args, options)
  if (type === 'pickup_item') return pickupItem(bot, args, options)
  if (type === 'craft') return craftItem(bot, args, options)
  if (type === 'place_block') return placeBlock(bot, args, options)
  if (type === 'dig_block') return digBlock(bot, args, options)
  throw new Error(`不支持的拟人动作：${type}`)
}

async function equipBest(bot, options) {
  const equipped = []
  const items = bot.inventory?.items?.() || []
  for (const [suffix, destination] of Object.entries(ARMOR_SLOT)) {
    const candidate = items.filter(item => String(item.name || '').endsWith(`_${suffix}`))
      .sort((left, right) => armorScore(right.name) - armorScore(left.name))[0]
    if (!candidate) continue
    options.assertActive?.()
    await bot.equip(candidate, destination)
    equipped.push({ destination, item_id: String(candidate.name), count: Number(candidate.count) || 1 })
  }
  const weapon = items.filter(item => /(?:^|_)(?:sword|axe)$/.test(String(item.name || '')))
    .sort((left, right) => weaponScore(right.name) - weaponScore(left.name))[0]
  if (weapon) {
    options.assertActive?.()
    await bot.equip(weapon, 'hand')
    equipped.push({ destination: 'hand', item_id: String(weapon.name), count: Number(weapon.count) || 1 })
  }
  options.emit?.({ type: 'equipment_updated', equipped })
  return { operation: 'equip_best', equipped }
}

function inspectEntity(bot, args) {
  const maximum = boundedInteger(args.distance, 16, 1, 32)
  const entityId = Number(args.entity_id)
  const wanted = String(args.entity_name || '').toLowerCase()
  const candidates = Object.values(bot.entities || {}).filter(entity => entity && entity !== bot.entity && entity.position)
  const entity = candidates.find(item => Number.isFinite(entityId) && item.id === entityId)
    || candidates.find(item => wanted && entityName(item).toLowerCase() === wanted)
  if (!entity || entity.position.distanceTo(bot.entity.position) > maximum) throw new Error('目标实体不在可观察范围内')
  return {
    operation: 'inspect_entity', entity: {
      id: entity.id, name: entityName(entity), type: String(entity.type || ''),
      distance: round(entity.position.distanceTo(bot.entity.position)), position: vector(entity.position),
      width: finite(entity.width), height: finite(entity.height), on_ground: Boolean(entity.onGround)
    }
  }
}

async function sleepInBed(bot, args, options) {
  const radius = boundedInteger(args.distance, 16, 2, 32)
  const ids = Object.values(bot.registry?.blocksByName || {})
    .filter(block => String(block.name || '').endsWith('_bed')).map(block => block.id)
  if (!ids.length || typeof bot.findBlock !== 'function') throw new Error('当前协议中没有可识别的床')
  const bed = bot.findBlock({ matching: ids, maxDistance: radius })
  if (!bed) throw new Error('附近没有可用床')
  options.assertAllowed?.(bed.position, args.dimension)
  if (bot.entity.position.distanceTo(bed.position) > 4) await options.navigate(bed.position, { ...args, tolerance: 3 })
  options.assertActive?.()
  if (typeof bot.sleep !== 'function') throw new Error('当前 Mineflayer 不支持睡觉')
  await bot.sleep(bed)
  options.emit?.({ type: 'agent_sleeping', position: vector(bed.position) })
  return { operation: 'sleep', position: vector(bed.position), sleeping: Boolean(bot.isSleeping) }
}

async function pickupItem(bot, args, options) {
  const id = Number(args.entity_id)
  if (!Number.isInteger(id)) throw new Error('拾取任务需要明确的掉落物实体 ID')
  const entity = bot.entities?.[id]
  if (!entity?.position || !['object', 'item'].includes(String(entity.type || '').toLowerCase())
      && !/item/i.test(String(entity.name || ''))) throw new Error('指定实体不是可确认的掉落物')
  options.assertAllowed?.(entity.position, args.dimension)
  await options.navigate(entity.position, { ...args, tolerance: 1 })
  options.assertActive?.()
  const deadline = Date.now() + boundedInteger(args.timeout_seconds, 8, 2, 30) * 1000
  while (Date.now() < deadline && bot.entities?.[id]) await delay(100)
  if (bot.entities?.[id]) throw new Error('到达掉落物位置后仍未确认拾取成功')
  options.emit?.({ type: 'item_pickup_completed', entity_id: id })
  return { operation: 'pickup_item', entity_id: id, picked_up: true }
}

async function craftItem(bot, args, options) {
  const requested = normalizeName(args.item_id || args.item_name)
  const item = bot.registry?.itemsByName?.[requested] || bot.registry?.itemsByName?.[requested.split(':').pop()]
  if (!item) throw new Error(`无法识别合成目标：${requested}`)
  const count = boundedInteger(args.count, 1, 1, 64)
  let table = null
  const tableId = bot.registry?.blocksByName?.crafting_table?.id
  if (Number.isInteger(tableId) && typeof bot.findBlock === 'function') {
    table = bot.findBlock({ matching: tableId, maxDistance: boundedInteger(args.distance, 16, 2, 32) })
  }
  if (table && bot.entity.position.distanceTo(table.position) > 4) {
    options.assertAllowed?.(table.position, args.dimension)
    await options.navigate(table.position, { ...args, tolerance: 3 })
  }
  options.assertActive?.()
  const recipes = bot.recipesFor?.(item.id, null, 1, table) || []
  if (!recipes.length) throw new Error(`当前背包和工作台无法合成：${requested}`)
  await bot.craft(recipes[0], count, table)
  options.emit?.({ type: 'craft_completed', item_id: requested, count })
  return { operation: 'craft', item_id: requested, count, crafting_table: table ? vector(table.position) : null }
}

async function placeBlock(bot, args, options) {
  const target = coordinate(args)
  options.assertAllowed?.(target, args.dimension)
  if (options.awarenessAt?.(target)?.protected || Number(options.awarenessAt?.(target)?.structure_confidence) >= 80) {
    throw new Error('目标位置属于受保护或高置信人工结构')
  }
  if (bot.entity.position.distanceTo(target) > 5) await options.navigate(target, { ...args, tolerance: 4 })
  options.assertActive?.()
  const current = bot.blockAt(target)
  if (current && !['air', 'cave_air', 'void_air', 'water', 'tall_grass', 'short_grass'].includes(String(current.name || ''))) {
    throw new Error('目标位置不可安全替换')
  }
  const requested = normalizeName(args.item_id || args.item_name)
  if (!requested) throw new Error('放置方块需要背包物品 ID')
  let item = null
  if (options.inventoryDegraded) {
    await options.serverAuthority('inventory_select', { item_id: requested })
  } else {
    item = (bot.inventory?.items?.() || []).find(entry => normalizeName(entry.name) === requested
      || normalizeName(entry.name).split(':').pop() === requested.split(':').pop())
    if (!item) throw new Error(`背包中没有可放置物品：${requested}`)
  }
  let reference = null
  let face = null
  for (const direction of DIRECTIONS) {
    const candidate = bot.blockAt(target.minus(direction))
    if (candidate && candidate.boundingBox === 'block' && !options.awarenessAt?.(candidate.position)?.protected) {
      reference = candidate
      face = direction
      break
    }
  }
  if (!reference) throw new Error('目标位置周围没有安全支撑面')
  if (item) await bot.equip(item, 'hand')
  await bot.placeBlock(reference, face)
  const after = bot.blockAt(target)
  if (!after || ['air', 'cave_air', 'void_air'].includes(String(after.name || ''))) throw new Error('放置后未观察到方块')
  options.emit?.({ type: 'block_place_completed', position: vector(target), item_id: requested })
  return { operation: 'place_block', position: vector(target), block: String(after.name || ''), item_id: requested }
}

async function digBlock(bot, args, options) {
  const target = coordinate(args)
  options.assertAllowed?.(target, args.dimension)
  if (bot.entity.position.distanceTo(target) > 5) await options.navigate(target, { ...args, tolerance: 4 })
  options.assertActive?.()
  const block = bot.blockAt(target)
  if (!block || ['air', 'cave_air', 'void_air'].includes(String(block.name || ''))) throw new Error('目标位置没有可挖掘方块')
  if (options.canBreak && !options.canBreak(block)) throw new Error('服务端策略禁止破坏该方块')
  if (options.awarenessAt?.(target)?.protected || Number(options.awarenessAt?.(target)?.structure_confidence) >= 80) {
    throw new Error('目标方块属于受保护或高置信人工结构')
  }
  if (options.inventoryDegraded && typeof options.serverAuthority === 'function') {
    await options.serverAuthority('inventory_select_tool', {
      x: Math.floor(target.x), y: Math.floor(target.y), z: Math.floor(target.z),
      dimension: String(args.dimension || 'minecraft:overworld')
    })
  }
  if (typeof bot.canDigBlock === 'function' && !bot.canDigBlock(block)) throw new Error('当前距离或工具无法安全挖掘目标')
  await bot.dig(block, true)
  options.emit?.({ type: 'block_dig_completed', position: vector(target), block: String(block.name || '') })
  return { operation: 'dig_block', position: vector(target), block: String(block.name || '') }
}

function armorScore(name) {
  const value = String(name || '')
  for (const [material, score] of Object.entries(ARMOR_MATERIAL)) if (value.startsWith(`${material}_`)) return score
  return 0
}

function weaponScore(name) {
  const value = String(name || '')
  const suffix = value.endsWith('_sword') ? 2 : value.endsWith('_axe') ? 1 : 0
  return armorScore(value.replace(/_(?:sword|axe)$/, '_helmet')) * 10 + suffix
}

function coordinate(args) {
  const values = ['x', 'y', 'z'].map(name => Number(args[name]))
  if (!values.every(Number.isFinite) || values.some(value => Math.abs(value) > 30_000_000)) throw new Error('动作坐标无效')
  return new Vec3(Math.floor(values[0]), Math.floor(values[1]), Math.floor(values[2]))
}

function normalizeName(value) { return String(value || '').trim().toLowerCase().replace(/^minecraft:/, '') }
function entityName(entity) { return String(entity.username || entity.displayName || entity.name || entity.type || '') }
function vector(value) { return { x: round(value.x), y: round(value.y), z: round(value.z) } }
function round(value) { return Math.round(Number(value) * 100) / 100 }
function finite(value) { return Number.isFinite(value) ? Number(value) : null }
function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }

module.exports = { ARMOR_SLOT, armorScore, executeHumanAction, inspectEntity, weaponScore }
