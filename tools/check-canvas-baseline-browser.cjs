// Remaining baseline variants, run through identical input on SVG and native Canvas.
const assert=require('node:assert/strict'), fs=require('node:fs/promises'), path=require('node:path');
const {pathToFileURL}=require('node:url'),{chromium}=require('playwright');
async function main(){
  assert.equal(process.argv.length,4,'Pass fixture main.js and output directory');
  const bundle=path.resolve(process.argv[2]),out=path.resolve(process.argv[3]);await fs.mkdir(out,{recursive:true});
  const html=path.join(out,'baseline.html');await fs.writeFile(html,`<!doctype html><html><head><meta charset="utf-8"><title>Baseline variants</title><style>body{margin:24px;font:14px system-ui}#baseline{width:600px}</style></head><body><h1>Baseline variants</h1><div id="baseline"></div><script src="${pathToFileURL(bundle).href}"></script></body></html>`);
  const browser=await chromium.launch({headless:true}),report={browser:browser.version(),runs:[]};
  try{for(const renderer of ['svg','canvas']){
    const page=await browser.newPage({viewport:{width:950,height:850}}),errors=[];page.on('pageerror',e=>errors.push(String(e)));
    await page.goto(pathToFileURL(html).href);await page.waitForFunction(()=>window.intaglioBaseline?.ready);
    const fx=(f,arg)=>page.evaluate(f,arg),settle=()=>page.waitForTimeout(80);
    const mount=async kind=>{await page.mouse.move(900,800);await fx(([k,r])=>window.intaglioBaseline.mount(k,r),[kind,renderer]);await settle();};
    const marks=()=>fx(()=>window.intaglioBaseline.marks()),selected=()=>fx(()=>window.intaglioBaseline.selected());
    const center=async()=> (await marks()).find(m=>m.id==='r4');
    const tip=()=>fx(()=>{const t=document.querySelector('.intaglio-tooltip'),r=document.querySelector('.intaglio-widget').getBoundingClientRect(),b=t.getBoundingClientRect();return{hidden:t.hidden,text:t.textContent,x:b.left-r.left,y:b.top-r.top}});
    const receipt={renderer,checks:[],trace:[]};
    const save=async name=>{receipt.checks.push(name);receipt.trace.push({name,events:await fx(()=>[...window.intaglioBaseline.events]),parts:await fx(()=>[...window.intaglioBaseline.parts]),selected:await selected()});};
    for(const kind of ['pointer','anchored','fixed']){
      await mount(kind);const m=await center();await page.mouse.move(m.x+2,m.y+1);await settle();const first=await tip();
      assert.equal(first.hidden,false);assert.equal(first.text,'r4');
      await page.mouse.move(m.x+2,m.y+1);await settle();const second=await tip();
      if(kind==='pointer'){const root=await page.locator('.intaglio-widget').boundingBox();assert.ok(Math.abs(first.x-(m.x+2-root.x+10))<1,JSON.stringify({first,m,root}));assert.ok(Math.abs(first.y-(m.y+1-root.y+10))<1);}
      else{assert.equal(second.x,first.x);assert.equal(second.y,first.y);}
      if(kind==='fixed'){assert.equal(first.x,28);assert.equal(first.y,42);}
      await save(`${kind} placement`);
    }
    await mount('pointer');let m=await center();await page.mouse.move(m.x+24,m.y);await settle();assert.equal((await tip()).hidden,true);await save('direct hover ignores empty space');
    await mount('nearest');m=await center();await page.mouse.move(m.x+24,m.y);await settle();assert.equal((await tip()).text,'r4');assert.equal((await tip()).hidden,false);await save('nearest hover reaches sparse observation');
    await mount('delayed');m=await center();await page.mouse.move(m.x,m.y);assert.equal((await tip()).hidden,true);await page.waitForTimeout(400);assert.equal((await tip()).hidden,false);await save('configured delay is observed before showing');
    await mount('histogram');const bin=(await marks())[1];await page.mouse.move(bin.x,bin.y);await settle();assert.equal((await tip()).hidden,false);assert.match((await tip()).text,/^bin rows: 3$/);await save('plain histogram membership tooltip');
    await mount('labels');m=await center();await page.mouse.move(m.x,m.y);await settle();assert.equal((await tip()).text,'r4');await page.mouse.click(m.x,m.y);assert.deepEqual(await selected(),['r4']);await save('data labels are addressable keyed marks');
    await mount('line');const line=(await marks())[0];await page.mouse.move(line.x,line.y);await settle();assert.equal((await tip()).text,'connected series');assert.equal((await tip()).hidden,false);await page.mouse.click(line.x,line.y);assert.equal(await fx(()=>window.intaglioBaseline.selectedTargets()),1);await save('line inspected and selected as a target');
    for(const kind of ['disabled','single','initial']){
      await mount(kind);const ms=await marks();if(kind==='initial')assert.deepEqual(await selected(),['r4']);
      await page.mouse.click(ms[0].x,ms[0].y);await page.mouse.click(ms[1].x,ms[1].y);await settle();
      assert.deepEqual(await selected(),kind==='disabled'?[]:['r1']);
      await save(`${kind} selection`);
    }
    await mount('multiple');m=await center();await page.mouse.click(m.x,m.y);await page.keyboard.down('Shift');await page.mouse.click(m.x,m.y);await page.keyboard.up('Shift');assert.deepEqual(await selected(),[]);await save('additive click toggles selected observation');
    await mount('multiple');m=await center();await page.mouse.click(m.x,m.y);await page.locator('.intaglio-plot').focus();await page.keyboard.press('Enter');await settle();
    const activation=await fx(()=>[...window.intaglioBaseline.events]);assert.ok(activation.includes('activate:r4:Pointer'));assert.ok(activation.includes('activate:r4:Keyboard'));await save('pointer and keyboard activate the same key');
    // Fixed grid provides an independent membership oracle: the middle row is r3,r4,r5.
    async function lasso(modifier){
      const ms=await marks(),row=ms.filter(m=>['r3','r4','r5'].includes(m.id));
      const left=Math.min(...row.map(m=>m.x))-9,right=Math.max(...row.map(m=>m.x))+9,top=row[0].y-9,bottom=row[0].y+9;
      await page.locator('button[data-mode=Lasso]').click();if(modifier)await page.keyboard.down(modifier);
      await page.mouse.move(left,top);await page.mouse.down();await page.mouse.move(right,top,{steps:4});await page.mouse.move(right,bottom,{steps:3});await page.mouse.move(left,bottom,{steps:4});await page.mouse.move(left,top,{steps:3});await page.mouse.up();if(modifier)await page.keyboard.up(modifier);await settle();
    }
    await mount('multiple');await lasso();assert.deepEqual(await selected(),['r3','r4','r5']);await save('lasso replaces with exact middle row');
    await fx(()=>window.intaglioBaseline.select(['r0']));await lasso('Shift');assert.deepEqual(await selected(),['r0','r3','r4','r5']);await save('shift lasso adds exact middle row');
    await lasso('Alt');assert.deepEqual(await selected(),['r0']);await save('alt lasso subtracts exact middle row');
    await page.locator('button[data-mode=Inspect]').click();m=await center();
    await fx(()=>{const r=document.querySelector('.intaglio-widget');r.style.setProperty('--intaglio-hover','#c01234');r.style.setProperty('--intaglio-dim','0.45');});
    await page.mouse.move(m.x,m.y);await page.waitForTimeout(220);
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-ring-hover')).stroke),'rgb(192, 18, 52)');
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-base')).opacity),'0.45');await save('application appearance overrides');
    await mount('appearance');m=await center();await page.mouse.move(m.x,m.y);await page.waitForTimeout(300);
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-ring-hover')).stroke),'rgb(192, 18, 52)');
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-tooltip')).backgroundColor),'rgb(20, 40, 60)');
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-base')).transitionDuration),'0.25s');
    assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-base')).opacity),'0.45');
    await page.emulateMedia({reducedMotion:'reduce'});assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-base')).transitionDuration),'0s');
    await page.emulateMedia({reducedMotion:'no-preference'});await save('typed appearance and transition options honor reduced motion');
    await page.mouse.move(900,800);await page.waitForTimeout(300);assert.equal(await fx(()=>getComputedStyle(document.querySelector('.intaglio-base')).opacity),'1');assert.equal(await page.locator('.intaglio-ring-hover').count(),0);await save('pointer out restores original appearance');
    await mount('authored-styles');m=await center();
    const markPixel=()=>fx(async m=>{
      const base=document.querySelector('.intaglio-base'),box=base.getBoundingClientRect();
      let canvas=base;
      if(base.tagName.toLowerCase()==='svg'){
        const image=new Image(),url=URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(base)],{type:'image/svg+xml'}));
        try{await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=reject;image.src=url});canvas=document.createElement('canvas');canvas.width=600;canvas.height=400;canvas.getContext('2d').drawImage(image,0,0,600,400)}finally{URL.revokeObjectURL(url)}
      }
      return Array.from(canvas.getContext('2d').getImageData(Math.floor((m.x-box.left)*canvas.width/box.width),Math.floor((m.y-box.top)*canvas.height/box.height),1,1).data);
    },m);
    assert.deepEqual(await markPixel(),[15,150,120,255]);
    await page.mouse.move(m.x,m.y);await settle();await page.mouse.move(900,800);await page.waitForTimeout(200);
    assert.deepEqual(await markPixel(),[15,150,120,255]);await save('application-assigned per-entity paint survives emphasis recovery');
    await mount('parts');const targets=await fx(()=>window.intaglioBaseline.partTargets());
    for(const kind of ['PlotTitle','PlotSubtitle','FacetStrip','Axis','Colorbar','Annotation']){
      const p=targets.find(p=>p.kind===kind);assert.ok(p,`fixture contains ${kind}`);
      await page.mouse.move(900,800);await page.mouse.move(p.x,p.y);await settle();assert.equal((await tip()).text,p.description,JSON.stringify(p));await page.mouse.click(p.x,p.y);await settle();
      const parts=await fx(()=>[...window.intaglioBaseline.parts]);
      assert.ok(parts.includes(`activate:${kind}:Pointer`),JSON.stringify({kind,p,parts}));
    }
    await save('title subtitle strip axis colorbar annotation activation');
    await page.screenshot({path:path.join(out,`${renderer}-parts.png`)});
    assert.deepEqual(errors,[]);assert.deepEqual(await fx(()=>[...window.intaglioBaseline.errors]),[]);
    if(renderer==='canvas'){assert.equal(await page.locator('svg.intaglio-base').count(),0);assert.equal(await page.locator('canvas.intaglio-base[data-render-state=ready]').count(),1);}
    assert.deepEqual(await fx(r=>window.intaglioBaseline.invalidAppearance(r),renderer),[true,true,true,true]);receipt.checks.push('invalid appearance fails mount without DOM mutation');
    await fx(()=>window.intaglioBaseline.dispose());await page.close();report.runs.push(receipt);
  }
  assert.deepEqual(report.runs[0].trace,report.runs[1].trace,'All normalized baseline traces match');
  }finally{await browser.close()}
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log(`PASS ${report.runs[0].checks.length} baseline variants on SVG and Canvas`);
}
main().catch(e=>{console.error(e);process.exitCode=1});
