// Browser-side independent fixtures for PickingSuite and PickViewportSuite.
// Requires Playwright; runs its Chromium by default, never the system Chrome profile.
// Audit browser ownership before and after invoking this script (see AGENTS.md).
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

async function main() {
  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = { browser: browser.version(), strokes: [], coordinates: [] };
  try {
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      report.strokes = await page.evaluate(() => {
        const d = 'M50 50L50.5 50L50.5 50.7Z';
        const ctx = document.createElement('canvas').getContext('2d');
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        document.body.append(svg);
        const path = document.createElementNS(svg.namespaceURI, 'path');
        svg.append(path);
        for (const [key, value] of Object.entries({ d, fill: 'none', stroke: 'black', 'stroke-width': '4', 'stroke-miterlimit': '4' }))
          path.setAttribute(key, value);
        ctx.lineWidth = 4;
        ctx.miterLimit = 4;
        const results = [];
        for (const cap of ['butt', 'round', 'square']) {
          for (const dash of [[], [6, 4], [1, 3]]) {
            ctx.lineCap = cap;
            ctx.setLineDash(dash);
            path.setAttribute('stroke-linecap', cap);
            path.setAttribute('stroke-dasharray', dash.length ? dash.join(' ') : 'none');
            results.push({ cap, dash, canvas: ctx.isPointInStroke(new Path2D(d), 48.137, 48.271),
              svg: path.isPointInStroke(new DOMPoint(48.137, 48.271)) });
          }
        }
        return results;
      });
      for (const row of report.strokes) {
        const expected = row.dash.length === 0 || row.cap === 'square';
        assert.equal(row.canvas, expected, JSON.stringify(row));
        assert.equal(row.svg, expected, JSON.stringify(row));
      }
    } finally {
      await context.close();
    }

    for (const dpr of [1, 1.25, 2, 3]) {
      const context = await browser.newContext({ deviceScaleFactor: dpr });
      try {
        const page = await context.newPage();
        const row = await page.evaluate((dpr) => {
          const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
          svg.setAttribute('viewBox', `0 0 ${800 * dpr} ${400 * dpr}`);
          svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
          svg.style.cssText = 'position:absolute;left:10px;top:20px;width:400px;height:400px';
          document.body.append(svg);
          // The browser supplies the inverse screen matrix, independently of PickViewport.fit.
          const inverse = svg.getScreenCTM().inverse();
          const points = [[10, 120], [210, 220], [410, 320], [210, 100], [216, 220]];
          return { dpr, actualDpr: window.devicePixelRatio, points: points.map(([x, y]) => {
            const p = new DOMPoint(x, y).matrixTransform(inverse);
            return [p.x, p.y];
          }) };
        }, dpr);
        const expected = [[0, 0], [400, 200], [800, 400], [400, -40], [412, 200]];
        assert.equal(row.actualDpr, dpr);
        row.points.forEach((point, i) => point.forEach((value, axis) =>
          assert.ok(Math.abs(value - expected[i][axis] * dpr) < 1e-8, JSON.stringify(row))));
        report.coordinates.push(row);
      } finally {
        await context.close();
      }
    }
  } finally {
    await browser.close();
  }
  console.log(JSON.stringify(report, null, 2));
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
