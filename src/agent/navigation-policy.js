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

function applyNavigationPolicy(movements, bot, options) {
  const allowDigging = Boolean(options.allowDigging)
  const allowPlacing = Boolean(options.allowPlacing)
  const forbidden = typeof options.isForbidden === 'function' ? options.isForbidden : () => false
  const awareness = typeof options.blockAwareness === 'function' ? options.blockAwareness : () => null
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

  const dimension = bot?.game?.dimension
  const forbiddenCost = block => forbidden(block?.position, dimension) ? 100 : 0
  movements.exclusionAreasStep.push(forbiddenCost)
  movements.exclusionAreasStep.push(block => {
    if (isLeafLike(block)) return 24
    const known = awareness(block?.position)
    if (known?.hazard) return 100
    // Unknown Mod geometry is traversable only at an explicit detour cost.
    return known?.modded ? (known.openable ? 1 : 6) : 0
  })
  movements.exclusionAreasPlace.push(forbiddenCost)
  movements.exclusionAreasBreak.push(block => {
    if (forbidden(block?.position, dimension)) return 100
    const known = awareness(block?.position)
    return known?.protected || known?.hazard || isProtectedNavigationBlock(block) ? 100 : 0
  })
  return movements
}

module.exports = {
  applyNavigationPolicy, isLeafLike, isOpenableBlock, isProtectedNavigationBlock, pathfinderDigMultiplier
}
