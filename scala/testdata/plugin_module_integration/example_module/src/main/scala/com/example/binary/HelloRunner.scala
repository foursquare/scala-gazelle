package com.example.binary

import com.example.library1.Hello
import com.example.library2.HelloJsonHelper

object HelloRunner {
  private val hello: Hello = new HelloJsonHelper

  def main(args: Array[String]): Unit = {
    println(hello.hello(args(0)))
  }
}
