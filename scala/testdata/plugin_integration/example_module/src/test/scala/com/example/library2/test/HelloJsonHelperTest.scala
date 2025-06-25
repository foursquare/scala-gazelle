package com.example.library2.test

import com.example.library2.HelloJsonHelper
import org.scalatest.funsuite.AnyFunSuite

class HelloJsonHelperTest extends AnyFunSuite {
  test("does hello") {
    val helper = new HelloJsonHelper
    assert(helper.hello("jacob") === """hello: {"hello":"jacob"}""")
  }
}
