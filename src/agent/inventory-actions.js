'use strict'

const { Vec3 } = require('vec3')

const CONTAINER_WINDOW_PREFIXES = [
  'minecraft:generic', 'minecraft:chest', 'minecraft:container', 'minecraft:dispenser',
  'minecraft:dropper', 'minecraft:hopper', 'minecraft:barrel', 'minecraft:shulker_box',
  'minecraft:ender_chest', 'minecraft:trapped_chest'
]
const FURNACE_WINDOW_PREFIXES = ['minecraft:furnace', 'minecraft:blast_furnace', 'minecraft:smoker']
const FURNACE_BLOCK_NAMES = new Set(['furnace', 'blast_furnace', 'smoker'])
const FUEL_PRIORITY = ['coal_block', 'coal', 'charcoal', 'blaze_rod', 'dried_kelp_block', 'bamboo', 'stick']

async function executeInventoryTask(bot, taskType, args = {}, options = {}) {
  if (!bot?.entity) throw new Error('Bot 尚未进入服务器')
  if (options.inventoryDegraded) {
    return executeAuthoritativeInventoryTask(bot, taskType, args, options)
  }
  const position = coordinate(args)
  options.assertAllowed?.(position, args.dimension)
  if (bot.entity.position.distanceTo(position) > 5) await options.navigate(position, { ...args, tolerance: 4 })
  options.assertActive?.()
  const block = bot.blockAt(position)
  if (!block) throw new Error('目标方块不可见')
  if (taskType === 'container_inspect' || taskType === 'container_transfer') {
    return executeContainer(bot, block, taskType, args, options)
  }
  if (taskType === 'furnace_inspect' || taskType === 'furnace_process') {
    return executeFurnace(bot, block, taskType, args, options)
  }
  throw new Error(`不支持的物品任务：${taskType}`)
}

async function executeAuthoritativeInventoryTask(bot, taskType, args, options) {
  if (typeof options.serverAuthority !== 'function') {
    throw new Error('当前 NeoForge 会话无法可靠解码动态物品，且服务端权威物品通道不可用')
  }
  const position = coordinate(args)
  options.assertAllowed?.(position, args.dimension)
  if (bot.entity.position.distanceTo(position) > 5) await options.navigate(position, { ...args, tolerance: 4 })
  options.assertActive?.()
  if (bot.entity.position.distanceTo(position) > 6) throw new Error('到达后仍距离目标容器超过 6 格')
  const authoritativeArgs = {
    ...args,
    x: Math.floor(position.x), y: Math.floor(position.y), z: Math.floor(position.z),
    dimension: String(args.dimension || bot.game?.dimension || 'minecraft:overworld')
  }
  if (taskType === 'container_inspect' || taskType === 'container_transfer') {
    const result = await options.serverAuthority(taskType, authoritativeArgs)
    options.emit?.({
      type: taskType === 'container_inspect' ? 'container_opened' : 'container_transfer_completed',
      position: vector(position), authority: 'minecraft_server'
    })
    return result
  }
  if (taskType === 'furnace_inspect') {
    const result = await options.serverAuthority('furnace_inspect', authoritativeArgs)
    options.emit?.({ type: 'furnace_opened', position: vector(position), authority: 'minecraft_server' })
    return result
  }
  if (taskType !== 'furnace_process') throw new Error(`不支持的权威物品任务：${taskType}`)

  const before = await options.serverAuthority('furnace_inspect', authoritativeArgs)
  const started = await options.serverAuthority('furnace_process', authoritativeArgs)
  const waitMode = ['none', 'first_output', 'all'].includes(String(args.wait_mode || '').toLowerCase())
    ? String(args.wait_mode).toLowerCase() : 'first_output'
  const initialOutput = authorityItemCount(before.output)
  const expected = waitMode === 'all' ? boundedInteger(args.input_count, 1, 1, 64) : 1
  let latest = started
  if (waitMode !== 'none') {
    const deadline = Date.now() + boundedInteger(args.timeout_seconds, 180, 5, 900) * 1000
    while (Date.now() < deadline) {
      options.assertActive?.()
      latest = await options.serverAuthority('furnace_inspect', authoritativeArgs)
      if (authorityItemCount(latest.output) >= initialOutput + expected) break
      await delay(1000)
    }
    if (authorityItemCount(latest.output) < initialOutput + expected) throw new Error('等待熔炉产物超时')
  }
  let collected = null
  if (args.take_output !== false && authorityItemCount(latest.output) > 0) {
    collected = await options.serverAuthority('furnace_collect', authoritativeArgs)
    latest = await options.serverAuthority('furnace_inspect', authoritativeArgs)
  }
  options.emit?.({
    type: 'furnace_process_completed', position: vector(position), authority: 'minecraft_server',
    input_item: String(args.input_item || ''), input_count: boundedInteger(args.input_count, 1, 1, 64),
    output: collected?.taken_output || null
  })
  return {
    operation: 'process', authority: 'minecraft_server', position: vector(position),
    input_item: String(args.input_item || ''), input_count: boundedInteger(args.input_count, 1, 1, 64),
    wait_mode: waitMode, taken_output: collected?.taken_output || null,
    before, started, after: latest
  }
}

