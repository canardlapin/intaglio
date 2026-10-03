// Real-browser evidence for Interaction 10: named selections and their algebra, undo/redo across
// linked plots without host effects, snapshots that survive a reload and refuse tampering, and an
// explicit filter whose report is checked against an oracle. One renderer per run, chosen by
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

    await check('keyboard undo and redo walk this plot\'s changes and the link follows, without effects', async () => {
      const before = { a: (await eventsOf('a')).length, h: (await eventsOf('h')).length };
      await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-plot').focus());
      const mod = process.platform === 'darwin' ? 'Meta' : 'Control';
      await tab.keyboard.press(`${mod}+z`); // undo the Intersect recall
      await settle();
      assert.deepEqual(await h('selected', 'a'), bin);
      assert.deepEqual(await h('selected', 'b'), bin, 'the linked plot follows the undo');
      await tab.keyboard.press(`${mod}+Shift+z`); // redo it
      await settle();
      const overlap = sorted(sweep.filter(x => bin.includes(x)));
      assert.deepEqual(await h('selected', 'a'), overlap);
      const later = [...(await eventsOf('a')).slice(before.a), ...(await eventsOf('h')).slice(before.h)];
      assert.ok(later.every(e => !e.startsWith('Activated') && !e.startsWith('MembershipRequested')), later.join(' '));
      report.trace.push(...later);
      return { events: later.length };
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
      for (const [k, bad] of Object.entries(cases)) messages[k] = await h('restore', 'a', bad);
      assert.match(messages.schema, /schema 2 is not supported/);
      assert.match(messages.codec, /codec/);
      assert.match(messages.revision, /data revision d0/);
      assert.match(messages.malformed, /not well formed/);
      assert.deepEqual(await h('selected', 'a'), before);
      return messages;
    });

    await check('filtering the histogram to a selection reports input and statistics, apart from emphasis', async () => {
      const keep = await h('selected', 'a');
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
