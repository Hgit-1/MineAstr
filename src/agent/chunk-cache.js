'use strict'

const fs = require('node:fs')
const path = require('node:path')
const zlib = require('node:zlib')
const { Vec3 } = require('vec3')

const SCHEMA_VERSION = 1
const AIR = 0
const SOLID = 1
const WATER = 2
const AVOID = 3

class ChunkNavigationCache {
  constructor(root, options = {}) {
    this.root = path.resolve(root)
    this.maxChunks = boundedInteger(options.maxChunks, 2048, 64, 16384)
    this.indexFile = path.join(this.root, 'index.json')
    this.index = this.loadIndex()
    this.pending = new Set()
    this.refreshTimers = new Map()
    fs.mkdirSync(this.root, { recursive: true, mode: 0o700 })
  }

  status() {
    const entries = Object.values(this.index.chunks)
    return {
      schema_version: SCHEMA_VERSION,
      cached_chunks: entries.length,
      compressed_bytes: entries.reduce((sum, entry) => sum + (entry.bytes || 0), 0),
      pending_chunks: this.pending.size,
      scheduled_refreshes: this.refreshTimers.size,
      max_chunks: this.maxChunks
    }
  }

  capture(bot, columnCorner, dimension) {
    if (!bot?.world || !bot?.registry || !columnCorner) return
    const chunkX = Math.floor(Number(columnCorner.x) / 16)
    const chunkZ = Math.floor(Number(columnCorner.z) / 16)
    const key = cacheKey(dimension, chunkX, chunkZ)
    if (this.pending.has(key)) return
    this.pending.add(key)
    setImmediate(() => {
      try {
        const column = bot.world.getColumn(chunkX, chunkZ)
        if (column) this.captureColumn(bot.registry, column, dimension, chunkX, chunkZ)
      } catch (_) {
        // Cache failures must never interrupt the game session or navigation.
      } finally {
        this.pending.delete(key)
      }
    })
  }

  captureLoaded(bot, dimension) {
    for (const entry of bot?.world?.getColumns?.() || []) {
      const chunkX = Number(entry.chunkX)
      const chunkZ = Number(entry.chunkZ)
      if (!Number.isFinite(chunkX) || !Number.isFinite(chunkZ)) continue
      this.capture(bot, new Vec3(chunkX * 16, 0, chunkZ * 16), dimension)
    }
  }

  refreshLater(bot, position, dimension, milliseconds = 5000) {
    if (!position) return
    const chunkX = Math.floor(Number(position.x) / 16)
    const chunkZ = Math.floor(Number(position.z) / 16)
    const key = cacheKey(dimension, chunkX, chunkZ)
    const existing = this.refreshTimers.get(key)
    if (existing) clearTimeout(existing)
    const timer = setTimeout(() => {
      this.refreshTimers.delete(key)
      this.capture(bot, new Vec3(chunkX * 16, 0, chunkZ * 16), dimension)
    }, milliseconds)
    timer.unref?.()
    this.refreshTimers.set(key, timer)
  }

  captureColumn(registry, column, dimension, chunkX, chunkZ) {
    const minY = Number.isFinite(column.minY) ? column.minY : -64
    const height = boundedInteger(column.worldHeight, 384, 16, 1024)
    const blockCount = 16 * 16 * height
    const packed = Buffer.alloc(Math.ceil(blockCount / 4))
    const surfaceY = new Int16Array(256)
    surfaceY.fill(-32768)
    const surfaceKind = Buffer.alloc(64)
    const category = buildCategoryLookup(registry)
    let airCount = 0
    let waterCount = 0
    let avoidCount = 0

    for (let yOffset = 0; yOffset < height; yOffset++) {
      const y = minY + yOffset
      for (let z = 0; z < 16; z++) {
        for (let x = 0; x < 16; x++) {
          const type = column.getBlockType(new Vec3(x, y, z))
          const kind = category(type)
          const index = (yOffset * 256) + (z * 16) + x
          setPacked2Bit(packed, index, kind)
          if (kind === AIR) airCount += 1
          else if (kind === WATER) waterCount += 1
          else if (kind === AVOID) avoidCount += 1
          if (kind !== AIR) {
            const columnIndex = z * 16 + x
            surfaceY[columnIndex] = y
            setPacked2Bit(surfaceKind, columnIndex, kind)
          }
        }
      }
    }

    const summary = summarizeSurface(surfaceY, surfaceKind, blockCount, airCount, waterCount, avoidCount)
    const header = Buffer.alloc(18)
    header.write('MNC2', 0, 'ascii')
    header.writeUInt8(SCHEMA_VERSION, 4)
    header.writeInt16LE(minY, 5)
    header.writeUInt16LE(height, 7)
    header.writeInt32LE(chunkX, 9)
    header.writeInt32LE(chunkZ, 13)
    header.writeUInt8(0, 17)
    const surfaceBuffer = Buffer.from(surfaceY.buffer, surfaceY.byteOffset, surfaceY.byteLength)
    const compressed = zlib.deflateRawSync(Buffer.concat([header, surfaceBuffer, surfaceKind, packed]), { level: 9 })
    const relative = chunkRelativePath(dimension, chunkX, chunkZ)
    const destination = path.join(this.root, relative)
    fs.mkdirSync(path.dirname(destination), { recursive: true, mode: 0o700 })
    atomicWrite(destination, compressed)

    const key = cacheKey(dimension, chunkX, chunkZ)
    this.index.chunks[key] = {
      dimension: normalizeDimension(dimension), chunk_x: chunkX, chunk_z: chunkZ,
      file: relative, bytes: compressed.length, updated_at_ms: Date.now(), ...summary
    }
    this.evictIfNeeded()
    this.saveIndex()
    return this.index.chunks[key]
  }

