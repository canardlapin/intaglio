// Real-browser evidence for region selection and data-window navigation (Interaction 05).
//
// Usage: node tools/check-navigation-browser.cjs <fixture main.js> <output dir>
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
  const template = await fs.readFile(path.join(__dirname, 'browser', 'navigation.html'), 'utf8');
  const page = path.join(out, 'navigation.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = { browser: browser.version(), checks: [] };
  const check = (name, fn) => fn().then(detail => report.checks.push({ name, ok: true, detail }));
  try {
    const context = await browser.newContext({ viewport: { width: 1100, height: 800 } });
    const tab = await context.newPage();
    const consoleErrors = [];
    tab.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    tab.on('pageerror', e => consoleErrors.push(String(e)));
    await tab.goto(pathToFileURL(page).href);
    await tab.waitForFunction(() => window.intaglioNav && window.intaglioNav.ready);
    const fx = (body, ...args) => tab.evaluate(body, ...args);
    const marks = slot => fx(s => window.intaglioNav.marks(s), slot);
    const selected = slot => fx(s => window.intaglioNav.selected(s), slot);
    const win = slot => fx(s => window.intaglioNav.window(s), slot);
    const dataAt = (slot, x, y) => fx(([s, a, b]) => window.intaglioNav.dataAt(s, a, b), [slot, x, y]);
    const statCalls = () => fx(() => window.intaglioNav.statCalls());
    const settle = () => tab.waitForTimeout(80);
    const button = (slot, name) => tab.locator(`[data-intaglio-widget=${slot}] .intaglio-toolbar button`, { hasText: name });
    const panelBox = slot => fx(s => {
      const r = document.querySelector(`[data-intaglio-widget=${s}] svg.intaglio-base`).getBoundingClientRect();
      return { left: r.left, top: r.top, right: r.right, bottom: r.bottom, width: r.width, height: r.height };
    }, slot);
    const drag = async (from, to, steps = 8, modifiers = []) => {
      for (const m of modifiers) await tab.keyboard.down(m);
      await tab.mouse.move(from[0], from[1]);
      await tab.mouse.down();
      await tab.mouse.move(to[0], to[1], { steps });
      await tab.mouse.up();
      for (const m of modifiers) await tab.keyboard.up(m);
      await settle();
    };
    const inside = (ms, x0, y0, x1, y1) =>
      ms.filter(([, x, y]) => x >= Math.min(x0, x1) && x <= Math.max(x0, x1) && y >= Math.min(y0, y1) && y <= Math.max(y0, y1))
        .map(([id]) => id).sort();
    const baseCalls = await statCalls();
    // The drawn circles, read from the DOM, must sit on the oracle's marks: this checks the markup
    // actually swapped in, not only the window the oracle rebuilds.
    const drawnMatchOracle = async slot => {
      const ms = await marks(slot);
      const panel = await fx(s => window.intaglioNav.panel(s), slot);
      const drawn = await fx(([s, p]) => [...document.querySelectorAll(`[data-intaglio-widget=${s}] svg.intaglio-base circle`)]
        .map(c => c.getBoundingClientRect()).map(r => [(r.left + r.right) / 2, (r.top + r.bottom) / 2])
        .filter(([x, y]) => x > p[0] + 6 && x < p[2] - 6 && y > p[1] + 6 && y < p[3] - 6), [slot, panel]);
      assert.ok(drawn.length > 0, 'some marks are drawn inside the panel');
      for (const [x, y] of drawn)
        assert.ok(ms.some(([, mx, my]) => Math.hypot(mx - x, my - y) < 0.75), `drawn mark at ${x},${y} has no oracle mark`);
      return drawn.length;
    };

    await check('mode and reset controls are visible and report their state', async () => {
      const pressed = await fx(() => [...document.querySelectorAll('[data-intaglio-widget=linear] .intaglio-toolbar button')]
        .map(b => [b.textContent, b.getAttribute('aria-pressed'), b.hidden]));
      assert.deepEqual(pressed.map(p => p[0]), ['Inspect', 'Select area', 'Lasso', 'Pan', 'Zoom to area', 'Reset view']);
      assert.equal(pressed[0][1], 'true');
      assert.ok(pressed.every(p => p[2] === false));
      return { pressed };
    });

    await check('a rectangle selects exactly the marks it covers; Shift adds and Alt subtracts', async () => {
      await button('linear', 'Select area').click();
      const b = await panelBox('linear');
      const ms = await marks('linear');
      const r1 = [b.left + b.width * 0.15, b.top + b.height * 0.1, b.left + b.width * 0.45, b.top + b.height * 0.9];
      await drag([r1[0], r1[1]], [r1[2], r1[3]]);
      const first = inside(ms, ...r1);
      assert.ok(first.length > 2);
      assert.deepEqual(await selected('linear'), first);
      const r2 = [b.left + b.width * 0.6, b.top + b.height * 0.1, b.left + b.width * 0.8, b.top + b.height * 0.9];
      await drag([r2[0], r2[1]], [r2[2], r2[3]], 8, ['Shift']);
      const added = [...new Set([...first, ...inside(ms, ...r2)])].sort();
      assert.deepEqual(await selected('linear'), added);
      const r3 = [b.left + b.width * 0.3, b.top + b.height * 0.05, b.left + b.width * 0.7, b.top + b.height * 0.95];
      await drag([r3[0], r3[1]], [r3[2], r3[3]], 8, ['Alt']);
      const removed = inside(ms, ...r3);
      assert.deepEqual(await selected('linear'), added.filter(id => !removed.includes(id)));
      await tab.screenshot({ path: path.join(out, 'rectangle-select.png') });
      return { first: first.length, added: added.length, removed: removed.length };
    });

    await check('a lasso selects the marks inside its polygon', async () => {
      await button('linear', 'Lasso').click();
      const b = await panelBox('linear');
      const ms = await marks('linear');
      const poly = [[0.2, 0.15], [0.55, 0.15], [0.55, 0.85], [0.2, 0.85]].map(([fx_, fy]) => [b.left + b.width * fx_, b.top + b.height * fy]);
      await tab.mouse.move(poly[0][0], poly[0][1]);
      await tab.mouse.down();
      for (const p of poly.slice(1)) await tab.mouse.move(p[0], p[1], { steps: 6 });
      await tab.mouse.move(poly[0][0], poly[0][1], { steps: 6 });
      await tab.mouse.up();
      await settle();
      const expected = inside(ms, poly[0][0], poly[0][1], poly[2][0], poly[2][1]);
      assert.deepEqual(await selected('linear'), expected);
      return { expected: expected.length };
    });

    await check('Escape abandons a drag without selecting', async () => {
      await button('linear', 'Select area').click();
      const before = await selected('linear');
      const b = await panelBox('linear');
      await tab.mouse.move(b.left + 20, b.top + 20);
      await tab.mouse.down();
      await tab.mouse.move(b.left + b.width - 20, b.top + b.height - 20, { steps: 6 });
      await tab.keyboard.press('Escape');
      const band = await fx(() => document.querySelectorAll('[data-intaglio-widget=linear] .intaglio-gesture > *').length);
      await tab.mouse.up();
      await settle();
      assert.equal(band, 0, 'the band is gone');
      assert.deepEqual(await selected('linear'), before);
      return {};
    });

    await check('wheel zoom keeps the data under the pointer, keeps the selection, and runs no statistic', async () => {
      await button('linear', 'Inspect').click();
      const sel = await selected('linear');
      const b = await panelBox('linear');
      const p = [b.left + b.width * 0.4, b.top + b.height * 0.5];
      await fx(() => document.querySelector('[data-intaglio-widget=linear] .intaglio-plot').focus());
      const before = await dataAt('linear', p[0], p[1]);
      await tab.mouse.move(p[0], p[1]);
      for (let i = 0; i < 4; i++) { await tab.mouse.wheel(0, -120); await settle(); }
      const after = await dataAt('linear', p[0], p[1]);
      assert.notEqual(await win('linear'), null);
      assert.ok(Math.abs(after[0] - before[0]) < 0.6, JSON.stringify({ before, after }));
      assert.deepEqual(await selected('linear'), sel);
      assert.equal(await statCalls(), baseCalls);
      const panelAfter = await panelBox('linear');
      assert.deepEqual([panelAfter.left, panelAfter.width], [b.left, b.width], 'the plot does not move');
      const drawn = await drawnMatchOracle('linear');
      await tab.screenshot({ path: path.join(out, 'wheel-zoom.png') });
      return { before, after, drawn };
    });

    await check('a burst of wheel events in one frame is applied in full and drawn once', async () => {
      const b = await panelBox('linear');
      const p = [b.left + b.width * 0.5, b.top + b.height * 0.5];
      const w0 = await win('linear');
      const swaps = await fx(([x, y]) => new Promise(resolve => {
        const host = document.querySelector('[data-intaglio-widget=linear] .intaglio-plot');
        host.focus();
        let count = 0;
        const observer = new MutationObserver(records => {
          count += records.filter(r => [...r.addedNodes].some(n => n.matches && n.matches('svg.intaglio-base'))).length;
        });
        observer.observe(host, { childList: true });
        for (let i = 0; i < 5; i++)
          host.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, clientX: x, clientY: y, bubbles: true, cancelable: true }));
        setTimeout(() => { observer.disconnect(); resolve(count); }, 200);
      }), p);
      const w1 = await win('linear');
      const ratio = (w1[1] - w1[0]) / (w0[1] - w0[0]);
      assert.ok(Math.abs(ratio - Math.exp(-1)) < 1e-6, `five notches compose: ${ratio}`);
      assert.equal(swaps, 1, 'one re-windowing for the burst');
      return { ratio, swaps };
    });

    await check('browser zoom keys stay with the browser; zooming out of the full view scrolls the page', async () => {
      await fx(() => {
        window.__prevented = [];
        document.addEventListener('keydown', e => window.__prevented.push([e.key, e.defaultPrevented]));
      });
      await fx(() => document.querySelector('[data-intaglio-widget=date] .intaglio-plot').focus());
      for (const key of ['Control+=', 'Control+-', 'Control+0', 'Meta+=']) await tab.keyboard.press(key);
      await settle();
      assert.equal(await win('date'), null);
      const prevented = await fx(() => window.__prevented.filter(([k]) => ['=', '-', '0'].includes(k)));
      assert.ok(prevented.length >= 3 && prevented.every(([, p]) => p === false), JSON.stringify(prevented));
      const y0 = await fx(() => window.scrollY);
      const b = await panelBox('date');
      await tab.mouse.move(b.left + b.width / 2, b.top + b.height / 2);
      await tab.mouse.wheel(0, 200);
      await settle();
      assert.equal(await win('date'), null);
      assert.ok((await fx(() => window.scrollY)) > y0, 'the page scrolled');
      await fx(() => window.scrollTo(0, 0));
      await settle();
      return { prevented };
    });

    await check('an application window is validated and kept inside the data', async () => {
      assert.match(await fx(() => window.intaglioNav.navigate('log', 0.6, 0.2)), /window/);
      assert.equal(await fx(() => window.intaglioNav.navigate('log', -0.2, 0.3)), 'ok');
      const w = await win('log');
      assert.ok(Math.abs(w[0]) < 1e-12 && Math.abs(w[1] - 0.5) < 1e-12, JSON.stringify(w));
      assert.equal(await fx(() => window.intaglioNav.navigate('log', 0, 1)), 'ok');
      assert.equal(await win('log'), null, 'a full window is the compiled view');
      return { w };
    });

    await check('without focus or Ctrl the wheel scrolls the page instead', async () => {
      await fx(() => document.activeElement && document.activeElement.blur());
      const before = await win('log');
      const y0 = await fx(() => window.scrollY);
      const b = await panelBox('log');
      await tab.mouse.move(b.left + b.width / 2, b.top + b.height / 2);
      await tab.mouse.wheel(0, 200);
      await settle();
      assert.deepEqual(await win('log'), before);
      assert.ok((await fx(() => window.scrollY)) > y0);
      await fx(() => window.scrollTo(0, 0));
      await settle();
      return {};
    });

    await check('pan mode drags the window and stops at the data bounds; reset restores it', async () => {
      await button('linear', 'Pan').click();
      const b = await panelBox('linear');
      const w0 = await win('linear');
      const from = [b.left + b.width * 0.5, b.top + b.height * 0.5];
      const to = [b.left + b.width * 0.3, b.top + b.height * 0.4];
      const grabbed = await dataAt('linear', ...from);
      // Many moves inside one animation frame: the pan is absolute, so none of them is lost.
      await drag(from, to, 24);
      const w1 = await win('linear');
      assert.ok(w1[0] > w0[0], JSON.stringify({ w0, w1 }));
      const held = await dataAt('linear', ...to);
      assert.ok(Math.abs(held[0] - grabbed[0]) < 0.3 && Math.abs(held[1] - grabbed[1]) < 0.02,
        `the grabbed data stays under the pointer: ${grabbed} -> ${held}`);
      await drag([b.left + 20, b.top + b.height / 2], [b.left + b.width - 20, b.top + b.height / 2], 4);
      await drag([b.left + 20, b.top + b.height / 2], [b.left + b.width - 20, b.top + b.height / 2], 4);
      const w2 = await win('linear');
      assert.ok(Math.abs(w2[0]) < 1e-9, `clamped at the lower bound: ${w2}`);
      await button('linear', 'Reset view').click();
      await settle();
      assert.equal(await win('linear'), null);
      assert.equal(await statCalls(), baseCalls);
      return { w0, w1, w2 };
    });

    await check('zoom to area on a log axis shows the rectangle\'s data range', async () => {
      await button('log', 'Zoom to area').click();
      const b = await panelBox('log');
      const a = [b.left + b.width * 0.35, b.top + b.height * 0.25];
      const c = [b.left + b.width * 0.65, b.top + b.height * 0.6];
      const lo = await dataAt('log', ...a);
      const hi = await dataAt('log', ...c);
      assert.equal(lo.length, 2); assert.equal(hi.length, 2);
      await drag(a, c);
      assert.notEqual(await win('log'), null);
      const panel = await fx(() => window.intaglioNav.panel('log'));
      const left = await dataAt('log', panel[0] + 0.01, (panel[1] + panel[3]) / 2);
      const right = await dataAt('log', panel[2] - 0.01, (panel[1] + panel[3]) / 2);
      // Positions on a log axis are log10 values: the new panel edges are the rectangle's edges.
      assert.ok(Math.abs(left[0] - lo[0]) < 0.01 && Math.abs(right[0] - hi[0]) < 0.01,
        JSON.stringify({ lo, hi, left, right }));
      const ticks = await fx(() => [...document.querySelectorAll('[data-intaglio-widget=log] svg.intaglio-base text')].map(t => t.textContent));
      assert.ok(ticks.includes('10') && !ticks.includes('100'), `log ticks for the window: ${ticks}`);
      await drawnMatchOracle('log');
      await tab.screenshot({ path: path.join(out, 'log-zoom.png') });
      return { lo, hi, left, right, ticks };
    });

    await check('a date axis keeps date labels when zoomed by keyboard; 0 resets', async () => {
      await fx(() => document.querySelector('[data-intaglio-widget=date] .intaglio-plot').focus());
      await tab.keyboard.press('+');
      await tab.keyboard.press('+');
      await settle();
      assert.notEqual(await win('date'), null);
      const labels = await fx(() => [...document.querySelectorAll('[data-intaglio-widget=date] svg.intaglio-base text')].map(t => t.textContent));
      assert.ok(labels.some(t => /^2026-/.test(t)), labels.join(' / '));
      await tab.keyboard.press('0');
      await settle();
      assert.equal(await win('date'), null);
      return { labels: labels.filter(t => /^2026-/.test(t)) };
    });

    await check('a two-finger pinch zooms in, in the default Inspect mode', async () => {
      await button('linear', 'Reset view').click();
      await button('linear', 'Inspect').click();
      // Synthetic touch events bypass the browser's own gestures: what reaches the widget on a
      // device is decided by touch-action, so it is checked directly.
      const touchAction = await fx(() => getComputedStyle(document.querySelector('[data-intaglio-widget=linear] .intaglio-plot')).touchAction);
      assert.equal(touchAction, 'pan-x pan-y', 'one finger scrolls the page; pinches go to the plot');
      const b = await panelBox('linear');
      const cx = b.left + b.width / 2, cy = b.top + b.height / 2;
      await fx(([x, y]) => {
        const host = document.querySelector('[data-intaglio-widget=linear] .intaglio-plot');
        const fire = (type, id, px) => host.dispatchEvent(new PointerEvent(type, {
          pointerId: id, pointerType: 'touch', clientX: px, clientY: y, button: 0, buttons: 1, bubbles: true }));
        fire('pointerdown', 11, x - 30); fire('pointerdown', 12, x + 30);
        for (let d = 30; d <= 90; d += 10) { fire('pointermove', 11, x - d); fire('pointermove', 12, x + d); }
        fire('pointerup', 11, x - 90); fire('pointerup', 12, x + 90);
      }, [cx, cy]);
      await settle();
      const w = await win('linear');
      assert.notEqual(w, null);
      assert.ok(w[1] - w[0] < 0.75 && Number.isFinite(w[2]) && Number.isFinite(w[3]), JSON.stringify(w));
      assert.equal(await statCalls(), baseCalls);
      return { w };
    });

    await check('magnification scales marks and text together; navigation does not', async () => {
      const textSize = () => fx(() => document.querySelector('[data-intaglio-widget=date] svg.intaglio-base text').getBoundingClientRect().height);
      const h1 = await textSize();
      assert.equal(await fx(() => window.intaglioNav.magnify('date', 2)), 'ok');
      await settle();
      const h2 = await textSize();
      assert.ok(Math.abs(h2 / h1 - 2) < 0.05, `${h1} -> ${h2}`);
      await fx(() => window.intaglioNav.magnify('date', 1));
      await fx(() => document.querySelector('[data-intaglio-widget=date] .intaglio-plot').focus());
      await tab.keyboard.press('+');
      await settle();
      const h3 = await textSize();
      assert.ok(Math.abs(h3 - h1) < 0.5, `navigation keeps text size: ${h1} vs ${h3}`);
      await tab.keyboard.press('0');
      assert.deepEqual(consoleErrors, []);
      return { h1, h2, h3 };
    });
    await context.close();

    // Real touch input through Chromium's input pipeline (CDP), so touch-action and the browser's
    // own pinch-zoom arbitrate exactly as on a device; synthetic PointerEvents above bypass them.
    const touchContext = await browser.newContext({ viewport: { width: 1100, height: 800 }, hasTouch: true, isMobile: false });
    const touchTab = await touchContext.newPage();
    await touchTab.goto(pathToFileURL(page).href);
    await touchTab.waitForFunction(() => window.intaglioNav && window.intaglioNav.ready);
    const cdp = await touchContext.newCDPSession(touchTab);
    await check('a real two-finger pinch in Inspect mode zooms the plot, not the page', async () => {
      const b = await touchTab.evaluate(() => {
        const r = document.querySelector('[data-intaglio-widget=linear] svg.intaglio-base').getBoundingClientRect();
        return { cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
      });
      const points = d => [{ x: b.cx - d, y: b.cy, id: 1 }, { x: b.cx + d, y: b.cy, id: 2 }];
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: points(30) });
      for (let d = 40; d <= 110; d += 10)
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: points(d) });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await touchTab.waitForTimeout(150);
      const w = await touchTab.evaluate(() => window.intaglioNav.window('linear'));
      const pageScale = await touchTab.evaluate(() => window.visualViewport ? window.visualViewport.scale : 1);
      assert.notEqual(w, null, 'the plot zoomed');
      // With the page taking part of the pinch (touch-action:manipulation) the plot zooms ~1.5x.
      assert.ok(w[1] - w[0] < 0.5, JSON.stringify(w));
      assert.equal(pageScale, 1, 'the page did not zoom');
      return { w, pageScale };
    });
    await touchContext.close();
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
