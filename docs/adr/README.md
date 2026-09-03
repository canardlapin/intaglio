# Architecture decision records

These records state why Intaglio's load-bearing structures are shaped the way they are. They are
descriptive of decisions already made and implemented; the policy documents beside them
(`docs/compatibility.md`, `docs/numerical-standards.md`, `docs/accessibility.md`) state what the
rules currently are. When the two disagree, the policy document is authoritative for behavior and
the ADR is authoritative for the reasoning.

An accepted ADR is not revised in place. A decision that changes gets a new record that supersedes
the old one, and the superseded record keeps its number.

| # | Title | Status |
| --- | --- | --- |
| [0001](0001-reference-identity-for-extension-keys.md) | Reference identity for extension keys | Accepted |
| [0002](0002-one-typed-error-channel.md) | One typed error channel | Accepted |
| [0003](0003-separate-scale-training-from-encoding.md) | Separate scale training from encoding | Accepted |
| [0004](0004-resolve-lengths-once-against-one-target.md) | Resolve lengths once, against one target | Accepted |
| [0005](0005-three-compatibility-courts.md) | Three compatibility courts | Accepted |
| [0006](0006-columnar-marks-stay-one-grob.md) | Columnar marks stay one grob | Accepted |
| [0007](0007-provenance-is-a-compiler-policy.md) | Provenance is a compiler policy | Accepted |
