// Same fixed specimens and real inputs over SVG and native Canvas. No system browser/profile.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
async function main() {
  assert.equal(process.argv.length, 4, 'Pass fixture main.js and output directory');
  const script = path.resolve(process.argv[2]), out = path.resolve(process.argv[3]);
  await fs.mkdir(out, {recursive:true});
  const html = path.join(out, 'parity.html');
  await fs.writeFile(html, `<!doctype html><html><head><meta charset="utf-8"><title>Interaction parity</title>
  <style>body{margin:20px;font:14px system-ui;background:white}#parity{display:grid;grid-template-columns:540px 540px;gap:24px}</style>
  </head><body><h1>Clipping, transforms, facets and composition</h1><div id="parity"></div><script src="${pathToFileURL(script).href}"></script></body></html>`);
  const browser = await chromium.launch({headless:true});
  const report = {browser:browser.version(), runs:[]};
  try {
    for (const dpr of [1,2]) for(const renderer of ['svg','canvas']) {
      const context = await browser.newContext({viewport:{width:1200,height:1450},deviceScaleFactor:dpr,hasTouch:true});
      const page = await context.newPage(), errors=[];
      page.on('pageerror',e=>errors.push(String(e)));
      await page.goto(pathToFileURL(html).href+`?${renderer}`);
      await page.waitForFunction(()=>window.intaglioParity?.ready);
      const result = {renderer,dpr,cases:[]};
      const settle = ()=>page.waitForTimeout(80);
      for(const slot of ['linear','clipped','log','facets','composed']) {
        const marks = await page.evaluate(s=>window.intaglioParity.marks(s),slot);
        assert.ok(marks.length >= 12, `${slot}: visible marks`);
        const chosen = [marks[Math.floor(marks.length/3)],marks[Math.floor(marks.length*2/3)]];
        const trace = {slot,marks:marks.map(m=>[m.id,m.localX,m.localY]), selections:[]};
        for(const mark of chosen) {
          await page.mouse.move(mark.x,mark.y); await settle();
          await page.mouse.click(mark.x,mark.y); await settle();
          const selected=await page.evaluate(s=>window.intaglioParity.selected(s),slot);
          assert.deepEqual(selected,[mark.id], `${slot}: pointer selects actual rendered mark`);
          trace.selections.push(selected);
          if(renderer==='canvas') {
            // The default point glyph is a hollow ring and the anchor is its centre (hollow points
            // pick on their whole disc), so the evidence is dark ink around the anchor whose
            // centroid is the anchor: a glyph drawn elsewhere, or not at all, fails.
            const ink = await page.evaluate(({slot,mark})=>{
              const c=document.querySelector(`[data-intaglio-widget=${slot}] canvas.intaglio-base`);
              const sx=c.width/540, sy=c.height/360, half=Math.ceil(8*sx);
              const cx=mark.localX*sx, cy=mark.localY*sy, x0=Math.floor(cx)-half, y0=Math.floor(cy)-half, n=2*half+1;
              const d=c.getContext('2d').getImageData(x0,y0,n,n).data;
              let count=0, mx=0, my=0;
              for(let i=0;i<n*n;i++) if(d[i*4+3]>0 && Math.min(d[i*4],d[i*4+1],d[i*4+2])<220) {
                count++; mx+=x0+i%n+0.5; my+=y0+Math.floor(i/n)+0.5;
              }
              return {count, dx: count ? mx/count-cx : NaN, dy: count ? my/count-cy : NaN, scale: sx};
            },{slot,mark});
            assert.ok(ink.count>=8*ink.scale && Math.hypot(ink.dx,ink.dy)<=0.75*ink.scale,
              `${slot}: actual mark paint centred on the anchor: ${JSON.stringify(ink)}`);
          }
        }
        await page.locator(`[data-intaglio-widget=${slot}] .intaglio-plot`).focus();
        await page.keyboard.press('Home'); await page.keyboard.press('Enter'); await settle();
        const first = await page.evaluate(s=>window.intaglioParity.selected(s),slot);
        assert.deepEqual(first,[marks[0].id]); trace.selections.push(first);
        await page.keyboard.press('Escape'); await settle();
        assert.deepEqual(await page.evaluate(s=>window.intaglioParity.selected(s),slot),[]);
        await page.touchscreen.tap(chosen[0].x,chosen[0].y); await settle();
        assert.deepEqual(await page.evaluate(s=>window.intaglioParity.selected(s),slot),[chosen[0].id]);
        trace.selections.push(await page.evaluate(s=>window.intaglioParity.selected(s),slot));
        if(slot==='composed') {
          assert.equal(await page.locator('[data-intaglio-widget=composed] .intaglio-ring-selected').count(),2,
            'shared entity highlights both transformed children');
          assert.match(await page.evaluate(()=>window.intaglioParity.navigate('composed')),/navigating this plot/);
        }
        result.cases.push(trace);
      }
      // Region selection and actual data-window navigation use the common input host.
      await page.evaluate(()=>window.intaglioParity.clear('linear'));
      await page.locator('[data-intaglio-widget=linear] [data-mode=Rectangle]').click();
      const marks=await page.evaluate(()=>window.intaglioParity.marks('linear'));
      const xs=marks.map(m=>m.x),ys=marks.map(m=>m.y);
      const left=Math.min(...xs)-8,top=Math.min(...ys)-8,right=Math.max(...xs)+8,bottom=Math.max(...ys)+8;
      await page.mouse.move(left,top); await page.mouse.down(); await page.mouse.move(right,bottom,{steps:8}); await page.mouse.up(); await settle();
      result.region=await page.evaluate(()=>window.intaglioParity.selected('linear'));
      assert.deepEqual(result.region,marks.map(m=>m.id).sort());
      assert.equal(await page.evaluate(()=>window.intaglioParity.navigate('log')),'ok'); await settle();
      assert.equal(await page.evaluate(()=>window.intaglioParity.reset('log')),'ok'); await settle();
      await page.locator('[data-intaglio-widget=composed] summary').click(); await settle();
      assert.ok(await page.locator('[data-intaglio-widget=composed] tbody tr').count()>=48);
      await page.locator('[data-intaglio-widget=composed] summary').click();
      await page.locator('[data-intaglio-widget=linear] [data-mode=Inspect]').click();
      await page.locator('[data-intaglio-widget=linear] .intaglio-plot').focus();
      await settle();
      const box=await page.locator('[data-intaglio-widget=linear] .intaglio-base').boundingBox();
      const cx=box.x+box.width/2,cy=box.y+box.height/2;
      const full = await page.evaluate(()=>window.intaglioParity.window('linear'));
      const fullWidth = full[1]-full[0];
      await page.mouse.move(cx,cy);
      await page.mouse.wheel(0,-200); await settle();
      result.wheel=await page.evaluate(()=>window.intaglioParity.window('linear'));
      assert.ok(result.wheel[1]-result.wheel[0]<fullWidth, JSON.stringify({renderer,dpr,box,w:result.wheel,
        dom:await page.evaluate(([x,y])=>({at:document.elementFromPoint(x,y)?.outerHTML.slice(0,200),active:document.activeElement?.className,scrollY,innerHeight}),[cx,cy])}));
      await page.evaluate(()=>window.intaglioParity.reset('linear')); await settle();
      const cdp=await context.newCDPSession(page);
      const points=d=>[{x:cx-d,y:cy,id:1},{x:cx+d,y:cy,id:2}];
      await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:points(30)});
      for(const d of [45,60,80,100]) {
        await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:points(d)});
        await settle();
      }
      await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); await settle();
      result.pinch=await page.evaluate(()=>window.intaglioParity.window('linear'));
      assert.ok(result.pinch[1]-result.pinch[0]<fullWidth*0.5);
      assert.equal(await page.evaluate(()=>visualViewport.scale),1);
      await cdp.detach();
      await page.evaluate(()=>window.intaglioParity.reset('linear')); await settle();
      if(renderer==='canvas') {
        assert.equal(await page.locator('svg.intaglio-base').count(),0);
        assert.equal(await page.locator('canvas.intaglio-base[data-render-state=ready]').count(),5);
        const sizing=await page.evaluate(()=>[...document.querySelectorAll('canvas.intaglio-base')].map(c=>{
          const b=c.getBoundingClientRect();return [c.width,c.height,Math.ceil(b.width*devicePixelRatio),Math.ceil(b.height*devicePixelRatio)];
        }));
        sizing.forEach(s=>assert.deepEqual(s.slice(0,2),s.slice(2)));
      }
      result.events=await page.evaluate(()=>window.intaglioParity.events);
      assert.deepEqual(await page.evaluate(()=>window.intaglioParity.errors),[]);
      assert.deepEqual(errors,[]);
      await page.screenshot({path:path.join(out,`${renderer}-${dpr}x.png`),fullPage:true});
      await page.evaluate(()=>window.intaglioParity.dispose());
      assert.equal(await page.locator('[data-intaglio-widget]').count(),0);
      report.runs.push(result);
      await context.close();
    }
    for(const dpr of [1,2]) {
      const [svg,canvas]=report.runs.filter(r=>r.dpr===dpr);
      assert.deepEqual(canvas.cases,svg.cases,`DPR ${dpr}: geometry and selected keys match`);
      assert.deepEqual(canvas.region,svg.region);
      assert.deepEqual(canvas.wheel,svg.wheel);
      assert.deepEqual(canvas.pinch,svg.pinch);
      assert.deepEqual(canvas.events,svg.events,`DPR ${dpr}: exact normalized logical event traces`);
    }
  } finally { await browser.close(); }
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
  console.log('PASS: SVG/Canvas traces, pixels, region/touch/keyboard, facets, clipping, transforms, composition; DPR 1 and 2');
}
main().catch(e=>{console.error(e);process.exitCode=1});
