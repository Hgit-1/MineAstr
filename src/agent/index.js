'use strict'

const fs = require('node:fs')
const http = require('node:http')
const net = require('node:net')
const path = require('node:path')
const mineflayer = require('mineflayer')
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder')
const { Vec3 } = require('vec3')
const { applyPathfinderCollisionCompatibility, navigateTo } = require('./navigation')
const { ChunkNavigationCache } = require('./chunk-cache')
const { applyNavigationPolicy } = require('./navigation-policy')
const { executeJoinCommands, initialJoinCommandState, parseJoinCommands } = require('./join-commands')
const { createCombatController, isAttackableHostile, isRetreatThreat } = require('./combat')
const { RoadNetwork } = require('./road-network')
const { RESUMABLE_TYPES, TaskStore } = require('./task-store')
const { resumeNavigationRecord, suspendNavigationRecord } = require('./task-lifecycle')
const { version: runtimeVersion } = require('./package.json')

const token = process.env.MINEASTR_AGENT_TOKEN || ''
const dataDir = process.env.MINEASTR_AGENT_DATA_DIR || process.cwd()
const host = process.env.MINEASTR_MC_HOST || '127.0.0.1'
const port = parseInteger(process.env.MINEASTR_MC_PORT, 25565, 1, 65535)
let username = validMinecraftUsername(process.env.MINEASTR_AGENT_USERNAME) || 'MineAstrBot'
const version = process.env.MINEASTR_MC_VERSION || false
const auth = process.env.MINEASTR_AGENT_AUTH === 'microsoft' ? 'microsoft' : 'offline'
const joinCommands = parseJoinCommands(process.env.MINEASTR_AGENT_JOIN_COMMANDS)
const joinCommandDelayMs = parseInteger(process.env.MINEASTR_AGENT_JOIN_COMMAND_DELAY_MS, 1000, 0, 5000)
const joinCommandSettleMs = parseInteger(process.env.MINEASTR_AGENT_JOIN_COMMAND_SETTLE_MS, 1500, 0, 10000)
const neoForgeQuery = decodeBase64(process.env.MINEASTR_NEOFORGE_QUERY_B64)
const neoForgeComponentCount = parseInteger(process.env.MINEASTR_NEOFORGE_COMPONENT_COUNT, 0, 0, 100000)
const useProxyProtocol = process.env.MINEASTR_PROXY_PROTOCOL === 'true'
const configuredSessionPolicy = String(process.env.MINEASTR_AGENT_SESSION_POLICY || 'on_demand').toLowerCase()
const sessionPolicy = ['on_demand', 'players_online', 'always'].includes(configuredSessionPolicy)
  ? configuredSessionPolicy : 'on_demand'
const idleDisconnectSeconds = parseInteger(process.env.MINEASTR_AGENT_IDLE_DISCONNECT_SECONDS, 60, 0, 3600)
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
const navigationAllowDigging = process.env.MINEASTR_NAV_ALLOW_DIGGING === 'true'
const navigationAllowPlacing = process.env.MINEASTR_NAV_ALLOW_PLACING === 'true'
const navigationDigCost = parseInteger(process.env.MINEASTR_NAV_DIG_COST, 12, 1, 99)
const navigationPlaceCost = parseInteger(process.env.MINEASTR_NAV_PLACE_COST, 18, 1, 99)
const navigationLiquidCost = parseInteger(process.env.MINEASTR_NAV_LIQUID_COST, 8, 1, 99)
const roadWeaverRoutingEnabled = process.env.MINEASTR_ROADWEAVER_ROUTING_ENABLED !== 'false'
const resumeInterruptedNavigation = process.env.MINEASTR_RESUME_INTERRUPTED_NAVIGATION !== 'false'
const combatEnabled = process.env.MINEASTR_COMBAT_ENABLED !== 'false'
const combatRadius = parseInteger(process.env.MINEASTR_COMBAT_RADIUS, 6, 3, 16)
const combatMinimumHealth = parseInteger(process.env.MINEASTR_COMBAT_MIN_HEALTH, 10, 1, 20)
const combatAttackCooldownMilliseconds = parseInteger(process.env.MINEASTR_COMBAT_ATTACK_COOLDOWN_MS, 650, 250, 2000)
const navigationCache = new ChunkNavigationCache(path.join(dataDir, 'navigation-cache'), {
  maxChunks: parseInteger(process.env.MINEASTR_NAV_CACHE_MAX_CHUNKS, 2048, 64, 16384)
})

