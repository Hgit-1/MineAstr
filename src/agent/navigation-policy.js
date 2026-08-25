'use strict'

const DEFAULT_CONFIGURED_DIG_COST = 12

function pathfinderDigMultiplier(configuredCost) {
  const parsed = Number(configuredCost)
  const safeCost = Number.isFinite(parsed) ? Math.max(1, Math.min(99, parsed)) : DEFAULT_CONFIGURED_DIG_COST
  // mineflayer-pathfinder already multiplies this value by the block's tool-aware
  // dig time and discards any single movement whose accumulated cost exceeds 100.
  // Treat the public default (12) as 1x so ordinary two-block tunnels remain
  // feasible without erasing the relative cost advantage of proper tools.
  return safeCost / DEFAULT_CONFIGURED_DIG_COST
}

function isProtectedNavigationBlock(block) {
  const name = String(block?.name || block?.displayName || '').toLowerCase()
  if (!name) return false
  return /(?:^|_)(?:chest|barrel|shulker_box|ender_chest|furnace|blast_furnace|smoker|hopper|dispenser|dropper|brewing_stand|beacon|spawner|trial_spawner|vault|command_block)$/.test(name) ||
    /(?:machine|controller|storage|drive|terminal|interface)/.test(name)
}

function isLeafLike(block) {
  const name = String(block?.name || block?.displayName || '').toLowerCase()
  return /(?:^|_)(?:leaves|leaf|foliage)(?:$|_)/.test(name) || /leaves$/.test(name)
}

function isOpenableBlock(block) {
  const name = String(block?.name || block?.displayName || '').toLowerCase()
  return !/(?:^|_)iron_(?:door|trapdoor)$/.test(name) &&
    /(?:^|_)(?:door|trapdoor|fence_gate)$/.test(name)
}

function installAuthoritativeWorldCollision(bot, blockAwareness) {
  const world = bot?.world
  if (!world || typeof world.getBlock !== 'function') return false
  if (world.mineastrAuthoritativeCollision) return true
  const awareness = typeof blockAwareness === 'function' ? blockAwareness : () => null
  const localGetBlock = world.getBlock.bind(world)
  world.getBlock = function (...args) {
    const block = localGetBlock(...args)
    return applyAuthoritativeCollisionShape(block, awareness(block?.position || args[0]))
  }
  world.mineastrAuthoritativeCollision = true
  return true
}

function applyAuthoritativeCollisionShape(block, known) {
  if (!block || !known || typeof known.collision !== 'boolean') return block
  const copy = Object.assign(Object.create(Object.getPrototypeOf(block)), block)
  const boxes = normalizeCollisionBoxes(known.collision_boxes)
  copy.serverAuthoritative = true
  copy.modded = Boolean(known.modded)
  copy.mineastrBreakProtected = Boolean(known.protected || known.openable
    || Number(known.structure_confidence) >= 80)
  if (known.id && (!copy.name || copy.name === 'unknown')) {
    copy.name = String(known.id).split(':').pop()
  }
  copy.shapes = known.collision ? (boxes.length > 0 ? boxes : [[0, 0, 0, 1, 1, 1]]) : []
  copy.boundingBox = known.collision ? 'block' : 'empty'
  return copy
}

function normalizeCollisionBoxes(value) {
  if (!Array.isArray(value)) return []
  return value.filter(box => Array.isArray(box) && box.length === 6 && box.every(Number.isFinite))
    .map(box => box.map(Number))
}

