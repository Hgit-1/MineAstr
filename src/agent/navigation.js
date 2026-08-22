'use strict'

function floorNode(position) {
  return {
    x: Math.floor(Number(position.x)),
    y: Math.floor(Number(position.y)),
    z: Math.floor(Number(position.z))
  }
}

function horizontalDistance(left, right) {
  return Math.hypot(Number(left.x) - Number(right.x), Number(left.z) - Number(right.z))
}

function spatialDistance(left, right) {
  return Math.hypot(
    Number(left.x) - Number(right.x),
    Number(left.y) - Number(right.y),
    Number(left.z) - Number(right.z)
  )
}

function goalReached(goal, position) {
  return Boolean(position && goal?.isEnd(floorNode(position)))
}

function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}

function defaultTimeoutMilliseconds(distance) {
  // Budget for terrain detours and repeated chunk loading, capped so a bad goal
  // cannot occupy the Agent indefinitely.
  return Math.max(120_000, Math.min(900_000, Math.ceil(distance / 2) * 1000))
}

function applyPathfinderCollisionCompatibility(bot, minecraftVersion = bot?.version) {
  const version = String(minecraftVersion || '')
  const physics = bot?.physics
  const applicable = /^1\.21(?:\.|$)/.test(version) && physics != null
  if (!applicable) return { applicable: false, applied: false, version }

  let applied = false
  if (Math.abs(Number(physics.playerHalfWidth) - 0.3) < 1e-9) {
    physics.playerHalfWidth = 0.30001
    applied = true
  }
  if (Math.abs(Number(physics.playerHeight) - 1.8) < 1e-9) {
    physics.playerHeight = 1.80001
    applied = true
  }
  return {
    applicable: true,
    applied,
    version,
    player_half_width: physics.playerHalfWidth,
    player_height: physics.playerHeight
  }
}

