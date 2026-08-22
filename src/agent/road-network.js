'use strict'

const fs = require('node:fs')
const path = require('node:path')

const SCHEMA_VERSION = 1
const MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
const MAX_SNAPSHOT_AGE_MS = 5 * 60 * 1000
const ROAD_WEIGHT = 0.55
const ENTRY_RADIUS = 128
const MAX_EXPANDED_NODES = 150000

class RoadNetwork {
  constructor(file, options = {}) {
    this.file = path.resolve(file)
    this.enabled = options.enabled !== false
    this.lastMtimeMs = -1
    this.generatedAtMs = 0
    this.sourceVersion = ''
    this.dimension = 'minecraft:overworld'
    this.nodes = []
    this.edges = []
    this.invalidZones = []
    this.roadCount = 0
    this.lastError = ''
    this.reason = this.enabled ? 'not_loaded' : 'disabled_by_config'
  }

  status() {
    this.reloadIfChanged()
    return {
      enabled: this.enabled,
      available: this.enabled && this.nodes.length > 1,
      reason: this.reason,
      source_version: this.sourceVersion || null,
      generated_at_ms: this.generatedAtMs || null,
      road_count: this.roadCount,
      node_count: this.nodes.length,
      edge_count: this.edges.length,
      invalid_zone_count: this.invalidZones.length,
      last_error: this.lastError || null
    }
  }

  reloadIfChanged() {
    if (!this.enabled) return false
    let stat
    try { stat = fs.statSync(this.file) } catch (_) {
      this.clear('snapshot_missing')
      return false
    }
    if (stat.size > MAX_SNAPSHOT_BYTES) {
      this.clear('snapshot_too_large', `RoadWeaver 路网快照超过 ${MAX_SNAPSHOT_BYTES} 字节`)
      this.lastMtimeMs = stat.mtimeMs
      return false
    }
    if (stat.mtimeMs === this.lastMtimeMs) return false
    this.lastMtimeMs = stat.mtimeMs
    try {
      const parsed = JSON.parse(fs.readFileSync(this.file, 'utf8'))
      this.load(parsed)
      return true
    } catch (error) {
      this.clear('snapshot_invalid', safeMessage(error))
      return false
    }
  }

  load(snapshot) {
    if (snapshot?.schema_version !== SCHEMA_VERSION) throw new Error('不支持的 RoadWeaver 路网快照版本')
    if (!snapshot.available) {
      this.clear(String(snapshot.reason || 'snapshot_unavailable'))
      this.sourceVersion = String(snapshot.source_version || '')
      this.generatedAtMs = finite(snapshot.generated_at_ms, 0)
      return
    }
    if (!Array.isArray(snapshot.roads)) throw new Error('RoadWeaver 快照缺少 roads')
    const nodes = []
    const edges = []
    const adjacency = []
    const endpointIndices = []
    for (let roadIndex = 0; roadIndex < snapshot.roads.length; roadIndex++) {
      const road = snapshot.roads[roadIndex]
      if (!Array.isArray(road?.points) || road.points.length < 2) continue
      let previous = null
      let first = null
      for (const raw of road.points) {
        const point = normalizedPoint(raw)
        if (!point) continue
        const index = nodes.length
        nodes.push({ ...point, road: roadIndex, width: bounded(road.width, 1, 64, 1) })
        adjacency.push([])
        if (first == null) first = index
        if (previous != null && distance(nodes[previous], point) <= 48) addEdge(edges, adjacency, nodes, previous, index)
        previous = index
      }
      if (first != null && previous != null && first !== previous) endpointIndices.push(first, previous)
    }
    connectEndpoints(nodes, edges, adjacency, endpointIndices)
    this.nodes = nodes
    this.edges = edges
    this.adjacency = adjacency
    this.roadCount = snapshot.roads.length
    this.generatedAtMs = finite(snapshot.generated_at_ms, Date.now())
    this.sourceVersion = String(snapshot.source_version || '')
    this.dimension = normalizeDimension(snapshot.dimension)
    this.reason = nodes.length > 1 ? 'ready' : 'no_roads'
    this.lastError = ''
  }

