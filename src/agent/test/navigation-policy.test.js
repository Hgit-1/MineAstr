'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { applyNavigationPolicy, isProtectedNavigationBlock } = require('../navigation-policy')

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
  assert.equal(movements.digCost, 12)
  assert.equal(movements.placeCost, 18)
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