async function navigateTo(bot, goals, target, options = {}) {
  if (!bot?.entity?.position || !bot?.pathfinder?.goto) throw new Error('Bot 尚未准备好寻路')
  const assertActive = typeof options.assertActive === 'function' ? options.assertActive : () => {}
  const emit = typeof options.emit === 'function' ? options.emit : () => {}
  const tolerance = boundedInteger(options.tolerance, 2, 1, 8)
  const segmentLength = boundedInteger(options.segmentLength, 24, 8, 48)
  const stallTimeoutMilliseconds = boundedInteger(options.stallTimeoutMilliseconds, 8_000, 100, 60_000)
  const actionStallTimeoutMilliseconds = boundedInteger(
    options.actionStallTimeoutMilliseconds, 30_000, stallTimeoutMilliseconds, 120_000
  )
  const segmentTimeoutMilliseconds = boundedInteger(options.segmentTimeoutMilliseconds, 45_000, 1_000, 120_000)
  const watchdogIntervalMilliseconds = boundedInteger(options.watchdogIntervalMilliseconds, 500, 25, 5_000)
  const unstuckMovementMilliseconds = boundedInteger(options.unstuckMovementMilliseconds, 900, 100, 2_000)
  const startedAt = Date.now()
  const initialDistance = horizontalDistance(bot.entity.position, target)
  const timeoutMilliseconds = boundedInteger(
    options.timeoutMilliseconds,
    defaultTimeoutMilliseconds(initialDistance),
    10_000,
    900_000
  )
  let attempts = 0
  let consecutiveStalls = 0
  let recoveryOffset = 0
  const recoveryOffsets = [3, -3, 5]
  let globalRoute = planGlobalRoute(bot.entity.position, target, options)
  let corridor = globalRoute.points
  let routeBackend = globalRoute.backend
  let corridorIndex = 0
  let globalReroutes = 0
  const localAvoidanceZones = []
  const removeLocalAvoidance = installLocalAvoidance(bot, localAvoidanceZones)

  emit({
    type: 'navigation_route_planned',
    backend: routeBackend,
    route_points: corridor.length,
    road_distance: globalRoute.road_distance || null,
    connector_distance: globalRoute.connector_distance || null,
    global_reroutes: globalReroutes
  })

  try {
    while (true) {
      assertActive()
      const current = bot.entity.position
      while (corridorIndex < corridor.length - 1 && horizontalDistance(current, corridor[corridorIndex]) <= 5) corridorIndex += 1
      const stitchedTarget = corridor[corridorIndex] || target
      if (routeBackend === 'roadweaver-hybrid' && standPositionState(bot, stitchedTarget) === 'invalid') {
        options.roadNetwork?.invalidateNear?.(stitchedTarget)
        globalReroutes += 1
        globalRoute = planGlobalRoute(current, target, globalReroutes <= 3 ? options : { ...options, roadNetwork: null })
        corridor = globalRoute.points
        routeBackend = globalRoute.backend
        corridorIndex = 0
        emit({
          type: 'navigation_road_segment_invalid',
          point: vectorJson(stitchedTarget),
          fallback_backend: routeBackend,
          global_reroutes: globalReroutes
        })
        continue
      }
      const remainingHorizontal = horizontalDistance(current, stitchedTarget)
      const atFinalCorridorPoint = corridorIndex >= corridor.length - 1
      let finalGoal = atFinalCorridorPoint && horizontalDistance(current, target) <= segmentLength
      const ratio = finalGoal || remainingHorizontal === 0 ? 1 : segmentLength / remainingHorizontal
      let checkpoint = finalGoal ? target : remainingHorizontal <= segmentLength ? stitchedTarget : {
        x: current.x + (stitchedTarget.x - current.x) * ratio,
        y: current.y,
        z: current.z + (stitchedTarget.z - current.z) * ratio
      }
      const recoverySegment = recoveryOffset !== 0
      if (recoverySegment) {
        checkpoint = findEscapeCheckpoint(bot, current, stitchedTarget, recoveryOffset, options.roadNetwork)
        finalGoal = false
      }
      const goal = finalGoal
        ? new goals.GoalNear(target.x, target.y, target.z, tolerance)
        : recoverySegment
          ? new goals.GoalNear(checkpoint.x, checkpoint.y, checkpoint.z, 1)
          : new goals.GoalNearXZ(checkpoint.x, checkpoint.z, 3)

      if (goalReached(goal, current)) {
        if (!finalGoal) continue
        return navigationResult(target, current, tolerance, attempts, startedAt)
      }
      if (Date.now() - startedAt > timeoutMilliseconds) {
        throw navigationError('寻路超时', target, current, attempts, startedAt)
      }

      attempts += 1
      const attemptStart = { x: current.x, y: current.y, z: current.z }
      const beforeTargetDistance = spatialDistance(current, target)
      emit({
        type: 'navigation_segment_started',
        attempt: attempts,
        final_segment: finalGoal,
        target: vectorJson(target),
        checkpoint: vectorJson(checkpoint),
        stitched_corridor_points: corridor.length,
        corridor_index: corridorIndex,
        recovery_offset: recoverySegment ? recoveryOffset : 0,
        remaining_distance: round(beforeTargetDistance)
      })

      let pathError = null
      try {
        await runPathfinderSegment(bot, goal, {
          deadlineMilliseconds: Math.max(
            1,
            Math.min(segmentTimeoutMilliseconds, timeoutMilliseconds - (Date.now() - startedAt))
          ),
          stallTimeoutMilliseconds,
          actionStallTimeoutMilliseconds,
          watchdogIntervalMilliseconds,
          assertActive,
          emit,
          attempt: attempts
        })
      } catch (error) {
        pathError = error
      }
      assertActive()

      let physicalRecovery = null
      let stalledAvoidancePoint = null
      if (pathError?.code === 'NAVIGATION_STALLED') {
        const stalledAt = { ...bot.entity.position }
        stalledAvoidancePoint = obstaclePoint(stalledAt, checkpoint)
        addAvoidanceZone(localAvoidanceZones, stalledAvoidancePoint)
        physicalRecovery = await performPhysicalUnstuck(bot, stitchedTarget, {
          durationMilliseconds: unstuckMovementMilliseconds,
          lateralOffset: recoveryOffset || (attempts % 2 === 0 ? -3 : 3),
          assertActive,
          emit,
          attempt: attempts,
          roadNetwork: options.roadNetwork,
          isForbidden: options.isForbidden
        })
        assertActive()
      }

      const actual = bot.entity.position
      if (goalReached(goal, actual)) {
        consecutiveStalls = 0
        emit({
          type: 'navigation_segment_finished',
          attempt: attempts,
          final_segment: finalGoal,
          position: vectorJson(actual),
          remaining_distance: round(spatialDistance(actual, target))
        })
        if (finalGoal) return navigationResult(target, actual, tolerance, attempts, startedAt)
        options.onCheckpoint?.({
          target: vectorJson(target), position: vectorJson(actual), route_backend: routeBackend,
          corridor_index: corridorIndex, route_points: corridor.length, global_reroutes: globalReroutes
        })
        if (recoverySegment) recoveryOffset = 0
        if (!recoverySegment && remainingHorizontal <= segmentLength && corridorIndex < corridor.length - 1) corridorIndex += 1
        continue
      }

      const moved = spatialDistance(attemptStart, actual)
      const progress = beforeTargetDistance - spatialDistance(actual, target)
      let avoidancePoint = null
      if (moved >= 1 && progress >= 0.5) {
        consecutiveStalls = 0
        recoveryOffset = 0
      } else {
        consecutiveStalls += 1
        recoveryOffset = recoveryOffsets[Math.min(consecutiveStalls - 1, recoveryOffsets.length - 1)]
        avoidancePoint = stalledAvoidancePoint || obstaclePoint(actual, checkpoint)
        addAvoidanceZone(localAvoidanceZones, avoidancePoint)
      }

      emit({
        type: 'navigation_segment_incomplete',
        attempt: attempts,
        final_segment: finalGoal,
        position: vectorJson(actual),
        moved_distance: round(moved),
        target_progress: round(progress),
        pathfinder_error: pathError ? safeMessage(pathError) : null,
        physical_recovery: physicalRecovery,
        consecutive_stalls: consecutiveStalls,
        avoidance_point: avoidancePoint ? vectorJson(avoidancePoint) : null
      })

      if (consecutiveStalls > recoveryOffsets.length && routeBackend === 'roadweaver-hybrid' && globalReroutes < 3) {
        globalReroutes += 1
        options.roadNetwork?.invalidateNear?.(actual)
        globalRoute = planGlobalRoute(actual, target, options)
        corridor = globalRoute.points
        routeBackend = globalRoute.backend
        corridorIndex = 0
        consecutiveStalls = 0
        recoveryOffset = 0
        emit({
          type: 'navigation_global_replanned',
          backend: routeBackend,
          route_points: corridor.length,
          global_reroutes: globalReroutes,
          failed_position: vectorJson(actual)
        })
        continue
      }

      if (consecutiveStalls > recoveryOffsets.length) {
        const reason = pathError ? `寻路失败：${safeMessage(pathError)}` : '寻路未到达检查点且连续无进展'
        throw navigationError(reason, target, actual, attempts, startedAt)
      }
    }
  } finally {
    removeLocalAvoidance()
  }
}

