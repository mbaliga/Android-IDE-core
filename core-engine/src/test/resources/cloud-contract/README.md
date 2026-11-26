# cloud-contract fixtures

Every `.sse` file here is **hand-authored from each provider's publicly documented
streaming wire format**. None was recorded from a live call. They prove the engines
parse the *documented* shapes; they do not prove the providers still send those shapes.
Only the opt-in live suite (`LiveContractTest`, see `docs/testing/live-contract.md`)
can do that, and it has never been run by the author of these files (no key available).

Each file starts with an SSE comment line (`: ...`) saying so; SSE parsers ignore it.