let bot = null
let state = 'standby'
let lastError = ''
let activeTask = null
let activeTaskArgs = null
let taskSequence = 0
const recentTasks = new Map()
let reconnectTimer = null
let idleDisconnectTimer = null
let taskConnectionTimer = null
let stopping = false
let connectionBlocked = false
let sessionDisconnecting = false
let sessionReady = false
let humanPlayerCount = 0
let wakeReason = null
let idleDisconnectAt = 0
let connectedAt = 0
let connectionStartedAt = 0
let connectionAttempts = 0
let lastDisconnectError = ''
let navigationCompatibility = null
let combatController = null
let eating = false
let retreating = false
let survivalTimer = null
let selfCareRunning = false
let neoForgeNegotiated = false
let taskGeneration = 0
const observedCustomChannels = new Set()
let lastProtocolDiagnostic = null
let lastPhysicalRecovery = null
let pendingSessionExit = null
let lastSessionExit = null
let lastDeathAt = 0
let joinCommandState = initialJoinCommandState(joinCommands.length)

const waypointFile = path.join(dataDir, 'waypoints.json')
let waypointData = loadWaypointData()
const roadNetwork = new RoadNetwork(path.join(dataDir, 'road-network.json'), { enabled: roadWeaverRoutingEnabled })
const taskStore = new TaskStore(path.join(dataDir, 'tasks.json'), { resumeEnabled: resumeInterruptedNavigation })
let restoredNavigationTask = false
restoreTaskState()

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

