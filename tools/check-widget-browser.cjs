// Real-browser evidence for the Intaglio SVG widget (Interaction 04).
//
// Usage: node tools/check-widget-browser.cjs <fixture main.js> <output dir>
// Build the fixture first: sbt browserFixture/fastLinkJS
// Runs Playwright's own Chromium headless; never a system browser or profile. Audit browser
// ownership before and after invoking this script (see AGENTS.md).
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

// Counts net DOM listener registrations and live ResizeObservers, installed before any page script.
const instrument = () => {
  window.__listeners = 0;
  window.__observers = 0;
  const add = EventTarget.prototype.addEventListener;
  const remove = EventTarget.prototype.removeEventListener;
  const seen = new WeakMap();
  EventTarget.prototype.addEventListener = function (type, fn, options) {
    let types = seen.get(this);
    if (!types) { types = new Map(); seen.set(this, types); }
    let fns = types.get(type);
    if (!fns) { fns = new Set(); types.set(type, fns); }
    if (fn && !fns.has(fn)) { fns.add(fn); window.__listeners++; }
    return add.call(this, type, fn, options);
  };
  EventTarget.prototype.removeEventListener = function (type, fn, options) {
    const fns = seen.get(this)?.get(type);
    if (fns && fns.delete(fn)) window.__listeners--;
    return remove.call(this, type, fn, options);
  };
  const Native = window.ResizeObserver;
  window.ResizeObserver = class extends Native {
    constructor(cb) { super(cb); window.__observers++; this.__live = true; }
    disconnect() { if (this.__live) { this.__live = false; window.__observers--; } super.disconnect(); }
  };
};

