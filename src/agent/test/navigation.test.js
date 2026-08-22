'use strict'

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const test = require('node:test')
const {
  applyPathfinderCollisionCompatibility, localAvoidanceCost, navigateTo, obstaclePoint
} = require('../navigation')

class GoalNear {
  constructor(x, y, z, range) {
    this.x = Math.floor(x)
    this.y = Math.floor(y)
    this.z = Math.floor(z)
    this.rangeSq = range * range
  }

  isEnd(node) {
    return (node.x - this.x) ** 2 + (node.y - this.y) ** 2 + (node.z - this.z) ** 2 <= this.rangeSq
  }
}

class GoalNearXZ {
  constructor(x, z, range) {
    this.x = Math.floor(x)
    this.z = Math.floor(z)
    this.rangeSq = range * range
  }

  isEnd(node) {
    return (node.x - this.x) ** 2 + (node.z - this.z) ** 2 <= this.rangeSq
  }
}

const fakeGoals = { GoalNear, GoalNearXZ }

test('applies the upstream collision epsilon only to Minecraft 1.21 physics', () => {
  const current = { version: '1.21.1', physics: { playerHalfWidth: 0.3, playerHeight: 1.8 } }
  const result = applyPathfinderCollisionCompatibility(current)
  assert.equal(result.applied, true)
  assert.equal(current.physics.playerHalfWidth, 0.30001)
  assert.equal(current.physics.playerHeight, 1.80001)

  const older = { version: '1.20.1', physics: { playerHalfWidth: 0.3, playerHeight: 1.8 } }
  assert.equal(applyPathfinderCollisionCompatibility(older).applicable, false)
  assert.equal(older.physics.playerHalfWidth, 0.3)
})

test('rejects pathfinder false-positive completion when the bot never moves', async () => {
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, async () => {})
  await assert.rejects(
    navigateTo(bot, fakeGoals, { x: 100, y: 64, z: 0 }, { timeoutMilliseconds: 10_000 }),
    error => error.code === 'NAVIGATION_FAILED' && /连续无进展/.test(error.message) && /剩余=100/.test(error.message)
  )
  assert.equal(bot.pathfinder.calls, 4)
})

test('segments a long route and verifies the final three-dimensional goal', async () => {
  const bot = fakeBot({ x: 0, y: 70, z: 0 }, async goal => {
    if (goal instanceof GoalNearXZ) {
      bot.entity.position.x = goal.x
      bot.entity.position.z = goal.z
    } else {
      bot.entity.position.x = goal.x
      bot.entity.position.y = goal.y
      bot.entity.position.z = goal.z
    }
  })
  const result = await navigateTo(bot, fakeGoals, { x: 100, y: 80, z: 0 }, { timeoutMilliseconds: 10_000 })
  assert.equal(result.actual.x, 100)
  assert.equal(result.actual.y, 80)
  assert.equal(result.remaining_distance, 0)
  assert.ok(result.path_segments >= 5)
})

test('replans after a partial pathfinder resolve while measurable progress continues', async () => {
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, async goal => {
    const targetX = goal.x
    bot.entity.position.x += Math.sign(targetX - bot.entity.position.x) * Math.min(8, Math.abs(targetX - bot.entity.position.x))
  })
  const result = await navigateTo(bot, fakeGoals, { x: 40, y: 64, z: 0 }, { timeoutMilliseconds: 10_000 })
  assert.ok(result.remaining_distance <= 2)
  assert.ok(bot.pathfinder.calls > 2)
})

test('watchdog cancels a local pathfinder call that never settles', async () => {
  const events = []
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, () => {
    bot.emit('path_update', {
      status: 'success', visitedNodes: 9, generatedNodes: 14,
      path: [{ x: 1, y: 64, z: 0, toBreak: [{ x: 1, y: 64, z: 0 }], toPlace: [] }]
    })
    bot.emit('path_reset', 'stuck')
    return new Promise(() => {})
  })
  bot.entity.onGround = false
  bot.entity.velocity = { x: 0, y: 0, z: 0 }
  bot.blockAt = () => ({ name: 'oak_log' })
  const startedAt = Date.now()
  await assert.rejects(
    navigateTo(bot, fakeGoals, { x: 20, y: 64, z: 0 }, {
      timeoutMilliseconds: 10_000,
      stallTimeoutMilliseconds: 100,
      actionStallTimeoutMilliseconds: 300,
      segmentTimeoutMilliseconds: 1_000,
      watchdogIntervalMilliseconds: 25,
      emit: event => events.push(event)
    }),
    error => error.code === 'NAVIGATION_FAILED' && /局部寻路长时间没有产生实际位移/.test(error.message)
  )
  assert.ok(Date.now() - startedAt < 2_000)
  assert.equal(bot.pathfinder.calls, 4)
  assert.equal(bot.pathfinder.cancellations, 4)
  assert.deepEqual(
    events.filter(event => event.type === 'navigation_segment_started').map(event => event.recovery_offset),
    [0, 3, -3, 5]
  )
  assert.equal(events.filter(event => event.type === 'navigation_watchdog_triggered').length, 4)
  const diagnostics = events.find(event => event.type === 'navigation_watchdog_triggered').diagnostics
  assert.equal(diagnostics.on_ground, false)
  assert.equal(diagnostics.last_path_reset, 'stuck')
  assert.equal(diagnostics.last_path_update.first_action.to_break[0].name, 'oak_log')
  assert.deepEqual(
    events.find(event => event.type === 'navigation_segment_incomplete').avoidance_point,
    { x: 1, y: 64, z: 0 }
  )
  assert.equal(bot.pathfinder.movements.exclusionAreasStep.length, 0)
})

