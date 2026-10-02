// Generate specimens with SvgFontBrowserFixture first. Uses Playwright's own Chromium.
// Audit browser ownership before and after; never pass a system browser executable/profile.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

async function main() {
  assert.equal(process.argv.length, 3, 'Pass the fixture output directory');
  const dir = path.resolve(process.argv[2]);
  const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  const font = await fs.readFile(path.join(dir, 'reference-font.bin'));
  const inputs = {};
  for (const name of ['embedded.svg', 'fallback.svg', 'reference-font.bin']) {
    inputs[name] = hash(await fs.readFile(path.join(dir, name)));
  }
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = { browser: browser.version(), inputs, cases: [], networkRequests: [] };
  try {
    const context = await browser.newContext({ viewport: { width: 800, height: 180 } });
    try {
      await context.route(/^https?:/, route => {
        report.networkRequests.push(route.request().url());
        return route.abort();
      });
      for (const name of ['embedded', 'fallback']) {
        const page = await context.newPage();
        try {
          const pageErrors = [];
          page.on('pageerror', error => pageErrors.push(error.message));
          await page.goto(pathToFileURL(path.join(dir, `${name}.svg`)).href);
          await page.evaluate(() => document.fonts.ready);
          const actual = await page.evaluate(async () => {
            const text = document.querySelector('text');
            const style = getComputedStyle(text);
            const faces = await document.fonts.load(`${style.fontSize} ${style.fontFamily}`, text.textContent);
            return { label: text.textContent, width: text.getComputedTextLength(),
              fontSize: style.fontSize, fontFamily: style.fontFamily,
              loadedFaces: faces.map(face => ({ family: face.family, status: face.status })) };
          });
          // Independent browser font load from original bytes, not the exporter's data URI.
          const expectedWidth = await page.evaluate(async ({ bytes, actual }) => {
            const face = new FontFace('Intaglio Independent Oracle', new Uint8Array(bytes));
            await face.load();
            document.fonts.add(face);
            const ctx = document.createElementNS('http://www.w3.org/1999/xhtml', 'canvas').getContext('2d');
            ctx.font = `${actual.fontSize} "Intaglio Independent Oracle"`;
            return ctx.measureText(actual.label).width;
          }, { bytes: [...font], actual });
          assert.deepEqual(pageErrors, []);
          if (name === 'embedded') {
            assert.equal(actual.loadedFaces.length, 1, `embedded face must load: ${JSON.stringify(actual)}`);
            assert.equal(actual.loadedFaces[0].status, 'loaded');
            assert.ok(Math.abs(actual.width - expectedWidth) < 0.1,
              `export width ${actual.width} differs from reference ${expectedWidth}`);
          } else {
            assert.equal(actual.loadedFaces.length, 0, 'negative control must use fallback');
            assert.ok(Math.abs(actual.width - expectedWidth) > 1,
              'font fixture must distinguish embedded font from browser fallback');
          }
          await page.screenshot({ path: path.join(dir, `${name}.png`) });
          report.cases.push({ name, ...actual, expectedWidth });
        } finally {
          await page.close();
        }
      }
      assert.deepEqual(report.networkRequests, [], 'self-contained SVG must not fetch a font');
    } finally {
      await context.close();
    }
    await fs.writeFile(path.join(dir, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
