#!/usr/bin/env node
// Copy the compiled documentation into the site.
//
// The source is mdoc's output (modules/docs/target/docs), not docs/ itself: mdoc has
// already compiled every example, appended evaluated results and compile errors, and
// replaced each gallery passthrough block with the image it rendered. This script
// only re-homes those pages: it moves the H1 into frontmatter, rewrites links to the
// site's routes, and copies the images the pages reference.
//
//   node scripts/sync-docs.mjs                # requires a current `sbt docsCheck`
//   node scripts/sync-docs.mjs --allow-stale  # draft against older mdoc output

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';
import { allPages, branch, repo } from '../docs-manifest.mjs';

const siteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(siteRoot, '..');
const docsSource = path.join(repoRoot, 'docs');
const compiled = path.join(repoRoot, 'modules/docs/target/docs');
const contentOut = path.join(siteRoot, 'src/content/docs');
const imagesOut = path.join(siteRoot, 'public/docs-images');
const generatedOut = path.join(siteRoot, 'src/generated');
const base = (process.env.SITE_BASE ?? '/intaglio').replace(/\/$/, '');
const allowStale = process.argv.includes('--allow-stale');

const fail = (message) => {
	console.error(`sync-docs: ${message}`);
	process.exit(1);
};

if (!fs.existsSync(compiled)) {
	fail(`${path.relative(repoRoot, compiled)} does not exist; run \`sbt docsCheck\` in the repository root first`);
}

const pages = allPages();
const bySource = new Map(pages.map((page) => [page.source, page]));

// A page whose docs/ source is newer than its mdoc output was edited after the last
// compile, so the site would publish examples that were never checked.
const stale = pages.filter((page) => {
	const src = path.join(docsSource, page.source);
	const out = path.join(compiled, page.source);
	if (!fs.existsSync(src)) fail(`docs/${page.source} is in the page map but does not exist`);
	if (!fs.existsSync(out)) return true;
	return fs.statSync(src).mtimeMs > fs.statSync(out).mtimeMs;
});
if (stale.length > 0) {
	const list = stale.map((page) => `  docs/${page.source}`).join('\n');
	const message = `mdoc output is older than these sources; run \`sbt docsCheck\`:\n${list}`;
	if (allowStale) console.warn(`sync-docs: warning: ${message}`);
	else fail(message);
}

/** GitHub URL for a repository path outside the published page map. */
function githubUrl(repoPath) {
	const full = path.join(repoRoot, repoPath);
	const kind = fs.existsSync(full) && fs.statSync(full).isDirectory() ? 'tree' : 'blob';
	return `${repo}/${kind}/${branch}/${repoPath}`;
}

/** Rewrite one Markdown link target found in docs/<source>. */
function rewriteTarget(target, source) {
	if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('#')) return target;
	const [rawPath, anchor] = target.split('#', 2);
	const hash = anchor === undefined ? '' : `#${anchor}`;
	const fromDocs = path.posix.normalize(path.posix.join(path.posix.dirname(source), rawPath));

	if (/\.(svg|png|jpe?g|webp)$/i.test(fromDocs) && fs.existsSync(path.join(docsSource, fromDocs))) {
		if (fromDocs.startsWith('..')) fail(`docs/${source} uses an image outside docs/: ${target}`);
		images.add(fromDocs);
		return `${base}/docs-images/${fromDocs}`;
	}
	const page = bySource.get(fromDocs);
	if (page) return `${base}/${page.slug}/${hash}`;

	const repoPath = path.posix.normalize(path.posix.join('docs', fromDocs));
	if (repoPath.startsWith('..')) fail(`docs/${source} links outside the repository: ${target}`);
	return githubUrl(repoPath) + hash;
}

