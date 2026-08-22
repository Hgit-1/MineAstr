'use strict'

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const test = require('node:test')
const { Vec3 } = require('vec3')
const minecraftData = require('minecraft-data')('1.21.1')
const Block = require('prismarine-block')(minecraftData)
const { goals, Movements, pathfinder } = require('mineflayer-pathfinder')
const { applyNavigationPolicy } = require('../navigation-policy')

function stateId(properties) {
  const definition = minecraftData.blocksByName.oak_door
  for (let id = definition.minStateId; id <= definition.maxStateId; id++) {
    const block = Block.fromStateId(id, 0)
    const actual = block.getProperties()
    if (Object.entries(properties).every(([name, value]) => actual[name] === value)) return id
  }
  throw new Error(`oak door state not found: ${JSON.stringify(properties)}`)
}

function blockAtState(id, position) {
  const block = Block.fromStateId(id, 0)
  block.position = { x: position.x, y: position.y, z: position.z }
  return block
}

function doorBot(open = false, diagonal = false) {
  const air = minecraftData.blocksByName.air.defaultState
  const stone = minecraftData.blocksByName.stone.defaultState
  const lower = stateId({ half: 'lower', open, powered: false, facing: 'east', hinge: 'left' })
  const upper = stateId({ half: 'upper', open, powered: false, facing: 'east', hinge: 'left' })
  const doorX = 1
  const doorZ = diagonal ? 1 : 0
  return {
    registry: minecraftData,
    inventory: { items: () => [] },
    entities: {},
    entity: { effects: {} },
    pathfinder: { bestHarvestTool: () => null },
    blockAt(position) {
      if (position.x === doorX && position.z === doorZ && position.y === 64) return blockAtState(lower, position)
      if (position.x === doorX && position.z === doorZ && position.y === 65) return blockAtState(upper, position)
      if (position.y === 63) return blockAtState(stone, position)
      return blockAtState(air, position)
    }
  }
}

function movements(bot) {
  return applyNavigationPolicy(new Movements(bot), bot, {
    allowDigging: true,
    allowPlacing: true,
    digCost: 12,
    structureBreakCost: 70,
    placeCost: 18,
    liquidCost: 8
  })
}

test('closed two-block doors are used without breaking or consuming scaffolding', () => {
  const bot = doorBot(false)
  const output = []
  movements(bot).getMoveForward({ x: 0, y: 64, z: 0, remainingBlocks: 10 }, { x: 1, z: 0 }, output)
  assert.equal(output.length, 1)
  assert.deepEqual(output[0].toBreak, [])
  assert.equal(output[0].toPlace.length, 0)
  assert.equal(output[0].toUse.length, 1)
  assert.equal(output[0].remainingBlocks, 10)
})

test('open doors are traversed without toggling them closed', () => {
  const bot = doorBot(true)
  const output = []
  movements(bot).getMoveForward({ x: 0, y: 64, z: 0, remainingBlocks: 0 }, { x: 1, z: 0 }, output)
  assert.equal(output.length, 1)
  assert.deepEqual(output[0].toBreak, [])
  assert.deepEqual(output[0].toUse, [])
})

test('diagonal movement never breaks a closed door', () => {
  const bot = doorBot(false, true)
  const output = []
  movements(bot).getMoveDiagonal({ x: 0, y: 64, z: 0, remainingBlocks: 10 }, { x: 1, z: 1 }, output)
  assert.equal(output.length, 0)
})

test('the real executor activates a door only once while awaiting confirmation', async () => {
  let open = false
  let activations = 0
  const controls = { forward: false, back: false, left: false, right: false, jump: false, sprint: false, sneak: false }
  const bot = Object.assign(new EventEmitter(), {
    registry: minecraftData,
    game: { minY: -64 },
    entity: { position: new Vec3(0.5, 64, 0.5), velocity: new Vec3(0, 0, 0), onGround: true,
      effects: {}, height: 1.8, width: 0.6 },
    inventory: { items: () => [] },
    entities: {},
    controlState: controls,
    physics: { simulatePlayer() {} },
    blockAt(position) {
      let id = minecraftData.blocksByName.air.defaultState
      if (position.x === 1 && position.z === 0 && position.y === 64) {
        id = stateId({ half: 'lower', open, powered: false, facing: 'east', hinge: 'left' })
      } else if (position.x === 1 && position.z === 0 && position.y === 65) {
        id = stateId({ half: 'upper', open, powered: false, facing: 'east', hinge: 'left' })
      } else if (position.x === 1 && Math.abs(position.z) <= 5 && (position.y === 64 || position.y === 65)) {
        id = minecraftData.blocksByName.bedrock.defaultState
      } else if (position.y === 63) id = minecraftData.blocksByName.stone.defaultState
      return blockAtState(id, position)
    },
    setControlState(name, value) { controls[name] = value },
    clearControlStates() { for (const name of Object.keys(controls)) controls[name] = false },
    look() { return Promise.resolve() },
    activateBlock() { activations += 1; open = true; return Promise.resolve() },
    dig() { return Promise.resolve() },
    equip() { return Promise.resolve() }
  })
  pathfinder(bot)
  const configured = movements(bot)
  configured.allowSprinting = false
  bot.pathfinder.setMovements(configured)
  bot.pathfinder.setGoal(new goals.GoalBlock(2, 64, 0))
  for (let tick = 0; tick < 10; tick++) {
    bot.emit('physicsTick')
    await new Promise(resolve => setTimeout(resolve, 40))
  }
  assert.equal(activations, 1)
  assert.equal(bot.pathfinder.isInteracting(), true)
  await new Promise(resolve => setTimeout(resolve, 800))
  assert.equal(activations, 1)
  assert.equal(bot.pathfinder.isInteracting(), false)
})
