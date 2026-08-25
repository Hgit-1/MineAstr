'use strict'

const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, 'node_modules', 'mineflayer-pathfinder')
const packageFile = path.join(root, 'package.json')
const expectedVersion = '2.4.5'
const marker = 'MineAstr: independent openable interaction queue'

function replaceOnce(source, before, after, file) {
  if (source.includes(after)) return source
  const first = source.indexOf(before)
  if (first < 0 || source.indexOf(before, first + before.length) >= 0) {
    throw new Error(`MineAstr pathfinder patch context mismatch: ${file}`)
  }
  return source.slice(0, first) + after + source.slice(first + before.length)
}

function patchFile(relative, operations) {
  const file = path.join(root, relative)
  let source = fs.readFileSync(file, 'utf8')
  for (const [before, after] of operations) source = replaceOnce(source, before, after, relative)
  fs.writeFileSync(file, source)
}

const metadata = JSON.parse(fs.readFileSync(packageFile, 'utf8'))
if (metadata.version !== expectedVersion) {
  throw new Error(`Unsupported mineflayer-pathfinder version ${metadata.version}; expected ${expectedVersion}`)
}

patchFile('lib/move.js', [[
  '  constructor (x, y, z, remainingBlocks, cost, toBreak = [], toPlace = [], parkour = false) {',
  `  constructor (x, y, z, remainingBlocks, cost, toBreak = [], toPlace = [], parkour = false, toUse = []) { // ${marker}`
], [
  '    this.toPlace = toPlace\n    this.parkour = parkour',
  '    this.toPlace = toPlace\n    this.toUse = toUse\n    this.parkour = parkour'
]])

patchFile('lib/movements.js', [[
  '    b.openable = this.openable.has(b.type)\n\n    for (const shape of b.shapes) {',
  `    b.openable = this.openable.has(b.type) // ${marker}\n    let properties = {}\n    try { properties = b.getProperties?.() || {} } catch (_) {}\n    b.open = b.openable && properties.open === true\n\n    for (const shape of b.shapes) {`
], [
  '    if (!this.canDig) {\n      return false\n    }',
  `    if (!this.canDig || block.openable
        || /(?:door|trapdoor|fence_gate)$/i.test(String(block.name || ''))) {
      return false
    }`
], [
  `    const toBreak = []
    const toPlace = []

    if (!blockD.physical && !blockC.liquid) {`,
  `    const toBreak = []
    const toPlace = []
    const toUse = []

    if (!blockD.physical && !blockC.liquid) {`
], [
  `    cost += this.safeOrBreak(blockB, toBreak)
    if (cost > 100) return

    // Open fence gates
    if (this.canOpenDoors && blockC.openable && blockC.shapes && blockC.shapes.length !== 0) {
      toPlace.push({ x: node.x + dir.x, y: node.y, z: node.z + dir.z, dx: 0, dy: 0, dz: 0, useOne: true }) // Indicate that a block should be used on this block not placed
    } else {
      cost += this.safeOrBreak(blockC, toBreak)
      if (cost > 100) return
    }

    if (this.getBlock(node, 0, 0, 0).liquid) cost += this.liquidCost

    neighbors.push(new Move(blockC.position.x, blockC.position.y, blockC.position.z, node.remainingBlocks - toPlace.length, cost, toBreak, toPlace))`,
  `    const canUseC = this.canOpenDoors && blockC.openable
    const canUseB = this.canOpenDoors && blockB.openable
    // ${marker}. Ordinary doors occupy both the feet and head cells. Never
    // schedule their upper half for digging; gates only occupy the feet cell.
    if (!canUseB) {
      cost += this.safeOrBreak(blockB, toBreak)
      if (cost > 100) return
    }

    if (canUseC) {
      if (!blockC.open) {
        toUse.push({ x: node.x + dir.x, y: node.y, z: node.z + dir.z, expectedOpen: true })
        cost += 2
      }
    } else {
      cost += this.safeOrBreak(blockC, toBreak)
      if (cost > 100) return
    }

    if (this.getBlock(node, 0, 0, 0).liquid) cost += this.liquidCost

    neighbors.push(new Move(blockC.position.x, blockC.position.y, blockC.position.z,
      node.remainingBlocks - toPlace.length, cost, toBreak, toPlace, false, toUse))`
]])

