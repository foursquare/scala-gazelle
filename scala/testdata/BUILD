load("@bazel_gazelle//:def.bzl", "gazelle_binary", "gazelle_generation_test")

# Prevent Gazelle from operating over this directory or sub-directories.
# gazelle:exclude *

gazelle_binary(
    name = "gazelle",
    languages = [
        "//scala",
    ],
    visibility = ["//visibility:private"],
)

# Test structure largely inspired by https://github.com/bazel-contrib/rules_jvm/blob/v0.29.0/java/gazelle/testdata/BUILD.bazel
#
# Each plugin test is set up as follows:
#
# * A WORKSPACE file.
# * Optionally an `expectedStdErr.txt`, an `expectedStdOut.txt`, and an `expectedExitCode.txt`.
#   If a test fails, it will offer you a command line to run to generate them.
# * Some input files.
# * A BUILD.out file for each expected generated BUILD file.
# * A paired BUILD.in file (generally empty, unless the test requires pre-existing BUILD
#   files, such as for directive comments).
#
# Any maven_install.json files were created by hand and only contain the limited data
# necessary for the plugin to function, though
# https://github.com/bazel-contrib/rules_jvm/blob/v0.29.0/java/gazelle/testdata/BUILD.bazel
# contains an example command for generating them.
#
# TODO(jacob): Switch to using actual maven install files instead of mocking them.

[
    gazelle_generation_test(
        name = file[0:-len("/WORKSPACE")],
        size = "small",
        gazelle_binary = ":gazelle",
        gazelle_timeout_seconds = 30,
        test_data = glob(
            include = [file[0:-len("/WORKSPACE")] + "/**"],
        ),
    )
    for file in glob(["**/WORKSPACE"])
]
