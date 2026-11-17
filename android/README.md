# Android core

This Gradle project currently includes only `:core`. The Android application
plugin is declared at the root for a future `:app` module. The build uses JDK 21
and emits Java 17 bytecode. `:core` uses Kotlin's standard library and JDK APIs;
JUnit is confined to tests.

```sh
./gradlew :core:test
./gradlew :core:test --offline
```

The offline command works after the wrapper and dependencies have been cached.
No Android SDK is needed to build or test `:core`.

## Integration

The application supplies `StorageRoot` objects, a live token provider, and a
`PingInfo` provider to `HttpServer`. Call `start()` and `stop()` with the service
lifecycle. Both calls are idempotent; the server can be restarted. Port zero
selects an ephemeral port, exposed by `boundPort` while running. A stopped server
reports `boundPort == -1`. Set `logger` to receive status messages without
request paths, headers, or tokens.

`FileApi` can also be used directly through `resolve`, `ls`, `stat`, `find`, and
`openFile`. `openFile` returns a `Closeable` bounded file view; always close it
with `use`, including when only reading metadata. `writeTo` streams the selected
range through a fixed-size buffer. Model `toJson()` methods expose the wire
field names to the small `Json.stringify` writer.

## Boundary choices

- The root with id `primary` is used for relative paths, with the first root as
  a fallback. Empty paths, `.` and `/` select it. Absolute aliases for configured
  roots are accepted when their canonical targets remain within a root.
- Names sort in natural, case-sensitive string order, with canonical paths as
  tie breakers. `ls` places directories before other entries.
- `find` excludes the requested root itself. Its children have depth one, so
  `max_depth=0` produces no entries. Time bounds are inclusive. Hidden entries
  and hidden directory trees are skipped unless `hidden=1`.
- `find` retains the first `limit` entries in the requested global sort order;
  later subtrees can replace earlier matches. Limits outside 1 through 5000
  and malformed extension lists produce 400.
  `truncated` means at least one matching entry was omitted. An explicit
  unreadable root or a directory that cannot be opened during traversal
  produces 403. Forbidden paths are skipped. Symbolic directory links are
  never traversed during a walk.
- Malformed, multiple, empty-file, or unsatisfiable byte ranges produce 416
  with the total size. Range ends beyond EOF and suffixes longer than the file
  are clamped to the file size. HEAD uses the same metadata and range handling
  as GET and writes no response body, including on errors.

The transport closes each connection after one request. Eight workers and a
bounded queue handle clients, with a five-second socket read timeout and an
8 KiB limit for each request line and the aggregate header block. Query values
use strict UTF-8 percent decoding, and `+` represents a space.
