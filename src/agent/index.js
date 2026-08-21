'use strict'

const fs = require('node:fs')
const http = require('node:http')
const net = require('node:net')
const path = require('node:path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const { Vec3 } = require('vec3')

const token = process.env.MINEASTR_AGENT_TOKEN || ''
const dataDir = process.env.MINEASTR_AGENT_DATA_DIR || process.cwd()
const host = process.env.MINEASTR_MC_HOST || '127.0.0.1'
const port = parseInteger(process.env.MINEASTR_MC_PORT, 25565, 1, 65535)
const username = (process.env.MINEASTR_AGENT_USERNAME || 'MineAstrBot').slice(0, 16)
const version = process.env.MINEASTR_MC_VERSION || false
const auth = process.env.MINEASTR_AGENT_AUTH === 'microsoft' ? 'microsoft' : 'offline'
const joinCommands = String(process.env.MINEASTR_AGENT_JOIN_COMMANDS || '').split(/\r?\n/)
  .map(command => command.trim()).filter(command => command.startsWith('/') && command.length <= 256).slice(0, 5)
const neoForgeQuery = decodeBase64(process.env.MINEASTR_NEOFORGE_QUERY_B64)
const neoForgeComponentCount = parseInteger(process.env.MINEASTR_NEOFORGE_COMPONENT_COUNT, 0, 0, 100000)
const useProxyProtocol = process.env.MINEASTR_PROXY_PROTOCOL === 'true'
const forbiddenRegions = parseForbiddenRegions(process.env.MINEASTR_FORBIDDEN_REGIONS || '')
const neoForgeCustomPackets = neoForgeQuery ? {
  '1.21': {
    play: { toClient: { types: {
      packet_declare_recipes: 'restBuffer',
      // Modded item data components use dynamically negotiated registry IDs.
      // Parsing them with vanilla IDs corrupts the stream; keep the Bot online
      // with inventory features degraded until registry-aware codecs are loaded.
      packet_window_items: 'restBuffer',
      packet_set_slot: 'restBuffer'
    } } }
  }
} : undefined
const maxBodyBytes = 128 * 1024

let bot = null
let state = 'starting'
let lastError = ''
let activeTask = null
let taskSequence = 0
const recentTasks = new Map()
let reconnectTimer = null
let stopping = false
let connectionBlocked = false
let connectedAt = 0
let connectionStartedAt = 0
let connectionAttempts = 0
let lastDisconnectError = ''
let eating = false
let retreating = false
let neoForgeNegotiated = false
let taskGeneration = 0
const observedCustomChannels = new Set()
let lastProtocolDiagnostic = null

const waypointFile = path.join(dataDir, 'waypoints.json')
let waypointData = loadWaypointData()

function parseInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}

function safeError(error) {
  let value = error?.message || error || 'unknown error'
  if (typeof value === 'object') {
    try { value = JSON.stringify(value) } catch (_) { value = String(value) }
  }
  return String(value).replace(/[\r\n\t]+/g, ' ').slice(0, 500)
}

function decodeBase64(value) {
  if (!value) return null
  try {
    const decoded = Buffer.from(value, 'base64')
    return decoded.length ? decoded : null
  } catch (_) {
    return null
  }
}

function parseForbiddenRegions(value) {
  return String(value).split(/\r?\n/).map(line => {
    const fields = line.split(',').map(field => field.trim())
    if (fields.length !== 7) return null
    const numbers = fields.slice(1).map(Number)
    if (numbers.some(number => !Number.isFinite(number))) return null
    return {
      dimension: fields[0],
      minX: Math.min(numbers[0], numbers[3]), minY: Math.min(numbers[1], numbers[4]), minZ: Math.min(numbers[2], numbers[5]),
      maxX: Math.max(numbers[0], numbers[3]), maxY: Math.max(numbers[1], numbers[4]), maxZ: Math.max(numbers[2], numbers[5])
    }
  }).filter(Boolean)
}

function isForbidden(position, dimension = bot?.game?.dimension) {
  if (!position) return false
  const normalized = normalizeDimension(dimension)
  return forbiddenRegions.some(region => normalizeDimension(region.dimension) === normalized &&
    position.x >= region.minX && position.x <= region.maxX &&
    position.y >= region.minY && position.y <= region.maxY &&
    position.z >= region.minZ && position.z <= region.maxZ)
}