test('marks the block ahead of a stall as a temporary path cost', () => {
  const point = obstaclePoint({ x: 10, y: 64, z: 10 }, { x: 20, y: 64, z: 10 })
  assert.deepEqual(point, { x: 11, y: 64, z: 10 })
  assert.equal(localAvoidanceCost({ position: { x: 11, y: 64, z: 10 } }, [point]), 32)
  assert.equal(localAvoidanceCost({ position: { x: 14, y: 64, z: 10 } }, [point]), 0)
})

test('uses a lateral recovery checkpoint after a stuck segment', async () => {
  const events = []
  let call = 0
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, goal => {
    call += 1
    if (call === 1) return new Promise(() => {})
    bot.entity.position.x = goal.x
    bot.entity.position.z = goal.z
    if (goal instanceof GoalNear) bot.entity.position.y = goal.y
  })
  const result = await navigateTo(bot, fakeGoals, { x: 20, y: 64, z: 0 }, {
    timeoutMilliseconds: 10_000,
    stallTimeoutMilliseconds: 100,
    actionStallTimeoutMilliseconds: 300,
    segmentTimeoutMilliseconds: 1_000,
    watchdogIntervalMilliseconds: 25,
    emit: event => events.push(event)
  })
  assert.equal(result.remaining_distance, 0)
  assert.ok(bot.pathfinder.cancellations >= 1)
  const recovery = events.find(event => event.type === 'navigation_segment_started' && event.recovery_offset === 3)
  assert.deepEqual(recovery.checkpoint, { x: 4, y: 64, z: 3 })
})

test('does not treat steady slow movement as a stall', async () => {
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, goal => new Promise(resolve => {
    const timer = setInterval(() => {
      bot.entity.position.x = Math.min(goal.x, bot.entity.position.x + 0.5)
      if (bot.entity.position.x >= goal.x) {
        clearInterval(timer)
        resolve()
      }
    }, 30)
  }))
  const result = await navigateTo(bot, fakeGoals, { x: 6, y: 64, z: 0 }, {
    timeoutMilliseconds: 10_000,
    stallTimeoutMilliseconds: 100,
    segmentTimeoutMilliseconds: 1_000,
    watchdogIntervalMilliseconds: 25,
    tolerance: 1
  })
  assert.equal(result.remaining_distance, 0)
  assert.equal(bot.pathfinder.cancellations, 0)
})

test('grants a longer no-movement budget while pathfinder is mining', async () => {
  let mining = true
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, goal => new Promise(resolve => {
    setTimeout(() => {
      bot.entity.position.x = goal.x
      bot.entity.position.y = goal.y
      bot.entity.position.z = goal.z
      mining = false
      resolve()
    }, 180)
  }))
  bot.pathfinder.isMining = () => mining
  const result = await navigateTo(bot, fakeGoals, { x: 6, y: 64, z: 0 }, {
    timeoutMilliseconds: 10_000,
    stallTimeoutMilliseconds: 100,
    actionStallTimeoutMilliseconds: 300,
    segmentTimeoutMilliseconds: 1_000,
    watchdogIntervalMilliseconds: 25,
    tolerance: 1
  })
  assert.equal(result.remaining_distance, 0)
  assert.equal(bot.pathfinder.cancellations, 0)
})

test('cancels an in-flight local path as soon as the task is canceled', async () => {
  let active = true
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, () => new Promise(() => {}))
  setTimeout(() => { active = false }, 60)
  await assert.rejects(
    navigateTo(bot, fakeGoals, { x: 20, y: 64, z: 0 }, {
      timeoutMilliseconds: 10_000,
      stallTimeoutMilliseconds: 1_000,
      segmentTimeoutMilliseconds: 2_000,
      watchdogIntervalMilliseconds: 25,
      assertActive() {
        if (!active) {
          const error = new Error('任务已取消')
          error.code = 'TASK_CANCELED'
          throw error
        }
      }
    }),
    error => error.code === 'TASK_CANCELED'
  )
  assert.equal(bot.pathfinder.cancellations, 1)
})

function fakeBot(position, goto) {
  return Object.assign(new EventEmitter(), {
    entity: { position: { ...position } },
    pathfinder: {
      calls: 0,
      cancellations: 0,
      movements: { exclusionAreasStep: [] },
      async goto(goal) {
        this.calls += 1
        return goto(goal)
      },
      setGoal(goal) {
        if (goal === null) this.cancellations += 1
      },
      isMining() {
        return false
      },
      isBuilding() {
        return false
      }
    },
    clearControlStates() {}
  })
}
