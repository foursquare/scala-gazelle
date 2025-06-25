package com.example.library1.subpackage

import org.scalatest.funsuite.AnyFunSuite

class HelloHelperTest extends AnyFunSuite {
  test("does hello") {
    val helper = new HelloHelper
    assert(helper.hello("jacob") === "hello: jacob")
  }
}
