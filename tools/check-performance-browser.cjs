// Real-browser performance characterization and regression gate (Interaction 11): a 10,000-mark
// SVG scatter and a 100,000-point Canvas scatter, plus the aggregate-membership cost of a histogram
// that keeps exact bin members. Records retained heap, picking-index build cost, pointer-to-
// highlight latency, redraw latency, update/rezoom cost and disposal behaviour, then compares each
// budgeted metric with performance/browser-budgets.json.
//
// Usage: node tools/check-performance-browser.cjs <fixture main.js> <output dir> [--record]
// Build the fixture first: sbt browserFixture/fastLinkJS (the budgets are set for that bundle).
// --record writes the measured medians to <output dir>/measured-budgets.json in the budget file's
// shape instead of failing on regression; review it before replacing the committed budgets.
//
// Runs Playwright's own Chromium headless; never a system browser or profile. Audit browser
// ownership before and after invoking this script (see AGENTS.md). Timing budgets are regression
// tripwires for this fixture on the recorded class of machine, not capacity guarantees.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const RUNS = Number(process.env.INTAGLIO_PERF_RUNS || 3);
const HOVER_SAMPLES = 31;
const FRAME_SAMPLES = 9;

const median = xs => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
};
const round = x => Math.round(x * 100) / 100;
const mib = bytes => round(bytes / (1024 * 1024));
const started = Date.now();
const log = message => console.error(`[${((Date.now() - started) / 1000).toFixed(1)}s] ${message}`);

