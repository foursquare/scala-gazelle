# Parser & Gazelle Plugin Test Data

The sub-folders here contain Scala source files, some taken from various open source
projects (denoted by an inline comment at the top of the file), used for testing the
Scala Gazelle plugin and its code parser. Generally they should be loosely organized by
test case, with the understanding that some may be shared across tests.

## Parser Tests

The current parser tests are written to compare serialized json rather than the
`ParseResult` structs directly to 1. get more user-friendly diff output for free, and 2.
not have to hardcode large symbol lists in the test files. The expected json output from
the parser lives alongside the Scala files, and can be generated from scratch with
something like

```
bazel run //scala:parser -- -file_path "$(pwd)/scala/testdata/parser_integration/spark/SparkSession.scala"
```

and then edited from there as needed (you will need to fix the `"source"` path at the
very least).

## Gazelle Plugin Tests

TODO