function validMinecraftUsername(value) {
  const candidate = String(value || '').trim()
  return /^[A-Za-z0-9_]{3,16}$/.test(candidate) ? candidate : null
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

function validateRestoredTask(task, args) {
  if (!args || typeof args !== 'object') return '持久化任务缺少参数'
  let target = args
  if (task.task_type === 'goto_waypoint') {
    target = waypointData.waypoints[String(args.id || '')]
    if (!target) return '要恢复的路径点已不存在'
  }
  const x = Number(target.x), y = Number(target.y), z = Number(target.z)
  if (![x, y, z].every(Number.isFinite)) return '要恢复的目标坐标无效'
  const dimension = target.dimension || args.dimension || 'minecraft:overworld'
  if (isForbidden({ x, y, z }, dimension)) return '要恢复的目标位于 Agent 禁区'
  return null
}

function restoreTaskState() {
  const restored = taskStore.restore(validateRestoredTask)
  recentTasks.clear()
  for (const task of restored.recentTasks) {
    if (task?.task_id) recentTasks.set(task.task_id, task)
  }
  if (!restored.activeTask) {
    persistTasks()
    return
  }
  activeTask = { ...restored.activeTask, run_id: ++taskGeneration }
  activeTaskArgs = restored.activeTaskArgs
  restoredNavigationTask = true
  persistTasks()
}

function persistTasks(activeOverride = undefined, argsOverride = undefined) {
  const candidate = activeOverride === undefined ? activeTask : activeOverride
  const args = argsOverride === undefined ? activeTaskArgs : argsOverride
  const resumableActive = candidate && ['waiting_for_connection', 'running', 'suspended'].includes(candidate.state)
    ? candidate : null
  taskStore.save(resumableActive, args, recentTasks.values())
}

function emit(record) {
  const event = { time_ms: Date.now(), ...record }
  if (record?.type === 'navigation_physical_unstuck') lastPhysicalRecovery = event
  process.stdout.write(`${JSON.stringify(event)}\n`)
}

function taskNeedsSession() {
  return ['waiting_for_connection', 'running', 'suspended'].includes(activeTask?.state)
}

function maintenanceNeedsSession() {
  return eating || retreating
}

function desiredWakeReason() {
  if (taskNeedsSession()) return activeTask?.task_type === 'chat' ? 'conversation_task' : 'task'
  if (maintenanceNeedsSession()) return 'self_care'
  if (sessionPolicy === 'always') return 'always'
  if (sessionPolicy === 'players_online' && humanPlayerCount > 0) return 'players_online'
  return null
}

function clearIdleDisconnect() {
  if (idleDisconnectTimer) clearTimeout(idleDisconnectTimer)
  idleDisconnectTimer = null
  idleDisconnectAt = 0
}

function reconcileSession() {
  if (stopping) return
  const desired = desiredWakeReason()
  wakeReason = desired
  if (desired) {
    clearIdleDisconnect()
    if (!bot && !reconnectTimer && !connectionBlocked) connectBot()
    return
  }
  if (!bot) {
    clearIdleDisconnect()
    state = 'standby'
    return
  }
  if (idleDisconnectTimer) return
  const delayMs = idleDisconnectSeconds * 1000
  idleDisconnectAt = Date.now() + delayMs
  idleDisconnectTimer = setTimeout(() => {
    idleDisconnectTimer = null
    idleDisconnectAt = 0
    if (desiredWakeReason() || !bot) return reconcileSession()
    sessionDisconnecting = true
    sessionReady = false
    state = 'disconnecting_idle'
    pendingSessionExit = {
      code: 'idle_standby', expected: true,
      detail: `按需会话闲置 ${idleDisconnectSeconds} 秒后进入待机`
    }
    emit({
      type: 'bot_idle_disconnect',
      idle_seconds: idleDisconnectSeconds,
      last_task: activeTask ? {
        task_id: activeTask.task_id,
        task_type: activeTask.task_type,
        state: activeTask.state,
        message: activeTask.message || null
      } : null
    })
    try { bot.quit('MineAstr on-demand standby') } catch (_) { bot.end?.('MineAstr on-demand standby') }
  }, delayMs)
  idleDisconnectTimer.unref()
}

function scheduleConnect() {
  if (stopping || connectionBlocked || reconnectTimer || !desiredWakeReason()) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    reconcileSession()
  }, 5000)
  reconnectTimer.unref()
}

