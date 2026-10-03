// Real-browser evidence for linked views (Interaction 06).
//
// Usage: node tools/check-linked-browser.cjs <fixture main.js> <output dir>
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
  await fs.mkdir(out, { recursive: true });
  const template = await fs.readFile(path.join(__dirname, 'browser', 'linked.html'), 'utf8');
  const page = path.join(out, 'linked.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const renderer = process.env.INTAGLIO_TEST_RENDERER || 'svg';
  assert.ok(['svg', 'canvas'].includes(renderer));
  const report = { renderer, browser: browser.version(), checks: [] };
  const check = (name, fn) => fn().then(detail => report.checks.push({ name, ok: true, detail }));
  try {
    const context = await browser.newContext({ viewport: { width: 1100, height: 900 } });
    const tab = await context.newPage();
    const consoleErrors = [];
    tab.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    tab.on('pageerror', e => consoleErrors.push(String(e)));
    await tab.goto(pathToFileURL(page).href + `?renderer=${renderer}`);
    await tab.waitForFunction(() => window.intaglioLinked && window.intaglioLinked.ready);
    const fx = (body, ...args) => tab.evaluate(body, ...args);
    const L = name => fx(n => window.intaglioLinked[n], name);
    const point = (slot, i) => fx(([s, n]) => window.intaglioLinked.markPoint(s, n), [slot, i]);
    const selected = slot => fx(s => window.intaglioLinked.selected(s), slot);
    const rings = (slot, kind) => fx(([s, k]) => window.intaglioLinked.rings(s, k), [slot, kind]);
    const eventCount = slot => fx(s => window.intaglioLinked.events[s].length, slot);
    const counts = async () => Object.fromEntries(await Promise.all(['a', 'b', 'c', 'd'].map(async s => [s, await eventCount(s)])));
    const settle = () => tab.waitForTimeout(60);

    await check('hovering a mark emphasizes the same observation in the differently ordered scatter only', async () => {
      const entity = await fx(() => window.intaglioLinked.markEntity('a', 4));
      const indexB = await fx(e => window.intaglioLinked.indexOf('b', e), entity);
      assert.notEqual(indexB, 4, 'the row orders differ');
      const before = await counts();
      const [x, y] = await point('a', 4);
      await tab.mouse.move(x, y);
      await settle();
      assert.equal(await rings('b', 'linked'), 1);
      // The ring surrounds the same observation's mark in B, not whatever sits at A's row index.
      // Containment, not centre distance: a mark at the panel edge has a clipped, off-centre ring.
      const ringBox = await fx(() => {
        const r = document.querySelector('[data-intaglio-widget=b] .intaglio-ring-linked').getBoundingClientRect();
        return [r.left, r.top, r.right, r.bottom];
      });
      const markB = await point('b', indexB);
      assert.ok(markB[0] >= ringBox[0] && markB[0] <= ringBox[2] && markB[1] >= ringBox[1] && markB[1] <= ringBox[3],
        JSON.stringify({ ringBox, markB }));
      const markAtSameIndex = await point('b', 4);
      assert.ok(!(markAtSameIndex[0] >= ringBox[0] && markAtSameIndex[0] <= ringBox[2] &&
        markAtSameIndex[1] >= ringBox[1] && markAtSameIndex[1] <= ringBox[3]), 'not linked by row index');
      assert.equal(await rings('c', 'linked'), 0, 'bins carry no observation key');
      assert.equal(await rings('d', 'linked'), 0, 'an impostor key space never joins');
      const after = await counts();
      assert.deepEqual({ b: after.b, c: after.c, d: after.d }, { b: before.b, c: before.c, d: before.d }, 'no events elsewhere');
      await tab.screenshot({ path: path.join(out, 'linked-hover.png') });
      return { entity, indexB };
    });

    await check('a reader selection is projected silently; missing and foreign keys are reported', async () => {
      const before = await counts();
      const [x, y] = await point('a', 2);
      await tab.mouse.click(x, y);
      await settle();
      const a = await selected('a');
      assert.equal(a.length, 1);
      assert.deepEqual(await selected('b'), a);
      assert.equal(await rings('b', 'selected'), 1);
      assert.equal(await rings('c', 'selected'), 0, 'the histogram does not show observations as bins');
      assert.deepEqual(await selected('d'), []);
      const after = await counts();
      assert.deepEqual({ b: after.b, c: after.c, d: after.d }, { b: before.b, c: before.c, d: before.d }, 'no echo events');
      const missing = await L('missing');
      assert.ok(missing.includes(`d:${a[0]}`), missing.join(' / '));
      return { a, missing };
    });

    await check('a bin selected in the histogram stays a bin and leaves the linked selection alone', async () => {
      const a = await selected('a');
      const [x, y] = await point('c', 0);
      await tab.mouse.click(x, y);
      await settle();
      assert.deepEqual(await selected('c'), ['#target']);
      assert.deepEqual(await selected('a'), a);
      assert.deepEqual(await selected('b'), a);
      return { c: await selected('c') };
    });

    await check('clearing a bin with Escape leaves the linked observation selection alone', async () => {
      const a = await selected('a');
      assert.ok(a.length > 0);
      const [x, y] = await point('c', 1);
      await tab.mouse.click(x, y);
      await tab.keyboard.press('Escape');
      await settle();
      assert.deepEqual(await selected('c'), [], 'the histogram cleared its own bin');
      assert.deepEqual(await selected('a'), a);
      assert.deepEqual(await selected('b'), a);
      return { a };
    });

    await check('legend links that cannot link are refused at mount; links do not chain', async () => {
      const results = await fx(() => window.intaglioLinked.badLegendLinks());
      assert.match(results[0], /matches no mark/);
      assert.match(results[1], /draws no legend 'blocks-legend'/);
      assert.match(await fx(() => window.intaglioLinked.relink()), /already belongs to a live link/);
      return { results };
    });

    await check('a legend in a foreign key space selects only its own plot\'s marks', async () => {
      const a = await selected('a');
      const before = await counts();
      const centre = await fx(() => window.intaglioLinked.legendPoint('d'));
      await tab.mouse.click(centre[0], centre[1]);
      await settle();
      assert.deepEqual(await selected('a'), a);
      assert.equal((await selected('d')).length, await fx(() => window.intaglioLinked.blockCount('A')));
      assert.equal(await rings('a', 'linked'), 0);
      const after = await counts();
      assert.equal(after.a, before.a);
      return {};
    });

    await check('a linked legend entry emphasizes and selects its category in both scatters', async () => {
      const blockA = await fx(() => window.intaglioLinked.blockCount('A'));
      const centre = await fx(() => window.intaglioLinked.legendPoint('a'));
      await tab.mouse.move(centre[0], centre[1]);
      await settle();
      assert.equal(await rings('a', 'linked'), blockA);
      assert.equal(await rings('b', 'linked'), blockA);
      assert.equal(await rings('d', 'linked'), 0, 'equal category labels in another key space never join');
      await tab.screenshot({ path: path.join(out, 'linked-legend.png') });
      await tab.mouse.move(5,5); await settle();
      assert.equal(await rings('a','linked'),0,'leaving legend clears local category emphasis');
      assert.equal(await rings('b','linked'),0,'leaving legend clears projected category emphasis');
      await tab.mouse.move(centre[0],centre[1]); await settle();
      assert.equal(await rings('b','linked'),blockA);
      await tab.mouse.click(centre[0], centre[1]);
      await settle();
      assert.equal((await selected('a')).length, blockA);
      assert.deepEqual(await selected('b'), await selected('a'));
      return { blockA };
    });

    await check('application-controlled selection is the application\'s own: it does not propagate', async () => {
      const b = await selected('b');
      const before = await counts();
      // setSelection is Projected: A's state changes, nothing is emitted, so no link reacts.
      assert.equal(await fx(() => window.intaglioLinked.selectInA(['t2'])), 'ok');
      await settle();
      assert.deepEqual(await selected('a'), ['t2']);
      const after = await counts();
      assert.deepEqual(after, before);
      assert.deepEqual(await selected('b'), b);
      return {};
    });

    await check('replacing one plot\'s data reconciles its selection and later projections report missing keys', async () => {
      assert.equal(await fx(() => window.intaglioLinked.replaceB()), 'ok');
      const b = await selected('b');
      assert.ok(b.every(id => !['t1', 't2', 't3', 't4', 't5'].includes(id)), b.join(','));
      const index = await fx(() => window.intaglioLinked.indexOf('a', 't1'));
      const [x, y] = await point('a', index);
      await tab.mouse.click(x, y);
      await settle();
      assert.deepEqual(await selected('a'), ['t1']);
      assert.deepEqual(await selected('b'), []);
      const missing = await L('missing');
      assert.ok(missing.includes('b:t1'), missing.join(' / '));
      return { missing: missing.slice(-3) };
    });

    await check('a key only one plot has survives an additive change in the other', async () => {
      // After replaceB, A holds t1 and B cannot. Shift-click t9 in B: A must keep t1 and add t9.
      assert.deepEqual(await selected('a'), ['t1']);
      const index = await fx(() => window.intaglioLinked.indexOf('b', 't9'));
      const [x, y] = await point('b', index);
      await tab.keyboard.down('Shift');
      await tab.mouse.click(x, y);
      await tab.keyboard.up('Shift');
      await settle();
      assert.deepEqual(await selected('b'), ['t9']);
      assert.deepEqual(await selected('a'), ['t1', 't9']);
      return {};
    });

    await check('disposing the link clears its emphasis and stops projection', async () => {
      const index = await fx(() => window.intaglioLinked.indexOf('a', 't9'));
      const [x, y] = await point('a', index);
      await tab.mouse.move(x, y);
      await settle();
      assert.equal(await rings('b', 'linked'), 1, 'B contains t9 and shows it linked');
      await fx(() => window.intaglioLinked.unlink());
      await settle();
      assert.equal(await rings('b', 'linked'), 0, 'unlinking clears the emphasis it set');
      const b = await selected('b');
      await tab.mouse.click(x, y);
      await settle();
      assert.deepEqual(await selected('a'), ['t9']);
      assert.deepEqual(await selected('b'), b);
      assert.equal(await rings('b', 'linked'), 0);
      assert.deepEqual(consoleErrors, []);
      return {};
    });
    report.trace = await fx(() => ({ events: window.intaglioLinked.events,
      selected: Object.fromEntries(['a', 'b', 'c', 'd'].map(s => [s, window.intaglioLinked.selected(s)])) }));
    if (renderer === 'canvas') {
      assert.equal(await tab.locator('svg.intaglio-base').count(), 0);
      assert.equal(await tab.locator('canvas.intaglio-base[data-render-state=ready]').count(), 4);
      const pixels = await fx(() => [...document.querySelectorAll('canvas.intaglio-base')].map(c => {
        const data = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        let ink = 0; for(let i=0; i<data.length; i+=4) if(data[i+3] && Math.min(data[i],data[i+1],data[i+2]) < 200) ink++;
        return ink;
      }));
      assert.ok(pixels.every(n => n > 1000), JSON.stringify(pixels));
      report.canvasInk = pixels;
    }
    await context.close();
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
