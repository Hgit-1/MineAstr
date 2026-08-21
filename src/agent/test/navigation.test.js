'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { navigateTo } = require('../navigation')

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

test('rejects pathfinder false-positive completion when the bot never moves', async () => {
  const bot = fakeBot({ x: 0, y: 64, z: 0 }, async () => {})
  await assert.rejects(
    navigateTo(bot, fakeGoals, { x: 100, y: 64, z: 0 }, { timeoutMilliseconds: 10_000 }),
    error => error.code === 'NAVIGATION_FAILED' && /连续无进展/.test(error.message) && /剩余=100/.test(error.message)
  )
  assert.equal(bot.pathfinder.calls, 2)
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

function fakeBot(position, goto) {
  return {
    entity: { position: { ...position } },
    pathfinder: {
      calls: 0,
      async goto(goal) {
        this.calls += 1
        return goto(goal)
      }
    }
  }
}
