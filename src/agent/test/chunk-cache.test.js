'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { ChunkNavigationCache } = require('../chunk-cache')

test('stores a persistent compressed 2-bit chunk classification', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mineastr-nav-cache-'))
  try {
    const cache = new ChunkNavigationCache(root, { maxChunks: 64 })
    const entry = cache.captureColumn(fakeRegistry(), fakeColumn(), 'minecraft:overworld', 2, -3)
    assert.equal(cache.status().cached_chunks, 1)
    assert.ok(entry.bytes < 1024, `expected repetitive test chunk to compress below 1 KiB, got ${entry.bytes}`)
    assert.ok(fs.statSync(path.join(root, entry.file)).isFile())

    const reloaded = new ChunkNavigationCache(root, { maxChunks: 64 })
    assert.equal(reloaded.status().cached_chunks, 1)
    assert.equal(reloaded.index.chunks['minecraft:overworld:2:-3'].chunk_z, -3)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('cached dangerous chunks influence the coarse stitched corridor', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mineastr-nav-route-'))
  try {
    const cache = new ChunkNavigationCache(root, { maxChunks: 64 })
    cache.index.chunks['minecraft:overworld:1:0'] = {
      dimension: 'minecraft:overworld', chunk_x: 1, chunk_z: 0,
      water_ratio: 0, avoid_ratio: 1, surface_variance: 0, updated_at_ms: Date.now()
    }
    const corridor = cache.planChunkCorridor(
      { x: 1, y: 64, z: 1 }, { x: 40, y: 64, z: 1 }, 'minecraft:overworld'
    )
    assert.ok(corridor.some(point => point.z !== 1), JSON.stringify(corridor))
    assert.deepEqual(corridor.at(-1), { x: 40, y: 64, z: 1 })
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

function fakeRegistry() {
  return {
    blocksByName: {
      air: { id: 0 }, cave_air: { id: 0 }, void_air: { id: 0 },
      stone: { id: 1 }, water: { id: 2 }, lava: { id: 3 },
      fire: { id: 4 }, magma_block: { id: 5 }, cactus: { id: 6 },
      sweet_berry_bush: { id: 7 }, cobweb: { id: 8 }, powder_snow: { id: 9 }
    }
  }
}

function fakeColumn() {
  return {
    minY: 0,
    worldHeight: 16,
    getBlockType(position) {
      if (position.y < 8) return 1
      if (position.y === 8 && position.x === 0) return 2
      if (position.y === 9 && position.x === 1 && position.z === 1) return 3
      return 0
    }
  }
}