function addAvoidanceZone(zones, point) {
  if (!point || zones.some(existing => spatialDistance(existing, point) < 0.75)) return false
  zones.push(point)
  while (zones.length > 64) zones.shift()
  return true
}

async function performPhysicalUnstuck(bot, target, options = {}) {
  if (!bot?.entity?.position || typeof bot.setControlState !== 'function'
      || typeof bot.clearControlStates !== 'function') return { attempted: false, reason: 'controls_unavailable' }
  const start = { ...bot.entity.position }
  const escape = findEscapeCheckpoint(
    bot, start, target, Number(options.lateralOffset) || 3, options.roadNetwork
  )
  if (typeof options.isForbidden === 'function') {
    const midpoint = {
      x: (Number(start.x) + Number(escape.x)) / 2,
      y: (Number(start.y) + Number(escape.y)) / 2,
      z: (Number(start.z) + Number(escape.z)) / 2
    }
    if (options.isForbidden(midpoint) || options.isForbidden(escape)) {
      return { attempted: false, reason: 'forbidden_direction' }
    }
  }
  const dx = Number(escape.x) - Number(start.x)
  const dz = Number(escape.z) - Number(start.z)
  if (!Number.isFinite(dx) || !Number.isFinite(dz) || Math.hypot(dx, dz) < 0.5) {
    return { attempted: false, reason: 'no_safe_direction' }
  }
  const durationMilliseconds = boundedInteger(options.durationMilliseconds, 900, 100, 2_000)
  try {
    options.assertActive?.()
    if (typeof bot.look === 'function') {
      const yaw = Math.atan2(-dx, -dz)
      await bot.look(yaw, 0, true)
    }
    bot.setControlState('forward', true)
    bot.setControlState('jump', true)
    bot.setControlState('sprint', true)
    const deadline = Date.now() + durationMilliseconds
    while (Date.now() < deadline) {
      options.assertActive?.()
      await new Promise(resolve => setTimeout(resolve, Math.min(100, deadline - Date.now())))
    }
  } finally {
    bot.clearControlStates()
  }
  const moved = spatialDistance(start, bot.entity.position)
  const result = {
    attempted: true,
    moved_distance: round(moved),
    start: vectorJson(start),
    target: vectorJson(escape),
    position: vectorJson(bot.entity.position)
  }
  try {
    options.emit?.({ type: 'navigation_physical_unstuck', attempt: options.attempt, ...result })
  } catch (_) {}
  return result
}

