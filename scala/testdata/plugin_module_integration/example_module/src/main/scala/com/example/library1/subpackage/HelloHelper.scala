package com.example.library1.subpackage

import com.example.library1.Hello

class HelloHelper extends Hello {
  override def hello(message: String): String = s"hello: $message"
}