function normalizeDimension(value) {
  const dimension = String(value || '')
  if (dimension === 'overworld') return 'minecraft:overworld'
  if (dimension === 'the_nether' || dimension === 'nether') return 'minecraft:the_nether'
  if (dimension === 'the_end' || dimension === 'end') return 'minecraft:the_end'
  return dimension
}

function emit(record) {
  process.stdout.write(`${JSON.stringify({ time_ms: Date.now(), ...record })}\n`)
}

function scheduleConnect() {
  if (stopping || connectionBlocked || reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectBot()
  }, 5000)
  reconnectTimer.unref()
}

function connectBot() {
  if (stopping || bot) return
  state = 'connecting'
  connectionStartedAt = Date.now()
  connectionAttempts += 1
  neoForgeNegotiated = false
  try {
    const options = {
      host, port, username, auth, version, hideErrors: true,
      customPackets: neoForgeCustomPackets,
      plugins: { craft: !neoForgeQuery }
    }
    if (useProxyProtocol) options.connect = connectWithProxyProtocol
    const created = mineflayer.createBot(options)
    bot = created
    const connectionTimeout = setTimeout(() => {
      if (bot !== created || state !== 'connecting') return
      lastError = `Minecraft 登录超时（协议状态 ${created._client?.state || 'unknown'}）`
      lastDisconnectError = lastError
      emit({ type: 'bot_connection_timeout', error: lastError })
      try { created.quit('MineAstr login timeout') } catch (_) { created.end?.('MineAstr login timeout') }
    }, 30_000)
    connectionTimeout.unref()
    const recentProtocolFrames = []
    const recentProtocolPackets = []
    const recordFrame = frame => {
      if (!Buffer.isBuffer(frame)) return
      recentProtocolFrames.push({ bytes: frame.length, prefix: frame.subarray(0, 12).toString('hex') })
      while (recentProtocolFrames.length > 8) recentProtocolFrames.shift()
    }
    created._client.decompressor?.prependListener('data', recordFrame)
    created._client.once('set_compression', () => created._client.decompressor?.prependListener('data', recordFrame))
    created._client.once('compress', () => created._client.decompressor?.prependListener('data', recordFrame))
    created.loadPlugin(pathfinder)
    created._client.on('custom_payload', packet => handleNeoForgePayload(created, packet))
    created._client.on('packet', (data, metadata) => {
      recentProtocolPackets.push({
        state: metadata?.state || null,
        name: metadata?.name || null
      })
      while (recentProtocolPackets.length > 20) recentProtocolPackets.shift()
      const channel = data?.channel || data?.identifier || null
      if (metadata?.name?.includes('custom') && channel && !observedCustomChannels.has(channel)) {
        observedCustomChannels.add(channel)
        emit({
          type: 'protocol_custom_payload',
          state: metadata.state,
          name: metadata.name,
          channel,
          bytes: Buffer.isBuffer(data?.data) ? data.data.length : null
        })
      }
    })
    created.once('spawn', () => {
      clearTimeout(connectionTimeout)
      state = 'online'
      connectedAt = Date.now()
      lastError = ''
      lastDisconnectError = ''
      try {
        created.pathfinder.setMovements(new Movements(created))
      } catch (error) {
        lastError = safeError(error)
      }
      emit({ type: 'bot_online', username: created.username, version: created.version })
      void runJoinCommands(created)
    })
    created.on('health', () => void selfCare())
    created.on('move', () => {
      if (isForbidden(created.entity?.position)) {
        created.pathfinder?.stop()
        created.clearControlStates()
        finishTask(activeTask?.run_id, false, '安全停止：Bot 进入了服务端禁区边界')
      }
    })
    created.on('kicked', reason => {
      lastError = safeError(reason)
      lastDisconnectError = lastError
      if (/running NeoForge|install NeoForge|neoforge\.network\.negotiation/i.test(lastError)) {
        connectionBlocked = true
        state = 'incompatible_server'
        emit({
          type: 'bot_incompatible',
          error: neoForgeQuery
            ? `NeoForge 兼容协商失败（已声明 ${neoForgeComponentCount} 个必需频道）；已拒绝继续连接以避免协议错位。`
            : '当前服务器要求 NeoForge 客户端握手；服务端未提供 MineAstr 兼容清单。'
        })
      } else emit({ type: 'bot_kicked', error: lastError })
    })
    created.on('error', error => {
      lastError = safeError(error)
      lastDisconnectError = lastError
      lastProtocolDiagnostic = {
        frames: recentProtocolFrames.map(frame => ({ ...frame })),
        packets: recentProtocolPackets.map(packet => ({ ...packet }))
      }
      emit({ type: 'bot_error', error: lastError, recent_protocol_frames: recentProtocolFrames,
        recent_protocol_packets: recentProtocolPackets })
    })
    created.once('end', reason => {
      clearTimeout(connectionTimeout)
      bot = null
      if (!lastDisconnectError) lastDisconnectError = safeError(reason)
      state = stopping ? 'stopped' : connectionBlocked ? 'incompatible_server' : 'disconnected'
      if (activeTask?.state === 'running') finishTask(activeTask.run_id, false, `连接中断：${safeError(reason)}`)
      if (!stopping) scheduleConnect()
    })
  } catch (error) {
    bot = null
    state = 'error'
    lastError = safeError(error)
    scheduleConnect()
  }
}

