// Real-browser evidence for aggregate membership (Interaction 09): exact bin members, linked
// coverage counts and emphasis, and deferred members through a resolver, on SVG and Canvas.
//
// Usage: node tools/check-membership-browser.cjs <fixture main.js> <output dir>
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
  const template = await fs.readFile(path.join(__dirname, 'browser', 'membership.html'), 'utf8');
  const page = path.join(out, 'membership.html');
  await fs.writeFile(page, template.replace('FIXTURE_SCRIPT', pathToFileURL(script).href));

  const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  const report = { browser: browser.version(), checks: [] };
  try {
    for (const renderer of ['svg', 'canvas']) {
      const check = (name, fn) =>
        fn().then(detail => report.checks.push({ name: `${renderer}: ${name}`, ok: true, detail }));
      const tab = await browser.newPage({ viewport: { width: 1000, height: 900 } });
      const errors = [];
      tab.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
      tab.on('pageerror', e => errors.push(String(e)));
      await tab.goto(pathToFileURL(page).href + (renderer === 'canvas' ? '?canvas' : ''));
      await tab.waitForFunction(() => window.intaglioMembers && window.intaglioMembers.ready);
      const fx = (body, ...args) => tab.evaluate(body, ...args);
      const m = (name, ...args) => fx(([n, a]) => window.intaglioMembers[n](...a), [name, args]);
      const settle = (ms = 80) => tab.waitForTimeout(ms);
      const sorted = xs => [...xs].sort();
      const eventsOf = slot => fx(s => window.intaglioMembers.events[s].slice(), slot);

      await check('clicking each bin selects exactly its members, in the histogram and both scatters', async () => {
        const bins = await m('bins', 'h');
        assert.ok(bins.length >= 3);
        const before = { a: (await eventsOf('a')).length, b: (await eventsOf('b')).length };
        for (const [x, y, members] of bins) {
          await tab.mouse.click(x, y);
          await settle();
          assert.deepEqual(await m('selected', 'h'), sorted(members), 'the histogram holds the members');
          assert.equal(await m('targets', 'h'), 0, 'members, not the bin');
          assert.deepEqual(await m('selected', 'a'), sorted(members));
          assert.deepEqual(await m('selected', 'b'), sorted(members));
        }
        // Projection is silent: the scatters emitted no selection events of their own.
        const after = { a: await eventsOf('a'), b: await eventsOf('b') };
        assert.ok(after.a.slice(before.a).every(e => !e.startsWith('SelectionChanged')), after.a.join(' '));
        assert.ok(after.b.slice(before.b).every(e => !e.startsWith('SelectionChanged')), after.b.join(' '));
        // In Members mode the bin's observations are the reader's: clearing the histogram clears
        // them in the linked scatters too.
        await tab.keyboard.press('Escape');
        await settle();
        assert.deepEqual(await m('selected', 'h'), []);
        assert.deepEqual(await m('selected', 'a'), [], 'Escape in a Members-mode histogram clears the group');
        assert.deepEqual(await m('selected', 'b'), []);
        return { bins: bins.map(b => b[2].length) };
      });

      await check('an area selected in a scatter shows each bin\'s covered count and rings bins covered by half', async () => {
        // Open the histogram's text companion first: it must follow the linked change.
        await tab.locator('[data-intaglio-widget=h] summary').click();
        await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Select area' }).click();
        const r = await fx(() => document.querySelector('[data-intaglio-widget=a] .intaglio-base').getBoundingClientRect().toJSON());
        // A band of accuracy (the y axis) across every RT bin, so bins are partly covered.
        await tab.mouse.move(r.left + r.width * 0.02, r.top + r.height * 0.05);
        await tab.mouse.down();
        await tab.mouse.move(r.left + r.width * 0.98, r.top + r.height * 0.45, { steps: 8 });
        await tab.mouse.up();
        await settle(120);
        const swept = new Set(await m('selected', 'a'));
        assert.ok(swept.size > 0);
        const snapshot = { a: await m('selected', 'a'), b: await m('selected', 'b'), h: await m('selected', 'h'), d: await m('selected', 'd') };
        assert.deepEqual(new Set(await m('selected', 'h')), swept, JSON.stringify(snapshot));
        const bins = await m('bins', 'h');
        const expected = bins.filter(([, , members]) => members.filter(id => swept.has(id)).length * 2 >= members.length).length;
        assert.equal(await m('covered', 'h'), expected, 'covered rings by the half rule');
        assert.equal(await m('covered', 'd'), 0, 'deferred bins show no coverage: their members are unknown');
        const companion = await fx(() => [...document.querySelectorAll('[data-intaglio-widget=h] details td')].map(td => td.textContent).join(' | '));
        for (const [, , members] of bins) {
          const k = members.filter(id => swept.has(id)).length;
          assert.ok(companion.includes(`${k} of ${members.length} selected`), `companion: ${companion}`);
        }
        // Hovering a bin states its coverage.
        const counts = [];
        for (const [x, y, members] of bins) {
          await tab.mouse.move(x, y);
          await tab.waitForFunction(() => !document.querySelector('[data-intaglio-widget=h] .intaglio-tooltip').hidden);
          const text = await fx(() => document.querySelector('[data-intaglio-widget=h] .intaglio-tooltip').textContent);
          const k = members.filter(id => swept.has(id)).length;
          assert.ok(text.includes(`${k} of ${members.length} selected`), `${text} vs ${k}/${members.length}`);
          counts.push(`${k}/${members.length}`);
        }
        await tab.mouse.move(5, 5);
        await tab.locator('[data-intaglio-widget=a] .intaglio-toolbar button', { hasText: 'Inspect' }).click();
        // Keyboard focus on a bin announces its coverage.
        await fx(() => document.querySelector('[data-intaglio-widget=h] .intaglio-plot').focus());
        await tab.keyboard.press('Home');
        await settle();
        assert.match(await m('live', 'h'), /\d+ of \d+ selected/, 'focus announces coverage');
        // Pointing at an observation in scatter a emphasizes the bin that holds it.
        const [id, px, py] = (await m('marks', 'a'))[0];
        await tab.mouse.move(px, py);
        await settle(120);
        const linkedRings = await fx(() => document.querySelectorAll('[data-intaglio-widget=h] .intaglio-ring-linked').length);
        assert.equal(linkedRings, 1, `hovering ${id} rings its one bin`);
        await tab.mouse.move(5, 5);
        await tab.screenshot({ path: path.join(out, `${renderer}-coverage.png`) });
        assert.ok(counts.some(c => { const [k, n] = c.split('/').map(Number); return k > 0 && k < n; }),
          `some bin is partly covered: ${counts}`);
        return { swept: swept.size, counts, covered: expected };
      });

      await check('a deferred bin asks the resolver, applies its exact reply, and announces it', async () => {
        const bins = await m('bins', 'd');
        const [x, y, members] = bins[1];
        await tab.mouse.click(x, y);
        await settle();
        const requests = await fx(() => window.intaglioMembers.requests.slice());
        assert.equal(requests.length, 1, 'one request recorded');
        await m('reply', 0, 'complete');
        await settle(120);
        const dState = { live: await m('live', 'd'), events: (await eventsOf('d')).slice(-6) };
        assert.deepEqual(await m('selected', 'd'), sorted(members), JSON.stringify(dState));
        assert.deepEqual(await m('selected', 'a'), sorted(members), 'resolved members link like exact ones');
        assert.match(await m('live', 'd'), new RegExp(`The ${members.length} members of the bin are applied`));
        return { members: members.length };
      });

      await check('superseded, short and failed replies change nothing; a failure is announced', async () => {
        const before = await m('selected', 'd');
        const [x, y] = (await m('bins', 'd'))[0];
        await tab.mouse.click(x, y); // request 2
        await settle();
        await tab.mouse.click(x, y); // request 3 supersedes it
        await settle();
        await m('reply', 1, 'complete'); // the superseded one
        await settle(120);
        assert.deepEqual(await m('selected', 'd'), before, 'a superseded reply is not applied');
        await m('reply', 2, 'short'); // the current one, one member short
        await settle(120);
        assert.deepEqual(await m('selected', 'd'), before, 'a partial reply is not applied');
        assert.match(await m('live', 'd'), /could not be applied/, 'a malformed current reply is announced');
        await tab.mouse.click(x, y); // request 4
        await settle();
        await m('reply', 3, 'failed');
        await settle(120);
        assert.deepEqual(await m('selected', 'd'), before);
        assert.match(await m('live', 'd'), /could not be retrieved/);
        assert.deepEqual(errors, []);
        return { requests: (await fx(() => window.intaglioMembers.requests.length)) };
      });
      await tab.close();
    }
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