function planGlobalRoute(start, target, options) {
  const road = options.roadNetwork?.plan?.(start, target, options.dimension)
  if (road?.points?.length > 1) return road
  const terrain = options.cache?.planChunkCorridor?.(start, target, options.dimension) || []
  return { backend: terrain.length ? 'chunk-corridor' : 'direct-local-a-star', points: terrain }
}

function obstaclePoint(current, target) {
  const dx = Number(target.x) - Number(current.x)
  const dz = Number(target.z) - Number(current.z)
  const length = Math.hypot(dx, dz) || 1
  return {
    x: Number(current.x) + dx / length,
    y: Number(current.y),
    z: Number(current.z) + dz / length
  }
}

function localAvoidanceCost(block, zones) {
  if (!block?.position || !Array.isArray(zones)) return 0
  return zones.some(zone =>
    Math.abs(Number(block.position.y) - Number(zone.y)) <= 2 &&
    horizontalDistance(block.position, zone) <= 1.75
  ) ? 32 : 0
}

function installLocalAvoidance(bot, zones) {
  const exclusions = bot?.pathfinder?.movements?.exclusionAreasStep
  if (!Array.isArray(exclusions)) return () => {}
  const avoidance = block => localAvoidanceCost(block, zones)
  exclusions.push(avoidance)
  return () => {
    const index = exclusions.indexOf(avoidance)
    if (index >= 0) exclusions.splice(index, 1)
  }
}

function recoveryCheckpoint(current, target, lateralOffset) {
  const dx = Number(target.x) - Number(current.x)
  const dz = Number(target.z) - Number(current.z)
  const length = Math.hypot(dx, dz) || 1
  const forward = Math.min(4, length)
  const unitX = dx / length
  const unitZ = dz / length
  return {
    x: Number(current.x) + unitX * forward - unitZ * lateralOffset,
    y: Number(current.y),
    z: Number(current.z) + unitZ * forward + unitX * lateralOffset
  }
}