async function runJoinCommands(created) {
  for (let index = 0; index < joinCommands.length; index++) {
    await new Promise(resolve => setTimeout(resolve, 750))
    if (bot !== created || state !== 'online') return
    created.chat(joinCommands[index])
    emit({ type: 'bot_join_command_sent', index })
  }
}

function connectWithProxyProtocol(client) {
  const socket = net.connect(port, host)
  socket.prependOnceListener('connect', () => {
    const localAddress = normalizeProxyAddress(socket.localAddress)
    const remoteAddress = normalizeProxyAddress(socket.remoteAddress)
    const family = net.isIPv6(localAddress) || net.isIPv6(remoteAddress) ? 'TCP6' : 'TCP4'
    const header = `PROXY ${family} ${localAddress} ${remoteAddress} ${socket.localPort} ${socket.remotePort}\r\n`
    socket.write(header)
    emit({ type: 'proxy_protocol_sent', family })
  })
  client.setSocket(socket)
}

function normalizeProxyAddress(value) {
  const address = String(value || '127.0.0.1')
  return address.startsWith('::ffff:') ? address.slice(7) : address
}

function handleNeoForgePayload(clientBot, packet) {
  const channel = String(packet?.channel || '')
  if (channel === 'neoforge:register' && neoForgeQuery) {
    clientBot._client.write('custom_payload', { channel, data: neoForgeQuery })
    neoForgeNegotiated = true
    emit({ type: 'neoforge_compatibility', state: 'manifest_sent', components: neoForgeComponentCount })
  } else if (channel === 'neoforge:extensible_enum_data' && neoForgeNegotiated) {
    clientBot._client.write('custom_payload', { channel: 'neoforge:extensible_enum_ack', data: Buffer.alloc(0) })
  } else if (channel === 'neoforge:feature_flags' && neoForgeNegotiated) {
    clientBot._client.write('custom_payload', { channel: 'neoforge:feature_flags_ack', data: Buffer.alloc(0) })
  }
}

function status() {
  const entity = bot?.entity
  return {
    ok: true,
    state,
    last_error: lastError || null,
    username: bot?.username || username,
    version: bot?.version || version || null,
    connected_at_ms: connectedAt || null,
    connection_started_at_ms: connectionStartedAt || null,
    connection_attempts: connectionAttempts,
    protocol_state: bot?._client?.state || null,
    last_disconnect_error: lastDisconnectError || null,
    health: numberOrNull(bot?.health),
    food: numberOrNull(bot?.food),
    position: entity ? vectorJson(entity.position) : null,
    dimension: normalizeDimension(bot?.game?.dimension) || null,
    active_task: activeTask,
    maintenance_state: retreating ? 'retreating_from_threat' : eating ? 'eating' : 'idle',
    waypoint_count: Object.keys(waypointData.waypoints).length,
    neoforge_compatibility: {
      available: Boolean(neoForgeQuery),
      negotiated: neoForgeNegotiated,
      component_count: neoForgeComponentCount,
      degraded_mod_data: neoForgeNegotiated
    },
    proxy_protocol: useProxyProtocol,
    last_protocol_diagnostic: lastProtocolDiagnostic
  }
}

function numberOrNull(value) {
  return Number.isFinite(value) ? value : null
}

