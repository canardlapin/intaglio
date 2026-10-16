// Profile: lean-browser-playwright. Uses installed Playwright Chromium and a fresh, owned context.
// Link ConsumerRasterBrowserProbe from canvasJS/Test with the test initializer disabled.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');

async function main() {
  assert.equal(process.argv.length, 4, 'Pass linked main.js and output directory');
  const script = path.resolve(process.argv[2]), out = path.resolve(process.argv[3]);
  await fs.mkdir(out, {recursive: true});
  const html = path.join(out, 'consumer-raster.html');
  await fs.writeFile(html, `<!doctype html><meta charset="utf-8"><canvas id="probe"></canvas><script src="${pathToFileURL(script).href}"></script>`);
  const browser = await chromium.launch({headless: true});
  const report = {browser: browser.version(), runs: []};
  try {
    for (const scale of [1, 2]) {
      const context = await browser.newContext({deviceScaleFactor: scale});
      try {
        const page = await context.newPage(), errors = [];
        page.on('pageerror', error => errors.push(String(error)));
        await page.goto(pathToFileURL(html).href);
        await page.waitForFunction(() => typeof ConsumerRasterProbe !== 'undefined');
        for (const mode of ['nearest','scaledHost']) for (const [x, width] of [[49.96,286.25],[0.49,301],[3.3,150.2],[49.96,79.01],[41.7,287.3],[10,602]]) {
          const pixels = await page.evaluate(({x,width,scale,mode}) => {
            const c = document.querySelector('#probe');
            ConsumerRasterProbe[mode](c, x, width, scale);
            return Array.from(c.getContext('2d').getImageData(0, Math.floor(c.height/2), c.width, 1).data);
          }, {x,width,scale,mode});
          let sampled = new Set();
          for (let column=0; column<pixels.length/4; column++) {
            const centre = column + 0.5;
            if (centre >= x*scale && centre < (x+width)*scale) {
              const source = Math.floor((centre-x*scale)/(width*scale)*301);
              assert.deepEqual(pixels.slice(column*4,column*4+4),[source%256,Math.floor(source/256),0,255], `scale=${scale} x=${x} width=${width} col=${column}`);
              sampled.add(source);
            } else assert.equal(pixels[column*4+3],0);
          }
          report.runs.push({kind:mode,scale,x,width,samples:sampled.size});
        }
        for (const width of [291,86]) {
          const pixels = await page.evaluate(({width,scale}) => {
            const c=document.querySelector('#probe'); ConsumerRasterProbe.dense(c,width,scale);
            return Array.from(c.getContext('2d').getImageData(0,Math.floor(c.height/2),c.width,1).data);
          }, {width,scale});
          for(let column=0;column<width*scale;column++) assert.deepEqual(pixels.slice(column*4,column*4+4),[0,0,0,255]);
          report.runs.push({kind:'dense-fixed-raster',scale,width});
        }
        for(const family of ['serif','sans-serif','monospace','cursive','fantasy','system-ui','Helvetica']) {
          const result=await page.evaluate(family=>{
            const c=document.querySelector('#probe'), measured=ConsumerRasterProbe.font(c,family);
            const reference=document.createElement('canvas');reference.width=c.width;reference.height=c.height;
            const ctx=reference.getContext('2d');ctx.font=`32px ${family==='Helvetica'?'"Helvetica"':family}`;
            ctx.textBaseline='top';ctx.fillText('Hamburgefonstiv',0,0);
            const expected=ctx.getImageData(0,0,c.width,c.height).data;
            const actual=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
            return {measured,expectedWidth:ctx.measureText('Hamburgefonstiv').width,equal:expected.every((v,i)=>v===actual[i])};
          },family);
          assert.equal(result.equal,true,`${family}: drawing matches native CSS family`);
          assert.ok(Math.abs(result.measured-result.expectedWidth)<1e-7,`${family}: measurement matches drawing`);
          report.runs.push({kind:'font',scale,family,...result});
        }
        await page.evaluate(() => {
          document.body.style.cssText='margin:16px;background:white;display:flex;gap:16px;font:14px sans-serif';
          for(const hidden of [false,true]) {
            const box=document.createElement('div');
            box.textContent=hidden?'Hidden axes; derived colorbar retained':'Measured axes and short colorbar';
            const c=document.createElement('canvas');c.style.cssText='display:block;width:360px;height:260px';
            box.append(c);document.body.append(box);ConsumerRasterProbe.guides(c,hidden);
          }
          document.querySelector('#probe').style.display='none';
        });
        await page.screenshot({path:path.join(out,`guides-dpr${scale}.png`),fullPage:true});
        assert.deepEqual(errors,[]);
      } finally { await context.close(); }
    }
    await fs.writeFile(path.join(out,'report.json'), JSON.stringify(report,null,2)+'\n');
    process.stdout.write(`Passed ${report.runs.length} browser consumer regressions (${report.browser})\n`);
  } finally { await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1});