  planChunkCorridor(start, target, dimension) {
    const startChunk = { x: Math.floor(start.x / 16), z: Math.floor(start.z / 16) }
    const targetChunk = { x: Math.floor(target.x / 16), z: Math.floor(target.z / 16) }
    if (startChunk.x === targetChunk.x && startChunk.z === targetChunk.z) return []
    const margin = 10
    const bounds = {
      minX: Math.min(startChunk.x, targetChunk.x) - margin,
      maxX: Math.max(startChunk.x, targetChunk.x) + margin,
      minZ: Math.min(startChunk.z, targetChunk.z) - margin,
      maxZ: Math.max(startChunk.z, targetChunk.z) + margin
    }
    const goalKey = pointKey(targetChunk.x, targetChunk.z)
    const startKey = pointKey(startChunk.x, startChunk.z)
    const open = new Map([[startKey, { ...startChunk, g: 0, f: heuristic(startChunk, targetChunk) }]])
    const cameFrom = new Map()
    const best = new Map([[startKey, 0]])
    let expanded = 0

    while (open.size && expanded < 8192) {
      let currentKey = null
      let current = null
      for (const [key, node] of open) {
        if (!current || node.f < current.f) { currentKey = key; current = node }
      }
      open.delete(currentKey)
      if (currentKey === goalKey) return simplifyCorridor(reconstruct(cameFrom, currentKey), target)
      expanded += 1
      for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const x = current.x + dx
        const z = current.z + dz
        if (x < bounds.minX || x > bounds.maxX || z < bounds.minZ || z > bounds.maxZ) continue
        const key = pointKey(x, z)
        const tentative = current.g + this.chunkTraversalCost(dimension, x, z)
        if (tentative >= (best.get(key) ?? Infinity)) continue
        cameFrom.set(key, currentKey)
        best.set(key, tentative)
        open.set(key, { x, z, g: tentative, f: tentative + heuristic({ x, z }, targetChunk) })
      }
    }
    return []
  }

  chunkTraversalCost(dimension, chunkX, chunkZ) {
    const entry = this.index.chunks[cacheKey(dimension, chunkX, chunkZ)]
    if (!entry) return 2.5
    const water = entry.surface_water_ratio ?? entry.water_ratio ?? 0
    const avoid = entry.surface_avoid_ratio ?? entry.avoid_ratio ?? 0
    return 1 + water * 8 + avoid * 40 + Math.min(8, (entry.surface_variance || 0) / 16)
  }

  loadIndex() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.indexFile, 'utf8'))
      if (parsed?.schema_version === SCHEMA_VERSION && parsed.chunks && typeof parsed.chunks === 'object') return parsed
    } catch (_) {}
    return { schema_version: SCHEMA_VERSION, chunks: {} }
  }

  saveIndex() {
    fs.mkdirSync(this.root, { recursive: true, mode: 0o700 })
    atomicWrite(this.indexFile, Buffer.from(JSON.stringify(this.index)))
  }

  evictIfNeeded() {
    const entries = Object.entries(this.index.chunks)
      .sort((left, right) => (left[1].updated_at_ms || 0) - (right[1].updated_at_ms || 0))
    while (entries.length > this.maxChunks) {
      const [key, entry] = entries.shift()
      delete this.index.chunks[key]
      try { fs.unlinkSync(path.join(this.root, entry.file)) } catch (_) {}
    }
  }
}