function vectorJson(value) {
  return { x: round(value.x), y: round(value.y), z: round(value.z) }
}

function round(value) {
  return Math.round(Number(value) * 100) / 100
}

function inventorySummary() {
  if (!bot) return []
  return bot.inventory.items().slice(0, 64).map(item => ({
    name: item.name,
    display_name: item.displayName,
    count: item.count,
    slot: item.slot
  }))
}

function observe(distance = 8) {
  if (!bot?.entity) throw new Error('Bot 尚未进入服务器')
  const target = bot.blockAtCursor(Math.max(1, Math.min(32, distance)))
  const nearby = Object.values(bot.entities)
    .filter(entity => entity !== bot.entity && entity.position.distanceTo(bot.entity.position) <= distance)
    .sort((left, right) => left.position.distanceTo(bot.entity.position) - right.position.distanceTo(bot.entity.position))
    .slice(0, 32)
    .map(entity => ({
      id: entity.id,
      name: entity.username || entity.displayName || entity.name || entity.type,
      type: entity.type,
      distance: round(entity.position.distanceTo(bot.entity.position)),
      position: vectorJson(entity.position)
    }))
  return {
    ...status(),
    yaw: round(bot.entity.yaw),
    pitch: round(bot.entity.pitch),
    target_block: target ? blockJson(target) : null,
    visible_blocks: visibleBlocks(distance),
    nearby_entities: nearby,
    inventory: inventorySummary()
  }
}

function blockJson(block) {
  return {
    name: block.name,
    display_name: block.displayName,
    position: vectorJson(block.position),
    bounding_box: block.boundingBox
  }
}

function visibleBlocks(distance) {
  if (!bot?.entity || typeof bot.world?.raycast !== 'function') return []
  const origin = bot.entity.position.offset(0, bot.entity.height || 1.62, 0)
  const seen = new Map()
  for (const yawOffset of [-0.7, -0.35, 0, 0.35, 0.7]) {
    for (const pitchOffset of [-0.35, 0, 0.35]) {
      const yaw = bot.entity.yaw + yawOffset
      const pitch = bot.entity.pitch + pitchOffset
      const direction = new Vec3(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch))
      const hit = bot.world.raycast(origin, direction, distance)
      if (hit?.position) seen.set(`${hit.position.x},${hit.position.y},${hit.position.z}`, blockJson(hit))
    }
  }
  return [...seen.values()].slice(0, 32)
}

async function selfCare() {
  if (bot?.health != null && bot.health <= 8 && activeTask?.state === 'running' && activeTask.task_type !== 'eat') {
    bot.pathfinder?.stop()
    bot.clearControlStates()
    finishTask(activeTask.run_id, false, '自主生存保护：生命值过低，已中止当前任务')
  }
  const ate = await autoEat(false)
  if (!ate && bot?.health != null && bot.health <= 8) void retreatFromThreat()
}

async function retreatFromThreat() {
  if (!bot?.entity || retreating) return
  const hostileNames = new Set(['zombie', 'husk', 'drowned', 'skeleton', 'stray', 'creeper', 'spider',
    'cave_spider', 'witch', 'pillager', 'vindicator', 'evoker', 'ravager', 'phantom', 'warden'])
  const threat = Object.values(bot.entities)
    .filter(entity => entity.position && hostileNames.has(String(entity.name || '').toLowerCase()))
    .sort((left, right) => left.position.distanceTo(bot.entity.position) - right.position.distanceTo(bot.entity.position))[0]
  if (!threat || threat.position.distanceTo(bot.entity.position) > 16) return
  const away = bot.entity.position.minus(threat.position)
  const length = Math.max(0.01, Math.sqrt(away.x * away.x + away.z * away.z))
  const x = Math.floor(bot.entity.position.x + (away.x / length) * 12)
  const z = Math.floor(bot.entity.position.z + (away.z / length) * 12)
  const target = new Vec3(x, Math.floor(bot.entity.position.y), z)
  if (isForbidden(target)) return
  retreating = true
  emit({ type: 'autonomous_retreat', threat: threat.name, target: vectorJson(target) })
  try {
    await bot.pathfinder.goto(new goals.GoalNear(target.x, target.y, target.z, 2))
  } catch (error) {
    lastError = `自主避险失败：${safeError(error)}`
  } finally {
    retreating = false
  }
}

