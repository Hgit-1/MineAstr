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

async function navigateTo(bot, goals, target, options = {}) {
  if (!bot?.entity?.position || !bot?.pathfinder?.goto) throw new Error('Bot 尚未准备好寻路')
  const assertActive = typeof options.assertActive === 'function' ? options.assertActive : () => {}
  const emit = typeof options.emit === 'function' ? options.emit : () => {}
  const tolerance = boundedInteger(options.tolerance, 2, 1, 8)
  const segmentLength = boundedInteger(options.segmentLength, 24, 8, 48)
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
  const corridor = options.cache?.planChunkCorridor?.(bot.entity.position, target, options.dimension) || []
  let corridorIndex = 0

  while (true) {
    assertActive()
    const current = bot.entity.position
    while (corridorIndex < corridor.length - 1 && horizontalDistance(current, corridor[corridorIndex]) <= 5) corridorIndex += 1
    const stitchedTarget = corridor[corridorIndex] || target
    const remainingHorizontal = horizontalDistance(current, stitchedTarget)
    const atFinalCorridorPoint = corridorIndex >= corridor.length - 1
    const finalGoal = atFinalCorridorPoint && horizontalDistance(current, target) <= segmentLength
    const ratio = finalGoal ? 1 : segmentLength / remainingHorizontal
    const checkpoint = finalGoal ? target : remainingHorizontal <= segmentLength ? stitchedTarget : {
      x: current.x + (stitchedTarget.x - current.x) * ratio,
      y: current.y,
      z: current.z + (stitchedTarget.z - current.z) * ratio
    }
    const goal = finalGoal
      ? new goals.GoalNear(target.x, target.y, target.z, tolerance)
      : new goals.GoalNearXZ(checkpoint.x, checkpoint.z, 3)

    if (goalReached(goal, current)) {
      if (!finalGoal) continue
      return navigationResult(target, current, tolerance, attempts, startedAt)
    }
    if (Date.now() - startedAt > timeoutMilliseconds) {
      throw navigationError('寻路超时', target, current, attempts)
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
      remaining_distance: round(beforeTargetDistance)
    })

    let pathError = null
    try {
      await withDeadline(
        bot.pathfinder.goto(goal),
        Math.max(1, timeoutMilliseconds - (Date.now() - startedAt)),
        () => {
          if (typeof bot.pathfinder.setGoal === 'function') bot.pathfinder.setGoal(null)
          else bot.pathfinder.stop?.()
        }
      )
    } catch (error) {
      pathError = error
    }
    assertActive()

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
      if (remainingHorizontal <= segmentLength && corridorIndex < corridor.length - 1) corridorIndex += 1
      continue
    }

    const moved = spatialDistance(attemptStart, actual)
    const progress = beforeTargetDistance - spatialDistance(actual, target)
    if (moved >= 1 && progress >= 0.5) consecutiveStalls = 0
    else consecutiveStalls += 1

    emit({
      type: 'navigation_segment_incomplete',
      attempt: attempts,
      final_segment: finalGoal,
      position: vectorJson(actual),
      moved_distance: round(moved),
      target_progress: round(progress),
      pathfinder_error: pathError ? safeMessage(pathError) : null,
      consecutive_stalls: consecutiveStalls
    })

    if (consecutiveStalls >= 2) {
      const reason = pathError ? `寻路失败：${safeMessage(pathError)}` : '寻路未到达检查点且连续无进展'
      throw navigationError(reason, target, actual, attempts)
    }
  }
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

function navigationError(reason, target, actual, attempts) {
  const error = new Error(
    `${reason}；目标=(${round(target.x)}, ${round(target.y)}, ${round(target.z)})，` +
    `当前位置=(${round(actual.x)}, ${round(actual.y)}, ${round(actual.z)})，` +
    `剩余=${round(spatialDistance(actual, target))} 格，尝试段数=${attempts}`
  )
  error.code = 'NAVIGATION_FAILED'
  error.navigation = navigationResult(target, actual, null, attempts, Date.now())
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

function withDeadline(promise, milliseconds, onTimeout) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try { onTimeout?.() } catch (_) {}
      const error = new Error('寻路超时')
      error.code = 'NAVIGATION_TIMEOUT'
      reject(error)
    }, milliseconds)
    timer.unref?.()
    Promise.resolve(promise).then(
      value => {
        clearTimeout(timer)
        resolve(value)
      },
      error => {
        clearTimeout(timer)
        reject(error)
      }
    )
  })
}

module.exports = {
  defaultTimeoutMilliseconds,
  floorNode,
  goalReached,
  horizontalDistance,
  navigateTo,
  spatialDistance
}