function findEscapeCheckpoint(bot, current, target, lateralOffset, roadNetwork = null) {
  if (typeof bot?.blockAt !== 'function') return recoveryCheckpoint(current, target, lateralOffset)
  const originY = Math.floor(Number(current.y))
  const desired = recoveryCheckpoint(current, target, lateralOffset)
  const candidates = []
  for (let radius = 3; radius <= 12; radius += 3) {
    for (let step = 0; step < 16; step++) {
      const angle = (Math.PI * 2 * step) / 16
      const x = Math.floor(Number(current.x) + Math.cos(angle) * radius)
      const z = Math.floor(Number(current.z) + Math.sin(angle) * radius)
      for (let y = originY + 3; y >= originY - 6; y--) {
        const point = { x, y, z }
        if (!isSafeStandPosition(bot, point)) continue
        const targetGain = spatialDistance(current, target) - spatialDistance(point, target)
        const desiredDistance = spatialDistance(point, desired)
        const verticalPenalty = Math.abs(y - originY) * 1.5
        const roadDistance = Math.min(32, Number(roadNetwork?.distanceToRoad?.(point) ?? 32))
        const support = safeBlockAt(bot, { x, y: y - 1, z })
        const leafPenalty = /leaves/i.test(String(support?.name || '')) ? 6 : 0
        candidates.push({ point, score: targetGain * 2 - desiredDistance - verticalPenalty - roadDistance * 0.15 - leafPenalty })
        break
      }
    }
    if (candidates.length >= 4) break
  }
  candidates.sort((left, right) => right.score - left.score)
  return candidates[0]?.point || recoveryCheckpoint(current, target, lateralOffset)
}

function isSafeStandPosition(bot, point) {
  return standPositionState(bot, point) === 'valid'
}

function standPositionState(bot, point) {
  const support = safeBlockAt(bot, { x: point.x, y: point.y - 1, z: point.z })
  const feet = safeBlockAt(bot, point)
  const head = safeBlockAt(bot, { x: point.x, y: point.y + 1, z: point.z })
  if (!support || !feet || !head) return 'unknown'
  const supportName = String(support.name || '')
  if (/(?:lava|fire|magma_block|cactus|sweet_berry_bush|powder_snow)/i.test(supportName)) return 'invalid'
  if (/(?:slab|carpet|snow)/i.test(String(feet.name || '')) && feet.boundingBox !== 'empty' && passable(head)) return 'valid'
  return support.boundingBox !== 'empty' && passable(feet) && passable(head) ? 'valid' : 'invalid'
}

function passable(block) {
  const name = String(block?.name || '')
  return block?.boundingBox === 'empty' && !/(?:lava|fire|cobweb|sweet_berry_bush|powder_snow)/i.test(name)
}

function safeBlockAt(bot, position) {
  try { return bot.blockAt(position, false) } catch (_) { return null }
}

