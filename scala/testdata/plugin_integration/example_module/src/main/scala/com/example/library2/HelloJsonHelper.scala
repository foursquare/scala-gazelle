package com.example.library2

import com.example.library1.Hello
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import java.io.StringWriter

class HelloJsonHelper extends com.example.library1.subpackage.HelloHelper with Hello {
  private val json = new ObjectMapper().registerModule(DefaultScalaModule)

  override def hello(message: String): String = {
    val writer = new StringWriter
    json.writeValue(writer, HelloJsonMessage(message))
    super.hello(writer.toString)
  }
}
