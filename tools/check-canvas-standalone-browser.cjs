// Offline packaging receipt: node tools/check-standalone-browser.cjs <fixture main.js> <out>
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
const { standaloneHtml } = require('./package-widget.cjs');
(async () => {
  const [input, output] = process.argv.slice(2);
  assert.ok(input && output, 'Pass the NoModule browser fixture bundle and output directory');
  const out = path.resolve(output); await fs.mkdir(out, {recursive:true});
  const script = await fs.readFile(input, 'utf8');
  const file = path.join(out,'standalone.html');
  await fs.writeFile(file, standaloneHtml({script,containers:['left','right'],title:'Offline interaction example'}));
  const browser = await chromium.launch({headless:true});
  const report = {browser:browser.version(),checks:[]};
  try {
    for (const [width, dpr] of [[1200,1],[390,2]]) {
      const context = await browser.newContext({viewport:{width,height:900},deviceScaleFactor:dpr,offline:true});
      try {
        const page = await context.newPage(); const requests = [], errors = [];
        page.on('request',r=>requests.push(r.url()));page.on('pageerror',e=>errors.push(e.message));
        await page.goto((pathToFileURL(file).href+"?canvas"));
        await page.waitForFunction(()=>window.intaglioFixture?.ready);
        const ids = await page.locator('[id]').evaluateAll(es=>es.map(e=>e.id));
        assert.equal(ids.length,new Set(ids).size);
        await page.locator('[data-intaglio-widget=left] .intaglio-plot').focus();
        await page.keyboard.press('Home');await page.keyboard.press('Enter');
        assert.equal((await page.evaluate(()=>window.intaglioFixture.selected('left'))).length,1);
        assert.deepEqual(await page.evaluate(()=>window.intaglioFixture.selected('right')),[]);
        const count = await page.evaluate(()=>window.intaglioFixture.events.left.length);
        assert.equal(await page.evaluate(()=>window.intaglioFixture.setSelection(['t2'])),'ok');
        assert.equal(await page.evaluate(()=>window.intaglioFixture.events.left.length),count,'controlled state emits no echo');
        await page.waitForTimeout(100);
        const layout = await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,
          error:document.getElementById('intaglio-package-error').hidden,
          plots:[...document.querySelectorAll('canvas.intaglio-base')].map(e=>e.getBoundingClientRect().width)}));
        assert.equal(layout.plots.length,2,'both widgets paint native Canvas');
        assert.ok(layout.scroll<=width,'responsive package does not overflow');assert.equal(layout.error,true);
        assert.deepEqual(errors,[]);assert.deepEqual(requests,[(pathToFileURL(file).href+"?canvas")],'only packaged HTML is requested');
        await page.screenshot({path:path.join(out,`offline-${width}-${dpr}x.png`),fullPage:true});
        report.checks.push({width,dpr,layout,requests,keyboardSelection:true,controlledNoEcho:true});
      } finally {await context.close();}
    }
    const negative = path.join(out,'unbundled.html');
    await fs.writeFile(negative,standaloneHtml({script:"fetch('https://example.invalid/missing').catch(()=>{});",containers:['one']}));
    const page = await browser.newPage();await page.goto(pathToFileURL(negative).href);
    await page.waitForFunction(()=>!document.getElementById('intaglio-package-error').hidden);
    assert.match(await page.locator('#intaglio-package-error').textContent(),/unbundled resource.*Embed assets/);
    const escaped = path.join(out,'escaped.html');
    const value = 'α </script><script>window.injected=true</script>';
    await fs.writeFile(escaped,standaloneHtml({script:`window.roundTrip=${JSON.stringify(value)};`,containers:['one']}));
    await page.goto(pathToFileURL(escaped).href);
    assert.equal(await page.evaluate(()=>window.roundTrip),value);assert.equal(await page.evaluate(()=>window.injected),undefined);
    report.unbundledResourceDiagnostic=true;report.unicodeAndClosingTags=true;
    await page.close();
  } finally {await browser.close();}
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