function runPathfinderSegment(bot, goal, options) {
  const startedAt = Date.now()
  let lastProgressAt = startedAt
  let blockActionStartedAt = null
  const segmentStartPosition = { ...bot.entity.position }
  const initialGoalDistance = distanceToGoal(goal, segmentStartPosition)
  let lastPosition = { ...bot.entity.position }
  let settled = false
  let interval = null
  let deadline = null
  let lastPathUpdate = null
  let lastPathReset = null
  let earlyResolve = null
  let incompletePlan = null

  return new Promise((resolve, reject) => {
    const onPathUpdate = results => { lastPathUpdate = summarizePathUpdate(bot, results) }
    const onPathReset = reason => { lastPathReset = String(reason || 'unknown').slice(0, 80) }
    const onGoalReached = reachedGoal => {
      if (reachedGoal === goal || goalReached(goal, bot.entity.position)) {
        finish(null, { elapsed_ms: Date.now() - startedAt, early_resolve: earlyResolve })
      }
    }
    bot.on?.('path_update', onPathUpdate)
    bot.on?.('path_reset', onPathReset)
    bot.on?.('goal_reached', onGoalReached)

    const finish = (error, value) => {
      if (settled) return
      settled = true
      if (interval) clearInterval(interval)
      if (deadline) clearTimeout(deadline)
      bot.removeListener?.('path_update', onPathUpdate)
      bot.removeListener?.('path_reset', onPathReset)
      bot.removeListener?.('goal_reached', onGoalReached)
      if (error) reject(error)
      else resolve(value)
    }
    const stopWith = (code, message, extra = {}) => {
      const diagnostics = pathfinderDiagnostics(bot, lastPathUpdate, lastPathReset, earlyResolve, incompletePlan)
      cancelPathfinder(bot)
      const error = new Error(message)
      error.code = code
      Object.assign(error, extra, { diagnostics })
      try {
        options.emit({
          type: 'navigation_watchdog_triggered',
          attempt: options.attempt,
          code,
          position: vectorJson(bot.entity.position),
          ...extra,
          diagnostics
        })
      } catch (_) {}
      finish(error)
    }

    let pathPromise
    try {
      pathPromise = bot.pathfinder.goto(goal)
    } catch (error) {
      finish(error)
      return
    }
    Promise.resolve(pathPromise).then(value => {
      if (goalReached(goal, bot.entity.position)) {
        finish(null, value)
        return
      }
      const goalProgress = initialGoalDistance - distanceToGoal(goal, bot.entity.position)
      if (horizontalDistance(segmentStartPosition, bot.entity.position) >= 1 && goalProgress >= 0.5) {
        finish(null, value)
        return
      }
      // mineflayer-pathfinder 2.4.5 resolves goto() when a path_update contains
      // an empty path, even when the goal has not been reached. This is common
      // just after login while the surrounding chunks are still arriving. Keep
      // the goal alive so chunk_loaded can trigger a new plan, and let the real
      // movement watchdog decide whether this segment is genuinely stalled.
      earlyResolve = {
        elapsed_ms: Date.now() - startedAt,
        position: vectorJson(bot.entity.position),
        path_update: lastPathUpdate
      }
      try {
        options.emit({
          type: 'navigation_pathfinder_early_resolve',
          attempt: options.attempt,
          ...earlyResolve
        })
      } catch (_) {}
    }, error => {
      if (['NoPath', 'Timeout'].includes(String(error?.name)) && !goalReached(goal, bot.entity.position)) {
        // goto() rejects as soon as the initial A* result is incomplete, but
        // mineflayer-pathfinder assigns its best partial path immediately after
        // emitting path_update. Do not replace the goal before that path can be
        // followed to the loaded-chunk boundary; chunk_loaded will then replan.
        incompletePlan = {
          reason: String(error.name),
          elapsed_ms: Date.now() - startedAt,
          position: vectorJson(bot.entity.position),
          path_update: lastPathUpdate
        }
        try {
          options.emit({
            type: 'navigation_pathfinder_incomplete_plan',
            attempt: options.attempt,
            ...incompletePlan
          })
        } catch (_) {}
        return
      }
      finish(error)
    })

    interval = setInterval(() => {
      if (settled) return
      try {
        options.assertActive()
      } catch (error) {
        cancelPathfinder(bot)
        finish(error)
        return
      }
      const now = Date.now()
      const current = bot.entity.position
      if (spatialDistance(lastPosition, current) >= 0.2) {
        lastPosition = { ...current }
        lastProgressAt = now
      }
      const activeBlockAction = Boolean(bot.pathfinder.isMining?.() || bot.pathfinder.isBuilding?.())
      if (activeBlockAction && blockActionStartedAt == null) blockActionStartedAt = now
      if (!activeBlockAction) blockActionStartedAt = null
      const allowedIdle = activeBlockAction
        ? options.actionStallTimeoutMilliseconds
        : options.stallTimeoutMilliseconds
      const idleReferenceAt = activeBlockAction
        ? Math.max(lastProgressAt, blockActionStartedAt)
        : lastProgressAt
      if (now - idleReferenceAt >= allowedIdle) {
        stopWith('NAVIGATION_STALLED', '局部寻路长时间没有产生实际位移', {
          inactive_ms: now - idleReferenceAt,
          block_action_active: activeBlockAction
        })
      }
    }, options.watchdogIntervalMilliseconds)
    interval.unref?.()

    deadline = setTimeout(() => {
      stopWith('NAVIGATION_SEGMENT_TIMEOUT', '局部寻路超过单段时间预算', {
        elapsed_ms: Date.now() - startedAt
      })
    }, options.deadlineMilliseconds)
    deadline.unref?.()
  })
}

function distanceToGoal(goal, position) {
  if (!position || !Number.isFinite(Number(goal?.x)) || !Number.isFinite(Number(goal?.z))) return Infinity
  const dx = Number(goal.x) - Number(position.x)
  const dz = Number(goal.z) - Number(position.z)
  if (!Number.isFinite(Number(goal?.y))) return Math.hypot(dx, dz)
  return Math.hypot(dx, Number(goal.y) - Number(position.y), dz)
}

