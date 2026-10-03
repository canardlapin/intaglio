// Records a host-neutral interaction trace in the browser widget (Interaction 12).
//
// Usage: node tools/check-host-trace-browser.cjs <fixture main.js> <script.json> <out.json> <svg|canvas>
// Build the fixture first: sbt browserFixture/fastLinkJS
//
// The script (tools/trace/*.json) names marks by their reading-order index, bins by their
// left-to-right index and legend keys by name, never by pixel, so the JavaFX host replays the same
// script (JavaFxTraceParitySuite) and must produce the same normalized events, selected keys,
// tooltips, announcements and followed links step by step. Runs Playwright's own Chromium
// headless; never a system browser or profile. Audit browser ownership before and after invoking
// this script (see AGENTS.md).
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const pages = {
  widget: { template: 'widget.html', api: 'intaglioFixture', slots: ['left', 'right'] },
  linked: { template: 'linked.html', api: 'intaglioLinked', slots: ['a', 'b', 'c', 'd'] },
  members: { template: 'membership.html', api: 'intaglioMembers', slots: ['a', 'b', 'h', 'd'] },
};

async function main() {
  assert.equal(process.argv.length, 6, 'Pass the fixture main.js, a script, an output file and a renderer');
  const fixture = path.resolve(process.argv[2]);
  const scriptPath = path.resolve(process.argv[3]);
  const outPath = path.resolve(process.argv[4]);
  const renderer = process.argv[5];
  assert.ok(['svg', 'canvas'].includes(renderer));
  const scriptText = await fs.readFile(scriptPath, 'utf8');
  const script = JSON.parse(scriptText);
  const page = pages[script.page];
  assert.ok(page, `unknown page ${script.page}`);
  const work = await fs.mkdtemp(path.join(path.dirname(outPath), '.trace-'));
  const template = await fs.readFile(path.join(__dirname, 'browser', page.template), 'utf8');
  const html = path.join(work, page.template);
  await fs.writeFile(html, template.replace('FIXTURE_SCRIPT', pathToFileURL(fixture).href));

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = {
    page: script.page,
    renderer,
    browser: browser.version(),
    script: path.basename(scriptPath),
    scriptSha256: crypto.createHash('sha256').update(scriptText).digest('hex'),
    fixtureSha256: crypto.createHash('sha256').update(await fs.readFile(fixture)).digest('hex'),
    steps: [],
  };
  try {
    const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
    const tab = await context.newPage();
    const errors = [];
    tab.on('pageerror', error => errors.push(String(error)));
    await tab.goto(pathToFileURL(html).href + `?renderer=${renderer}`);
    await tab.waitForFunction(api => window[api] && window[api].ready, page.api);
    const api = (body, arg) => tab.evaluate(([name, source, a]) => {
      const fn = new Function('api', 'arg', source);
      return fn(window[name], a);
    }, [page.api, body, arg]);
    const settle = async () => {
      await tab.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
      await tab.waitForTimeout(20);
    };

    // Client coordinates for a step's target.
    const point = async step => {
      const slot = step.slot;
      if (step.mark !== undefined) {
        if (script.page === 'members') return api('return api.marks(arg[0])[arg[1]].slice(1, 3)', [slot, step.mark]);
        return api('return api.markPoint(arg[0], arg[1])', [slot, step.mark]);
      }
      if (step.bin !== undefined) return api('return api.bins(arg[0])[arg[1]].slice(0, 2)', [slot, step.bin]);
      if (step.legend) return api('return api.legendPoint(arg)', slot);
      if (step.empty) {
        return tab.evaluate(s => {
          const r = document.querySelector(`[data-intaglio-widget=${s}] .intaglio-base`).getBoundingClientRect();
          return [r.left + 4, r.bottom - 4];
        }, slot);
      }
      throw new Error(`step names no target: ${JSON.stringify(step)}`);
    };
    const keyName = key => ({ ' ': 'Space' }[key] || key);

    const snapshot = async () => ({
      events: await api('const o = {}; for (const s of arg) o[s] = [...api.events[s]]; return o;', page.slots),
      selected: await api('const o = {}; for (const s of arg) o[s] = [...api.selected(s)]; return o;', page.slots),
      parts: await api('return api.parts ? [...api.parts] : [];'),
      missing: await api('return api.missing ? [...api.missing] : [];'),
      requests: await api('return api.requests ? api.requests.length : 0;'),
    });
    let before = await snapshot();
    for (const [index, step] of script.steps.entries()) {
      switch (step.op) {
        case 'focus':
          await tab.locator(`[data-intaglio-widget=${step.slot}] .intaglio-plot`).focus();
          break;
        case 'key':
          await tab.keyboard.press(step.shift ? `Shift+${keyName(step.key)}` : keyName(step.key));
          break;
        case 'move': {
          const [x, y] = await point(step);
          await tab.mouse.move(x, y);
          break;
        }
        case 'click': {
          const [x, y] = await point(step);
          if (step.shift) await tab.keyboard.down('Shift');
          await tab.mouse.click(x, y);
          if (step.shift) await tab.keyboard.up('Shift');
          break;
        }
        case 'press': {
          const [x, y] = await point(step);
          await tab.mouse.move(x, y);
          await tab.mouse.down();
          break;
        }
        case 'dragOutside':
          await tab.mouse.move(1150, 880);
          break;
        case 'release':
          await tab.mouse.up();
          break;
        case 'leave':
          await tab.mouse.move(1150, 880);
          break;
        case 'wait':
          await tab.waitForTimeout(step.ms);
          break;
        case 'select':
          if (script.page === 'widget') assert.equal(await api('return api.setSelection(arg)', step.keys), 'ok');
          else if (script.page === 'linked') assert.equal(await api('return api.selectInA(arg)', step.keys), 'ok');
          else assert.equal(await api('return api.setSelection(arg[0], arg[1])', [step.slot, step.keys]), 'ok');
          break;
        case 'reply':
          await api('return api.reply(arg[0], arg[1])', [step.index, step.kind]);
          break;
        default:
          throw new Error(`unknown op ${step.op}`);
      }
      await settle();
      if (step.op === 'reply') { await tab.waitForTimeout(30); await settle(); }
      const after = await snapshot();
      const record = {
        step: index,
        op: step.op,
        events: Object.fromEntries(page.slots.map(s => [s, after.events[s].slice(before.events[s].length)])),
        selected: after.selected,
      };
      if (script.page === 'widget') record.parts = after.parts.slice(before.parts.length);
      if (script.page !== 'widget') record.missing = after.missing.slice(before.missing.length);
      if (script.page === 'members') record.requests = after.requests;
      if (step.inspect) {
        record.inspect = await tab.evaluate(s => {
          const root = document.querySelector(`[data-intaglio-widget=${s}]`);
          const tip = root.querySelector('.intaglio-tooltip');
          return {
            tooltip: tip.hidden ? null : tip.textContent,
            announce: root.querySelector('.intaglio-live').textContent,
            link: location.hash,
          };
        }, step.inspect);
      }
      report.steps.push(record);
      before = after;
    }
    assert.deepEqual(errors, [], 'no page errors during the trace');
    await context.close();
  } finally {
    await browser.close();
    await fs.rm(work, { recursive: true, force: true });
  }
  await fs.writeFile(outPath, JSON.stringify(report, null, 2) + '\n');
  console.log(`${report.page}/${renderer}: ${report.steps.length} steps -> ${outPath}`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