function connectBot() {
  if (stopping || bot || !desiredWakeReason()) return
  sessionDisconnecting = false
  sessionReady = false
  joinCommandState = initialJoinCommandState(joinCommands.length)
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
      pendingSessionExit = { code: 'login_timeout', expected: false, detail: lastError }
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
    created.once('spawn', async () => {
      clearTimeout(connectionTimeout)
      state = 'online'
      connectedAt = Date.now()
      lastError = ''
      lastDisconnectError = ''
      navigationCompatibility = applyPathfinderCollisionCompatibility(created, created.version || version)
      if (navigationCompatibility.applied) {
        emit({ type: 'pathfinder_compatibility_applied', compatibility: navigationCompatibility })
      }
      try {
        const movements = new Movements(created)
        applyNavigationPolicy(movements, created, {
          allowDigging: navigationAllowDigging,
          allowPlacing: navigationAllowPlacing,
          digCost: navigationDigCost,
          placeCost: navigationPlaceCost,
          liquidCost: navigationLiquidCost,
          isForbidden
        })
        created.pathfinder.setMovements(movements)
      } catch (error) {
        lastError = safeError(error)
      }
      emit({ type: 'bot_online', username: created.username, version: created.version })
      created.world.on('chunkColumnLoad', corner => navigationCache.capture(created, corner, normalizeDimension(created.game?.dimension)))
      created.on('blockUpdate', (_oldBlock, newBlock) => navigationCache.refreshLater(
        created, newBlock?.position, normalizeDimension(created.game?.dimension)
      ))
      navigationCache.captureLoaded(created, normalizeDimension(created.game?.dimension))
      const joinResult = await executeJoinCommands({
        commands: joinCommands,
        commandDelayMs: joinCommandDelayMs,
        settleDelayMs: joinCommandSettleMs,
        send: command => created.chat(command),
        isActive: () => bot === created && state === 'online',
        onState: nextState => {
          joinCommandState = nextState
          emit({ type: 'bot_join_command_state', join_commands: nextState })
        }
      })
      if (!joinResult.ok) {
        if (joinResult.aborted) return
        const failedNumber = Number.isInteger(joinResult.failed_index) ? joinResult.failed_index + 1 : 0
        lastError = failedNumber > 0
          ? `第 ${failedNumber} 条前置指令发送失败；指令内容已隐藏。`
          : '前置指令发送失败；指令内容已隐藏。'
        lastDisconnectError = lastError
        pendingSessionExit = { code: 'join_command_failed', expected: false, detail: lastError }
        emit({ type: 'bot_join_command_failed', index: joinResult.failed_index, error: 'send_failed' })
        try { created.quit('MineAstr join command failed') } catch (_) { created.end?.('MineAstr join command failed') }
        return
      }
      if (bot !== created || state !== 'online') return
      sessionReady = true
      combatController = createCombatController(created, {
        enabled: combatEnabled,
        radius: combatRadius,
        attackRange: 3.1,
        minimumHealth: combatMinimumHealth,
        attackCooldownMilliseconds: combatAttackCooldownMilliseconds,
        emit,
        isForbidden,
        shouldPause: () => bot !== created || !sessionReady || sessionDisconnecting || eating || retreating
          || created.isUsingHeldItem || created.pathfinder?.isMining?.() || created.pathfinder?.isBuilding?.(),
        onDanger: (threat, reason) => handleCombatDanger(threat, reason)
      })
      combatController.start()
      survivalTimer = setInterval(() => void selfCare(), 1000)
      survivalTimer.unref?.()
      startWaitingTask()
      reconcileSession()
    })
    created.on('health', () => void selfCare())
    created.on('death', () => {
      lastDeathAt = Date.now()
      suspendNavigationTask('自主生存保护：Bot 死亡，等待重生后续行', null, 'death')
      emit({ type: 'bot_death', position: created.entity?.position ? vectorJson(created.entity.position) : null })
    })
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
      pendingSessionExit = { code: 'kicked', expected: false, detail: lastError }
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
      pendingSessionExit ||= { code: 'network_error', expected: false, detail: lastError }
      lastProtocolDiagnostic = {
        frames: recentProtocolFrames.map(frame => ({ ...frame })),
        packets: recentProtocolPackets.map(packet => ({ ...packet }))
      }
      emit({ type: 'bot_error', error: lastError, recent_protocol_frames: recentProtocolFrames,
        recent_protocol_packets: recentProtocolPackets })
    })
    created.once('end', reason => {
      clearTimeout(connectionTimeout)
      combatController?.stop('connection_ended')
      combatController = null
      if (survivalTimer) clearInterval(survivalTimer)
      survivalTimer = null
      bot = null
      sessionReady = false
      const expectedIdleDisconnect = sessionDisconnecting
      sessionDisconnecting = false
      const protocolReason = safeError(reason)
      if (!expectedIdleDisconnect && !lastDisconnectError) lastDisconnectError = protocolReason
      const recordedExit = pendingSessionExit || {
        code: stopping ? 'server_stopping' : 'connection_ended',
        expected: stopping,
        detail: protocolReason
      }
      lastSessionExit = {
        ...recordedExit,
        protocol_reason: protocolReason,
        time_ms: Date.now(),
        after_recent_death: Boolean(lastDeathAt && Date.now() - lastDeathAt <= 120_000)
      }
      pendingSessionExit = null
      emit({ type: 'bot_offline', session_exit: lastSessionExit })
      const wakeAfterDisconnect = desiredWakeReason()
      state = stopping ? 'stopped' : connectionBlocked ? 'incompatible_server'
        : wakeAfterDisconnect ? 'disconnected' : 'standby'
      if (activeTask?.state === 'running') {
        if (!suspendNavigationTask(`连接中断：${safeError(reason)}`, null, 'connection_interrupted', false)) {
          finishTask(activeTask.run_id, false, `连接中断：${safeError(reason)}`)
        }
      }
      if (!stopping) {
        if (desiredWakeReason()) scheduleConnect()
        else reconcileSession()
      }
    })
  } catch (error) {
    bot = null
    state = 'error'
    lastError = safeError(error)
    scheduleConnect()
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
    runtime_version: runtimeVersion,
    state,
    last_error: lastError || null,
    username: bot?.username || username,
    preferred_username: username,
    identity_change_pending: Boolean(bot?.username && bot.username !== username),
    version: bot?.version || version || null,
    connected_at_ms: connectedAt || null,
    connection_started_at_ms: connectionStartedAt || null,
    connection_attempts: connectionAttempts,
    protocol_state: bot?._client?.state || null,
    last_disconnect_error: lastDisconnectError || null,
    last_session_exit: lastSessionExit,
    last_death_at_ms: lastDeathAt || null,
    health: numberOrNull(bot?.health),
    food: numberOrNull(bot?.food),
    position: entity ? vectorJson(entity.position) : null,
    dimension: normalizeDimension(bot?.game?.dimension) || null,
    active_task: taskStatus(activeTask, true),
    recent_tasks: Array.from(recentTasks.values()).slice(-10).map(task => taskStatus(task, false)),
    session_policy: sessionPolicy,
    human_player_count: humanPlayerCount,
    wake_reason: wakeReason,
    idle_disconnect_seconds: idleDisconnectSeconds,
    idle_disconnect_at_ms: idleDisconnectAt || null,
    join_commands: joinCommandState,
    maintenance_state: retreating ? 'retreating_from_threat' : eating ? 'eating' : 'idle',
    combat: combatController?.status() || {
      enabled: combatEnabled,
      state: 'idle',
      target: null,
      radius: combatRadius,
      attack_range: 3.1,
      minimum_health: combatMinimumHealth,
      attack_cooldown_ms: combatAttackCooldownMilliseconds,
      attacks: 0,
      danger_events: 0,
      last_danger: null,
      last_attack_at_ms: null,
      last_error: null
    },
    waypoint_count: Object.keys(waypointData.waypoints).length,
    neoforge_compatibility: {
      available: Boolean(neoForgeQuery),
      negotiated: neoForgeNegotiated,
      component_count: neoForgeComponentCount,
      degraded_mod_data: neoForgeNegotiated
    },
    proxy_protocol: useProxyProtocol,
    last_protocol_diagnostic: lastProtocolDiagnostic,
    navigation: {
      backend: roadNetwork.status().available ? 'roadweaver-hybrid' : 'mineflayer-pathfinder-a-star',
      segmented: true,
      stitched_chunk_corridor: true,
      tool_aware_dig_cost: true,
      allow_digging: navigationAllowDigging,
      allow_placing: navigationAllowPlacing,
      dig_cost: navigationDigCost,
      place_cost: navigationPlaceCost,
      liquid_cost: navigationLiquidCost,
      cache: navigationCache.status(),
      road_network: roadNetwork.status(),
      task_persistence: taskStore.status(),
      collision_compatibility: navigationCompatibility,
      last_physical_recovery: lastPhysicalRecovery
    }
  }
}

