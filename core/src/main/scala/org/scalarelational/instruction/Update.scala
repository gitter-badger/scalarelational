package org.scalarelational.instruction

import org.scalarelational.op.Condition
import org.scalarelational.model.Table
import org.scalarelational.ColumnValue

/**
 * @author Matt Hicks <matt@outr.com>
 */
case class Update(values: List[ColumnValue[_]],
                  table: Table,
                  whereCondition: Condition = null) extends WhereSupport[Update] {
  def where(condition: Condition) = copy(whereCondition = condition)

  def result = {
    val datastore = table.datastore
    datastore.exec(this)
  }

  def async = table.datastore.async {
    result
  }

  override def toString = s"Update(values = ${values.mkString(", ")}, table = ${table.tableName}, where = $whereCondition)"
}