async function autoEat(force = false) {
  if (!bot || eating || bot.food == null || (!force && bot.food > 14) || bot.isUsingHeldItem) return false
  const candidates = bot.inventory.items()
    .map(item => ({ item, food: bot.registry?.foodsByName?.[item.name] }))
    .filter(entry => entry.food && !entry.food.effects?.length)
    .sort((left, right) => (right.food.foodPoints || 0) - (left.food.foodPoints || 0))
  if (!candidates.length) return false
  eating = true
  try {
    await bot.equip(candidates[0].item, 'hand')
    await bot.consume()
    return true
  } catch (error) {
    lastError = `自动进食失败：${safeError(error)}`
    return false
  } finally {
    eating = false
  }
}

function finishTask(runId, ok, message, data = null) {
  if (!activeTask || activeTask.run_id !== runId || activeTask.state !== 'running') return false
  activeTask = { ...activeTask, state: ok ? 'completed' : 'failed', finished_at_ms: Date.now(), message, data }
  recentTasks.set(activeTask.task_id, activeTask)
  while (recentTasks.size > 100) recentTasks.delete(recentTasks.keys().next().value)
  emit({ type: 'task_finished', task: activeTask })
  return true
}

async function runTask(input) {
  if (!bot || state !== 'online') throw new Error('Bot 尚未连接到服务器')
  if (retreating) throw new Error('Bot 正在执行自主生存避险，请稍后重试')
  const taskId = String(input.task_id || `task-${Date.now()}-${++taskSequence}`).slice(0, 80)
  const type = String(input.task_type || '').toLowerCase()
  const args = input.args && typeof input.args === 'object' ? input.args : {}
  if (activeTask?.task_id === taskId) return { ok: true, accepted: false, duplicate: true, task: activeTask }
  if (recentTasks.has(taskId)) return { ok: true, accepted: false, duplicate: true, task: recentTasks.get(taskId) }
  if (activeTask?.state === 'running') throw new Error('已有任务正在执行')
  const runId = ++taskGeneration
  activeTask = {
    task_id: taskId, task_type: type, run_id: runId, state: 'running', started_at_ms: Date.now(),
    requester_id: String(input.requester_id || '').slice(0, 100) || null,
    requester_name: String(input.requester_name || '').slice(0, 100) || null,
    requester_platform: String(input.requester_platform || '').slice(0, 50) || null
  }
  queueMicrotask(async () => {
    try {
      if (type === 'chat') {
        bot.chat(String(args.message || '').slice(0, 256))
      } else if (type === 'crouch_greet') {
        const count = parseInteger(args.count, 2, 1, 5)
        for (let index = 0; index < count; index++) {
          assertTaskActive(runId)
          bot.setControlState('sneak', true)
          await taskDelay(300, runId)
          bot.setControlState('sneak', false)
          await taskDelay(250, runId)
        }
      } else if (type === 'goto') {
        const x = finiteCoordinate(args.x, 'x')
        const y = finiteCoordinate(args.y, 'y')
        const z = finiteCoordinate(args.z, 'z')
        assertAllowedTarget(new Vec3(x, y, z), args.dimension)
        await bot.pathfinder.goto(new goals.GoalBlock(x, y, z))
      } else if (type === 'goto_waypoint') {
        const waypoint = waypointData.waypoints[String(args.id || '')]
        if (!waypoint) throw new Error('未找到路径点')
        if (normalizeDimension(waypoint.dimension) !== normalizeDimension(bot.game?.dimension)) throw new Error('路径点与 Bot 不在同一维度')
        assertAllowedTarget(waypoint, waypoint.dimension)
        await bot.pathfinder.goto(new goals.GoalBlock(waypoint.x, waypoint.y, waypoint.z))
      } else if (type === 'follow_player') {
        const playerName = String(args.player_name || '').slice(0, 16)
        const target = bot.players[playerName]?.entity
        if (!target) throw new Error(`玩家不在可观测范围：${playerName}`)
        const seconds = parseInteger(args.seconds, 10, 1, 120)
        const distance = parseInteger(args.distance, 3, 2, 8)
        bot.pathfinder.setGoal(new goals.GoalFollow(target, distance), true)
        await taskDelay(seconds * 1000, runId)
        bot.pathfinder.stop()
      } else if (type === 'look_at') {
        const target = new Vec3(finiteNumber(args.x, 'x'), finiteNumber(args.y, 'y'), finiteNumber(args.z, 'z'))
        assertAllowedTarget(target, args.dimension)
        await bot.lookAt(target, true)
      } else if (type === 'wait') {
        await taskDelay(parseInteger(args.milliseconds, 1000, 100, 30000), runId)
      } else if (type === 'eat') {
        if (!await autoEat(true)) throw new Error('背包中没有 Mineflayer 可安全识别的食物')
      } else if (type === 'interact_block') {
        const position = new Vec3(finiteCoordinate(args.x, 'x'), finiteCoordinate(args.y, 'y'), finiteCoordinate(args.z, 'z'))
        assertAllowedTarget(position, args.dimension)
        if (bot.entity.position.distanceTo(position) > 5) await bot.pathfinder.goto(new goals.GoalNear(position.x, position.y, position.z, 4))
        assertTaskActive(runId)
        const block = bot.blockAt(position)
        if (!block) throw new Error('目标方块不可见')
        await bot.activateBlock(block)
      } else if (type === 'use_item') {
        const itemName = String(args.item_name || '').toLowerCase()
        const shortName = itemName.includes(':') ? itemName.slice(itemName.indexOf(':') + 1) : itemName
        const item = bot.inventory.items().find(entry => entry.name.toLowerCase() === itemName || entry.name.toLowerCase() === shortName)
        if (!item) throw new Error(`背包中没有物品：${itemName}`)
        await bot.equip(item, 'hand')
        bot.activateItem(Boolean(args.offhand))
        await taskDelay(parseInteger(args.milliseconds, 500, 100, 5000), runId)
        bot.deactivateItem()
      } else {
        throw new Error(`不支持的任务类型：${type}`)
      }
      assertTaskActive(runId)
      let observation = null
      try { observation = observe(8) } catch (_) {}
      finishTask(runId, true, '任务完成', observation)
    } catch (error) {
      if (error?.code !== 'TASK_CANCELED') finishTask(runId, false, safeError(error))
    } finally {
      bot?.clearControlStates()
    }
  })
  return { ok: true, accepted: true, task: activeTask }
}

