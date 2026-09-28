# Frontend Acceptance Corpus

`frontend-v1.tsv` is the versioned, finite acceptance manifest for the frontend
migration. Each non-comment row has five tab-separated fields:

1. stable case ID;
2. `normative`, `boundary`, or `overlap` category;
3. living specification path;
4. exact section marker present in that specification;
5. exact JVM test method that supplies executable evidence.

The categories have distinct purposes. `normative` records accepted language
feature/example families. `boundary` records malformed, unsupported, resource-limit,
cycle, scope, and collision behavior that must fail deterministically. `overlap`
records only the finite inputs for which legacy-versus-AST behavior is compared;
AST-only syntax is not forced through the legacy frontend.

`frontend-v1-contract.tsv` is the companion result contract. It contains the same
68 IDs and declares the required evidence kind: `materialize` for accepted
normative behavior, `mapped-diagnostic` for deterministic boundary rejection, and
`differential` for legacy/Tree-sitter overlap. The contract deliberately describes
the evidence class rather than duplicating generated-C snapshots; the referenced
tests remain responsible for exact output, source-map, diagnostic, and runtime
assertions.

`frontendCorpusManifestIsCompleteAndReferencesExecutableEvidence` validates both
files, schema, unique IDs, category prefixes, specification markers, evidence method
names, and the category/result relationship. Because every evidence method runs in
the same JVM suite, a green manifest gate plus that suite is the corpus report. Add a
row whenever a new normative feature family or failure invariant is introduced; do
not silently broaden an existing row.
