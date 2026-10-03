// Smoke check for the external consumer: the published artifact mounts, picks, links, navigates and
// disposes in a real browser. Usage: node smoke.cjs <consumer main.js> <output dir>
// Playwright's own Chromium only; audit browser ownership before and after (see AGENTS.md).
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

async function main() {
  assert.equal(process.argv.length, 4, 'Pass the consumer main.js and an output directory');
  const script = path.resolve(process.argv[2]);
  const out = path.resolve(process.argv[3]);
  await fs.mkdir(out, { recursive: true });
  const template = await fs.readFile(path.join(__dirname, 'index.html'), 'utf8');
  const page = path.join(out, 'consumer.html');
  await fs.writeFile(page, template.replace('CONSUMER_SCRIPT', pathToFileURL(script).href));
  const browser = await chromium.launch({ headless: true });
  const report = { browser: browser.version(), checks: [], runs: [] };
  let referenceMark;
  try {
    for (const renderer of ['svg', 'canvas']) {
    const run = { renderer, checks: [] };
    report.runs.push(run);
    const check = (name, fn) => fn().then(detail => {
      run.checks.push({ name, ok: true, detail });
      report.checks.push({ name: `${renderer}: ${name}`, ok: true, detail });
    });
    const tab = await browser.newPage({ viewport: { width: 1000, height: 700 } });
    const errors = [];
    tab.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    tab.on('pageerror', e => errors.push(String(e)));
    await tab.goto(pathToFileURL(page).href + `?renderer=${renderer}`);
    await tab.waitForFunction(() => window.consumer && window.consumer.ready);
    const fx = (body, ...args) => tab.evaluate(body, ...args);

    await check('both widgets mount with one plot tab stop each', async () => {
      const plots = await fx(() => document.querySelectorAll('.intaglio-plot[tabindex="0"]').length);
      assert.equal(plots, 2);
      assert.equal(await tab.locator(`${renderer}.intaglio-base`).count(), 2);
      if (renderer === 'canvas') {
        assert.equal(await tab.locator('svg.intaglio-base').count(), 0);
        await tab.waitForFunction(() => [...document.querySelectorAll('canvas.intaglio-base')]
          .every(c => c.dataset.renderState === 'ready'));
      }
      return { plots };
    });

    await check('keyboard selection in one plot is projected into the other', async () => {
      await fx(() => document.querySelector('#a .intaglio-plot').focus());
      await tab.keyboard.press('Home');
      await tab.keyboard.press('Enter');
      const a = await fx(() => window.consumer.selectedA());
      const b = await fx(() => window.consumer.selectedB());
      assert.equal(a.length, 1);
      assert.deepEqual(b, a, 'the linked plot holds the same observation');
      const live = await fx(() => document.querySelector('#a .intaglio-live').textContent);
      assert.match(live, /observation o\d+/);
      return { a, live };
    });

    await check('the pointer at a hollow point\'s unpainted centre hits it and shows its tooltip', async () => {
      // The drawn SVG glyph supplies the independent coordinates for the Canvas check too.
      if (renderer === 'svg') referenceMark = await fx(() => {
        const base = document.querySelector('#b svg.intaglio-base').getBoundingClientRect();
        const c = document.querySelector('#b svg.intaglio-base circle').getBoundingClientRect();
        return { x: ((c.left + c.right) / 2 - base.left) / base.width,
          y: ((c.top + c.bottom) / 2 - base.top) / base.height };
      });
      const centre = await fx(mark => {
        const base = document.querySelector('#b .intaglio-base').getBoundingClientRect();
        return [base.left + mark.x * base.width, base.top + mark.y * base.height];
      }, referenceMark);
      if (renderer === 'canvas') {
        const ink = await fx(mark => {
          const c = document.querySelector('#b canvas.intaglio-base');
          const x = Math.floor(mark.x * c.width), y = Math.floor(mark.y * c.height);
          const pixels = c.getContext('2d').getImageData(x - 8, y - 8, 17, 17).data;
          let count = 0;
          for (let i = 0; i < pixels.length; i += 4)
            if (pixels[i + 3] && Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) < 220) count++;
          return count;
        }, referenceMark);
        assert.ok(ink >= 8, `native Canvas ring at SVG-derived location: ${ink}`);
        run.canvasInk = ink;
      }
      await tab.mouse.move(centre[0], centre[1]);
      await tab.waitForFunction(() => !document.querySelector('#b .intaglio-tooltip').hidden, null, { timeout: 3000 });
      const text = await fx(() => document.querySelector('#b .intaglio-tooltip').textContent);
      assert.match(text, /observation o\d+/);
      return { text };
    });

    await check('the application navigates a data window', async () => {
      assert.equal(await fx(() => window.consumer.zoomA()), 'ok');
      await tab.waitForTimeout(50);
      const w = await fx(() => window.consumer.windowA());
      assert.ok(w && w[0] >= 0.2 - 1e-9 && w[1] <= 0.5 + 1e-9, JSON.stringify(w));
      return { w };
    });

    await check('disposal leaves no listeners and no console errors', async () => {
      run.trace = await fx(() => ({ events: [...window.consumer.events],
        a: window.consumer.selectedA(), b: window.consumer.selectedB(), window: window.consumer.windowA() }));
      await tab.screenshot({ path: path.join(out, `consumer-${renderer}.png`) });
      const counts = await fx(() => window.consumer.dispose());
      assert.deepEqual(counts, [0, 0]);
      assert.deepEqual(errors, []);
      return { counts };
    });
    await tab.close();
    }
    assert.deepEqual(report.runs[0].trace, report.runs[1].trace, 'same public events and state on SVG and Canvas');
    report.pairedTracesMatch = true;
  } finally {
    await browser.close();
  }
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}

main().catch(error => { console.error(error); process.exit(1); });