async function executeContainer(bot, block, taskType, args, options) {
  const window = await openWindow(bot, block)
  try {
    assertContainerWindow(window)
    const before = containerSnapshot(window)
    options.emit?.({ type: 'container_opened', position: vector(block.position),
      block: block.name || null, window_type: String(window.type || '') })
    if (taskType === 'container_inspect') return { operation: 'inspect', position: vector(block.position), ...before }

    const direction = String(args.direction || '').toLowerCase()
    if (!['to_container', 'from_container'].includes(direction)) {
      throw new Error('容器搬运方向必须是 to_container 或 from_container')
    }
    const itemName = normalizeItemName(args.item_id || args.item_name)
    if (!itemName) throw new Error('容器搬运需要物品 ID')
    const sourceItems = direction === 'to_container' ? playerItems(window) : containerItems(window)
    const item = sourceItems.find(candidate => itemMatches(candidate, itemName))
    if (!item) throw new Error(`来源中没有物品：${itemName}`)
    const requested = boundedInteger(args.count, item.count, 1, 2304)
    const count = Math.min(requested, item.count)
    const transfer = {
      window,
      itemType: item.type,
      metadata: Number.isFinite(item.metadata) ? item.metadata : null,
      count,
      sourceStart: direction === 'to_container' ? window.inventoryStart : 0,
      sourceEnd: direction === 'to_container' ? window.inventoryEnd : window.inventoryStart,
      destStart: direction === 'to_container' ? 0 : window.inventoryStart,
      destEnd: direction === 'to_container' ? window.inventoryStart : window.inventoryEnd
    }
    await bot.transfer(transfer)
    options.assertActive?.()
    const after = containerSnapshot(window)
    const result = {
      operation: 'transfer', direction, requested_item: itemName, requested_count: requested,
      transferred_count: count, position: vector(block.position), before, after
    }
    options.emit?.({ type: 'container_transfer_completed', position: vector(block.position),
      direction, item_id: itemName, count })
    return result
  } finally {
    safeClose(window)
  }
}

