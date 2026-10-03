#!/usr/bin/env node
// Package a trusted, self-contained Scala.js NoModule application for offline use.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const escape = value => value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function standaloneHtml({ script, containers, title = 'Intaglio interactive plot', css = '' }) {
  if (typeof script !== 'string' || !script.trim()) throw new Error('Provide a nonempty Scala.js NoModule bundle.');
  if (typeof title !== 'string' || typeof css !== 'string') throw new Error('Title and CSS must be strings.');
  if (!Array.isArray(containers) || !containers.length || containers.some(id => typeof id !== 'string' || !/^[A-Za-z][A-Za-z0-9_-]*$/.test(id)))
    throw new Error('Provide mount container IDs using letters, digits, hyphens or underscores, starting with a letter.');
  if (new Set(containers).size !== containers.length || containers.some(id => id.startsWith('intaglio-package-')))
    throw new Error('Container IDs must be unique and may not use the reserved intaglio-package- prefix.');
  const payload = Buffer.from(script, 'utf8').toString('base64');
  const stylesheet = Buffer.from(css, 'utf8').toString('base64');
  const sha256 = crypto.createHash('sha256').update(script).digest('hex');
  // Encoding preserves Unicode and literal HTML closing tags in JS/CSS without rewriting code.
  // No eval is used: the decoded NoModule bundle executes as an ordinary inline script.
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'">
<title>${escape(title)}</title>
<meta name="intaglio-runtime-sha256" content="${sha256}">
<style>body{margin:24px;font:14px system-ui,sans-serif;background:white;color:#18212f}main{display:flex;gap:24px;flex-wrap:wrap;align-items:flex-start}.intaglio-package-mount{width:480px;max-width:100%;min-width:0}#intaglio-package-error{white-space:pre-wrap;color:#9b1c1c}</style>
</head><body><h1>${escape(title)}</h1><p id="intaglio-package-error" role="alert" hidden></p>
<noscript>This interactive plot requires JavaScript. Enable it or request a static SVG export.</noscript>
<main>${containers.map(id => `<div class="intaglio-package-mount" id="${id}"></div>`).join('')}</main>
<script>
(function(){
  function report(message){var e=document.getElementById('intaglio-package-error');e.hidden=false;e.textContent='Interactive plot could not complete: '+message;}
  window.addEventListener('error',function(e){report(e.message || 'runtime error');});
  window.addEventListener('unhandledrejection',function(e){report(String(e.reason));});
  document.addEventListener('securitypolicyviolation',function(e){report('An unbundled resource was blocked ('+e.violatedDirective+'). Embed assets in the bundle or use data URLs.');});
  function decode(s){return new TextDecoder().decode(Uint8Array.from(atob(s),function(c){return c.charCodeAt(0);}));}
  try {
    var style=document.createElement('style');style.textContent=decode('${stylesheet}');document.head.appendChild(style);
    var script=document.createElement('script');script.textContent=decode('${payload}');document.body.appendChild(script);
  } catch(e){report(String(e));}
})();
</script></body></html>\n`;
}

function main(args) {
  const [input, output, ...rest] = args;
  if (!input || !output) throw new Error('Usage: node tools/package-widget.cjs <NoModule main.js> <output.html> --containers left,right [--title title] [--css stylesheet.css]');
  const options = {};
  for (let i = 0; i < rest.length; i += 2) {
    const key = rest[i];
    if (!['--containers','--title','--css'].includes(key) || rest[i + 1] === undefined || options[key] !== undefined)
      throw new Error('Unknown, repeated or incomplete option: ' + key);
    options[key] = rest[i + 1];
  }
  if (!options['--containers']) throw new Error('--containers must name the IDs the application mounts into.');
  if (path.resolve(input) === path.resolve(output) || (options['--css'] && path.resolve(options['--css']) === path.resolve(output)))
    throw new Error('Output must not overwrite an input asset.');
  const html = standaloneHtml({script:fs.readFileSync(input,'utf8'),containers:options['--containers'].split(','),
    title:options['--title'],css:options['--css'] ? fs.readFileSync(options['--css'],'utf8') : ''});
  fs.writeFileSync(output, html);
  console.log(JSON.stringify({output:path.resolve(output),bytes:Buffer.byteLength(html),sha256:crypto.createHash('sha256').update(html).digest('hex')}));
}
module.exports = { standaloneHtml };
if (require.main === module) { try { main(process.argv.slice(2)); } catch(e) { console.error(e.message); process.exitCode = 1; } }
