# Parser & Gazelle Plugin Test Data

The sub-folders here contain Scala source files, some taken from various open source projects (denoted by an inline
comment at the top of the file), used for testing the Scala Gazelle plugin and its code parser. Generally they should
be loosely organized by test case, with the understanding that some may be shared across tests.

## Parser Tests

The current parser tests are written to compare serialized json rather than the `ParseResult` structs directly to 1.
get more user-friendly diff output for free, and 2. not have to hardcode large symbol lists in the test files. The
expected json output from the parser lives alongside the Scala files, and can be generated from scratch with something
like

```
bazel run //scala:parser -- -file_path "$(pwd)/scala/testdata/parser_integration/spark/SparkSession.scala"
```

and then edited from there as needed (you will need to fix the `"source"` path at the very least).

## Gazelle Plugin Tests

The plugin tests make use of [gazelle_generation_test](https://github.com/bazel-contrib/bazel-gazelle/blob/v0.43.0/extend.md#gazelle_generation_test)
to run a full invocation of Gazelle over a test workspace and compare generated build file data with its expected
result.

Adding a new test essentially boils down to setting up a new test workspace directory, mocking out a
`maven_install.json` file if needed, and setting up the input (`BUILD.in`) build files and expect output (`BUILD.out`)
build files. The test will also print out a command to update `BUILD.out` files if the test fails.

Tests can also be set up to validate stdout, stderr, and exit code. See the[gazelle_generation_test](
https://github.com/bazel-contrib/bazel-gazelle/blob/v0.43.0/extend.md#gazelle_generation_test) docs for details.
