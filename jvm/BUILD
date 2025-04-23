load("@rules_go//go:def.bzl", "go_library")

go_library(
    name = "jvm",
    srcs = [
        "config.go",
        "constants.go",
        "resolve.go",
    ],
    importpath = "github.com/foursquare/scala-gazelle/jvm",
    visibility = ["//visibility:public"],
    deps = [
        "@com_github_emirpasic_gods//sets/treeset",
        "@gazelle//config",
        "@gazelle//label",
        "@gazelle//resolve",
        "@gazelle//rule",
    ],
)
