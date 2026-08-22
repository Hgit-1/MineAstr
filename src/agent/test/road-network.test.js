'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { RoadNetwork } = require('../road-network')

function fixture(roads, extra = {}) {
  return {
    schema_version: 1,
    source: 'roadweaver',
    source_version: '2.3.1',
    dimension: 'minecraft:overworld',
    generated_at_ms: Date.now(),
    available: true,
    roads,
    ...extra
  }
}

function withSnapshot(snapshot) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mineastr-road-test-'))
  const file = path.join(root, 'road-network.json')
  fs.writeFileSync(file, JSON.stringify(snapshot))
  return { root, file, network: new RoadNetwork(file) }
}

test('uses persisted RoadWeaver centerlines as a three-dimensional route', t => {
  const state = withSnapshot(fixture([{ width: 5, points: [
    { x: 0, y: 65, z: 0 }, { x: 20, y: 66, z: 0 }, { x: 40, y: 67, z: 0 }, { x: 60, y: 67, z: 0 }
  ] }]))
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  const route = state.network.plan({ x: 1, y: 65, z: 1 }, { x: 59, y: 67, z: 1 }, 'minecraft:overworld')
  assert.equal(route.backend, 'roadweaver-hybrid')
  assert.ok(route.points.some(point => point.y === 66))
  assert.equal(state.network.status().source_version, '2.3.1')
})

test('connects nearby RoadWeaver road endpoints into one graph', t => {
  const state = withSnapshot(fixture([
    { width: 5, points: [{ x: 0, y: 65, z: 0 }, { x: 20, y: 65, z: 0 }] },
    { width: 5, points: [{ x: 22, y: 65, z: 1 }, { x: 50, y: 65, z: 1 }] }
  ]))
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  assert.ok(state.network.plan({ x: 0, y: 65, z: 0 }, { x: 50, y: 65, z: 1 }, 'minecraft:overworld'))
})

test('temporarily blacklists a broken road area and falls back safely', t => {
  const state = withSnapshot(fixture([{ width: 5, points: [
    { x: 0, y: 65, z: 0 }, { x: 20, y: 65, z: 0 }, { x: 40, y: 65, z: 0 }
  ] }]))
  t.after(() => fs.rmSync(state.root, { recursive: true, force: true }))
  assert.ok(state.network.plan({ x: 0, y: 65, z: 0 }, { x: 40, y: 65, z: 0 }, 'minecraft:overworld'))
  state.network.invalidateNear({ x: 20, y: 65, z: 0 }, 6)
  assert.equal(state.network.plan({ x: 0, y: 65, z: 0 }, { x: 40, y: 65, z: 0 }, 'minecraft:overworld'), null)
})

test('rejects stale, unavailable, and wrong-dimension snapshots', t => {
  const stale = withSnapshot(fixture([{ points: [{ x: 0, y: 64, z: 0 }, { x: 10, y: 64, z: 0 }] }], {
    generated_at_ms: Date.now() - 10 * 60 * 1000
  }))
  t.after(() => fs.rmSync(stale.root, { recursive: true, force: true }))
  assert.equal(stale.network.plan({ x: 0, y: 64, z: 0 }, { x: 10, y: 64, z: 0 }, 'minecraft:overworld'), null)
  assert.equal(stale.network.plan({ x: 0, y: 64, z: 0 }, { x: 10, y: 64, z: 0 }, 'minecraft:the_nether'), null)
})
