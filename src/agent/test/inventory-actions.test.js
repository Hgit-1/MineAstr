'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { Vec3 } = require('vec3')
const { executeInventoryTask } = require('../inventory-actions')

function item(name, type, count, slot) {
  return { name, displayName: name, type, metadata: 0, count, slot }
}

function fakeBot(window) {
  const block = { name: 'barrel', position: new Vec3(1, 64, 0) }
  return {
    entity: { position: new Vec3(0, 64, 0) },
    blockAt: position => {
      assert.ok(position instanceof Vec3)
      return block
    },
    openBlock: async () => window,
    transfer: async options => {
      const source = window.slots.find((entry, index) => entry && index >= options.sourceStart
        && index < options.sourceEnd && entry.type === options.itemType)
      assert.ok(source)
      source.count -= options.count
      if (source.count === 0) window.slots[source.slot] = null
      const destination = options.destStart
      window.slots[destination] = item(source.name, source.type, options.count, destination)
    }
  }
}

test('inspects and transfers standard container items with before and after evidence', async () => {
  const window = {
    type: 'minecraft:generic_9x3', inventoryStart: 27, inventoryEnd: 63,
    slots: Array(63).fill(null), close() { this.closed = true }
  }
  window.slots[27] = item('cobblestone', 1, 12, 27)
  const bot = fakeBot(window)
  const result = await executeInventoryTask(bot, 'container_transfer', {
    x: 1, y: 64, z: 0, direction: 'to_container', item_id: 'minecraft:cobblestone', count: 5
  }, { navigate: async () => assert.fail('nearby container must not navigate'), assertActive() {} })
  assert.equal(result.transferred_count, 5)
  assert.equal(result.after.items.find(entry => entry.item_id === 'cobblestone').count, 5)
  assert.equal(window.closed, true)
})

test('keeps an unknown Mod window read-only', async () => {
  let transfers = 0
  const window = { type: 'example:unmapped_machine', inventoryStart: 6, inventoryEnd: 42,
    slots: Array(42).fill(null), close() { this.closed = true } }
  const bot = fakeBot(window)
  bot.transfer = async () => { transfers += 1 }
  await assert.rejects(executeInventoryTask(bot, 'container_transfer', {
    x: 1, y: 64, z: 0, direction: 'to_container', item_id: 'stone', count: 1
  }, { navigate: async () => {} }), /未知或不兼容/)
  assert.equal(transfers, 0)
  assert.equal(window.closed, true)
})

test('processes a furnace using Mineflayer furnace methods and returns output evidence', async () => {
  const slots = Array(39).fill(null)
  slots[3] = item('raw_iron', 10, 2, 3)
  slots[4] = item('coal', 11, 2, 4)
  slots[2] = item('iron_ingot', 12, 1, 2)
  const furnace = {
    type: 'minecraft:furnace', inventoryStart: 3, inventoryEnd: 39, slots,
    inputItem() { return this.slots[0] }, fuelItem() { return this.slots[1] }, outputItem() { return this.slots[2] },
    async putInput(_type, _metadata, count) { this.slots[0] = item('raw_iron', 10, count, 0) },
    async putFuel(_type, _metadata, count) { this.slots[1] = item('coal', 11, count, 1) },
    async takeOutput() { const value = this.slots[2]; this.slots[2] = null; return value },
    close() { this.closed = true }
  }
  const bot = fakeBot(furnace)
  bot.blockAt = () => ({ name: 'furnace', position: new Vec3(1, 64, 0) })
  bot.openFurnace = async () => furnace
  const result = await executeInventoryTask(bot, 'furnace_process', {
    x: 1, y: 64, z: 0, input_item: 'raw_iron', input_count: 1,
    fuel_item: 'coal', fuel_count: 1, wait_mode: 'none', take_output: true
  }, { navigate: async () => {}, assertActive() {} })
  assert.equal(result.taken_output.item_id, 'iron_ingot')
  assert.equal(furnace.closed, true)
})

test('refuses degraded NeoForge inventory mutation without server authority', async () => {
  const bot = fakeBot({})
  await assert.rejects(executeInventoryTask(bot, 'container_inspect', { x: 1, y: 64, z: 0 }, {
    inventoryDegraded: true, navigate: async () => {}
  }), /权威物品通道不可用/)
})

test('routes degraded NeoForge container operations through server authority', async () => {
  const bot = fakeBot({})
  const calls = []
  const result = await executeInventoryTask(bot, 'container_inspect', {
    x: 1, y: 64, z: 0, dimension: 'minecraft:overworld'
  }, {
    inventoryDegraded: true,
    navigate: async () => assert.fail('nearby container must not navigate'),
    serverAuthority: async (type, args) => {
      calls.push({ type, args })
      return { operation: 'inspect', authority: 'minecraft_server', items: [{ item_id: 'example:gear', count: 2 }] }
    }
  })
  assert.equal(result.authority, 'minecraft_server')
  assert.deepEqual(calls.map(call => call.type), ['container_inspect'])
  assert.equal(calls[0].args.dimension, 'minecraft:overworld')
})

test('routes degraded NeoForge furnace processing and collection through server authority', async () => {
  const bot = fakeBot({})
  bot.blockAt = () => ({ name: 'furnace', position: new Vec3(1, 64, 0) })
  const calls = []
  const result = await executeInventoryTask(bot, 'furnace_process', {
    x: 1, y: 64, z: 0, input_item: 'example:raw_gear', input_count: 1,
    fuel_item: 'minecraft:coal', fuel_count: 1, wait_mode: 'first_output', timeout_seconds: 5
  }, {
    inventoryDegraded: true,
    navigate: async () => {},
    assertActive() {},
    serverAuthority: async type => {
      calls.push(type)
      if (type === 'furnace_collect') return { taken_output: { item_id: 'example:gear', count: 1 } }
      if (type === 'furnace_inspect' && calls.filter(value => value === 'furnace_inspect').length > 1) {
        return { output: { item_id: 'example:gear', count: 1 } }
      }
      return { output: null }
    }
  })
  assert.equal(result.authority, 'minecraft_server')
  assert.deepEqual(result.taken_output, { item_id: 'example:gear', count: 1 })
  assert.ok(calls.includes('furnace_process'))
  assert.ok(calls.includes('furnace_collect'))
})