patchFile('index.js', [[
  '  let placing = false\n  let placingBlock = null',
  `  let placing = false
  let placingBlock = null
  let interacting = false // ${marker}
  let interactingBlock = null
  let interactionPendingConfirmation = false`
], [
  '    placing = false\n    pathUpdated = false',
  '    placing = false\n    interacting = false\n    interactingBlock = null\n    interactionPendingConfirmation = false\n    pathUpdated = false'
], [
  `  bot.pathfinder.isBuilding = () => placing
`,
  `  bot.pathfinder.isBuilding = () => placing
  bot.pathfinder.isInteracting = () => interacting
`
], [
  `        const tool = bot.pathfinder.bestHarvestTool(block)
        fullStop()

        const digBlock = () => {`,
  `        if (block?.mineastrBreakProtected) {
          digging = false
          fullStop()
          bot.emit('path_dig_protected', block)
          resetPath('protected_block')
          return
        }
        const tool = bot.pathfinder.bestHarvestTool(block)
        fullStop()

        const digBlock = () => {`
], [
  `        if (block?.mineastrBreakProtected) {
          digging = false
          fullStop()
          bot.emit('path_dig_protected', block)
          resetPath('protected_block')
          return
        }`,
  `        const mineastrCanDig = typeof bot.mineastrCanDigBlock === 'function'
          ? bot.mineastrCanDigBlock(block) : !block?.mineastrBreakProtected
        if (!mineastrCanDig || block?.mineastrBreakProtected) {
          digging = false
          fullStop()
          bot.emit('path_dig_protected', block)
          resetPath('protected_block')
          return
        }`
], [
  'curPoint.toBreak.length > 0 || curPoint.toPlace.length > 0',
  'curPoint.toBreak.length > 0 || curPoint.toPlace.length > 0 || curPoint.toUse.length > 0'
], [
  'node.toBreak.length > 0 || node.toPlace.length > 0',
  'node.toBreak.length > 0 || node.toPlace.length > 0 || node.toUse.length > 0'
], [
  'node.toBreak.length !== 0 || node.toPlace.length !== 0',
  'node.toBreak.length !== 0 || node.toPlace.length !== 0 || node.toUse.length !== 0'
], [
  'n1.toBreak.length === 0 && n1.toPlace.length === 0',
  'n1.toBreak.length === 0 && n1.toPlace.length === 0 && n1.toUse.length === 0'
], [
  `    // Handle block placement
    // TODO: sneak when placing or make sure the block is not interactive`,
  `    // ${marker}. Interactions have their own state and lock; they must not
    // consume scaffolding or inherit the long building timeout.
    if (interacting || nextPoint.toUse.length > 0) {
      if (!interacting) {
        interacting = true
        interactingBlock = nextPoint.toUse.shift()
        fullStop()
        bot.emit('path_interaction_start', interactingBlock)
      }
      if (interactionPendingConfirmation) return
      if (!lockUseBlock.tryAcquire()) return
      const target = bot.blockAt(new Vec3(interactingBlock.x, interactingBlock.y, interactingBlock.z), false)
      interactionPendingConfirmation = true
      Promise.resolve(target ? bot.activateBlock(target) : Promise.reject(new Error('interaction target unavailable')))
        .then(() => {
          lockUseBlock.release()
          const completed = interactingBlock
          setTimeout(() => {
            const refreshed = bot.blockAt(new Vec3(completed.x, completed.y, completed.z), false)
            let open = null
            try {
              const value = refreshed?.getProperties?.()?.open
              if (typeof value === 'boolean') open = value
            } catch (_) {}
            interacting = false
            interactingBlock = null
            interactionPendingConfirmation = false
            lastNodeTime = performance.now()
            if (completed.expectedOpen && open === false) {
              bot.emit('path_interaction_failed', new Error('openable state unchanged'))
              resetPath('block_interaction_unchanged')
            } else {
              bot.emit('path_interaction_complete', refreshed || target)
              resetPath('block_interaction_complete')
            }
          }, 1100)
        }, error => {
          lockUseBlock.release()
          interacting = false
          interactingBlock = null
          interactionPendingConfirmation = false
          bot.emit('path_interaction_failed', error)
          resetPath('block_interaction_error')
        })
      return
    }

    // Handle block placement
    // TODO: sneak when placing or make sure the block is not interactive`
], [
  `      // Open gates or doors
      if (placingBlock?.useOne) {
        if (!lockUseBlock.tryAcquire()) return
        bot.activateBlock(bot.blockAt(new Vec3(placingBlock.x, placingBlock.y, placingBlock.z))).then(() => {
          lockUseBlock.release()
          placingBlock = nextPoint.toPlace.shift()
        }, err => {
          console.error(err)
          lockUseBlock.release()
        })
        return
      }
`,
  ''
], [
  'nextPoint.toBreak.length > 0 || nextPoint.toPlace.length > 0',
  'nextPoint.toBreak.length > 0 || nextPoint.toPlace.length > 0 || nextPoint.toUse.length > 0'
]])

process.stdout.write(`MineAstr pathfinder patch ready (${expectedVersion})\n`)