function taskStatus(task, includeData) {
  if (!task) return null
  const result = { ...task }
  if (!includeData) delete result.data
  return result
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
  if (!bot?.entity) throw new Error(state === 'standby' ? 'Agent 正在按需待机，尚未进入服务器' : 'Bot 尚未进入服务器')
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
  if (selfCareRunning) return
  selfCareRunning = true
  try {
    if (bot?.health != null && bot.health <= combatMinimumHealth
        && activeTask?.state === 'running' && activeTask.task_type !== 'eat') {
      suspendNavigationTask('自主生存保护：生命值过低', null, 'low_health')
    }
    const ate = await autoEat(false)
    const nearbyThreat = activeTask?.state === 'suspended' ? nearbySurvivalThreat() : null
    if (!ate && bot?.health != null && bot.health <= combatMinimumHealth) {
      await retreatFromThreat(nearbyThreat, 'low_health')
    } else if (!ate && nearbyThreat) {
      await retreatFromThreat(nearbyThreat, 'navigation_safety')
    }
    tryResumeSuspendedNavigation()
  } finally {
    selfCareRunning = false
  }
}

function handleCombatDanger(threat, reason) {
  if (!threat || retreating || eating) return
  if (activeTask?.state === 'running' && activeTask.task_type !== 'eat') {
    suspendNavigationTask(
      `自主生存保护：检测到${reason === 'low_health' ? '低生命值与' : ''}高危生物 ${String(threat.name || threat.mobType || 'unknown').slice(0, 40)}`,
      threat, reason)
  }
  void retreatFromThreat(threat, reason)
}

