'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { Vec3 } = require('vec3')
const { armorScore, executeHumanAction, inspectEntity, weaponScore } = require('../human-actions')

test('scores standard equipment without guessing unknown Mod equipment', () => {
  assert.ok(armorScore('diamond_helmet') > armorScore('iron_helmet'))
  assert.ok(weaponScore('netherite_sword') > weaponScore('wooden_axe'))
  assert.equal(armorScore('modded_quantum_helmet'), 0)
})

test('equip best changes only self-owned equipment slots', async () => {
  const equipped = []
  const bot = {
    entity: { position: new Vec3(0, 64, 0) },
    inventory: { items: () => [
      { name: 'iron_helmet', count: 1 }, { name: 'diamond_helmet', count: 1 }, { name: 'iron_sword', count: 1 }
    ] },
    async equip(item, destination) { equipped.push([item.name, destination]) }
  }
  const result = await executeHumanAction(bot, 'equip_best', {}, { assertActive() {} })
  assert.deepEqual(equipped, [['diamond_helmet', 'head'], ['iron_sword', 'hand']])
  assert.equal(result.operation, 'equip_best')
})

test('inspection returns bounded entity facts and no raw metadata', () => {
  const bot = {
    entity: { position: new Vec3(0, 64, 0) },
    entities: { 4: { id: 4, name: 'cow', type: 'mob', position: new Vec3(2, 64, 0), metadata: { secret: true } } }
  }
  const result = inspectEntity(bot, { entity_id: 4, distance: 8 })
  assert.equal(result.entity.name, 'cow')
  assert.equal(Object.hasOwn(result.entity, 'metadata'), false)
})

test('world-changing actions refuse protected positions before mutation', async () => {
  const bot = { entity: { position: new Vec3(0, 64, 0) }, blockAt: () => ({ name: 'stone', position: new Vec3(1, 64, 0) }) }
  await assert.rejects(executeHumanAction(bot, 'dig_block', { x: 1, y: 64, z: 0 }, {
    assertAllowed() {}, assertActive() {}, awarenessAt: () => ({ protected: true }), navigate: async () => {}
  }), /受保护/)
})

test('inventory-degraded sessions refuse equipment, crafting, and item placement', async () => {
  const bot = { entity: { position: new Vec3(0, 64, 0) } }
  await assert.rejects(executeHumanAction(bot, 'equip_best', {}, { inventoryDegraded: true }), /无法可靠解码/)
  await assert.rejects(executeHumanAction(bot, 'craft', { item_id: 'stick' }, { inventoryDegraded: true }), /无法可靠解码/)
  await assert.rejects(executeHumanAction(bot, 'place_block', {
    x: 1, y: 64, z: 1, item_name: 'stone'
  }, { inventoryDegraded: true }), /无法可靠解码/)
})