  clear(reason, error = '') {
    this.nodes = []
    this.edges = []
    this.adjacency = []
    this.roadCount = 0
    this.reason = reason
    this.lastError = error
  }

  plan(start, target, dimension) {
    this.reloadIfChanged()
    this.pruneInvalidZones()
    if (!this.enabled || this.nodes.length < 2 || normalizeDimension(dimension) !== this.dimension) return null
    if (Date.now() - this.generatedAtMs > MAX_SNAPSHOT_AGE_MS) {
      this.reason = 'snapshot_stale'
      return null
    }
    const startIndex = nearestNode(this.nodes, start, ENTRY_RADIUS, this.invalidZones)
    const targetIndex = nearestNode(this.nodes, target, ENTRY_RADIUS, this.invalidZones)
    if (startIndex < 0 || targetIndex < 0) return null
    const indices = aStar(this.nodes, this.adjacency, startIndex, targetIndex, this.invalidZones)
    if (indices.length < 2) return null
    const startConnector = distance(start, this.nodes[startIndex])
    const endConnector = distance(target, this.nodes[targetIndex])
    const roadDistance = polylineDistance(indices.map(index => this.nodes[index]))
    const directDistance = Math.max(1, distance(start, target))
    const estimatedCost = startConnector + roadDistance * ROAD_WEIGHT + endConnector
    if (estimatedCost > directDistance * 1.35) return null
    const rawPoints = [normalizedPoint(start), ...indices.map(index => this.nodes[index]), normalizedPoint(target)].filter(Boolean)
    return {
      backend: 'roadweaver-hybrid',
      points: samplePolyline(rawPoints, 8),
      road_distance: round(roadDistance),
      connector_distance: round(startConnector + endConnector),
      estimated_cost: round(estimatedCost),
      source_version: this.sourceVersion
    }
  }

  invalidateNear(position, radius = 5, lifetimeMs = 10 * 60 * 1000) {
    const point = normalizedPoint(position)
    if (!point) return
    this.invalidZones.push({ ...point, radius, expires_at_ms: Date.now() + lifetimeMs })
    if (this.invalidZones.length > 64) this.invalidZones.shift()
  }

  distanceToRoad(position) {
    if (!position || !this.nodes.length) return Infinity
    let best = Infinity
    for (const node of this.nodes) best = Math.min(best, distance(node, position))
    return best
  }

  pruneInvalidZones() {
    const now = Date.now()
    this.invalidZones = this.invalidZones.filter(zone => zone.expires_at_ms > now)
  }
}

function connectEndpoints(nodes, edges, adjacency, endpoints) {
  const buckets = new Map()
  for (const index of endpoints) {
    const node = nodes[index]
    const key = `${Math.floor(node.x / 8)},${Math.floor(node.z / 8)}`
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(index)
  }
  const seen = new Set()
  for (const index of endpoints) {
    const node = nodes[index]
    const bx = Math.floor(node.x / 8)
    const bz = Math.floor(node.z / 8)
    for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
      for (const other of buckets.get(`${bx + dx},${bz + dz}`) || []) {
        if (other === index || nodes[other].road === node.road) continue
        const key = index < other ? `${index}:${other}` : `${other}:${index}`
        if (seen.has(key)) continue
        seen.add(key)
        if (horizontalDistance(node, nodes[other]) <= Math.max(4, node.width, nodes[other].width) &&
            Math.abs(node.y - nodes[other].y) <= 4) addEdge(edges, adjacency, nodes, index, other)
      }
    }
  }
}

function addEdge(edges, adjacency, nodes, left, right) {
  const edge = { left, right, distance: distance(nodes[left], nodes[right]) }
  const edgeIndex = edges.length
  edges.push(edge)
  adjacency[left].push({ node: right, edge: edgeIndex })
  adjacency[right].push({ node: left, edge: edgeIndex })
}

