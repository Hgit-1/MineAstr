'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { Vec3 } = require('vec3')
const {
  createCombatController,
  isAttackableHostile,
  selectBestWeapon,
  selectCombatTarget,
  weaponScore
} = require('../combat')

function entity(id, name, x, type = 'mob', entityType = id) {
  return { id, name, type, entityType, height: 1.8, position: new Vec3(x, 64, 0) }
}

function fakeBot(entities = {}) {
  const attacks = []
  const equips = []
  const looks = []
  const bot = {
    entity: { id: 1, position: new Vec3(0, 64, 0) },
    entities,
    health: 20,
    heldItem: null,
    registry: { entities: {} },
    inventory: { items: () => [] },
    equip: async (item, destination) => { equips.push({ item, destination }); bot.heldItem = item },
    lookAt: async position => { looks.push(position) },
    attack: target => { attacks.push(target) }
  }
  return { bot, attacks, equips, looks }
}

test('targets only safe hostile mobs and never players or neutral-dangerous mobs', () => {
  const zombie = entity(2, 'zombie', 2)
  const player = entity(3, 'player', 1, 'player')
  player.username = 'Alex'
  const enderman = entity(4, 'enderman', 1.5)
  const unknownHostile = entity(5, 'modded_raider', 2.5, 'mob', 50)
  const { bot } = fakeBot({ 2: zombie, 3: player, 4: enderman, 5: unknownHostile })
  bot.registry.entities[50] = { category: 'Hostile mobs' }
  assert.equal(isAttackableHostile(bot, zombie), true)
  assert.equal(isAttackableHostile(bot, player), false)
  assert.equal(isAttackableHostile(bot, enderman), false)
  assert.equal(isAttackableHostile(bot, unknownHostile), true)
  assert.equal(selectCombatTarget(bot, { radius: 6 }).entity, zombie)
})

test('selects the strongest recognized melee weapon without treating tools as weapons', () => {
  const woodenSword = { type: 1, name: 'wooden_sword' }
  const diamondSword = { type: 2, name: 'diamond_sword' }
  const pickaxe = { type: 3, name: 'netherite_pickaxe' }
  const { bot } = fakeBot()
  bot.inventory.items = () => [pickaxe, woodenSword, diamondSword]
  assert.equal(weaponScore(pickaxe), 0)
  assert.equal(selectBestWeapon(bot), diamondSword)
})

test('equips, aims, and attacks a hostile inside melee reach with cooldown', async () => {
  const zombie = entity(2, 'zombie', 2.5)
  const sword = { type: 10, name: 'iron_sword' }
  const events = []
  const { bot, attacks, equips, looks } = fakeBot({ 2: zombie })
  bot.inventory.items = () => [sword]
  const controller = createCombatController(bot, {
    enabled: true, radius: 6, attackRange: 3.1, minimumHealth: 10,
    attackCooldownMilliseconds: 650, emit: event => events.push(event)
  })
  assert.equal(await controller.tick(1000), true)
  assert.equal(await controller.tick(1200), false)
  assert.equal(await controller.tick(1700), true)
  assert.equal(attacks.length, 2)
  assert.equal(equips.length, 1)
  assert.equal(looks.length, 2)
  assert.equal(events.filter(event => event.type === 'combat_started').length, 1)
  assert.equal(controller.status().attacks, 2)
})

test('retreats from creepers and low-health combat instead of attacking', async () => {
  const dangers = []
  const creeper = entity(2, 'creeper', 4)
  const { bot, attacks } = fakeBot({ 2: creeper })
  const controller = createCombatController(bot, { onDanger: (target, reason) => dangers.push({ target, reason }) })
  assert.equal(await controller.tick(1000), false)
  assert.equal(dangers[0].reason, 'dangerous_target')
  assert.equal(attacks.length, 0)

  const zombie = entity(3, 'zombie', 2)
  bot.entities = { 3: zombie }
  bot.health = 8
  assert.equal(await controller.tick(2000), false)
  assert.equal(dangers[1].reason, 'low_health')
  assert.equal(attacks.length, 0)
  assert.equal(controller.status().danger_events, 2)
  assert.equal(controller.status().last_danger.name, 'zombie')
  assert.equal(controller.status().last_danger.reason, 'low_health')
})