async function main() {
  const args = process.argv.slice(2);
  const record = args.includes('--record');
  const positional = args.filter(a => !a.startsWith('--'));
  assert.equal(positional.length, 2, 'Pass the fixture main.js and an output directory');
  const script = path.resolve(positional[0]);
  const out = path.resolve(positional[1]);
  await fs.mkdir(out, { recursive: true });
  const template = await fs.readFile(path.join(__dirname, 'browser', 'performance.html'), 'utf8');
  const page = path.join(out, 'performance.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));
  const budgets = JSON.parse(
    await fs.readFile(path.join(__dirname, '..', 'performance', 'browser-budgets.json'), 'utf8')
  );

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  // Precise heap readings need an explicit collection, which the DevTools protocol provides.
  const browser = await chromium.launch({
    headless: true,
    args: ['--js-flags=--expose-gc'],
    ...(executablePath ? { executablePath } : {}),
  });
  const report = {
    browser: `Chromium ${browser.version()} (Playwright, headless)`,
    machine: {
      platform: `${os.platform()} ${os.release()} ${os.arch()}`,
      cpu: os.cpus()[0] && os.cpus()[0].model,
      cores: os.cpus().length,
      memoryGiB: round(os.totalmem() / 2 ** 30),
      loadAverageAtStart: os.loadavg().map(round),
      node: process.version,
    },
    fixture: script,
    runs: RUNS,
    checks: [],
    workloads: {},
    budgets: [],
  };

  async function openPage() {
    const tab = await browser.newPage({ viewport: { width: 900, height: 700 } });
    const errors = [];
    tab.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    tab.on('pageerror', e => errors.push(String(e)));
    await tab.goto(pathToFileURL(page).href);
    await tab.waitForFunction(() => window.intaglioPerf && window.intaglioPerf.ready);
    const cdp = await tab.context().newCDPSession(tab);
    await cdp.send('HeapProfiler.enable');
    const heap = async () => {
      // Two collections: weak references and finalizers settle after the first.
      await cdp.send('HeapProfiler.collectGarbage');
      await cdp.send('HeapProfiler.collectGarbage');
      return (await cdp.send('Runtime.getHeapUsage')).usedSize;
    };
    const p = (name, ...a) => tab.evaluate(([n, xs]) => window.intaglioPerf[n](...xs), [name, a]);
    // Resolve after the frame that follows `action`: the widget's redraw callback was queued during
    // the action, so it runs before ours in that frame. `frame` is the time spent in that frame's
    // callbacks before ours (the widget's redraw); `total` runs from the action to that point.
    const acrossFrame = (action, actionArgs) => tab.evaluate(([n, xs]) => new Promise(resolve => {
      const t0 = performance.now();
      const sync = window.intaglioPerf[n](...xs);
      const t1 = performance.now();
      requestAnimationFrame(ts => {
        const t2 = performance.now();
        resolve({ sync, actionMs: t1 - t0, frameMs: t2 - Math.max(ts, t1), totalMs: t2 - t0 });
      });
    }), [action, actionArgs]);
    const idle = () => tab.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => r()))));
    return { tab, errors, heap, p, acrossFrame, idle, cdp };
  }

  async function hoverLatency(t, count) {
    const samples = [];
    for (let i = 0; i < count; i++) {
      const [x, y] = await t.p('anchor', (i * 7919) % 1000000);
      const r = await t.tab.evaluate(([cx, cy]) => new Promise(resolve => {
        const host = document.querySelector('#perf .intaglio-plot');
        const t0 = performance.now();
        host.dispatchEvent(new PointerEvent('pointermove', {
          clientX: cx, clientY: cy, bubbles: true, pointerId: 1, pointerType: 'mouse', isPrimary: true,
        }));
        const t1 = performance.now();
        requestAnimationFrame(ts => {
          const t2 = performance.now();
          resolve({
            handlerMs: t1 - t0,
            frameMs: t2 - Math.max(ts, t1),
            totalMs: t2 - t0,
            ring: document.querySelectorAll('#perf .intaglio-ring-hover').length,
          });
        });
      }), [x, y]);
      assert.ok(r.ring > 0, `hover at target ${i} drew no ring`);
      samples.push(r);
    }
    // Leave, so the next measurement starts without a hover.
    await t.tab.evaluate(() => document.querySelector('#perf .intaglio-plot')
      .dispatchEvent(new PointerEvent('pointerleave', { bubbles: false, pointerId: 1 })));
    await t.idle();
    return {
      handlerMs: median(samples.map(s => s.handlerMs)),
      frameMs: median(samples.map(s => s.frameMs)),
      totalMs: median(samples.map(s => s.totalMs)),
      worstTotalMs: Math.max(...samples.map(s => s.totalMs)),
    };
  }

  async function framed(t, action, actionArgs, count = FRAME_SAMPLES, between) {
    const samples = [];
    for (let i = 0; i < count; i++) {
      if (between) await between(i);
      samples.push(await t.acrossFrame(action, actionArgs));
      await t.idle();
    }
    return {
      actionMs: median(samples.map(s => s.actionMs)),
      frameMs: median(samples.map(s => s.frameMs)),
      totalMs: median(samples.map(s => s.totalMs)),
      last: samples[samples.length - 1].sync,
    };
  }

  async function scatterRun(n, canvas) {
    const t = await openPage();
    try {
      const m = {};
      const empty = await t.heap();
      log(`${n} ${canvas ? 'canvas' : 'svg'}: prepare`);
      const prepared = await t.p('prepare', n, canvas);
      Object.assign(m, {
        marks: prepared.marks,
        planMs: prepared.planMs,
        lowerMs: prepared.lowerMs,
        pickingBuildMs: prepared.pickingMs,
        navigationBuildMs: prepared.navigationMs,
        viewCompileMs: prepared.viewMs,
        svgMarkupMiB: mib(prepared.markupChars * 2),
      });
      assert.equal(prepared.marks, n, 'every row is one keyed target');
      const viewHeap = await t.heap();
      m.viewRetainedMiB = mib(viewHeap - empty);
      log('mount');
      const mounted = await t.p('mount');
      await t.idle();
      m.mountMs = mounted.ms;
      m.domNodes = mounted.nodes;
      const mountedHeap = await t.heap();
      m.widgetRetainedMiB = mib(mountedHeap - viewHeap);
      m.totalRetainedMiB = mib(mountedHeap - empty);

      const burst = await t.p('pickBurst', 2000, 4);
      m.pickQueryUs = round(burst.perQueryMs * 1000);

      log('hover');
      const hover = await hoverLatency(t, HOVER_SAMPLES);
      m.hoverHandlerMs = hover.handlerMs;
      m.hoverRedrawMs = hover.frameMs;
      m.pointerToHighlightMs = hover.totalMs;
      m.pointerToHighlightWorstMs = hover.worstTotalMs;

      log('select');
      for (const k of [1, 1000]) {
        const s = await framed(t, 'select', [k], FRAME_SAMPLES, async () => { await t.p('select', 0); await t.idle(); });
        m[`select${k}Ms`] = s.actionMs;
        m[`select${k}RedrawMs`] = s.frameMs;
      }
      // Leave 1000 selected for the update checks. Hovering over a standing selection redraws its
      // rings and emphasis from the cached selection layer.
      await t.p('select', 1000);
      await t.idle();
      log('hover with selection');
      const busy = await hoverLatency(t, 11);
      m.hoverWithSelectionRedrawMs = busy.frameMs;
      m.pointerToHighlightWithSelectionMs = busy.totalMs;
      if (!canvas) {
        // The parsed emphasis copy is reused across redraws of one view.
        const same = await t.tab.evaluate(async () => {
          const plot = document.querySelector('#perf .intaglio-plot');
          const frame = () => new Promise(r => requestAnimationFrame(() => r()));
          const at = i => window.intaglioPerf.anchor(i);
          const move = ([x, y]) => plot.dispatchEvent(new PointerEvent('pointermove', { clientX: x, clientY: y, bubbles: true, pointerId: 1, pointerType: 'mouse' }));
          move(at(3)); await frame();
          const first = document.querySelector('#perf .intaglio-emphasis');
          move(at(5)); await frame();
          const second = document.querySelector('#perf .intaglio-emphasis');
          window.__intaglioEmphasis = second;
          return first !== null && first === second;
        });
        assert.ok(same, 'the emphasis copy is parsed once per view');
      }

      log('rezoom');
      const zoom = await framed(t, 'navigate', [0.5], 5, async () => { await t.p('navigate', 1); await t.idle(); });
      m.rezoomMs = zoom.actionMs;
      m.rezoomRedrawMs = zoom.frameMs;
      await t.p('navigate', 1);
      await t.idle();
      assert.equal(await t.p('selectedCount'), 1000, 'navigation keeps the selection');

      log('restyle');
      const restyle = await framed(t, 'restyle', [100], 3);
      m.restyleMs = restyle.actionMs;

      log('update');
      const same = await t.p('update', false);
      m.updateSameRevisionCompileMs = same.compileMs;
      m.updateSameRevisionMs = same.updateMs;
      assert.ok(same.preserved && same.selectedAfter === 1000, `same-revision update kept selection: ${JSON.stringify(same)}`);
      const changed = await t.p('update', true);
      m.updateNewRevisionCompileMs = changed.compileMs;
      m.updateNewRevisionMs = changed.updateMs;
      assert.ok(changed.preserved, `new-revision update reconciled by entity: ${JSON.stringify(changed)}`);
      assert.equal(changed.selectedAfter, 990, 'ten of the first thousand rows were removed');
      await t.idle();
      if (!canvas) {
        const replaced = await t.tab.evaluate(() => {
          const now = document.querySelector('#perf .intaglio-emphasis');
          return now !== null && now !== window.__intaglioEmphasis && !window.__intaglioEmphasis.isConnected;
        });
        assert.ok(replaced, 'a new view parses its own emphasis copy and the old one is detached');
        await t.tab.evaluate(() => { delete window.__intaglioEmphasis; });
      }

      log('dispose');
      const disposed = await t.p('dispose');
      assert.equal(disposed.listeners, 0, 'dispose removes every DOM listener');
      assert.equal(disposed.roots, 0, 'dispose removes the widget root');
      const afterDispose = await t.heap();
      m.retainedAfterDisposeMiB = mib(afterDispose - viewHeap);
      m.cycleMountDisposeMs = await t.p('cycle', 5);
      await t.p('release');
      const released = await t.heap();
      m.survivingWidgets = await t.p('survivors');
      m.retainedAfterReleaseMiB = mib(released - empty);
      assert.equal(m.survivingWidgets, 0, 'cycled widgets are collectable after dispose');
      assert.deepEqual(t.errors, [], 'no console errors');
      return m;
    } finally {
      await t.tab.close();
    }
  }

  async function membershipRun(n, canvas, retention) {
    const t = await openPage();
    try {
      const m = {};
      await t.p('load', n, canvas);
      const before = await t.heap();
      log(`membership ${retention}`);
      const result = await t.p('membership', retention, 0);
      await t.idle();
      const after = await t.heap();
      m.bins = result.bins;
      m.compileMs = result.compileMs;
      m.viewCompileMs = result.viewMs;
      m.mountMs = result.mountMs;
      m.retainedMiB = mib(after - before);
      const sel = await framed(t, 'select', [n / 2], 5, async () => { await t.p('select', 0); await t.idle(); });
      m.selectHalfMs = sel.actionMs;
      m.selectHalfRedrawMs = sel.frameMs;
      m.coveredRings = await t.p('coveredRings');
      if (retention === 'ExactKeys') assert.ok(m.coveredRings > 0, 'exact members drive coverage rings');
      else assert.equal(m.coveredRings, 0, 'counts alone measure no coverage');
      await t.p('release');
      const released = await t.heap();
      m.retainedAfterReleaseMiB = mib(released - before);
      assert.deepEqual(t.errors, [], 'no console errors');
      return m;
    } finally {
      await t.tab.close();
    }
  }

  const workloads = [
    ['svg10k', () => scatterRun(10000, false)],
    ['canvas100k', () => scatterRun(100000, true)],
    ['membershipCountOnly100k', () => membershipRun(100000, true, 'CountOnly')],
    ['membershipExactKeys100k', () => membershipRun(100000, true, 'ExactKeys')],
  ];
  const only = process.env.INTAGLIO_PERF_ONLY;
  let failed = false;
  try {
    for (const [name, run] of workloads) {
      if (only && !only.split(',').includes(name)) continue;
      const runs = [];
      for (let i = 0; i < RUNS; i++) {
        try {
          runs.push(await run());
        } catch (error) {
          failed = true;
          report.checks.push({ name: `${name} run ${i + 1}`, ok: false, error: String(error && error.stack || error) });
          break;
        }
      }
      if (runs.length === 0) continue;
      const summary = {};
      for (const key of Object.keys(runs[0])) summary[key] = round(median(runs.map(r => r[key])));
      report.workloads[name] = { median: summary, runs };
      report.checks.push({ name: `${name}: correctness and lifecycle assertions (${runs.length} runs)`, ok: runs.length === RUNS });
    }
  } finally {
    await browser.close();
  }

  // Compare medians with the committed budgets.
  const measured = {};
  for (const [workload, metrics] of Object.entries(budgets.workloads)) {
    const got = report.workloads[workload];
    for (const [metric, spec] of Object.entries(metrics)) {
      if (!got) continue;
      const value = got.median[metric];
      const ok = typeof value === 'number' && value <= spec.budget;
      report.budgets.push({ workload, metric, value, budget: spec.budget, recorded: spec.recorded, ok });
      if (!ok && !record) failed = true;
    }
  }
  if (only === undefined) {
    for (const workload of Object.keys(budgets.workloads)) {
      if (!report.workloads[workload]) { failed = true; report.checks.push({ name: `${workload} measured`, ok: false }); }
    }
  }
  if (record) {
    for (const [workload, w] of Object.entries(report.workloads)) {
      measured[workload] = {};
      for (const metric of Object.keys((budgets.workloads[workload] || {}))) {
        measured[workload][metric] = w.median[metric];
      }
    }
    await fs.writeFile(path.join(out, 'measured-budgets.json'), JSON.stringify(measured, null, 2) + '\n');
  }
  // A loaded machine slows every timed metric alike; the load is part of the evidence.
  report.machine.loadAverageAtEnd = os.loadavg().map(round);
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n');

  console.log(report.browser, '|', report.machine.cpu, '|', report.machine.platform, '| load', report.machine.loadAverageAtStart.join(' '), '->', report.machine.loadAverageAtEnd.join(' '));
  for (const [name, w] of Object.entries(report.workloads)) console.log(name, JSON.stringify(w.median));
  for (const b of report.budgets) {
    console.log(`${b.ok ? 'ok  ' : 'FAIL'} ${b.workload}.${b.metric} = ${b.value} (budget ${b.budget}, recorded ${b.recorded})`);
  }
  for (const c of report.checks) if (!c.ok) console.log('FAIL', c.name, c.error || '');
  if (failed && !record) {
    console.error('performance gate failed');
    process.exit(1);
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
