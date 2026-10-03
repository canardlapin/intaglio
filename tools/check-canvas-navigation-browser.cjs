// Paired navigation receipts supplement the SVG-specific DOM geometry checks.
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const {pathToFileURL}=require('node:url'),{chromium}=require('playwright');
async function main(){
  assert.equal(process.argv.length,4);const bundle=path.resolve(process.argv[2]),out=path.resolve(process.argv[3]);await fs.mkdir(out,{recursive:true});
  const html=path.join(out,'navigation.html');await fs.writeFile(html,(await fs.readFile(path.join(__dirname,'browser/navigation.html'),'utf8')).replace('FIXTURE_SCRIPT',pathToFileURL(bundle).href));
  const browser=await chromium.launch({headless:true}),report={browser:browser.version(),runs:[]};
  try{for(const renderer of ['svg','canvas']){
    const page=await browser.newPage({viewport:{width:1100,height:1100}}),errors=[];page.on('pageerror',e=>errors.push(String(e)));
    await page.goto(pathToFileURL(html).href+`?${renderer}`);await page.waitForFunction(()=>window.intaglioNav?.ready);
    const fx=(f,a)=>page.evaluate(f,a),settle=()=>page.waitForTimeout(100),result={renderer,checks:[],trace:[]};
    const win=s=>fx(s=>window.intaglioNav.window(s),s),sel=s=>fx(s=>window.intaglioNav.selected(s),s);
    const button=(s,mode)=>page.locator(`[data-intaglio-widget=${s}] button[data-mode=${mode}]`);
    const box=s=>page.locator(`[data-intaglio-widget=${s}] .intaglio-base`).boundingBox();
    const drag=async(a,b)=>{await page.mouse.move(...a);await page.mouse.down();await page.mouse.move(...b,{steps:12});await page.mouse.up();await settle()};
    const save=async(name,s)=>{result.checks.push(name);result.trace.push({name,window:await win(s),selection:await sel(s)});};
    const stats=await fx(()=>window.intaglioNav.statCalls());
    let b=await box('linear');await button('linear','Rectangle').click();await drag([b.x+b.width*.2,b.y+b.height*.2],[b.x+b.width*.6,b.y+b.height*.8]);
    const selection=await sel('linear');assert.ok(selection.length>3);
    assert.equal(await fx(()=>window.intaglioNav.navigate('linear',.2,.6)),'ok');await settle();
    await button('linear','Pan').click();b=await box('linear');const from=[b.x+b.width*.5,b.y+b.height*.5],to=[b.x+b.width*.4,b.y+b.height*.5];
    const grabbed=await fx(([x,y])=>window.intaglioNav.dataAt('linear',x,y),from),w0=await win('linear');await drag(from,to);
    const held=await fx(([x,y])=>window.intaglioNav.dataAt('linear',x,y),to),w1=await win('linear');
    assert.ok(w1[0]>w0[0]);assert.ok(Math.abs(held[0]-grabbed[0])<.3);assert.deepEqual(await sel('linear'),selection);await save('pan preserves grabbed datum and selection','linear');
    for(let i=0;i<3;i++)await drag([b.x+20,b.y+b.height/2],[b.x+b.width-20,b.y+b.height/2]);
    assert.ok(Math.abs((await win('linear'))[0])<1e-9);await save('pan clamps to lower bound','linear');
    await page.locator('[data-intaglio-widget=linear] button[data-action=reset]').click();await settle();assert.equal(await win('linear'),null);assert.deepEqual(await sel('linear'),selection);await save('reset preserves selection','linear');
    await button('log','ZoomRectangle').click();b=await box('log');const a=[b.x+b.width*.35,b.y+b.height*.25],c=[b.x+b.width*.65,b.y+b.height*.6];
    const lo=await fx(([x,y])=>window.intaglioNav.dataAt('log',x,y),a),hi=await fx(([x,y])=>window.intaglioNav.dataAt('log',x,y),c);await drag(a,c);
    const p=await fx(()=>window.intaglioNav.panel('log'));
    const left=await fx(([x,y])=>window.intaglioNav.dataAt('log',x,y),[p[0]+.01,(p[1]+p[3])/2]),right=await fx(([x,y])=>window.intaglioNav.dataAt('log',x,y),[p[2]-.01,(p[1]+p[3])/2]);
    assert.ok(Math.abs(left[0]-lo[0])<.01&&Math.abs(right[0]-hi[0])<.01);await save('log rectangle zoom preserves data edges','log');
    await page.locator('[data-intaglio-widget=date] .intaglio-plot').focus();await page.keyboard.press('+');await page.keyboard.press('+');await settle();assert.notEqual(await win('date'),null);await save('temporal keyboard zoom','date');
    if(renderer==='svg')assert.ok((await page.locator('[data-intaglio-widget=date] .intaglio-base text').allTextContents()).some(t=>/^2026-/.test(t)));
    await page.screenshot({path:path.join(out,`${renderer}-date.png`),fullPage:true});
    await page.keyboard.press('0');await settle();assert.equal(await win('date'),null);await save('temporal reset','date');
    if(renderer==='canvas'){
      // Paint, not just state: every strictly interior oracle point must have native ink nearby.
      for(const slot of ['linear','log','date']){
        const points=await fx(s=>window.intaglioNav.marks(s),slot),panel=await fx(s=>window.intaglioNav.panel(s),slot);
        const interior=points.filter(([,x,y])=>x>panel[0]+7&&x<panel[2]-7&&y>panel[1]+7&&y<panel[3]-7);
        assert.ok(interior.length>2);
        const painted=await fx(({slot,points})=>{const c=document.querySelector(`[data-intaglio-widget=${slot}] canvas.intaglio-base`),b=c.getBoundingClientRect(),ctx=c.getContext('2d');return points.map(([,x,y])=>{const px=(x-b.left)*c.width/b.width,py=(y-b.top)*c.height/b.height,d=ctx.getImageData(Math.floor(px)-5,Math.floor(py)-5,11,11).data;let n=0;for(let i=0;i<d.length;i+=4)if(d[i+3]&&Math.min(d[i],d[i+1],d[i+2])<170)n++;return n;})},{slot,points:interior});
        assert.ok(painted.every(n=>n>2),`${slot} missing painted oracle mark: ${painted}`);
      }
      assert.equal(await page.locator('svg.intaglio-base').count(),0);
    }
    assert.equal(await fx(()=>window.intaglioNav.statCalls()),stats,'navigation never recomputes the statistic');assert.deepEqual(errors,[]);
    report.runs.push(result);await page.close();
  }assert.deepEqual(report.runs[0].trace,report.runs[1].trace);
  }finally{await browser.close()}
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log('PASS: paired pan, bounds, log rectangle, temporal navigation and native paint');
}main().catch(e=>{console.error(e);process.exitCode=1});