function suspendNavigationTask(message, threat = null, reason = 'survival', reconcile = true) {
  if (!resumeInterruptedNavigation || !activeTask || activeTask.state !== 'running'
      || !RESUMABLE_TYPES.has(activeTask.task_type)) return false
  const checkpoint = activeTask.navigation_checkpoint || (bot?.entity?.position
    ? { position: vectorJson(bot.entity.position), reason } : null)
  bot?.pathfinder?.stop()
  bot?.clearControlStates()
  activeTask = suspendNavigationRecord(activeTask, {
    runId: ++taskGeneration, message, reason, checkpoint
  })
  persistTasks()
  emit({
    type: 'task_suspended', task: taskStatus(activeTask, false), reason,
    threat: threat ? String(threat.name || threat.mobType || 'unknown').slice(0, 40) : null,
    checkpoint
  })
  if (reconcile) reconcileSession()
  return true
}

function nearbySurvivalThreat(radius = combatRadius + 2) {
  if (!bot?.entity?.position) return null
  return Object.values(bot.entities || {})
    .filter(entity => entity?.position && (isAttackableHostile(bot, entity) || isRetreatThreat(entity)))
    .map(entity => ({ entity, distance: entity.position.distanceTo(bot.entity.position) }))
    .filter(candidate => candidate.distance <= radius)
    .sort((left, right) => left.distance - right.distance)[0]?.entity || null
}

function tryResumeSuspendedNavigation() {
  if (!activeTask || activeTask.state !== 'suspended' || !RESUMABLE_TYPES.has(activeTask.task_type)
      || !bot || state !== 'online' || !sessionReady || retreating || eating) return false
  const safeHealth = Math.min(20, combatMinimumHealth + 2)
  if (Number(bot.health) < safeHealth || nearbySurvivalThreat()) return false
  activeTask = resumeNavigationRecord(activeTask, { runId: ++taskGeneration })
  persistTasks()
  emit({ type: 'task_resumed', task: taskStatus(activeTask, false), checkpoint: activeTask.navigation_checkpoint || null })
  startWaitingTask()
  reconcileSession()
  return true
}

async function retreatFromThreat(providedThreat = null, reason = 'threat') {
  if (!bot?.entity || retreating || eating) return
  const threat = providedThreat || Object.values(bot.entities)
    .filter(entity => entity.position && (isAttackableHostile(bot, entity) || isRetreatThreat(entity)))
    .sort((left, right) => left.position.distanceTo(bot.entity.position) - right.position.distanceTo(bot.entity.position))[0]
  if (!threat || threat.position.distanceTo(bot.entity.position) > 16) return
  const away = bot.entity.position.minus(threat.position)
  const length = Math.max(0.01, Math.sqrt(away.x * away.x + away.z * away.z))
  const x = Math.floor(bot.entity.position.x + (away.x / length) * 12)
  const z = Math.floor(bot.entity.position.z + (away.z / length) * 12)
  const target = new Vec3(x, Math.floor(bot.entity.position.y), z)
  if (isForbidden(target)) return
  retreating = true
  emit({ type: 'autonomous_retreat', threat: threat.name, reason, target: vectorJson(target) })
  try {
    await bot.pathfinder.goto(new goals.GoalNear(target.x, target.y, target.z, 2))
  } catch (error) {
    lastError = `自主避险失败：${safeError(error)}`
  } finally {
    retreating = false
    tryResumeSuspendedNavigation()
    reconcileSession()
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
    reconcileSession()
  }
}

