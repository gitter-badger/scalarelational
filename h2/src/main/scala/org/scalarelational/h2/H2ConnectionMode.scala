package org.scalarelational.h2

import java.io.File

import org.powerscala.Unique

/**
 * @author Matt Hicks <matt@outr.com>
 */
trait H2ConnectionMode {
  def url: String
}

object H2ConnectionMode {
  def apply(connectionURL: String) = new H2ConnectionMode {
    def url = connectionURL
  }
}

case class H2Embedded(file: File) extends H2ConnectionMode {
  lazy val url = s"jdbc:h2:file:${file.getAbsolutePath}"
}

case class H2Memory(name: String = Unique(), closeDelay: Long = 0L) extends H2ConnectionMode {
  lazy val url = s"jdbc:h2:mem:$name${if (closeDelay != 0L) s";DB_CLOSE_DELAY=$closeDelay"}"
}