function finiteCoordinate(value, name) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || Math.abs(parsed) > 30000000) throw new Error(`无效坐标 ${name}`)
  return Math.floor(parsed)
}

function finiteNumber(value, name) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || Math.abs(parsed) > 30000000) throw new Error(`无效坐标 ${name}`)
  return parsed
}

function assertAllowedTarget(position, dimension = bot?.game?.dimension) {
  if (dimension && bot?.game?.dimension && normalizeDimension(dimension) !== normalizeDimension(bot.game.dimension)) throw new Error('目标维度与 Bot 当前维度不一致')
  if (isForbidden(position, dimension)) throw new Error('目标坐标位于 Agent 禁区内')
}

function assertTaskActive(runId) {
  if (!activeTask || activeTask.run_id !== runId || activeTask.state !== 'running') {
    const error = new Error('任务已取消')
    error.code = 'TASK_CANCELED'
    throw error
  }
}

async function taskDelay(milliseconds, runId) {
  let remaining = milliseconds
  while (remaining > 0) {
    assertTaskActive(runId)
    const slice = Math.min(remaining, 100)
    await delay(slice)
    remaining -= slice
  }
  assertTaskActive(runId)
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function cancelTask() {
  if (!activeTask || activeTask.state !== 'running') return { ok: true, canceled: false }
  bot?.pathfinder?.stop()
  bot?.clearControlStates()
  activeTask = { ...activeTask, state: 'canceled', finished_at_ms: Date.now() }
  recentTasks.set(activeTask.task_id, activeTask)
  while (recentTasks.size > 100) recentTasks.delete(recentTasks.keys().next().value)
  emit({ type: 'task_canceled', task: activeTask })
  return { ok: true, canceled: true, task: activeTask }
}

function loadWaypointData() {
  try {
    const parsed = JSON.parse(fs.readFileSync(waypointFile, 'utf8'))
    if (parsed && typeof parsed.waypoints === 'object' && typeof parsed.links === 'object') return parsed
  } catch (_) {}
  return { schema_version: 1, waypoints: {}, links: {} }
}

function saveWaypointData() {
  fs.mkdirSync(dataDir, { recursive: true })
  const temporary = `${waypointFile}.tmp`
  fs.writeFileSync(temporary, JSON.stringify(waypointData, null, 2), { mode: 0o600 })
  fs.renameSync(temporary, waypointFile)
}

function waypointOperation(input) {
  const action = String(input.action || 'list').toLowerCase()
  if (action === 'list') return { ok: true, waypoints: Object.values(waypointData.waypoints), links: Object.values(waypointData.links) }
  const id = String(input.id || '').replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 64)
  if (!id) throw new Error('路径点 ID 不能为空')
  if (action === 'delete') {
    delete waypointData.waypoints[id]
    for (const key of Object.keys(waypointData.links)) {
      if (waypointData.links[key].from === id || waypointData.links[key].to === id) delete waypointData.links[key]
    }
  } else if (action === 'set') {
    waypointData.waypoints[id] = {
      id,
      name: String(input.name || id).slice(0, 100),
      type: String(input.waypoint_type || 'generic').slice(0, 32),
      dimension: String(input.dimension || bot?.game?.dimension || 'minecraft:overworld').slice(0, 128),
      x: finiteCoordinate(input.x, 'x'), y: finiteCoordinate(input.y, 'y'), z: finiteCoordinate(input.z, 'z'),
      risk: String(input.risk || 'unknown').slice(0, 32),
      updated_at_ms: Date.now()
    }
  } else if (action === 'link') {
    const to = String(input.to || '').replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 64)
    if (!waypointData.waypoints[id] || !waypointData.waypoints[to]) throw new Error('连接两端的路径点必须存在')
    const mode = String(input.mode || 'walk').toLowerCase()
    if (!['walk', 'rail'].includes(mode)) throw new Error('交通连接模式必须是 walk 或 rail')
    const key = `${id}->${to}`
    waypointData.links[key] = { id: key, from: id, to, mode, updated_at_ms: Date.now() }
  } else {
    throw new Error(`不支持的路径点操作：${action}`)
  }
  saveWaypointData()
  return waypointOperation({ action: 'list' })
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    request.on('data', chunk => {
      size += chunk.length
      if (size > maxBodyBytes) {
        reject(new Error('请求体过大'))
        request.destroy()
      } else chunks.push(chunk)
    })
    request.on('end', () => {
      if (!chunks.length) return resolve({})
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))) } catch (_) { reject(new Error('无效 JSON')) }
    })
    request.on('error', reject)
  })
}

