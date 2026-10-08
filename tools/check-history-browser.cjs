// Real-browser evidence for Interaction 10: named selections and their algebra, undo/redo across
// linked plots without host effects, a pan drag recorded as one entry however long it is held (and
// when Escape abandons it) while a key-zoom run ends at its pause, snapshots that survive a reload
// and refuse tampering or a viewport the plot cannot draw, and an explicit filter whose report is
// checked against an oracle. One renderer per run, chosen by
// INTAGLIO_TEST_RENDERER (svg or canvas; default svg), so the suite runner can pair traces.
//
// Usage: node tools/check-history-browser.cjs <fixture main.js> <output dir>
// Build the fixture first: sbt browserFixture/fastLinkJS
// Runs Playwright's own Chromium headless; never a system browser or profile. Audit browser
// ownership before and after invoking this script (see AGENTS.md).
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

async function main() {
  assert.equal(process.argv.length, 4, 'Pass the fixture main.js and an output directory');
  const script = path.resolve(process.argv[2]);
  const out = path.resolve(process.argv[3]);
  const renderer = process.env.INTAGLIO_TEST_RENDERER === 'canvas' ? 'canvas' : 'svg';
  await fs.mkdir(out, { recursive: true });
  const template = await fs.readFile(path.join(__dirname, 'browser', 'history.html'), 'utf8');
  const page = path.join(out, 'history.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));
  const url = pathToFileURL(page).href + (renderer === 'canvas' ? '?canvas' : '');

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = { browser: browser.version(), renderer, checks: [], trace: [] };
  const check = (name, fn) => fn().then(detail => report.checks.push({ name, ok: true, detail }));
  try {
    const tab = await browser.newPage({ viewport: { width: 1000, height: 900 } });
    const errors = [];
    tab.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    tab.on('pageerror', e => errors.push(String(e)));
    const open = async () => {
      await tab.goto(url);
      await tab.waitForFunction(() => window.intaglioHistory && window.intaglioHistory.ready);
    };
    await open();
    const fx = (body, ...args) => tab.evaluate(body, ...args);
    const h = (name, ...args) => fx(([n, a]) => window.intaglioHistory[n](...a), [name, args]);
    const settle = (ms = 100) => tab.waitForTimeout(ms);
    const sorted = xs => [...xs].sort();
    const eventsOf = slot => fx(s => window.intaglioHistory.events[s].slice(), slot);
    const ids = await fx(() => window.intaglioHistory.ids.slice());
    const rts = await fx(() => window.intaglioHistory.rts.slice());
    const breaks = await fx(() => window.intaglioHistory.breaks.slice());
    const binOf = rt => breaks.findIndex((b, j) => j < breaks.length - 1 && (rt > b || (j === 0 && rt === b)) && rt <= breaks[j + 1]);

    let sweep, bin;
    await check('a swept area and a bin\'s members are saved by name', async () => {
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Select area' }).click();
      const r = await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-base').getBoundingClientRect().toJSON());
      const x0 = r.left + r.width * 0.02, y0 = r.top + r.height * 0.05, x1 = r.left + r.width * 0.98, y1 = r.top + r.height * 0.45;
      await tab.mouse.move(x0, y0); await tab.mouse.down(); await tab.mouse.move(x1, y1, { steps: 8 }); await tab.mouse.up();
      await settle();
      const marks = await h('marks', 'a');
      sweep = sorted(marks.filter(([, x, y]) => x >= x0 && x <= x1 && y >= y0 && y <= y1).map(m => m[0]));
      assert.deepEqual(await h('selected', 'a'), sweep, 'the sweep selects the marks inside it');
      assert.equal(await h('save', 'a', 'sweep'), 'ok');
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Inspect' }).click();
      const [bx, by] = (await h('bins'))[1];
      await tab.mouse.click(bx, by);
      await settle();
      bin = sorted(ids.filter((_, i) => binOf(rts[i]) === 1));
      assert.deepEqual(await h('selected', 'a'), bin, 'the bin\'s members reach scatter a');
      assert.equal(await h('save', 'a', 'bin'), 'ok');
      assert.deepEqual(Object.keys(await h('named', 'a')).sort(), ['bin', 'sweep']);
      return { sweep: sweep.length, bin: bin.length };
    });

    await check('intersection, union and difference of saved selections match set oracles', async () => {
      const s = new Set(sweep), b = new Set(bin);
      const oracles = {
        Intersection: sorted(sweep.filter(x => b.has(x))),
        Union: sorted([...new Set([...sweep, ...bin])]),
        Difference: sorted(sweep.filter(x => !b.has(x)))
      };
      for (const [how, expected] of Object.entries(oracles)) {
        assert.equal(await h('combine', 'a', 'sweep', 'bin', how, how.toLowerCase()), 'ok');
        assert.deepEqual((await h('named', 'a'))[how.toLowerCase()], expected, how);
      }
      assert.ok(oracles.Intersection.length > 0 && oracles.Difference.length > 0, 'a partial overlap');
      // Recalling with Intersect keeps only the overlap with the current selection (the bin).
      assert.equal(await h('recall', 'a', 'sweep', 'Intersect'), 'ok');
      await settle();
      assert.deepEqual(await h('selected', 'a'), oracles.Intersection);
      assert.deepEqual(await h('selected', 'b'), oracles.Intersection, 'the link carries the recall');
      assert.ok([...s].length > 0);
      return Object.fromEntries(Object.entries(oracles).map(([k, v]) => [k, v.length]));
    });

    const mod = process.platform === 'darwin' ? 'Meta' : 'Control';
    const overlap = sorted(sweep.filter(x => bin.includes(x)));
    await check('keyboard undo and redo walk this plot\'s changes and the link follows, without effects', async () => {
      const before = Object.fromEntries(await Promise.all(['a', 'b', 'h'].map(async s => [s, (await eventsOf(s)).length])));
      assert.equal(await h('canUndo', 'b'), false, 'projected input is not history in the linked plot');
      await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-plot').focus());
      await tab.keyboard.press(`${mod}+z`); // undo the Intersect recall
      await settle();
      assert.deepEqual(await h('selected', 'a'), bin);
      assert.deepEqual(await h('selected', 'b'), bin, 'the linked plot follows the undo');
      assert.equal(await h('canRedo', 'a'), true);
      await tab.keyboard.press(`${mod}+Shift+z`); // redo it
      await settle();
      assert.deepEqual(await h('selected', 'a'), overlap);
      assert.deepEqual(await h('selected', 'b'), overlap, 'the linked plot follows the redo');
      await tab.keyboard.press(`${mod}+z`);
      await settle();
      await tab.keyboard.press('Control+y'); // the other redo shortcut
      await settle();
      assert.deepEqual(await h('selected', 'a'), overlap);
      assert.equal(await h('canRedo', 'a'), false);
      assert.equal(await h('canUndo', 'b'), false, 'undo and redo carried by the link are not history there');
      const later = [];
      for (const [slot, n] of Object.entries(before)) later.push(...(await eventsOf(slot)).slice(n));
      assert.ok(later.length > 0 && later.every(e => e.startsWith('SelectionChanged')), later.join(' '));
      report.trace.push(...later);
      return { events: later.length };
    });

    const near = (a, b) => a && b && a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) < 1e-9);
    await check('a pan is one history entry, held still or not, and undo restores each window in turn', async () => {
      assert.equal(await h('zoom', 'a', 0.2, 0.5), 'ok'); // an application command: one entry
      await settle();
      const zoomed = await h('window', 'a');
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: /^Pan$/ }).click();
      const r = await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-base').getBoundingClientRect().toJSON());
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      await tab.mouse.move(cx, cy); await tab.mouse.down();
      for (let i = 1; i <= 10; i++) { await tab.mouse.move(cx - i * 6, cy, { steps: 1 }); await settle(20); }
      // The reader holds the button still for longer than the pause that ends a zoom run.
      await settle(900);
      const held = await h('window', 'a');
      assert.ok(held[0] > zoomed[0] + 1e-9, JSON.stringify({ zoomed, held }));
      assert.equal(await h('canUndo', 'a'), true, 'the open pan is undoable');
      assert.equal(await h('canRedo', 'a'), false);
      for (let i = 11; i <= 20; i++) { await tab.mouse.move(cx - i * 6, cy, { steps: 1 }); await settle(20); }
      await tab.mouse.up();
      await settle(700); // past any pause: nothing more is recorded after the release
      const panned = await h('window', 'a');
      assert.ok(panned[0] > held[0] + 1e-9, JSON.stringify({ held, panned }));
      await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-plot').focus());
      await tab.keyboard.press(`${mod}+z`); // undo the whole pan, the part before the hold too
      await settle(200);
      const back = await h('window', 'a');
      assert.ok(near(back, zoomed), JSON.stringify({ zoomed, held, back }));
      await tab.keyboard.press(`${mod}+z`); // undo the zoom
      await settle(200);
      assert.equal(await h('window', 'a'), null);
      assert.deepEqual(await h('selected', 'a'), overlap, 'navigation undo leaves the selection');
      await tab.keyboard.press(`${mod}+Shift+z`);
      await tab.keyboard.press(`${mod}+Shift+z`);
      await settle(200);
      const again = await h('window', 'a');
      assert.ok(Math.abs(again[0] - panned[0]) < 1e-9, JSON.stringify({ panned, again }));
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Inspect' }).click();
      return { zoomed, held, panned };
    });

    await check('Escape abandons a pan as one entry that keeps its window; a key-zoom run is one entry', async () => {
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: /^Pan$/ }).click();
      const start = await h('window', 'a');
      const r = await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-base').getBoundingClientRect().toJSON());
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      await tab.mouse.move(cx, cy); await tab.mouse.down();
      for (let i = 1; i <= 10; i++) { await tab.mouse.move(cx + i * 6, cy, { steps: 1 }); await settle(20); }
      await tab.keyboard.press('Escape');
      await settle(50);
      const abandoned = await h('window', 'a');
      assert.ok(!near(abandoned, start), JSON.stringify({ start, abandoned }));
      // The drag is over: moving the held pointer pans no further.
      for (let i = 11; i <= 15; i++) { await tab.mouse.move(cx + i * 6, cy, { steps: 1 }); await settle(20); }
      await tab.mouse.up();
      await settle(50);
      assert.ok(near(await h('window', 'a'), abandoned), 'the abandoned pan keeps the window it reached');
      // Three key zooms closer together than the pause are one run, recorded at the pause.
      for (let i = 0; i < 3; i++) { await tab.keyboard.press('='); await settle(60); }
      await settle(700);
      const zoomedIn = await h('window', 'a');
      assert.ok(zoomedIn[1] - zoomedIn[0] < abandoned[1] - abandoned[0] - 1e-9, JSON.stringify({ abandoned, zoomedIn }));
      await tab.keyboard.press(`${mod}+z`); // the whole key-zoom run
      await settle(200);
      assert.ok(near(await h('window', 'a'), abandoned), 'undo takes back the key-zoom run only');
      await tab.keyboard.press(`${mod}+z`); // the abandoned pan
      await settle(200);
      assert.ok(near(await h('window', 'a'), start), 'then the abandoned pan, as one entry');
      await tab.keyboard.press(`${mod}+Shift+z`);
      await tab.keyboard.press(`${mod}+Shift+z`);
      await settle(200);
      assert.ok(near(await h('window', 'a'), zoomedIn));
      await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Inspect' }).click();
      return { start, abandoned, zoomedIn };
    });

    await check('a snapshot survives a reload and restores keys, saved selections and the window', async () => {
      assert.equal(await h('zoom', 'a', 0.25, 0.6), 'ok');
      await settle();
      const window = await h('window', 'a');
      const selection = await h('selected', 'a');
      const named = await h('named', 'a');
      const json = await h('snapshot', 'a');
      await fs.writeFile(path.join(out, `${renderer}-snapshot.json`), json);
      await open(); // a fresh page: nothing selected, full window
      assert.deepEqual(await h('selected', 'a'), []);
      assert.equal(await h('window', 'a'), null);
      assert.equal(await h('restore', 'a', json), 'ok');
      await settle(200);
      assert.deepEqual(await h('selected', 'a'), selection);
      assert.deepEqual(await h('named', 'a'), named);
      const restored = await h('window', 'a');
      assert.ok(restored && Math.abs(restored[0] - window[0]) < 1e-9 && Math.abs(restored[1] - window[1]) < 1e-9, JSON.stringify({ window, restored }));
      return { window, observations: selection.length };
    });

    await check('a tampered snapshot is refused with the reason, and nothing changes', async () => {
      const json = await h('snapshot', 'a');
      const before = await h('selected', 'a');
      const cases = {
        schema: json.replace('"schema":1', '"schema":2'),
        codec: json.replace(/"version":1/g, '"version":9'),
        revision: json.replace('"dataRevision":"d1"', '"dataRevision":"d0"'),
        malformed: json.slice(0, -2)
      };
      const messages = {};
      const named = await h('named', 'a'), window = await h('window', 'a');
      for (const [k, bad] of Object.entries(cases)) messages[k] = await h('restore', 'a', bad);
      assert.match(messages.schema, /schema 2 is not supported/);
      assert.match(messages.codec, /codec/);
      assert.match(messages.revision, /data revision d0/);
      assert.match(messages.malformed, /not well formed/);
      assert.deepEqual(await h('selected', 'a'), before);
      assert.deepEqual(await h('named', 'a'), named);
      assert.deepEqual(await h('window', 'a'), window);
      return messages;
    });

    const panel = await fx(() => window.intaglioHistory.panel); // the widget's one navigable panel
    const dom = slot => fx(s => {
      const plot = document.querySelector(`[data-intaglio-widget=${s}] .intaglio-plot`);
      return plot.innerHTML + [...plot.querySelectorAll('canvas')].map(c => c.toDataURL()).join('');
    }, slot);
    await check('a viewport the plot cannot draw is refused before anything changes', async () => {
      const before = { a: await h('drawn', 'a'), selected: await h('selected', 'a'), named: await h('named', 'a'), dom: await dom('a') };
      const elsewhere = await h('restoreViewport', 'a', 'another-panel', 0.2, 0.4, 0.2, 0.4);
      assert.match(elsewhere, /cannot be applied: this view has no panel another-panel/);
      assert.deepEqual(await h('drawn', 'a'), before.a, 'window, recorded viewport and history unchanged');
      assert.deepEqual(await h('selected', 'a'), before.selected);
      assert.deepEqual(await h('named', 'a'), before.named);
      await settle(100);
      assert.equal(await dom('a'), before.dom, 'nothing is redrawn');
      // A faceted plot cannot navigate, so any viewport on it is refused.
      const fixedBefore = await h('drawn', 'f');
      await settle(100);
      const fixedDom = await dom('f');
      const fixed = await h('restoreViewport', 'f', panel, 0.2, 0.4, 0.2, 0.4);
      assert.match(fixed, /its viewport cannot be drawn: this plot .* cannot navigate/);
      assert.deepEqual(await h('drawn', 'f'), fixedBefore);
      assert.equal(fixedBefore.recorded, null);
      assert.equal(fixedBefore.canUndo, false);
      await settle(100);
      assert.equal(await dom('f'), fixedDom);
      return { elsewhere, fixed };
    });

    await check('a restored viewport outside the bounds is drawn shifted inside and recorded as drawn', async () => {
      const start = await h('drawn', 'a');
      assert.equal(await h('zoom', 'a', 0.3, 0.45), 'ok');
      await settle();
      const zoomed = await h('drawn', 'a');
      const [x0, x1] = zoomed.x;
      const [, , y0, y1] = zoomed.recorded;
      assert.equal(await h('restoreViewport', 'a', panel, x0 + 1e4, x1 + 1e4, y0, y1), 'ok');
      // Drawn and recorded at once, before any animation frame.
      const restored = await h('drawn', 'a');
      assert.ok(restored.x && Math.abs((restored.x[1] - restored.x[0]) - (x1 - x0)) < 1e-6, JSON.stringify({ zoomed, restored }));
      assert.ok(restored.x[0] > x0 + 1e-9, 'shifted to the far edge, inside the bounds');
      assert.ok(near(restored.recorded.slice(0, 2), restored.x), JSON.stringify(restored));
      const saved = JSON.parse(await h('snapshot', 'a')).viewports[0];
      assert.equal(saved.xMin, restored.recorded[0], 'a later snapshot names what was seen');
      assert.equal(await h('undo', 'a'), 'true', 'the restore is one entry');
      assert.ok(near(await h('window', 'a'), zoomed.x));
      assert.equal(await h('undo', 'a'), 'true');
      assert.deepEqual((await h('drawn', 'a')).x, start.x);
      // A viewport wider than the data draws the full window: the state records no viewport.
      assert.equal(await h('restoreViewport', 'a', panel, -1e9, 1e9, -1e9, 1e9), 'ok');
      assert.equal((await h('drawn', 'a')).recorded, null);
      return { zoomed: zoomed.x, restored: restored.x };
    });

    await check('the inspector shows each plot\'s selection, aggregate coverage and saved selections', async () => {
      const keep = await h('selected', 'a');
      const text = await h('inspector');
      const plot = label => fx(l => document.querySelector(`#history-inspector [data-plot="${l}"]`).textContent, label);
      const a = await plot('a');
      assert.ok(a.includes(`Observations selected${keep.length}`), a);
      for (const n of ['sweep', 'bin', 'intersection', 'union', 'difference'])
        assert.ok(a.includes(n), `saved selection ${n}`);
      // Each bin the selection reaches reads "k of n selected", against the bin oracle.
      const counts = breaks.slice(0, -1).map((_, j) => [keep.filter(id => binOf(rts[ids.indexOf(id)]) === j).length, ids.filter((_, i) => binOf(rts[i]) === j).length]);
      for (const [k, n] of counts.filter(([k]) => k > 0)) assert.ok(text.includes(`${k} of ${n} selected`), `${k} of ${n}`);
      assert.ok(text.includes('Unresolved observationsnone'), text);
      return { counts };
    });

    await check('filtering the histogram to a selection reports input and statistics, apart from emphasis', async () => {
      const keep = await h('selected', 'a');
      const named = await h('named', 'a');
      const result = await h('filterToSelection', 'a');
      assert.ok(Array.isArray(result), String(result));
      const [rowsBefore, rowsAfter, totals] = result;
      assert.equal(rowsBefore, ids.length);
      assert.equal(rowsAfter, keep.length);
      const counts = breaks.slice(0, -1).map((_, j) => keep.filter(id => binOf(rts[ids.indexOf(id)]) === j).length).filter(c => c > 0);
      assert.deepEqual(totals, counts, 'bin totals after the filter');
      await settle(150);
      const text = await h('inspector');
      assert.ok(text.includes(`${ids.length} → ${keep.length}`), text);
      assert.ok(text.includes('Statistical result'), text);
      // The filter selected and emphasized nothing: the scatters keep their selection, and the
      // recompiled histogram reconciles to the same observations.
      assert.deepEqual(await h('selected', 'a'), keep);
      assert.deepEqual(await h('selected', 'b'), keep);
      assert.deepEqual(await h('selected', 'h'), keep);
      assert.deepEqual(await h('named', 'a'), named);
      assert.deepEqual(errors, []);
      await tab.screenshot({ path: path.join(out, `${renderer}-history.png`) });
      return { rowsAfter, totals };
    });
  } finally {
    await browser.close();
  }
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
