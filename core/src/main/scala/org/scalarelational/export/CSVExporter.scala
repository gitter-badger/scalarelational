package org.scalarelational.export

import java.io.{File, FileWriter}
import org.scalarelational.dsl.select
import org.scalarelational.model.Table

/**
 * @author Matt Hicks <matt@outr.com>
 */
object CSVExporter {
  private val NewLine = "\r\n"

  def exportTables(directory: File, tables: Table*) = {
    directory.mkdirs()
    tables.foreach {
      case table => exportTable(new File(directory, s"${table.tableName.toLowerCase}.csv"), table)
    }
  }

  def exportTable(file: File, table: Table) = {
    val writer = new FileWriter(file)
    try {
      val columnNames = table.columns.map(c => c.name).mkString(",")
      writer.write(columnNames)
      writer.write(NewLine)

      val query = select(table.*) from table orderBy table.primaryKeys.head.asc
      val results = table.datastore.exec(query)
      results.foreach {
        case r => {
          val values = r.values.map {
            case ev => ev.value match {
              case null => "null"
              case v => v.toString
            }
          }
          writer.write(values.mkString(","))
          writer.write(NewLine)
        }
      }
    } finally {
      writer.flush()
      writer.close()
    }
  }
}