async function executeFurnace(bot, block, taskType, args, options) {
  const furnace = await openFurnaceWindow(bot, block)
  try {
    assertFurnaceWindow(furnace)
    const before = furnaceSnapshot(furnace)
    options.emit?.({ type: 'furnace_opened', position: vector(block.position),
      block: block.name || null, window_type: String(furnace.type || '') })
    if (taskType === 'furnace_inspect') return { operation: 'inspect', position: vector(block.position), ...before }

    const inputName = normalizeItemName(args.input_item)
    if (!inputName) throw new Error('熔炉加工需要 input_item')
    const input = playerItems(furnace).find(item => itemMatches(item, inputName))
    if (!input) throw new Error(`背包中没有熔炼原料：${inputName}`)
    const inputCount = Math.min(input.count, boundedInteger(args.input_count, 1, 1, 64))
    await putFurnaceSlot(bot, furnace, 0, input, inputCount)
    options.assertActive?.()

    const fuelName = normalizeItemName(args.fuel_item)
    const fuel = chooseFuel(playerItems(furnace), fuelName)
    if (!fuel) throw new Error(fuelName ? `背包中没有燃料：${fuelName}` : '背包中没有可识别的燃料')
    const fuelCount = Math.min(fuel.count, boundedInteger(args.fuel_count, 1, 1, 64))
    await putFurnaceSlot(bot, furnace, 1, fuel, fuelCount)
    options.assertActive?.()
    options.emit?.({ type: 'furnace_process_started', position: vector(block.position),
      input_item: inputName, input_count: inputCount, fuel_item: fuel.name, fuel_count: fuelCount })

    const waitMode = ['none', 'first_output', 'all'].includes(String(args.wait_mode || '').toLowerCase())
      ? String(args.wait_mode).toLowerCase() : 'first_output'
    const initialOutput = itemCount(furnaceSlot(furnace, 2))
    if (waitMode !== 'none') {
      const timeoutMs = boundedInteger(args.timeout_seconds, 180, 5, 900) * 1000
      const expected = waitMode === 'all' ? inputCount : 1
      await waitForOutput(furnace, initialOutput + expected, timeoutMs, options.assertActive)
    }
    let taken = null
    if (args.take_output !== false && furnaceSlot(furnace, 2)) {
      taken = await takeFurnaceOutput(bot, furnace)
      options.assertActive?.()
    }
    const after = furnaceSnapshot(furnace)
    const result = {
      operation: 'process', position: vector(block.position), input_item: inputName, input_count: inputCount,
      fuel_item: fuel.name, fuel_count: fuelCount, wait_mode: waitMode,
      taken_output: itemSummary(taken), before, after
    }
    options.emit?.({ type: 'furnace_process_completed', position: vector(block.position),
      input_item: inputName, input_count: inputCount, output: itemSummary(taken) })
    return result
  } finally {
    safeClose(furnace)
  }
}

async function openWindow(bot, block) {
  if (typeof bot.openBlock !== 'function') throw new Error('当前 Mineflayer 不支持打开方块窗口')
  return bot.openBlock(block)
}

async function openFurnaceWindow(bot, block) {
  if (FURNACE_BLOCK_NAMES.has(String(block.name || '')) && typeof bot.openFurnace === 'function') {
    return bot.openFurnace(block)
  }
  return openWindow(bot, block)
}

function assertContainerWindow(window) {
  const type = String(window?.type || '')
  if (!window || !Number.isInteger(window.inventoryStart) || window.inventoryStart <= 0
      || !CONTAINER_WINDOW_PREFIXES.some(prefix => type.startsWith(prefix))) {
    throw new Error(`未知或不兼容的容器窗口：${type || 'unknown'}；已保持只读且未点击槽位`)
  }
}

function assertFurnaceWindow(window) {
  const type = String(window?.type || '')
  if (!window || Number(window.inventoryStart) !== 3
      || !FURNACE_WINDOW_PREFIXES.some(prefix => type.startsWith(prefix))) {
    throw new Error(`未知或不兼容的熔炉窗口：${type || 'unknown'}；已保持只读且未点击槽位`)
  }
}

async function putFurnaceSlot(bot, furnace, slot, item, count) {
  if (slot === 0 && typeof furnace.putInput === 'function') return furnace.putInput(item.type, item.metadata ?? null, count)
  if (slot === 1 && typeof furnace.putFuel === 'function') return furnace.putFuel(item.type, item.metadata ?? null, count)
  return bot.transfer({ window: furnace, itemType: item.type, metadata: item.metadata ?? null, count,
    sourceStart: furnace.inventoryStart, sourceEnd: furnace.inventoryEnd, destStart: slot, destEnd: slot + 1 })
}