async function main() {
  assert.equal(process.argv.length, 4, 'Pass the fixture main.js and an output directory');
  const script = path.resolve(process.argv[2]);
  const out = path.resolve(process.argv[3]);
  await fs.mkdir(out, { recursive: true });
  const template = await fs.readFile(path.join(__dirname, 'browser', 'widget.html'), 'utf8');
  const page = path.join(out, 'widget.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const renderer = process.env.INTAGLIO_TEST_RENDERER || 'svg';
  assert.ok(['svg', 'canvas'].includes(renderer));
  const report = { renderer, browser: browser.version(), checks: [] };
  const check = (name, fn) => fn().then(detail => report.checks.push({ name, ok: true, detail }));
  try {
    const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
    const tab = await context.newPage();
    const consoleErrors = [];
    tab.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
    tab.on('pageerror', error => consoleErrors.push(String(error)));
    await tab.addInitScript(instrument);
    await tab.goto(pathToFileURL(page).href + `?renderer=${renderer}`);
    await tab.waitForFunction(() => window.intaglioFixture && window.intaglioFixture.ready);
    const fx = (body, ...args) => tab.evaluate(body, ...args);
    const last = slot => fx(s => window.intaglioFixture.events[s].slice(-6), slot);
    // SVG: the legend key's drawn DOM box is the oracle, and the model point that a Canvas run
    // (which has no DOM marks) clicks must fall inside it. Canvas: the model point, checked here.
    const legendCentre = async slot => {
      const model = await fx(s => window.intaglioFixture.legendPoint(s), slot);
      if (renderer === 'canvas') return model;
      const box = await fx(s => {
        const key = document.querySelector(`[data-intaglio-widget=${s}] [data-name="block-legend-entry-0-key"]`);
        const r = key.getBoundingClientRect();
        return [r.left, r.top, r.right, r.bottom];
      }, slot);
      assert.ok(model[0] >= box[0] && model[0] <= box[2] && model[1] >= box[1] && model[1] <= box[3],
        `model legend point ${model} outside the drawn key ${box}`);
      return [(box[0] + box[2]) / 2, (box[1] + box[3]) / 2];
    };
    const point = (slot, i) => fx(([s, n]) => window.intaglioFixture.markPoint(s, n), [slot, i]);
    const tooltipBox = slot => fx(s => {
      const root = document.querySelector(`[data-intaglio-widget=${s}]`);
      const tip = root.querySelector('.intaglio-tooltip');
      const r = root.getBoundingClientRect(); const t = tip.getBoundingClientRect();
      return { hidden: tip.hidden, text: tip.textContent, markup: tip.querySelectorAll('b').length,
        inside: t.left >= r.left - 0.5 && t.right <= r.right + 0.5 && t.top >= r.top - 0.5 && t.bottom <= r.bottom + 0.5 };
    }, slot);

    await check('two widgets, no duplicate ids, one tab stop per plot and per toolbar', async () => {
      const ids = await fx(() => [...document.querySelectorAll('[id]')].map(e => e.id));
      assert.equal(new Set(ids).size, ids.length, `duplicate ids: ${ids}`);
      const stops = await fx(() => ['left', 'right'].map(s => {
        const root = `[data-intaglio-widget=${s}]`;
        const sequential = document.querySelectorAll(
          `${root} [tabindex="0"], ${root} summary, ${root} button:not([tabindex="-1"]):not([hidden])`);
        return new Set(sequential).size;
      }));
      assert.deepEqual(stops, [3, 3], 'toolbar, plot and companion toggle; not one stop per mark or button');
      return { ids: ids.length, stops };
    });

    await check('a duplicate id prefix and a prefix change are refused; the table does not rescale the plot', async () => {
      assert.match(await fx(() => window.intaglioFixture.mountDuplicate()), /already mounted/);
      assert.match(await fx(() => window.intaglioFixture.updateOtherPrefix()), /id prefix/);
      const width = () => fx(() => document.querySelector('[data-intaglio-widget=left] .intaglio-base').getBoundingClientRect().width);
      const before = await width();
      await fx(() => { document.querySelector('[data-intaglio-widget=left] details').open = true; });
      await tab.waitForTimeout(50);
      const opened = await width();
      await fx(() => { document.querySelector('[data-intaglio-widget=left] details').open = false; });
      assert.equal(opened, before);
      return { before, opened };
    });

    await check('long tooltip fields wrap within narrow widgets without losing text', async () => {
      const token = 'participant_' + '0123456789'.repeat(6);
      const results = [];
      for (const width of [480, 240, 160]) {
        await fx(([width, token]) => {
          document.getElementById('left').style.width = `${width}px`;
          window.intaglioFixture.setTooltipNote(token);
        }, [width, token]);
        await tab.waitForTimeout(100); // settle ResizeObserver before keyboard focus
        await tab.locator('[data-intaglio-widget=left] .intaglio-plot').focus();
        await tab.keyboard.press('End');
        await tab.keyboard.press('Home');
        const box = await fx(() => {
          const root = document.querySelector('[data-intaglio-widget=left]');
          const tip = root.querySelector('.intaglio-tooltip');
          const r = root.getBoundingClientRect(), t = tip.getBoundingClientRect();
          return { hidden: tip.hidden, text: tip.textContent, width: r.width,
            inside: t.left >= r.left - 0.5 && t.right <= r.right + 0.5,
            scrollWidth: tip.scrollWidth, clientWidth: tip.clientWidth };
        });
        await tab.screenshot({ path: path.join(out, `long-tooltip-${width}.png`) });
        assert.equal(box.hidden, false);
        assert.ok(box.text.includes(token), 'full identifier remains available');
        assert.ok(box.inside && box.scrollWidth <= box.clientWidth + 1, JSON.stringify(box));
        results.push(box);
      }
      await fx(() => {
        document.getElementById('left').style.width = '480px';
        window.intaglioFixture.setTooltipNote('<b>not markup</b>');
        document.activeElement.blur();
      });
      await tab.waitForTimeout(100);
      return results;
    });

    const marks = await fx(() => window.intaglioFixture.markCount('left'));
    await check('pointer hover shows a delayed, escaped tooltip and inverse emphasis', async () => {
      const [x, y] = await point('left', 3);
      await tab.mouse.move(x, y);
      await tab.waitForTimeout(450);
      const box = await tooltipBox('left');
      assert.equal(box.hidden, false);
      assert.match(box.text, /Trial t\d+/);
      assert.match(box.text, /<b>not markup<\/b>/, 'content is text');
      assert.equal(box.markup, 0, 'no element was parsed from content');
      assert.ok(box.inside);
      const emphasis = await fx(renderer => ({
        dimmed: document.querySelector('[data-intaglio-widget=left] .intaglio-plot').classList.contains('intaglio-dimmed'),
        clipped: renderer === 'canvas' ? (() => {
          const c = document.querySelector('[data-intaglio-widget=left] .intaglio-canvas-emphasis');
          const d = c.getContext('2d').getImageData(0,0,c.width,c.height).data;
          let n=0; for(let i=3;i<d.length;i+=4) if(d[i]) n++;
          return n > 5 && n < 1000; // A clipped mark, never a copied full plot.
        })() : !!document.querySelector('[data-intaglio-widget=left] .intaglio-emphasis[clip-path]'),
        hover: document.querySelectorAll('[data-intaglio-widget=left] .intaglio-ring-hover').length
      }), renderer);
      assert.deepEqual(emphasis, { dimmed: true, clipped: true, hover: 1 });
      assert.ok((await last('left')).some(e => /^hover:t\d+:Pointer$/.test(e)));
      await tab.screenshot({ path: path.join(out, 'hover.png') });
      return { marks, tooltip: box.text };
    });

    await check('click selects and activates; a link follows only real input', async () => {
      const [x, y] = await point('left', 3);
      await tab.mouse.click(x, y);
      const events = await last('left');
      const selected = events.find(e => e.startsWith('select:'));
      assert.match(selected, /^select:t\d+\|0:Pointer$/);
      assert.ok(events.some(e => /^activate:t\d+:Pointer$/.test(e)));
      const hash = await fx(() => location.hash);
      assert.match(hash, /^#trial-t\d+$/);
      const [x2, y2] = await point('left', 7);
      // mouse.click has no modifier option; hold Shift on the keyboard instead.
      await tab.keyboard.down('Shift');
      await tab.mouse.click(x2, y2);
      await tab.keyboard.up('Shift');
      assert.equal(await fx(() => String(window.getSelection())), '', 'additive clicks select no text');
      const both = (await last('left')).filter(e => e.startsWith('select:')).pop();
      assert.equal(both.split('|')[0].split(':')[1].split(',').length, 2, both);
      // Application-controlled selection changes state and overlay, emits nothing, never navigates.
      await fx(() => { location.hash = ''; });
      const count = await fx(() => window.intaglioFixture.events.left.length);
      assert.equal(await fx(() => window.intaglioFixture.setSelection(['t2'])), 'ok');
      await tab.waitForTimeout(50);
      assert.deepEqual(await fx(() => window.intaglioFixture.selected('left')), ['t2']);
      assert.equal(await fx(() => window.intaglioFixture.events.left.length), count, 'no echo event');
      assert.equal(await fx(() => document.querySelectorAll('[data-intaglio-widget=left] .intaglio-ring-selected').length), 1);
      assert.equal(await fx(() => location.hash), '');
      return { selected, both };
    });

    await check('after a click, moving to empty space hides the hover tooltip', async () => {
      const [x, y] = await point('left', 5);
      await tab.mouse.move(x, y);
      await tab.waitForTimeout(450);
      await tab.mouse.click(x, y);
      assert.equal((await tooltipBox('left')).hidden, false);
      const corner = await fx(() => {
        const r = document.querySelector('[data-intaglio-widget=left] .intaglio-base').getBoundingClientRect();
        return [r.left + 4, r.bottom - 4];
      });
      await tab.mouse.move(corner[0], corner[1]);
      await tab.waitForTimeout(450);
      assert.equal((await tooltipBox('left')).hidden, true);
      return {};
    });

    await check('a press released outside the plot does not leave a gesture open', async () => {
      const [x, y] = await point('left', 2);
      await tab.mouse.move(x, y);
      await tab.mouse.down();
      await tab.mouse.move(1100, 850);
      await tab.mouse.up();
      await tab.mouse.move(x, y);
      await tab.mouse.down();
      await tab.mouse.up();
      const ended = (await last('left')).filter(e => e.startsWith('Gesture'));
      assert.equal(ended[ended.length - 1], 'GestureEnded:Pointer', ended.join(' / '));
      assert.deepEqual(consoleErrors, []);
      return { ended };
    });

    await check('keyboard roves focus with a visible ring, announces it, chooses and clears', async () => {
      // Reset the sequential focus starting point to the page top, as a reader clicking the page
      // background would; Tab then reaches the first widget's plot.
      await tab.mouse.click(5, 5);
      await tab.keyboard.press('Tab');
      const toolbarStop = await fx(() => [document.activeElement.closest('.intaglio-toolbar') !== null,
        document.activeElement.getAttribute('aria-pressed')]);
      assert.deepEqual(toolbarStop, [true, 'true'], 'the toolbar is one stop, at its active mode');
      await tab.keyboard.press('ArrowRight');
      assert.equal(await fx(() => document.activeElement.textContent), 'Select area');
      await tab.keyboard.press('Tab');
      const focused = await fx(() => document.activeElement.closest('[data-intaglio-widget]')?.dataset.intaglioWidget);
      assert.equal(focused, 'left');
      await tab.keyboard.press('ArrowRight');
      await tab.keyboard.press('End');
      await tab.waitForTimeout(50);
      const state = await fx(() => ({
        ring: document.querySelectorAll('[data-intaglio-widget=left] .intaglio-ring-focus').length,
        live: document.querySelector('[data-intaglio-widget=left] .intaglio-live').textContent,
        tooltip: !document.querySelector('[data-intaglio-widget=left] .intaglio-tooltip').hidden,
        active: document.activeElement.className,
        rings: [...document.querySelectorAll('[data-intaglio-widget=left] .intaglio-overlay > path')].map(p => p.getAttribute('class'))
      }));
      assert.equal(state.ring, 1, JSON.stringify({ state, events: await last('left') }));
      assert.match(state.live, /Trial t\d+/);
      assert.ok(state.tooltip, 'focus shows the tooltip without a pointer');
      assert.ok((await last('left')).some(e => /^focus:t\d+:Keyboard$/.test(e)));
      await tab.screenshot({ path: path.join(out, 'keyboard-focus.png') });
      await tab.keyboard.press('Enter');
      assert.ok((await last('left')).some(e => /^select:t\d+\|0:Keyboard$/.test(e)));
      await tab.keyboard.press('Escape');
      assert.ok((await last('left')).pop().startsWith('select:|0:Keyboard'));
      return state;
    });

    await check('a tooltip at the right edge stays inside the widget', async () => {
      const points = [];
      for (let i = 0; i < marks; i++) points.push(await point('left', i));
      const edge = points.reduce((a, b) => (b[0] > a[0] ? b : a));
      await tab.mouse.move(edge[0], edge[1]);
      await tab.waitForTimeout(450);
      const box = await tooltipBox('left');
      assert.equal(box.hidden, false);
      assert.ok(box.inside);
      await tab.screenshot({ path: path.join(out, 'edge-tooltip.png') });
      return { edge };
    });

    await check('histogram bins are selected as bins, not as member observations', async () => {
      const [x, y] = await point('right', 0);
      await tab.mouse.click(x, y);
      const selected = (await last('right')).find(e => e.startsWith('select:'));
      assert.equal(selected, 'select:|1:Pointer');
      assert.deepEqual(await fx(() => window.intaglioFixture.events.left.filter(e => e.includes('right'))), []);
      return { selected };
    });

    await check('a legend entry is a typed part under the pointer', async () => {
      const centre = await legendCentre('left');
      await tab.mouse.move(centre[0], centre[1]);
      await tab.waitForTimeout(50);
      const parts = await fx(() => window.intaglioFixture.parts.slice(-3));
      assert.ok(parts.includes('hover:block: A'), parts.join(' / '));
      return { parts };
    });

    await check('a resize with the same revision keeps the selection', async () => {
      await fx(() => window.intaglioFixture.setSelection(['t3']));
      assert.equal(await fx(() => window.intaglioFixture.resize()), 'ok');
      assert.deepEqual(await fx(() => window.intaglioFixture.selected('left')), ['t3']);
      return {};
    });

    await check('update reconciles the selection by entity key', async () => {
      await fx(() => window.intaglioFixture.setSelection(['t1', 't2', 't9']));
      assert.equal(await fx(() => window.intaglioFixture.update()), 'ok');
      const events = await last('left');
      assert.ok(events.some(e => e.startsWith('Reconciled')), events.join(' / '));
      return { events };
    });

    await check('reduced motion removes the emphasis transition', async () => {
      const before = await fx(() => getComputedStyle(document.querySelector('[data-intaglio-widget=left] .intaglio-base')).transitionDuration);
      await tab.emulateMedia({ reducedMotion: 'reduce' });
      const after = await fx(() => getComputedStyle(document.querySelector('[data-intaglio-widget=left] .intaglio-base')).transitionDuration);
      assert.notEqual(before, '0s');
      assert.equal(after, '0s');
      return { before, after };
    });

    report.trace = await fx(() => ({ events: window.intaglioFixture.events, parts: window.intaglioFixture.parts }));
    await check('repeated mount and dispose retain no listeners, observers or nodes', async () => {
      const base = await fx(() => ({ listeners: window.__listeners, observers: window.__observers }));
      await fx(() => window.intaglioFixture.remount(25));
      const cycled = await fx(() => ({ listeners: window.__listeners, observers: window.__observers,
        widgets: document.querySelectorAll('[data-intaglio-widget=left]').length }));
      assert.deepEqual(cycled, { ...base, widgets: 1 });
      const remaining = await fx(() => window.intaglioFixture.dispose('left'));
      const after = await fx(() => ({ listeners: window.__listeners, observers: window.__observers,
        widgets: document.querySelectorAll('[data-intaglio-widget=left]').length }));
      assert.equal(remaining, 0);
      assert.equal(after.widgets, 0);
      assert.ok(after.listeners < base.listeners && after.observers === base.observers - 1, JSON.stringify({ base, after }));
      return { base, cycled, after };
    });
    assert.deepEqual(consoleErrors, [], 'no console errors during the run');
    await context.close();

    const hidpi = await browser.newContext({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 2 });
    const tab2 = await hidpi.newPage();
    await tab2.goto(pathToFileURL(page).href + `?renderer=${renderer}`);
    await tab2.waitForFunction(() => window.intaglioFixture && window.intaglioFixture.ready);
    await check('at device scale 2 the pointer still hits the drawn mark', async () => {
      const [x, y] = await tab2.evaluate(() => window.intaglioFixture.markPoint('left', 5));
      await tab2.mouse.move(x, y);
      await tab2.waitForTimeout(450);
      const events = await tab2.evaluate(() => window.intaglioFixture.events.left.slice(-3));
      assert.ok(events.some(e => /^hover:t\d+:Pointer$/.test(e)), events.join(' / '));
      await tab2.screenshot({ path: path.join(out, 'hover-2x.png') });
      if (renderer === 'canvas') {
        const size = await tab2.evaluate(() => {
          const c = document.querySelector('canvas.intaglio-base'), r=c.getBoundingClientRect();
          return [c.width, c.height, Math.round(r.width*devicePixelRatio), Math.round(r.height*devicePixelRatio)];
        });
        assert.deepEqual(size.slice(0,2), size.slice(2));
      }
      return { events };
    });
    await hidpi.close();
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
