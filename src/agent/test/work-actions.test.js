'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { Vec3 } = require('vec3')
const { executeWorkAction, isDroppedItem } = require('../work-actions')

function botAt(position = new Vec3(0, 64, 0)) {
  return {
    entity: { id: 1, position }, entities: {}, game: { dimension: 'minecraft:overworld' },
    inventory: { items: () => [] }, swingArm() {}
  }
}

test('farm workflow scans mature crops, harvests each through server authority, and replants', async () => {
  const bot = botAt()
  const calls = []
  const result = await executeWorkAction(bot, 'farm_tend', {
    x: 0, y: 64, z: 0, radius: 4, max_count: 8, pickup_timeout_seconds: 2
  }, {
    assertAllowed() {}, assertActive() {}, navigate: async () => {},
    serverAuthority: async (type, args) => {
      calls.push({ type, args })
      if (type === 'farm_scan') return { crops: [
        { x: 1, y: 64, z: 0, block_id: 'minecraft:wheat', age: 7, max_age: 7 },
        { x: 2, y: 64, z: 0, block_id: 'example:rice_crop', age: 5, max_age: 5 }
      ] }
      return { operation: 'farm_harvest', block_id: args.x === 1 ? 'minecraft:wheat' : 'example:rice_crop', replanted: true }
    }
  })
  assert.equal(result.mature_detected, 2)
  assert.equal(result.harvested_count, 2)
  assert.equal(result.replanted_count, 2)
  assert.deepEqual(calls.map(call => call.type), ['farm_scan', 'farm_harvest', 'farm_harvest'])
})

test('collect workflow visits bounded dropped-item entities and confirms disappearance', async () => {
  const bot = botAt()
  const drop = { id: 7, name: 'item', type: 'object', position: new Vec3(2, 64, 0) }
  bot.entities = { 7: drop }
  const result = await executeWorkAction(bot, 'collect_items', {
    radius: 8, max_count: 4, timeout_seconds: 2
  }, {
    assertAllowed() {}, assertActive() {}, isForbidden: () => false,
    navigate: async () => { delete bot.entities[7] }
  })
  assert.equal(isDroppedItem(drop), true)
  assert.equal(result.collected_entities, 1)
  assert.deepEqual(result.entity_ids, [7])
})

test('entity interaction selects a real server item before activating a Mod entity', async () => {
  const bot = botAt()
  const target = { id: 9, name: 'example_machine_cart', type: 'mob', position: new Vec3(2, 64, 0) }
  bot.entities = { 9: target }
  let activated = null
  bot.activateEntity = async entity => { activated = entity.id }
  const calls = []
  const result = await executeWorkAction(bot, 'interact_entity', {
    entity_id: '9', item_name: 'example:wrench', distance: 8
  }, {
    inventoryDegraded: true, assertAllowed() {}, assertActive() {}, navigate: async () => {},
    serverAuthority: async (type, args) => calls.push({ type, args })
  })
  assert.equal(activated, 9)
  assert.deepEqual(calls, [{ type: 'inventory_select', args: { item_id: 'example:wrench' } }])
  assert.equal(result.inventory_authority, 'minecraft_server')
})
