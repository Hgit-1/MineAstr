'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const {
  applyNavigationPolicy, isLeafLike, isOpenableBlock, isProtectedNavigationBlock, pathfinderDigMultiplier
} = require('../navigation-policy')

function fakeMovements() {
  return {
    scafoldingBlocks: [1, 2],
    exclusionAreasStep: [],
    exclusionAreasPlace: [],
    exclusionAreasBreak: []
  }
}

test('enables real-player digging and placing while applying configured costs', () => {
  const movements = applyNavigationPolicy(fakeMovements(), { game: { dimension: 'overworld' } }, {
    allowDigging: true, allowPlacing: true, digCost: 12, placeCost: 18, liquidCost: 8,
    isForbidden: () => false
  })
  assert.equal(movements.canDig, true)
  assert.equal(movements.allow1by1towers, true)
  assert.deepEqual(movements.scafoldingBlocks, [1, 2])
  assert.equal(movements.digCost, 1)
  assert.equal(movements.placeCost, 18)
})

test('normalizes the public digging cost around the safe pathfinder baseline', () => {
  assert.equal(pathfinderDigMultiplier(12), 1)
  assert.equal(pathfinderDigMultiplier(24), 2)
  assert.equal(pathfinderDigMultiplier(1), 1 / 12)
  assert.equal(pathfinderDigMultiplier(999), 99 / 12)
  assert.equal(pathfinderDigMultiplier('invalid'), 1)
})

test('forbidden regions block walking, breaking, and placing', () => {
  const deniedPosition = { x: 10, y: 64, z: 10 }
  const movements = applyNavigationPolicy(fakeMovements(), { game: { dimension: 'overworld' } }, {
    allowDigging: true, allowPlacing: true, digCost: 12, placeCost: 18, liquidCost: 8,
    isForbidden: position => position === deniedPosition
  })
  const denied = { name: 'stone', position: deniedPosition }
  assert.equal(movements.exclusionAreasStep[0](denied), 100)
  assert.equal(movements.exclusionAreasPlace[0](denied), 100)
  assert.equal(movements.exclusionAreasBreak[0](denied), 100)
})

test('automatic navigation never breaks containers or common machine blocks', () => {
  assert.equal(isProtectedNavigationBlock({ name: 'trapped_chest' }), true)
  assert.equal(isProtectedNavigationBlock({ name: 'create_machine_controller' }), true)
  assert.equal(isProtectedNavigationBlock({ name: 'stone' }), false)
})

test('disabling placement removes all scaffolding candidates', () => {
  const movements = applyNavigationPolicy(fakeMovements(), null, {
    allowDigging: false, allowPlacing: false, digCost: 12, placeCost: 18, liquidCost: 8
  })
  assert.equal(movements.canDig, false)
  assert.equal(movements.allow1by1towers, false)
  assert.deepEqual(movements.scafoldingBlocks, [])
})

test('opens wooden and Mod doors while avoiding iron doors', () => {
  const bot = {
    game: { dimension: 'overworld' },
    registry: { blocksArray: [
      { id: 1, name: 'oak_door' }, { id: 2, name: 'iron_door' },
      { id: 3, name: 'modded_blast_door' }, { id: 4, name: 'spruce_trapdoor' }
    ] }
  }
  const movements = applyNavigationPolicy(fakeMovements(), bot, {
    allowDigging: true, allowPlacing: true, digCost: 12, placeCost: 18, liquidCost: 8
  })
  assert.equal(movements.canOpenDoors, true)
  assert.equal(movements.maxDropDown, 3)
  assert.deepEqual([...movements.openable], [1, 3, 4])
  assert.equal(isOpenableBlock({ name: 'iron_trapdoor' }), false)
})

test('penalizes tree canopies and protects server-described Mod machinery', () => {
  const position = { x: 1, y: 64, z: 2 }
  const movements = applyNavigationPolicy(fakeMovements(), null, {
    allowDigging: true, allowPlacing: true, digCost: 12, placeCost: 18, liquidCost: 8,
    blockAwareness: candidate => candidate === position
      ? { modded: true, protected: true, hazard: false } : null
  })
  assert.equal(isLeafLike({ name: 'modded_maple_leaves' }), true)
  assert.equal(movements.exclusionAreasStep[1]({ name: 'oak_leaves' }), 24)
  assert.equal(movements.exclusionAreasStep[1]({ name: 'unknown', position }), 6)
  assert.equal(movements.exclusionAreasBreak[0]({ name: 'unknown', position }), 100)
})