function summarizePathUpdate(bot, results) {
  const path = Array.isArray(results?.path) ? results.path : []
  const action = path.find(node => node?.toBreak?.length || node?.toPlace?.length)
  return {
    status: String(results?.status || 'unknown').slice(0, 40),
    path_length: path.length,
    visited_nodes: finiteOrNull(results?.visitedNodes),
    generated_nodes: finiteOrNull(results?.generatedNodes),
    first_action: action ? {
      position: vectorJson(action),
      to_break: (action.toBreak || []).slice(0, 3).map(position => blockDiagnostic(bot, position)),
      to_place_count: Array.isArray(action.toPlace) ? action.toPlace.length : 0
    } : null
  }
}

function pathfinderDiagnostics(bot, lastPathUpdate, lastPathReset, earlyResolve = null, incompletePlan = null) {
  return {
    on_ground: Boolean(bot?.entity?.onGround),
    is_in_water: Boolean(bot?.entity?.isInWater),
    velocity: bot?.entity?.velocity ? vectorJson(bot.entity.velocity) : null,
    moving: Boolean(bot?.pathfinder?.isMoving?.()),
    mining: Boolean(bot?.pathfinder?.isMining?.()),
    building: Boolean(bot?.pathfinder?.isBuilding?.()),
    controls: {
      forward: Boolean(bot?.controlState?.forward),
      jump: Boolean(bot?.controlState?.jump),
      sprint: Boolean(bot?.controlState?.sprint)
    },
    last_path_reset: lastPathReset,
    last_path_update: lastPathUpdate,
    early_resolve: earlyResolve,
    incomplete_plan: incompletePlan
  }
}

function blockDiagnostic(bot, position) {
  let name = null
  try { name = bot?.blockAt?.(position, false)?.name || null } catch (_) {}
  return { position: vectorJson(position), name: name ? String(name).slice(0, 100) : null }
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? Number(value) : null
}

function cancelPathfinder(bot) {
  try {
    if (typeof bot.pathfinder.setGoal === 'function') bot.pathfinder.setGoal(null)
    else bot.pathfinder.stop?.()
  } catch (_) {
    try { bot.pathfinder.stop?.() } catch (_) {}
  }
  try { bot.clearControlStates?.() } catch (_) {}
}

function navigationResult(target, actual, tolerance, attempts, startedAt) {
  return {
    target: vectorJson(target),
    actual: vectorJson(actual),
    remaining_distance: round(spatialDistance(actual, target)),
    tolerance,
    path_segments: attempts,
    elapsed_ms: Date.now() - startedAt
  }
}

function navigationError(reason, target, actual, attempts, startedAt = Date.now()) {
  const error = new Error(
    `${reason}；目标=(${round(target.x)}, ${round(target.y)}, ${round(target.z)})，` +
    `当前位置=(${round(actual.x)}, ${round(actual.y)}, ${round(actual.z)})，` +
    `剩余=${round(spatialDistance(actual, target))} 格，尝试段数=${attempts}`
  )
  error.code = 'NAVIGATION_FAILED'
  error.navigation = navigationResult(target, actual, null, attempts, startedAt)
  return error
}

function vectorJson(value) {
  return { x: round(value.x), y: round(value.y), z: round(value.z) }
}

function round(value) {
  return Math.round(Number(value) * 100) / 100
}

function safeMessage(error) {
  return String(error?.message || error || 'unknown error').replace(/[\r\n\t]+/g, ' ').slice(0, 300)
}

module.exports = {
  applyPathfinderCollisionCompatibility,
  defaultTimeoutMilliseconds,
  floorNode,
  goalReached,
  horizontalDistance,
  localAvoidanceCost,
  navigateTo,
  obstaclePoint,
  performPhysicalUnstuck,
  recoveryCheckpoint,
  findEscapeCheckpoint,
  isSafeStandPosition,
  runPathfinderSegment,
  spatialDistance
}