/** Rewrite inline links and images outside fenced code. Link text may span lines. */
function rewriteLinks(body, source) {
	// Alternation: an inline code span is matched first and returned unchanged, so a
	// `[a](b)` written as code stays literal while [`Code`](b.md) is still a link.
	// A code span closes only on a backtick run of the same length, and spans never cross
	// a blank line, so one stray backtick cannot hide the links in later paragraphs.
	const token = /(?<!`)(`+)(?!`)[\s\S]*?(?<!`)\1(?!`)|(!?\[(?:[^\]`]|`[^`]*`)*\])\(([^)\s]+)\)/g;
	const rewriteParagraph = (paragraph) =>
		paragraph.replace(token, (match, _code, text, target) =>
			text === undefined ? match : `${text}(${rewriteTarget(target, source)})`,
		);
	const rewriteProse = (prose) =>
		prose
			.split(/(\n[ \t]*\n)/)
			.map((part, i) => (i % 2 === 1 ? part : rewriteParagraph(part)))
			.join('');

	const out = [];
	let prose = [];
	let fence = null;
	const flush = () => {
		if (prose.length > 0) out.push(rewriteProse(prose.join('\n')));
		prose = [];
	};
	for (const line of body.split('\n')) {
		const open = line.match(/^ {0,3}(`{3,}|~{3,})/);
		if (fence === null && open) {
			flush();
			fence = open[1];
			out.push(line);
		} else if (fence !== null) {
			// A fence closes on a bare run of the same character, at least as long as the opener.
			const close = line.match(/^ {0,3}(`{3,}|~{3,})\s*$/);
			if (close && close[1][0] === fence[0] && close[1].length >= fence.length) fence = null;
			out.push(line);
		} else {
			prose.push(line);
		}
	}
	flush();
	return out.join('\n');
}

/** First prose paragraph, flattened to plain text, for the page description. */
function describe(body) {
	const paragraph = body
		.split(/\n\s*\n/)
		.map((block) => block.trim())
		.find((block) => block && !/^(#|```|~~~|\||<|>|-\s|\*\s|\d+\.\s|!\[|Status:)/.test(block));
	if (!paragraph) return undefined;
	const text = paragraph
		.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
		.replace(/`|\*\*?(?=\S)|(?<=\S)\*\*?/g, '')
		.replace(/\s+/g, ' ')
		.trim();
	if (text.length <= 200) return text;
	const cut = text.slice(0, 200);
	return `${cut.slice(0, cut.lastIndexOf(' '))}…`;
}

const yaml = (value) => JSON.stringify(value);

/** docs/-relative paths of every image a published page references. */
const images = new Set();

// Remove the previous sync so a page dropped from the map does not linger.
for (const entry of fs.readdirSync(contentOut)) {
	if (entry === 'index.mdx') continue;
	fs.rmSync(path.join(contentOut, entry), { recursive: true, force: true });
}

/** Code blocks marked `<!-- site:snippet NAME -->` in docs/, joined by name, for the landing page. */
const snippets = {};
/** Each gallery plate with the heading it sits under, for the landing page's wall of plates. */
const plates = [];

pages.forEach((page, order) => {
	const text = fs.readFileSync(path.join(compiled, page.source), 'utf8');
	for (const match of text.matchAll(/<!-- site:snippet ([\w-]+) -->\s*\n```\w*\n([\s\S]*?)\n```/g)) {
		snippets[match[1]] = snippets[match[1]] ? `${snippets[match[1]]}\n\n${match[2]}` : match[2];
	}
	if (page.source === 'gallery.md') {
		const slugger = new GithubSlugger();
		let heading = null;
		for (const line of text.split('\n')) {
			const h = line.match(/^#{2,3} (.+)$/);
			if (h) heading = { text: h[1].trim(), anchor: slugger.slug(h[1].trim()) };
			const image = line.match(/^!\[[^\]]*\]\(gallery\/([\w-]+)\.svg\)/);
			if (image && heading) plates.push({ plate: image[1], title: heading.text, href: `${base}/${page.slug}/#${heading.anchor}`, src: `${base}/docs-images/gallery/${image[1]}.svg` });
		}
	}
	const h1 = text.match(/^# (.+)$/m);
	if (!h1) fail(`docs/${page.source} has no H1 title`);
	const body = text.replace(h1[0], '').replace(/^\s+/, '');
	const description = describe(body);

	const frontmatter = [
		'---',
		`title: ${yaml(h1[1].trim())}`,
		description ? `description: ${yaml(description)}` : null,
		`editUrl: ${yaml(`${repo}/edit/${branch}/docs/${page.source}`)}`,
		'sidebar:',
		`  order: ${order}`,
		page.label ? `  label: ${yaml(page.label)}` : null,
		'---',
		'',
		// Markdown, not MDX: compiled Scala output is full of braces and angle brackets.
		`<!-- Generated by site/scripts/sync-docs.mjs from docs/${page.source}. Edit the source, not this file. -->`,
		'',
		'',
	].filter((line) => line !== null);
	const rewritten = rewriteLinks(body, page.source);
	// Anything still pointing at a relative .md file escaped the rewriter: a reference-style
	// link, a titled target, or raw HTML. Fail rather than publish a dead link.
	const leftover = rewritten.match(/\]\((?!https?:|\/|#)[^)]*\.md(?:#[^)]*)?\)|href="(?!https?:|\/|#)[^"]*\.md/);
	if (leftover) fail(`docs/${page.source} has a link the rewriter did not handle: ${leftover[0]}`);
	const out = path.join(contentOut, `${page.slug}.md`);
	fs.mkdirSync(path.dirname(out), { recursive: true });
	fs.writeFileSync(out, frontmatter.join('\n') + rewritten);
});

fs.rmSync(imagesOut, { recursive: true, force: true });
for (const image of images) {
	const out = path.join(imagesOut, image);
	fs.mkdirSync(path.dirname(out), { recursive: true });
	fs.copyFileSync(path.join(docsSource, image), out);
}

// The hero image goes through Astro's asset pipeline, which only reads from src/.
const heroPlate = 'composed-figure';
fs.mkdirSync(path.join(siteRoot, 'src/assets/generated'), { recursive: true });
fs.copyFileSync(path.join(docsSource, 'gallery', `${heroPlate}.svg`), path.join(siteRoot, 'src/assets/generated/hero-plate.svg'));

if (!snippets['first-plot']) fail('no `<!-- site:snippet first-plot -->` blocks found; the landing page needs them');
fs.mkdirSync(generatedOut, { recursive: true });
fs.writeFileSync(path.join(generatedOut, 'snippets.json'), `${JSON.stringify(snippets, null, 2)}\n`);
fs.writeFileSync(path.join(generatedOut, 'plates.json'), `${JSON.stringify(plates, null, 2)}\n`);

console.log(`sync-docs: ${pages.length} pages and ${images.size} images from ${path.relative(repoRoot, compiled)}`);