function applyNavigationPolicy(movements, bot, options) {
  const allowDigging = Boolean(options.allowDigging)
  const allowPlacing = Boolean(options.allowPlacing)
  const forbidden = typeof options.isForbidden === 'function' ? options.isForbidden : () => false
  const canBreakBlock = typeof options.canBreakBlock === 'function' ? options.canBreakBlock : () => true
  const awareness = typeof options.blockAwareness === 'function' ? options.blockAwareness : () => null
  const structureCache = new Map()
  movements.canDig = allowDigging
  movements.digCost = pathfinderDigMultiplier(options.digCost)
  movements.placeCost = options.placeCost
  movements.liquidCost = options.liquidCost
  movements.allow1by1towers = allowPlacing
  movements.allowParkour = false
  movements.maxDropDown = Math.min(Number(movements.maxDropDown) || 4, 3)
  movements.canOpenDoors = true
  if (!allowPlacing) movements.scafoldingBlocks = []

  // mineflayer-pathfinder only enables fence gates by default. Add ordinary
  // wooden doors/trapdoors and any server-described Mod openable block IDs.
  if (!(movements.openable instanceof Set)) movements.openable = new Set(movements.openable || [])
  for (const block of bot?.registry?.blocksArray || []) {
    if (Number.isFinite(block?.id) && isOpenableBlock(block)) movements.openable.add(block.id)
  }

  // Overlay the authoritative server view onto Mineflayer's vanilla registry.
  // This is essential when a NeoForge block state has no faithful prismarine
  // representation (for example BOP leaves or a Mod door).
  if (!movements.mineastrAuthoritativeGetBlock && typeof movements.getBlock === 'function') {
    const localGetBlock = movements.getBlock.bind(movements)
    movements.getBlock = function (...args) {
      const block = localGetBlock(...args)
      const known = awareness(block?.position)
      if (!block || !known) return block
      block.serverAuthoritative = true
      block.modded = Boolean(known.modded)
      block.leaf = Boolean(known.leaf)
      block.mineastrBreakProtected = Boolean(known.protected || known.openable
        || Number(known.structure_confidence) >= 80)
      if (known.openable) {
        block.openable = known.hand_openable !== false
        block.open = known.open === true
      }
      if (known.hazard) {
        block.safe = false
      } else if (known.openable && known.open === true && known.center_passable !== false) {
        block.safe = true
        block.physical = false
        block.replaceable = false
      } else if (known.collision === true) {
        block.safe = false
        block.physical = true
        block.replaceable = false
      } else if (known.modded && known.collision === false) {
        block.safe = true
        block.physical = false
      }
      return block
    }
    movements.mineastrAuthoritativeGetBlock = true
  }

  const dimension = bot?.game?.dimension
  const forbiddenCost = block => forbidden(block?.position, dimension) ? 100 : 0
  movements.exclusionAreasStep.push(forbiddenCost)
  movements.exclusionAreasStep.push(block => {
    const known = awareness(block?.position)
    if (known?.leaf || isLeafLike(block)) return 24
    if (known?.hazard) return 100
    // Unknown Mod geometry is traversable only at an explicit detour cost.
    return known?.modded ? (known.openable ? 1 : known.collision ? 16 : 6) : 0
  })
  movements.exclusionAreasPlace.push(forbiddenCost)
  movements.exclusionAreasBreak.push(block => {
    if (forbidden(block?.position, dimension)) return 100
    if (!canBreakBlock(block)) return 100
    const known = awareness(block?.position)
    if (known?.protected || known?.hazard || known?.openable || isProtectedNavigationBlock(block)) return 100
    const key = block?.position ? `${block.position.x},${block.position.y},${block.position.z}` : null
    let localConfidence = 0
    if (!known && key) {
      if (!structureCache.has(key)) {
        if (structureCache.size >= 2048) structureCache.delete(structureCache.keys().next().value)
        structureCache.set(key, estimateLocalStructureConfidence(bot, block.position))
      }
      localConfidence = structureCache.get(key) || 0
    }
    const confidence = Math.max(0, Math.min(100,
      Number(known?.structure_confidence) || localConfidence))
    // Automatic navigation must never regard a verified building envelope as
    // a merely expensive shortcut. The live BOP-door regression demonstrated
    // that a cost of 70 still lets A* choose glass walls after an interaction
    // failure. Keep ordinary terrain diggable, but make high-confidence
    // structures an absolute exclusion.
    if (confidence >= 80) return 100
    const configured = Math.max(1, Math.min(99, Number(options.structureBreakCost) || 70))
    return Math.round(configured * confidence / 100)
  })
  return movements
}

function estimateLocalStructureConfidence(bot, position) {
  if (!position || typeof bot?.blockAt !== 'function') return 0
  let best = 0
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
    const inside = { x: position.x + dx, y: position.y, z: position.z + dz }
    if (!localColumnPassable(bot, inside)) continue
    let walls = 0
    for (const [rayX, rayZ] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      for (let distance = 1; distance <= 8; distance++) {
        const cursor = { x: inside.x + rayX * distance, y: inside.y, z: inside.z + rayZ * distance }
        if (localCollision(bot, cursor) || localCollision(bot, { ...cursor, y: cursor.y + 1 })) {
          walls += 1
          break
        }
      }
    }
    let roof = false
    for (let distance = 2; distance <= 8; distance++) {
      if (localCollision(bot, { x: inside.x, y: inside.y + distance, z: inside.z })) {
        roof = true
        break
      }
    }
    if (walls >= 3 && roof) best = Math.max(best, Math.min(90, 35 + walls * 10 + 10))
  }
  return best
}

function localColumnPassable(bot, position) {
  return !localCollision(bot, position)
    && !localCollision(bot, { x: position.x, y: position.y + 1, z: position.z })
    && localCollision(bot, { x: position.x, y: position.y - 1, z: position.z })
}

function localCollision(bot, position) {
  try {
    const block = bot.blockAt(position, false)
    return block != null && block.boundingBox !== 'empty' && block.name !== 'air'
  } catch (_) {
    return false
  }
}

module.exports = {
  applyAuthoritativeCollisionShape, applyNavigationPolicy, estimateLocalStructureConfidence,
  installAuthoritativeWorldCollision, isLeafLike, isOpenableBlock, isProtectedNavigationBlock,
  normalizeCollisionBoxes, pathfinderDigMultiplier
}
