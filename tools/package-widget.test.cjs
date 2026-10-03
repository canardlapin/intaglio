const { test } = require('node:test');
const assert = require('node:assert/strict');
const { standaloneHtml } = require('./package-widget.cjs');
test('packages exact UTF-8 source without script or style termination injection', () => {
  const script = 'window.label = "α </script><script>bad()</script>";';
  const css = 'body::after {content:"</style> λ"}';
  const html = standaloneHtml({script,css,containers:['one','two'],title:'<chart> & "two"'});
  assert.ok(html.includes(Buffer.from(script).toString('base64')));
  assert.ok(html.includes(Buffer.from(css).toString('base64')));
  assert.equal((html.match(/<script>/g)||[]).length,1);
  assert.equal((html.match(/<\/script>/g)||[]).length,1);
  assert.ok(html.includes('&lt;chart&gt; &amp; &quot;two&quot;'));
  assert.ok(html.includes("connect-src 'none'"));
});
test('rejects ambiguous or unsafe mount contracts and empty runtime', () => {
  for (const containers of [[],['a','a'],['a"'],['intaglio-package-error']])
    assert.throws(() => standaloneHtml({script:'ok()',containers}));
  assert.throws(() => standaloneHtml({script:' ',containers:['one']}));
});
