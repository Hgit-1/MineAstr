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

test('inventory-degraded equipment uses the server-authoritative backpack', async () => {
  const calls = []
  const bot = { entity: { position: new Vec3(0, 64, 0) } }
  const result = await executeHumanAction(bot, 'equip_best', {}, {
    inventoryDegraded: true,
    serverAuthority: async (type, args) => {
      calls.push({ type, args })
      return { operation: 'inventory_equip_best', authority: 'minecraft_server', equipped: [
        { item_id: 'example:steel_helmet', destination: 'head' }
      ] }
    }
  })
  assert.equal(result.authority, 'minecraft_server')
  assert.deepEqual(calls, [{ type: 'inventory_equip_best', args: {} }])
})

test('inventory-degraded placement selects the requested server item before placing', async () => {
  const calls = []
  const target = new Vec3(1, 64, 0)
  const bot = {
    entity: { position: new Vec3(0, 64, 0) },
    blockAt(position) {
      if (position.equals(target)) return { name: 'air', position, boundingBox: 'empty' }
      return { name: 'stone', position, boundingBox: 'block' }
    },
    async placeBlock(reference, face) {
      calls.push({ type: 'place', reference: reference.position, face })
      this.blockAt = position => position.equals(target)
        ? { name: 'example_block', position, boundingBox: 'block' }
        : { name: 'stone', position, boundingBox: 'block' }
    }
  }
  const result = await executeHumanAction(bot, 'place_block', {
    x: 1, y: 64, z: 0, item_name: 'example:example_block'
  }, {
    inventoryDegraded: true, awarenessAt: () => ({}), assertAllowed() {}, assertActive() {},
    navigate: async () => {},
    serverAuthority: async (type, args) => calls.push({ type, args })
  })
  assert.equal(result.block, 'example_block')
  assert.equal(calls[0].type, 'inventory_select')
  assert.equal(calls[0].args.item_id, 'example:example_block')
  assert.equal(calls.some(call => call.type === 'place'), true)
})

test('inventory-degraded digging asks the server to select the best real tool', async () => {
  const calls = []
  const target = new Vec3(1, 64, 0)
  const block = { name: 'example_ore', position: target, boundingBox: 'block' }
  const bot = {
    entity: { position: new Vec3(0, 64, 0) }, blockAt: () => block,
    canDigBlock: () => true, async dig() { calls.push({ type: 'dig' }) }
  }
  await executeHumanAction(bot, 'dig_block', {
    x: 1, y: 64, z: 0, dimension: 'minecraft:overworld'
  }, {
    inventoryDegraded: true, awarenessAt: () => ({}), assertAllowed() {}, assertActive() {},
    navigate: async () => {}, canBreak: () => true,
    serverAuthority: async (type, args) => calls.push({ type, args })
  })
  assert.equal(calls[0].type, 'inventory_select_tool')
  assert.deepEqual(calls[0].args, { x: 1, y: 64, z: 0, dimension: 'minecraft:overworld' })
  assert.equal(calls[1].type, 'dig')
})
