# PDF tracked-text extraction boundary

`PdfTrackingFixture` writes the actual mixed-bidi PDF through `PdfRenderer`.
Run `python3 tools/check-pdf-tracking.py <fixture.pdf>` from the repository root
to inspect it with `pdftotext`
resolved on `PATH`; the script fails explicitly if the tool is absent. Poppler
25.12.0 emitted `A Δ RLE א PDF é` (U+202B/U+202C wrap the Hebrew run), which
is the expected directional-formatting projection of logical `A Δאé`, not a
changed character sequence. The script accepts only that exact code-point
sequence after PDF page terminators; it does not strip arbitrary controls.
The `/ActualText` UTF-16 bytes are asserted directly in the ordinary suite.

PDFBox `3.0.8` has the known `PDFBOX-6281` bidi `/ActualText` reordering defect:
its `PDFTextStripper` does not return that same logical string despite the
standards-correct marked-content span. This is retained as a documented reader
limitation rather than an ordinary-test negative assertion: a future PDFBox fix
should make tests stronger, not fail them. The suite continues to require exact
`PDFTextStripper` extraction for Latin/Greek/combining text, where that reader
is within the supported claim.
