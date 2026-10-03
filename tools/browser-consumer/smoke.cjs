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
  const report = { browser: browser.version(), checks: [] };
  const check = (name, fn) => fn().then(detail => report.checks.push({ name, ok: true, detail }));
  try {
    const tab = await browser.newPage({ viewport: { width: 1000, height: 700 } });
    const errors = [];
    tab.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    tab.on('pageerror', e => errors.push(String(e)));
    await tab.goto(pathToFileURL(page).href);
    await tab.waitForFunction(() => window.consumer && window.consumer.ready);
    const fx = (body, ...args) => tab.evaluate(body, ...args);

    await check('both widgets mount with one plot tab stop each', async () => {
      const plots = await fx(() => document.querySelectorAll('.intaglio-plot[tabindex="0"]').length);
      assert.equal(plots, 2);
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
      const centre = await fx(() => {
        const c = document.querySelector('#b svg.intaglio-base circle').getBoundingClientRect();
        return [(c.left + c.right) / 2, (c.top + c.bottom) / 2];
      });
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
      const counts = await fx(() => window.consumer.dispose());
      assert.deepEqual(counts, [0, 0]);
      assert.deepEqual(errors, []);
      return { counts };
    });
    await tab.screenshot({ path: path.join(out, 'consumer.png') });
  } finally {
    await browser.close();
  }
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}

main().catch(error => { console.error(error); process.exit(1); });
