// The site's page map. Every page here comes from the mdoc output of docs/, so the
// site publishes exactly what tools/check-docs.sh compiled. Pages that docs/ holds
// but this map leaves out (phase plans, visual-QA courts, the docs/ index) stay on
// GitHub, and links to them are rewritten to their GitHub URLs.

export const repo = 'https://github.com/canardlapin/intaglio';
export const branch = 'main';

/** @typedef {{ source: string, slug: string, label?: string }} Page */
/** @typedef {{ label: string, pages?: Page[], groups?: Group[], collapsed?: boolean, single?: boolean }} Group */

/** @type {Group[]} */
export const groups = [
	{
		label: 'Start here',
		pages: [{ source: 'how-it-works.md', slug: 'start/how-it-works', label: 'How Intaglio works' }],
	},
	{
		label: 'Tutorial',
		pages: [
			{ source: 'tutorial/01-first-plot.md', slug: 'tutorial/first-plot', label: '1. Data in, SVG out' },
			{ source: 'tutorial/02-scales-and-guides.md', slug: 'tutorial/scales-and-guides', label: '2. Scales and guides' },
			{ source: 'tutorial/03-statistics.md', slug: 'tutorial/statistics', label: '3. Statistics' },
			{ source: 'tutorial/04-facets-and-coords.md', slug: 'tutorial/facets-and-coords', label: '4. Facets and coordinates' },
			{ source: 'tutorial/05-themes-and-styling.md', slug: 'tutorial/themes-and-styling', label: '5. Themes and styling' },
			{ source: 'tutorial/06-composition.md', slug: 'tutorial/composition', label: '6. Several plots in one figure' },
			{ source: 'tutorial/07-saving-output.md', slug: 'tutorial/saving-output', label: '7. Saving output' },
		],
	},
	{
		label: 'Gallery',
		single: true,
		pages: [{ source: 'gallery.md', slug: 'gallery', label: 'All plates' }],
	},
	{
		label: 'Guides',
		groups: [
			{
				label: 'Layers and encodings',
				pages: [
					{ source: 'common-statistics.md', slug: 'guides/common-statistics' },
					{ source: 'style-aesthetics.md', slug: 'guides/style-aesthetics' },
					{ source: 'scalar-mappings.md', slug: 'guides/scalar-mappings' },
					{ source: 'date-time-and-zoom.md', slug: 'guides/date-time-and-zoom' },
					{ source: 'scientific-heatmaps.md', slug: 'guides/scientific-heatmaps' },
					{ source: 'stroke-casing.md', slug: 'guides/stroke-casing' },
					{ source: 'text-plates.md', slug: 'guides/text-plates' },
					{ source: 'composition.md', slug: 'guides/composition' },
					{ source: 'viewport-mapping.md', slug: 'guides/viewport-mapping' },
					{ source: 'accessibility.md', slug: 'guides/accessibility' },
				],
			},
			{
				label: 'Output',
				pages: [
					{ source: 'backends.md', slug: 'guides/backends' },
					{ source: 'notebooks.md', slug: 'guides/notebooks' },
					{ source: 'svg-fonts.md', slug: 'guides/svg-fonts' },
				],
			},
			{
				label: 'Interaction',
				pages: [
					{ source: 'browser-widget.md', slug: 'guides/browser-widget' },
					{ source: 'canvas-widget.md', slug: 'guides/canvas-widget' },
					{ source: 'standalone-browser.md', slug: 'guides/standalone-browser' },
					{ source: 'selection-history.md', slug: 'guides/selection-history' },
					{ source: 'interaction-coverage.md', slug: 'guides/interaction-coverage' },
					{ source: 'picking.md', slug: 'guides/picking' },
					{ source: 'javafx-interaction.md', slug: 'guides/javafx-interaction' },
					{ source: 'browser-capabilities.md', slug: 'guides/browser-capabilities' },
				],
			},
			{
				label: 'Performance',
				pages: [
					{ source: 'limits.md', slug: 'guides/limits' },
					{ source: 'performance.md', slug: 'guides/browser-performance' },
				],
			},
		],
	},
	{
		label: 'Extending',
		pages: [
			{ source: 'extending/scales.md', slug: 'extending/scales', label: 'Scales, transforms, palettes' },
			{ source: 'extending/stats.md', slug: 'extending/stats', label: 'Statistics' },
			{ source: 'extending/geoms.md', slug: 'extending/geoms', label: 'Geoms' },
			{ source: 'extending/coords.md', slug: 'extending/coords', label: 'Coordinate systems' },
			{ source: 'extending/recipes.md', slug: 'extending/recipes', label: 'Plot recipes' },
			{ source: 'extending/backends.md', slug: 'extending/backends', label: 'Backends' },
			{ source: 'extending/unsafe.md', slug: 'extending/unsafe', label: 'Throwing entry points' },
		],
	},
	{
		label: 'Design decisions',
		collapsed: true,
		pages: [
			{ source: 'adr/README.md', slug: 'decisions', label: 'About the records' },
			{ source: 'adr/0001-reference-identity-for-extension-keys.md', slug: 'decisions/0001-reference-identity' },
			{ source: 'adr/0002-one-typed-error-channel.md', slug: 'decisions/0002-typed-error-channel' },
			{ source: 'adr/0003-separate-scale-training-from-encoding.md', slug: 'decisions/0003-scale-training' },
			{ source: 'adr/0004-resolve-lengths-once-against-one-target.md', slug: 'decisions/0004-one-target' },
			{ source: 'adr/0005-three-compatibility-courts.md', slug: 'decisions/0005-compatibility-courts' },
			{ source: 'adr/0006-columnar-marks-stay-one-grob.md', slug: 'decisions/0006-columnar-marks' },
			{ source: 'adr/0007-provenance-is-a-compiler-policy.md', slug: 'decisions/0007-provenance' },
			{ source: 'adr/0008-signed-color-is-a-primitive.md', slug: 'decisions/0008-signed-colour' },
			{ source: 'adr/0009-svg-fonts-are-caller-supplied.md', slug: 'decisions/0009-svg-fonts' },
			{ source: 'design/interaction.md', slug: 'decisions/interaction-proposal', label: 'Interaction proposal' },
		],
	},
	{
		label: 'Contracts',
		collapsed: true,
		pages: [
			{ source: 'compatibility.md', slug: 'contracts/compatibility' },
			{ source: 'compatibility/javafx-runtimes.md', slug: 'contracts/javafx-runtimes' },
			{ source: 'numerical-standards.md', slug: 'contracts/numerical-standards' },
			{ source: 'visual-regression.md', slug: 'contracts/visual-regression' },
			{ source: 'releasing.md', slug: 'contracts/releasing' },
		],
	},
];

/** Every page in sidebar order. */
export function allPages() {
	/** @param {Group} g @returns {Page[]} */
	const walk = (g) => [...(g.pages ?? []), ...(g.groups ?? []).flatMap(walk)];
	return groups.flatMap(walk);
}

/** The Starlight sidebar for this map. */
export function sidebar() {
	/** @param {Group} g @returns {any} */
	const toItem = (g) =>
		g.single && g.pages?.length === 1 ? { slug: g.pages[0].slug, label: g.label } : ({
		label: g.label,
		collapsed: g.collapsed ?? false,
		items: [
			...(g.pages ?? []).map((p) => (p.label ? { slug: p.slug, label: p.label } : p.slug)),
			...(g.groups ?? []).map(toItem),
		],
	});
	return groups.map(toItem);
}