function send(response, statusCode, payload) {
  const encoded = Buffer.from(JSON.stringify(payload))
  response.writeHead(statusCode, { 'content-type': 'application/json; charset=utf-8', 'content-length': encoded.length })
  response.end(encoded)
}

const control = http.createServer(async (request, response) => {
  if (!token || request.headers.authorization !== `Bearer ${token}`) return send(response, 401, { ok: false, error: 'unauthorized' })
  try {
    const body = request.method === 'POST' ? await readJson(request) : {}
    if (request.url === '/health') return send(response, 200, status())
    if (request.url === '/status') return send(response, 200, status())
    if (request.url === '/observe') return send(response, 200, observe(parseInteger(body.distance, 8, 1, 32)))
    if (request.url === '/task') return send(response, 202, await runTask(body))
    if (request.url === '/cancel') return send(response, 200, cancelTask())
    if (request.url === '/waypoints') return send(response, 200, waypointOperation(body))
    return send(response, 404, { ok: false, error: 'not_found' })
  } catch (error) {
    return send(response, 400, { ok: false, error: safeError(error) })
  }
})

function shutdown() {
  if (stopping) return
  stopping = true
  state = 'stopping'
  if (reconnectTimer) clearTimeout(reconnectTimer)
  cancelTask()
  try { bot?.quit('MineAstr server stopping') } catch (_) {}
  control.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 3000).unref()
}

process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
process.on('uncaughtException', error => {
  lastError = safeError(error)
  emit({ type: 'uncaught_exception', error: lastError })
})
process.on('unhandledRejection', error => {
  lastError = safeError(error)
  emit({ type: 'unhandled_rejection', error: lastError })
})

fs.mkdirSync(dataDir, { recursive: true })
control.listen(0, '127.0.0.1', () => {
  emit({ type: 'ready', port: control.address().port, runtime_version: '0.10.0' })
  connectBot()
})
