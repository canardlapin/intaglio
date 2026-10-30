# Intaglio documentation site

The [Starlight](https://starlight.astro.build) site published at
<https://canardlapin.github.io/intaglio/>.

The pages are not written here. They are the mdoc output of [`docs/`](../docs), so every example on
the site has been compiled against the current modules. `scripts/sync-docs.mjs` copies that output
into `src/content/docs/`, moves each page's H1 into frontmatter, rewrites links to site routes (and
links outside `docs/` to GitHub), and copies the images the pages use. Only the landing page,
`src/content/docs/index.mdx`, lives in this directory.

| To change | Edit |
| --- | --- |
| A guide, tutorial, or gallery page | its source in `docs/` |
| The sidebar, page order, or URLs | `docs-manifest.mjs` |
| The landing page | `src/content/docs/index.mdx` |
| Colours and type | `src/styles/theme.css` |

A code block marked `<!-- site:snippet NAME -->` in `docs/` is exported to the landing page as
`snippets[NAME]`, so the landing example is compiled code too.

## Build

Requires Node 22.23.4 or later.

```sh
sbt docsCheck            # in the repository root: compile docs/ with mdoc
cd site
npm ci
npm run build            # refuses mdoc output older than its docs/ source
npm run preview
```

`npm run dev` syncs with `--allow-stale`, so you can edit the landing page or the theme without
recompiling. It warns about stale pages but does not stop.

`.github/workflows/docs-site.yml` runs the same steps and deploys `dist/` to GitHub Pages from
`main`.