function finishTask(runId, ok, message, data = null) {
  if (!activeTask || activeTask.run_id !== runId
      || !['waiting_for_connection', 'running'].includes(activeTask.state)) return false
  if (taskConnectionTimer) clearTimeout(taskConnectionTimer)
  taskConnectionTimer = null
  activeTaskArgs = null
  activeTask = { ...activeTask, state: ok ? 'completed' : 'failed', finished_at_ms: Date.now(), message, data }
  recentTasks.set(activeTask.task_id, activeTask)
  while (recentTasks.size > 100) recentTasks.delete(recentTasks.keys().next().value)
  persistTasks()
  // The Java supervisor only needs the audit fields. Keep the potentially large
  // final observation available through the control API, but do not duplicate it
  // onto the line-oriented child-process event stream.
  emit({ type: 'task_finished', task: taskStatus(activeTask, false) })
  reconcileSession()
  return true
}

async function runTask(input) {
  if (retreating) throw new Error('Bot 正在执行自主生存避险，请稍后重试')
  const taskId = String(input.task_id || `task-${Date.now()}-${++taskSequence}`).slice(0, 80)
  const type = String(input.task_type || '').toLowerCase()
  const args = input.args && typeof input.args === 'object' ? input.args : {}
  if (activeTask?.task_id === taskId) return { ok: true, accepted: false, duplicate: true, task: activeTask }
  if (recentTasks.has(taskId)) return { ok: true, accepted: false, duplicate: true, task: recentTasks.get(taskId) }
  if (['waiting_for_connection', 'running', 'suspended'].includes(activeTask?.state)) throw new Error('已有任务正在执行或等待生存条件恢复')
  const runId = ++taskGeneration
  activeTask = {
    task_id: taskId, task_type: type, run_id: runId, state: 'waiting_for_connection',
    accepted_at_ms: Date.now(), started_at_ms: null,
    requester_id: String(input.requester_id || '').slice(0, 100) || null,
    requester_name: String(input.requester_name || '').slice(0, 100) || null,
    requester_platform: String(input.requester_platform || '').slice(0, 50) || null
  }
  activeTaskArgs = args
  if (sessionDisconnecting || state === 'disconnecting_idle') {
    pendingSessionExit = {
      code: 'idle_disconnect_superseded',
      expected: true,
      detail: '闲置断开已开始后收到新任务；会话结束后将自动重连并继续等待任务。'
    }
  }
  taskConnectionTimer = setTimeout(() => {
    taskConnectionTimer = null
    finishTask(runId, false, 'Bot 在 90 秒内未能进入服务器，任务已取消')
  }, 90_000)
  taskConnectionTimer.unref()
  reconcileSession()
  persistTasks()
  startWaitingTask()
  return { ok: true, accepted: true, task: activeTask }
}

function startWaitingTask() {
  if (!activeTask || activeTask.state !== 'waiting_for_connection' || !bot || state !== 'online' || !sessionReady) return
  if (taskConnectionTimer) clearTimeout(taskConnectionTimer)
  taskConnectionTimer = null
  const runId = activeTask.run_id
  const type = activeTask.task_type
  const args = activeTaskArgs || {}
  activeTask = { ...activeTask, state: 'running', started_at_ms: Date.now() }
  persistTasks()
  emit({ type: 'task_started', task: activeTask })
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
        const target = new Vec3(x, y, z)
        assertAllowedTarget(target, args.dimension)
        await navigateTask(target, args, runId)
      } else if (type === 'goto_waypoint') {
        const waypoint = waypointData.waypoints[String(args.id || '')]
        if (!waypoint) throw new Error('未找到路径点')
        if (normalizeDimension(waypoint.dimension) !== normalizeDimension(bot.game?.dimension)) throw new Error('路径点与 Bot 不在同一维度')
        assertAllowedTarget(waypoint, waypoint.dimension)
        await navigateTask(new Vec3(waypoint.x, waypoint.y, waypoint.z), args, runId)
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
        if (bot.entity.position.distanceTo(position) > 5) await navigateTask(position, { ...args, tolerance: 4 }, runId)
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
}

