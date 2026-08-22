'use strict'

function isProtectedNavigationBlock(block) {
  const name = String(block?.name || block?.displayName || '').toLowerCase()
  if (!name) return false
  return /(?:^|_)(?:chest|barrel|shulker_box|ender_chest|furnace|blast_furnace|smoker|hopper|dispenser|dropper|brewing_stand|beacon|spawner|trial_spawner|vault|command_block)$/.test(name) ||
    /(?:machine|controller|storage|drive|terminal|interface)/.test(name)
}

function applyNavigationPolicy(movements, bot, options) {
  const allowDigging = Boolean(options.allowDigging)
  const allowPlacing = Boolean(options.allowPlacing)
  const forbidden = typeof options.isForbidden === 'function' ? options.isForbidden : () => false
  movements.canDig = allowDigging
  movements.digCost = options.digCost
  movements.placeCost = options.placeCost
  movements.liquidCost = options.liquidCost
  movements.allow1by1towers = allowPlacing
  movements.allowParkour = false
  if (!allowPlacing) movements.scafoldingBlocks = []

  const dimension = bot?.game?.dimension
  const forbiddenCost = block => forbidden(block?.position, dimension) ? 100 : 0
  movements.exclusionAreasStep.push(forbiddenCost)
  movements.exclusionAreasPlace.push(forbiddenCost)
  movements.exclusionAreasBreak.push(block => {
    if (forbidden(block?.position, dimension)) return 100
    return isProtectedNavigationBlock(block) ? 100 : 0
  })
  return movements
}

module.exports = { applyNavigationPolicy, isProtectedNavigationBlock }