function buildCategoryLookup(registry) {
  const air = new Set(['air', 'cave_air', 'void_air'].map(name => registry.blocksByName?.[name]?.id).filter(Number.isFinite))
  const water = new Set(['water', 'bubble_column'].map(name => registry.blocksByName?.[name]?.id).filter(Number.isFinite))
  const avoid = new Set(['lava', 'fire', 'soul_fire', 'magma_block', 'cactus', 'sweet_berry_bush', 'cobweb', 'powder_snow']
    .map(name => registry.blocksByName?.[name]?.id).filter(Number.isFinite))
  return type => air.has(type) ? AIR : water.has(type) ? WATER : avoid.has(type) ? AVOID : SOLID
}

function summarizeSurface(surfaceY, surfaceKind, blockCount, airCount, waterCount, avoidCount) {
  const values = [...surfaceY].filter(value => value !== -32768)
  const average = values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0
  const variance = values.length
    ? Math.sqrt(values.reduce((sum, value) => sum + ((value - average) ** 2), 0) / values.length) : 0
  let surfaceWater = 0
  let surfaceAvoid = 0
  for (let index = 0; index < 256; index++) {
    const kind = getPacked2Bit(surfaceKind, index)
    if (kind === WATER) surfaceWater += 1
    else if (kind === AVOID) surfaceAvoid += 1
  }
  return {
    air_ratio: round(airCount / blockCount),
    water_ratio: round(waterCount / blockCount),
    avoid_ratio: round(avoidCount / blockCount),
    surface_water_ratio: round(surfaceWater / 256),
    surface_avoid_ratio: round(surfaceAvoid / 256),
    surface_y_average: round(average),
    surface_variance: round(variance)
  }
}

function reconstruct(cameFrom, key) {
  const result = []
  let cursor = key
  while (cursor) {
    const [x, z] = cursor.split(',').map(Number)
    result.push({ x, z })
    cursor = cameFrom.get(cursor)
  }
  return result.reverse()
}

function simplifyCorridor(chunks, target) {
  if (chunks.length <= 2) return [{ x: target.x, y: target.y, z: target.z }]
  const result = []
  let previousDirection = null
  for (let index = 1; index < chunks.length; index++) {
    const direction = { x: chunks[index].x - chunks[index - 1].x, z: chunks[index].z - chunks[index - 1].z }
    if (previousDirection && (direction.x !== previousDirection.x || direction.z !== previousDirection.z)) {
      const corner = chunks[index - 1]
      result.push({ x: corner.x * 16 + 8, y: target.y, z: corner.z * 16 + 8 })
    }
    previousDirection = direction
  }
  result.push({ x: target.x, y: target.y, z: target.z })
  return result
}

function setPacked2Bit(buffer, index, value) {
  const byte = index >> 2
  const shift = (index & 3) * 2
  buffer[byte] = (buffer[byte] & ~(3 << shift)) | ((value & 3) << shift)
}

function getPacked2Bit(buffer, index) {
  return (buffer[index >> 2] >> ((index & 3) * 2)) & 3
}

function atomicWrite(destination, contents) {
  const temporary = `${destination}.tmp-${process.pid}`
  fs.writeFileSync(temporary, contents, { mode: 0o600 })
  fs.renameSync(temporary, destination)
}

function chunkRelativePath(dimension, x, z) {
  const safeDimension = normalizeDimension(dimension).replace(/[^A-Za-z0-9_.-]/g, '_')
  return path.join(safeDimension, `${x}.${z}.mnc`)
}

function cacheKey(dimension, x, z) { return `${normalizeDimension(dimension)}:${x}:${z}` }
function pointKey(x, z) { return `${x},${z}` }
function heuristic(left, right) { return Math.abs(left.x - right.x) + Math.abs(left.z - right.z) }
function normalizeDimension(value) { return String(value || 'minecraft:overworld') }
function round(value) { return Math.round(Number(value) * 10000) / 10000 }
function boundedInteger(value, fallback, min, max) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback
}

module.exports = { AIR, AVOID, ChunkNavigationCache, SOLID, WATER, setPacked2Bit }