async function navigateTask(target, args, runId) {
  return navigateTo(bot, goals, target, {
    tolerance: parseInteger(args.tolerance, 2, 1, 8),
    timeoutMilliseconds: parseInteger(args.timeout_seconds, 0, 0, 900) * 1000 || undefined,
    assertActive: () => assertTaskActive(runId),
    dimension: normalizeDimension(args.dimension || bot?.game?.dimension),
    isForbidden: position => isForbidden(position, args.dimension || bot?.game?.dimension),
    cache: navigationCache,
    roadNetwork,
    onCheckpoint: checkpoint => {
      if (!activeTask || activeTask.run_id !== runId) return
      activeTask = { ...activeTask, navigation_checkpoint: checkpoint, updated_at_ms: Date.now() }
      persistTasks()
    },
    emit
  })
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
  if (!activeTask || !['waiting_for_connection', 'running', 'suspended'].includes(activeTask.state)) {
    return { ok: true, canceled: false }
  }
  if (taskConnectionTimer) clearTimeout(taskConnectionTimer)
  taskConnectionTimer = null
  activeTaskArgs = null
  bot?.pathfinder?.stop()
  bot?.clearControlStates()
  activeTask = { ...activeTask, state: 'canceled', finished_at_ms: Date.now() }
  recentTasks.set(activeTask.task_id, activeTask)
  while (recentTasks.size > 100) recentTasks.delete(recentTasks.keys().next().value)
  persistTasks()
  emit({ type: 'task_canceled', task: activeTask })
  reconcileSession()
  return { ok: true, canceled: true, task: activeTask }
}

function updateSession(input) {
  humanPlayerCount = parseInteger(input.human_player_count, 0, 0, 100000)
  const preferredUsername = validMinecraftUsername(input.preferred_username)
  if (preferredUsername && preferredUsername !== username) {
    const previous = username
    username = preferredUsername
    emit({
      type: 'agent_identity_updated', previous_username: previous,
      preferred_username: username, applies_after_reconnect: Boolean(bot)
    })
  }
  emit({
    type: 'session_presence', human_player_count: humanPlayerCount,
    session_policy: sessionPolicy, preferred_username: username
  })
  reconcileSession()
  return status()
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
    if (request.url === '/session') return send(response, 200, updateSession(body))
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
  clearIdleDisconnect()
  if (taskConnectionTimer) clearTimeout(taskConnectionTimer)
  taskConnectionTimer = null
  if (survivalTimer) clearInterval(survivalTimer)
  survivalTimer = null
  if (activeTask && ['waiting_for_connection', 'running'].includes(activeTask.state)) {
    if (resumeInterruptedNavigation && RESUMABLE_TYPES.has(activeTask.task_type)) {
      const suspended = { ...activeTask, state: 'suspended', suspended_at_ms: Date.now(), updated_at_ms: Date.now() }
      persistTasks(suspended, activeTaskArgs)
      emit({ type: 'task_suspended', task: taskStatus(suspended, false), reason: 'agent_process_stopping' })
      activeTask = null
      activeTaskArgs = null
    } else {
      finishTask(activeTask.run_id, false, '服务端停止导致任务中断')
    }
  }
  pendingSessionExit = { code: 'server_stopping', expected: true, detail: 'MineAstr Agent 控制进程正在停止' }
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
  emit({ type: 'ready', port: control.address().port, runtime_version: runtimeVersion })
  if (restoredNavigationTask && activeTask) {
    emit({ type: 'task_resumed', task: taskStatus(activeTask, false), checkpoint: activeTask.navigation_checkpoint || null })
  }
  reconcileSession()
})
