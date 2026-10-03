const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require('playwright');
const {standaloneHtml} = require('./package-widget.cjs');
(async()=>{
  const [input,output,fontFile]=process.argv.slice(2);assert.ok(input&&output);
  const out=path.resolve(output);await fs.mkdir(out,{recursive:true});
  const file=path.join(out,'export.html');
  await fs.writeFile(file,standaloneHtml({script:await fs.readFile(input,'utf8'),containers:['left','right']}));
  const browser=await chromium.launch({headless:true});const report={browser:browser.version(),images:[]};
  try {
    const page=await browser.newPage();
    await page.addInitScript(()=>{
      window.liveUrls=0;
      const create=URL.createObjectURL.bind(URL),revoke=URL.revokeObjectURL.bind(URL);
      URL.createObjectURL=x=>{window.liveUrls++;return create(x)};
      URL.revokeObjectURL=x=>{window.liveUrls--;return revoke(x)};
    });
    await page.goto(pathToFileURL(file).href);await page.waitForFunction(()=>window.intaglioFixture?.ready);
    await page.evaluate(()=>{window.exportApi=intaglioExportFixture()});
    const root = page.locator('[data-intaglio-widget=export-original]');
    const toolbar = root.locator('.intaglio-toolbar');
    assert.deepEqual(await toolbar.locator('button').allTextContents(), ['Reset view','Fullscreen','Download PNG']);
    assert.equal(await toolbar.locator('button[tabindex="0"]').count(),1,'omitting Inspect retains a toolbar tab stop');
    await toolbar.locator('button[tabindex="0"]').focus();await page.keyboard.press('End');
    assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Download PNG');
    const boxes = await root.evaluate(r=>({root:r.getBoundingClientRect().width,
      toolbar:r.querySelector('.intaglio-toolbar').getBoundingClientRect().top,
      plot:r.querySelector('.intaglio-plot').getBoundingClientRect().bottom}));
    assert.equal(boxes.root,400);assert.ok(boxes.toolbar>=boxes.plot,'bottom toolbar is below plot');
    assert.equal(await page.evaluate(()=>window.exportApi.select()),'ok');
    assert.equal(await page.evaluate(()=>window.exportApi.navigate()),'ok');
    const beforeExportEvents = await page.evaluate(()=>window.exportApi.events());
    const originalPlain = await page.evaluate(()=>window.exportApi.widgetPNG('Original','Omit'));
    const currentSelected = await page.evaluate(()=>window.exportApi.widgetPNG('Current','Include'));
    assert.notEqual(originalPlain,currentSelected);
    await toolbar.locator('[aria-label="PNG viewport"]').selectOption('Current');
    await toolbar.locator('[aria-label="PNG selection"]').selectOption('Include');
    const downloadEvent = page.waitForEvent('download');
    await toolbar.locator('[data-action=download]').click();const download = await downloadEvent;
    const downloaded = await fs.readFile(await download.path());
    assert.equal(downloaded.toString('base64'),currentSelected.split(',')[1],'download uses explicit current/selection choices');
    assert.equal(await page.evaluate(()=>window.exportApi.events()),beforeExportEvents,'export and download emit no interaction events');
    const fullscreenResult = await page.evaluate(async()=>{
      const r=document.querySelector('[data-intaglio-widget=export-original]');
      Object.defineProperty(r,'requestFullscreen',{value:undefined,configurable:true});
      try{return await window.exportApi.fullscreen()}finally{delete r.requestFullscreen;}
    });assert.match(fullscreenResult,/fullscreen.*does not provide/);
    await toolbar.locator('[data-action=fullscreen]').click();
    await page.waitForFunction(()=>document.fullscreenElement || !document.querySelector('#export-controls [role=alert]').hidden);
    assert.equal(await page.evaluate(()=>!!document.fullscreenElement),true,'real reader fullscreen request succeeds');
    await page.evaluate(()=>document.exitFullscreen());
    await root.screenshot({path:path.join(out,'controls.png')});
    report.controls={subset:true,bottom:true,fixedWidth:400,keyboard:true,download:true,fullscreen:true,unavailable:fullscreenResult};
    const floating = await page.evaluate(()=>window.exportApi.variant('FloatingTop','OnFocus',0));
    const floatingRoot = page.locator(`[data-intaglio-widget="${floating}"]`);
    await page.mouse.move(0,0);
    assert.equal(await floatingRoot.evaluate(r=>r.getBoundingClientRect().width),240);
    assert.equal(await floatingRoot.locator('.intaglio-toolbar').evaluate(t=>getComputedStyle(t).opacity),'0');
    await floatingRoot.locator('button').focus();
    assert.equal(await floatingRoot.locator('.intaglio-toolbar').evaluate(t=>getComputedStyle(t).opacity),'1');
    assert.equal(await floatingRoot.locator('.intaglio-toolbar').evaluate(t=>getComputedStyle(t).position),'absolute');
    const hidden = await page.evaluate(()=>window.exportApi.variant('Bottom','Hidden',320));
    assert.equal(await page.locator(`[data-intaglio-widget="${hidden}"] .intaglio-toolbar`).isVisible(),false);
    report.controls.floatingFocusResponsive=true;report.controls.hidden=true;
    const data=[];
    for(const [view,selection,scale] of [['original',false,1],['original',true,1],['current',true,1],['current',true,2]]){
      const png=await page.evaluate(([v,s,z])=>window.exportApi.png(v,s,z),[view,selection,scale]);
      assert.ok(png.startsWith('data:image/png;base64,'),png);
      const pixels=await page.evaluate(async src=>{
        const image=new Image();image.src=src;await image.decode();
        const c=document.createElement('canvas');c.width=image.width;c.height=image.height;
        const ctx=c.getContext('2d');ctx.drawImage(image,0,0);const p=ctx.getImageData(0,0,c.width,c.height).data;
        let ink=0,orange=0;for(let i=0;i<p.length;i+=4){if(p[i+3]>0&&p[i]+p[i+1]+p[i+2]<700)ink++;
          if(p[i+3]>100&&Math.abs(p[i]-180)<8&&Math.abs(p[i+1]-83)<8&&Math.abs(p[i+2]-9)<8)orange++;}
        return {width:c.width,height:c.height,ink,orange};
      },png);
      assert.equal(pixels.width,400*scale);assert.equal(pixels.height,300*scale);assert.ok(pixels.ink>100);
      assert.ok(selection ? pixels.orange>10 : pixels.orange===0,JSON.stringify(pixels));
      await fs.writeFile(path.join(out,`${view}-${selection?'selected':'plain'}-${scale}x.png`),Buffer.from(png.split(',')[1],'base64'));
      data.push(png);report.images.push({view,selection,scale,...pixels});
      assert.equal(await page.evaluate(()=>window.liveUrls),0,'temporary URLs released');
    }
    assert.notEqual(data[0],data[1]);assert.notEqual(data[1],data[2]);
    const unavailable=await page.evaluate(async()=>{
      const original=HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext=()=>null;
      try{return await window.exportApi.png('original',false,1)}finally{HTMLCanvasElement.prototype.getContext=original;}
    });assert.match(unavailable,/ERROR: PNG export unavailable.*2D canvas/);
    const failed=await page.evaluate(async()=>{
      const original=HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL=()=>{throw Error('simulated encoder failure')};
      try{return await window.exportApi.png('original',false,1)}finally{HTMLCanvasElement.prototype.toDataURL=original;}
    });assert.match(failed,/ERROR: PNG export failed.*simulated encoder failure/);
    assert.equal(await page.evaluate(()=>window.liveUrls),0);
    await page.evaluate(()=>window.exportApi.dispose());
    assert.equal(await root.count(),0);
    assert.match(await page.evaluate(()=>window.exportApi.widgetPNG('Current','Include')),/ERROR:/);
    if(fontFile){
      const bytes = [...await fs.readFile(fontFile)];
      await page.evaluate(bytes=>{window.exportApi=intaglioExportFontFixture(bytes)},bytes);
      const measure = async()=>page.evaluate(async bytes=>{
        await document.fonts.load('21.3333px "Intaglio Export Font"');
        const text=[...document.querySelectorAll('[data-intaglio-widget=export-original] svg.intaglio-base text')].find(t=>t.textContent==='Export α');
        const style=getComputedStyle(text);
        const face=new FontFace('Independent Export Font',new Uint8Array(bytes));await face.load();document.fonts.add(face);
        const c=document.createElement('canvas').getContext('2d');c.font=`${style.fontSize} "Independent Export Font"`;
        return {actual:text.getComputedTextLength(),expected:c.measureText(text.textContent).width,family:style.fontFamily};
      },bytes);
      const before = await measure();assert.ok(Math.abs(before.actual-before.expected)<.1,JSON.stringify(before));
      await page.evaluate(()=>window.exportApi.navigate());const after=await measure();
      assert.ok(Math.abs(after.actual-after.expected)<.1,JSON.stringify(after));
      const png=await page.evaluate(()=>window.exportApi.widgetPNG('Original','Omit'));
      assert.notEqual(png,data[0],'embedded font changes exported pixels versus fallback');
      await fs.writeFile(path.join(out,'embedded-font.png'),Buffer.from(png.split(',')[1],'base64'));
      report.embeddedFont={before,after};await page.evaluate(()=>window.exportApi.dispose());
    }
    report.unavailable=unavailable;report.encoderFailure=failed;report.liveUrls=0;
  }finally{await browser.close();}
  await fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