async function takeFurnaceOutput(bot, furnace) {
  const item = furnaceSlot(furnace, 2)
  if (!item) return null
  if (typeof furnace.takeOutput === 'function') return furnace.takeOutput()
  await bot.transfer({ window: furnace, itemType: item.type, metadata: item.metadata ?? null, count: item.count,
    sourceStart: 2, sourceEnd: 3, destStart: furnace.inventoryStart, destEnd: furnace.inventoryEnd })
  return item
}

async function waitForOutput(furnace, expectedCount, timeoutMs, assertActive = () => {}) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    assertActive?.()
    if (itemCount(furnaceSlot(furnace, 2)) >= expectedCount) return
    await delay(250)
  }
  throw new Error('等待熔炉产物超时')
}

function containerSnapshot(window) {
  return {
    window_type: String(window.type || ''),
    container_slots: Number(window.inventoryStart),
    items: summarize(containerItems(window))
  }
}

function furnaceSnapshot(window) {
  return {
    window_type: String(window.type || ''),
    input: itemSummary(furnaceSlot(window, 0)),
    fuel: itemSummary(furnaceSlot(window, 1)),
    output: itemSummary(furnaceSlot(window, 2)),
    progress: finiteOrNull(window.progress),
    fuel_remaining: finiteOrNull(window.fuel)
  }
}

function containerItems(window) { return (window.slots || []).slice(0, window.inventoryStart).filter(Boolean) }
function playerItems(window) { return (window.slots || []).slice(window.inventoryStart, window.inventoryEnd).filter(Boolean) }
function furnaceSlot(window, slot) {
  if (slot === 0 && typeof window.inputItem === 'function') return window.inputItem()
  if (slot === 1 && typeof window.fuelItem === 'function') return window.fuelItem()
  if (slot === 2 && typeof window.outputItem === 'function') return window.outputItem()
  return window.slots?.[slot] || null
}

function chooseFuel(items, requested) {
  if (requested) return items.find(item => itemMatches(item, requested)) || null
  for (const name of FUEL_PRIORITY) {
    const match = items.find(item => itemMatches(item, name))
    if (match) return match
  }
  return items.find(item => /(?:planks|log|wood|stem)$/.test(String(item.name || ''))) || null
}

function summarize(items) {
  const grouped = new Map()
  for (const item of items) {
    const key = String(item.name || `type:${item.type}`)
    const entry = grouped.get(key) || { item_id: key, display_name: String(item.displayName || ''), count: 0 }
    entry.count += itemCount(item)
    grouped.set(key, entry)
  }
  return [...grouped.values()].slice(0, 128)
}

function itemSummary(item) {
  if (!item) return null
  return { item_id: String(item.name || `type:${item.type}`), display_name: String(item.displayName || ''), count: itemCount(item) }
}
function itemCount(item) { return Math.max(0, Number(item?.count) || 0) }
function authorityItemCount(item) { return Math.max(0, Number(item?.count) || 0) }
function itemMatches(item, requested) {
  const actual = normalizeItemName(item?.name)
  return actual === requested || actual.split(':').pop() === requested.split(':').pop()
}
function normalizeItemName(value) { return String(value || '').trim().toLowerCase().replace(/^minecraft:/, '') }
function coordinate(args) {
  const values = ['x', 'y', 'z'].map(name => Number(args[name]))
  if (!values.every(Number.isFinite) || values.some(value => Math.abs(value) > 30_000_000)) throw new Error('容器坐标无效')
  return new Vec3(Math.floor(values[0]), Math.floor(values[1]), Math.floor(values[2]))
}
function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}
function finiteOrNull(value) { return Number.isFinite(value) ? Number(value) : null }
function safeClose(window) { try { window?.close?.() } catch (_) {} }
function vector(value) { return { x: Math.floor(value.x), y: Math.floor(value.y), z: Math.floor(value.z) } }
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }

module.exports = {
  CONTAINER_WINDOW_PREFIXES, FURNACE_WINDOW_PREFIXES, assertContainerWindow, assertFurnaceWindow,
  containerSnapshot, executeInventoryTask, furnaceSnapshot, normalizeItemName
}