function aStar(nodes, adjacency, start, goal, invalidZones) {
  const open = new MinHeap()
  const cameFrom = new Map()
  const best = new Map([[start, 0]])
  open.push({ node: start, score: distance(nodes[start], nodes[goal]) })
  let expanded = 0
  while (open.size && expanded++ < MAX_EXPANDED_NODES) {
    const current = open.pop().node
    if (current === goal) return reconstruct(cameFrom, current)
    const currentCost = best.get(current)
    for (const step of adjacency[current] || []) {
      if (insideInvalidZone(nodes[current], invalidZones) || insideInvalidZone(nodes[step.node], invalidZones)) continue
      const tentative = currentCost + stepCost(nodes[current], nodes[step.node])
      if (tentative >= (best.get(step.node) ?? Infinity)) continue
      cameFrom.set(step.node, current)
      best.set(step.node, tentative)
      open.push({ node: step.node, score: tentative + distance(nodes[step.node], nodes[goal]) * ROAD_WEIGHT })
    }
  }
  return []
}

function stepCost(left, right) {
  const slopePenalty = Math.abs(left.y - right.y) * 0.35
  return distance(left, right) * ROAD_WEIGHT + slopePenalty
}

function nearestNode(nodes, position, radius, invalidZones) {
  let best = radius
  let result = -1
  for (let index = 0; index < nodes.length; index++) {
    if (insideInvalidZone(nodes[index], invalidZones)) continue
    const value = distance(nodes[index], position)
    if (value < best) { best = value; result = index }
  }
  return result
}

function insideInvalidZone(point, zones) {
  return zones.some(zone => Math.abs(point.y - zone.y) <= 5 && horizontalDistance(point, zone) <= zone.radius)
}

function samplePolyline(points, spacing) {
  if (points.length <= 2) return points
  const output = [points[0]]
  let accumulated = 0
  let previous = points[0]
  for (let index = 1; index < points.length - 1; index++) {
    accumulated += distance(previous, points[index])
    previous = points[index]
    if (accumulated >= spacing) {
      output.push(points[index])
      accumulated = 0
    }
  }
  output.push(points[points.length - 1])
  return output
}

function reconstruct(cameFrom, node) {
  const result = [node]
  while (cameFrom.has(node)) { node = cameFrom.get(node); result.push(node) }
  return result.reverse()
}

class MinHeap {
  constructor() { this.values = [] }
  get size() { return this.values.length }
  push(value) {
    this.values.push(value)
    let index = this.values.length - 1
    while (index > 0) {
      const parent = Math.floor((index - 1) / 2)
      if (this.values[parent].score <= value.score) break
      this.values[index] = this.values[parent]
      index = parent
    }
    this.values[index] = value
  }
  pop() {
    const root = this.values[0]
    const tail = this.values.pop()
    if (this.values.length && tail) {
      let index = 0
      while (true) {
        const left = index * 2 + 1
        const right = left + 1
        if (left >= this.values.length) break
        let child = right < this.values.length && this.values[right].score < this.values[left].score ? right : left
        if (this.values[child].score >= tail.score) break
        this.values[index] = this.values[child]
        index = child
      }
      this.values[index] = tail
    }
    return root
  }
}

function normalizedPoint(value) {
  const x = Number(value?.x), y = Number(value?.y), z = Number(value?.z)
  return [x, y, z].every(Number.isFinite) ? { x, y, z } : null
}

function finite(value, fallback) { return Number.isFinite(Number(value)) ? Number(value) : fallback }
function bounded(value, min, max, fallback) { const parsed = Number(value); return Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : fallback }
function horizontalDistance(a, b) { return Math.hypot(Number(a.x) - Number(b.x), Number(a.z) - Number(b.z)) }
function distance(a, b) { return Math.hypot(Number(a.x) - Number(b.x), Number(a.y) - Number(b.y), Number(a.z) - Number(b.z)) }
function polylineDistance(points) { let result = 0; for (let i = 1; i < points.length; i++) result += distance(points[i - 1], points[i]); return result }
function round(value) { return Math.round(Number(value) * 100) / 100 }
function normalizeDimension(value) { const v = String(value || ''); return v === 'overworld' ? 'minecraft:overworld' : v }
function safeMessage(error) { return String(error?.message || error || 'unknown error').replace(/[\r\n\t]+/g, ' ').slice(0, 300) }

module.exports = { RoadNetwork, samplePolyline }
