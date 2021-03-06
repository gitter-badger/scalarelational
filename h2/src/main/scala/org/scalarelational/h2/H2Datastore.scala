package org.scalarelational.h2

import java.io.File

import org.h2.jdbcx.JdbcConnectionPool
import org.powerscala.event.processor.UnitProcessor
import org.powerscala.log.Logging
import org.powerscala.property.Property
import org.scalarelational._
import org.scalarelational.column.property._
import org.scalarelational.fun.SimpleFunction
import org.scalarelational.op._
import org.scalarelational.datatype.DataType
import org.scalarelational.h2.trigger.{TriggerEvent, TriggerType}
import org.scalarelational.instruction._
import org.scalarelational.model._
import org.scalarelational.model.table.property.Index
import org.scalarelational.result.QueryResultsIterator

import scala.collection.mutable.ListBuffer

/**
 * @author Matt Hicks <matt@outr.com>
 */
abstract class H2Datastore protected(mode: H2ConnectionMode = H2Memory(),
                                     val dbUser: String = "sa",
                                     val dbPassword: String = "sa") extends Datastore with Logging {
  Class.forName("org.h2.Driver")

  val modeProperty = Property[H2ConnectionMode](default = Some(mode))
  val dataSourceProperty = Property[JdbcConnectionPool]()

  def dataSource = dataSourceProperty()
  val trigger = new UnitProcessor[TriggerEvent]("trigger")

  val querying = new UnitProcessor[Query]("querying")
  val inserting = new UnitProcessor[Insert]("inserting")
  val merging = new UnitProcessor[Merge]("merging")
  val updating = new UnitProcessor[Update]("updating")
  val deleting = new UnitProcessor[Delete]("deleting")

  private var functions = Set.empty[H2Function]

  init()

  private def init() = {
    updateDataSource()
    modeProperty.change.on {
      case evt => updateDataSource()      // Update the data source if the mode changes
    }
  }

  def updateDataSource() = {
    dataSourceProperty.get match {
      case Some(ds) => ds.dispose()
      case None => // No previous dataSource
    }
    dataSourceProperty := JdbcConnectionPool.create(modeProperty().url, dbUser, dbPassword)
  }

  def function[F](obj: AnyRef, methodName: String, functionName: Option[String] = None) = synchronized {
    val f = H2Function(this, obj, methodName, functionName)
    functions += f
    f
  }

  def createTableSQL(table: Table) = {
    val b = new StringBuilder

    b.append("CREATE TABLE IF NOT EXISTS ")
    b.append(table.tableName)
    b.append('(')
    b.append(table.columns.map(c => column2SQL(c)).mkString(", "))

    if (table.primaryKeys.nonEmpty) {
      b.append(s", PRIMARY KEY(${table.primaryKeys.map(c => c.name).mkString(", ")})")
    }

    b.append(");")

    b.toString()
  }

  def createTableExtras(table: Table, b: StringBuilder) = {
    createTableReferences(table, b)
    createTableIndexes(table, b)
    createTableTriggers(table, b)
  }

  def createTableReferences(table: Table, b: StringBuilder) = {
    table.foreignKeys.foreach {
      case c => {
        val foreignKey = ForeignKey(c).foreignColumn
        b.append(s"ALTER TABLE ${table.tableName}\r\n")
        b.append(s"\tADD FOREIGN KEY(${c.name})\r\n")
        b.append(s"\tREFERENCES ${foreignKey.table.tableName} (${foreignKey.name});\r\n\r\n")
      }
    }
  }

  def createTableIndexes(table: Table, b: StringBuilder) = {
    table.columns.foreach {
      case c => c.get[Indexed](Indexed.name) match {
        case Some(index) => {
          b.append(s"CREATE INDEX IF NOT EXISTS ${index.indexName} ON ${table.tableName}(${c.name});\r\n\r\n")
        }
        case None => // No index on this column
      }
    }

    table.properties.foreach {
      case index: Index => b.append(s"CREATE ${if (index.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS ${index.indexName} ON ${table.tableName}(${index.columns.map(c => c.name).mkString(", ")});\r\n\r\n")
      case _ => // Ignore other table properties
    }
  }

  private def createTableTriggers(table: Table, b: StringBuilder) = if (table.has(Triggers.name)) {
    val triggers = table.get[Triggers](Triggers.name).get
    if (triggers.has(TriggerType.Insert)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_INSERT_TRIGGER AFTER INSERT ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Update)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_UPDATE_TRIGGER AFTER UPDATE ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Delete)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_DELETE_TRIGGER AFTER DELETE ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Select)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_SELECT_TRIGGER BEFORE SELECT ON ${table.tableName} CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
  }

  override def createExtras(b: StringBuilder) = {
    createFunctions(b)
  }

  private def createFunctions(b: StringBuilder) = {
    functions.foreach {
      case f => b.append(s"""CREATE ALIAS IF NOT EXISTS ${f.name} FOR "${f.obj.getClass.getName.replaceAll("[$]", "")}.${f.methodName}";\r\n\r\n""")
    }
  }

  def column2SQL(column: Column[_]) = {
    val b = new StringBuilder
    b.append(column.name)
    b.append(' ')
    b.append(column.sqlType)
    if (column.has(NotNull)) {
      b.append(" NOT NULL")
    }
    if (column.has(AutoIncrement)) {
      b.append(" AUTO_INCREMENT")
    }
    if (column.has(Unique)) {
      b.append(" UNIQUE")
    }
    b.toString()
  }

  private def expression2SQL(expression: SelectExpression) = expression match {
    case c: ColumnLike[_] => c.longName
    case f: SimpleFunction[_] => s"${f.functionType.name.toUpperCase}(${f.column.longName})"
  }

  def sqlFromQuery(query: Query) = {
    val columns = query.expressions.map(expression2SQL).mkString(", ")

    var args = List.empty[Any]

    // Generate SQL
    val (joins, joinArgs) = joins2SQL(query.joins)
    args = args ::: joinArgs
    val (where, whereArgs) = where2SQL(query.whereCondition)
    args = args ::: whereArgs
    val groupBy = if (query._groupBy.nonEmpty) {
      s" GROUP BY ${query._groupBy.map(expression2SQL).mkString(", ")}"
    } else {
      ""
    }
    val orderBy = if (query._orderBy.nonEmpty) {
      s" ORDER BY ${query._orderBy.map(ob => s"${expression2SQL(ob.expression)} ${ob.direction.sql}").mkString(", ")}"
    } else {
      ""
    }
    val limit = if (query._limit != -1) {
      s" LIMIT ${query._limit}"
    } else {
      ""
    }
    val offset = if (query._offset != -1) {
      s" OFFSET ${query._offset}"
    } else {
      ""
    }
    s"SELECT $columns FROM ${query.table.tableName}$joins$where$groupBy$orderBy$limit$offset" -> args
  }

  def exportTable(table: Table, file: File, drop: Boolean = true) = {
    val command = new StringBuilder("SCRIPT ")
    if (drop) {
      command.append("DROP ")
    }
    command.append("TO '")
    command.append(file.getCanonicalPath)
    command.append("' TABLE ")
    command.append(table.tableName)

//    val command = s"SCRIPT TO '${file.getCanonicalPath}' TABLE ${table.tableName}"
    session.execute(command.toString)
  }

  def importScript(file: File) = {
    val command = s"RUNSCRIPT FROM '${file.getCanonicalPath}'"
    session.execute(command)
  }

  def exec(query: Query) = {
    val (sql, args) = sqlFromQuery(query)

    querying.fire(query)
    val resultSet = session.executeQuery(sql, args)
    new QueryResultsIterator(resultSet, query)
  }

  def exec(insert: InsertSingle) = {
    if (insert.values.isEmpty) throw new IndexOutOfBoundsException(s"Attempting an insert query with no values: $insert")
    val table = insert.values.head.column.table
    val columnNames = insert.values.map(cv => cv.column.name).mkString(", ")
    val columnValues = insert.values.map(cv => cv.toSQL)
    val placeholder = columnValues.map(v => "?").mkString(", ")
    val insertString = s"INSERT INTO ${table.tableName} ($columnNames) VALUES($placeholder)"
    inserting.fire(insert)
    val resultSet = session.executeInsert(insertString, columnValues)
    try {
      if (resultSet.next()) {
        resultSet.getInt(1)
      } else {
        -1
      }
    } finally {
      resultSet.close()
    }
  }

  def exec(insert: InsertMultiple) = {
    if (insert.rows.isEmpty) throw new IndexOutOfBoundsException(s"Attempting a multi-insert with no values: $insert")
    if (!insert.rows.map(_.length).sliding(2).forall { case Seq(first, second) => first == second }) throw new IndexOutOfBoundsException(s"All rows must have the exact same length.")
    val table = insert.rows.head.head.column.table
    val columnNames = insert.rows.head.map(cv => cv.column.name).mkString(", ")
    val columnValues = insert.rows.map(r => r.map(cv => cv.toSQL))
    val placeholder = insert.rows.head.map(v => "?").mkString(", ")
    val insertString = s"INSERT INTO ${table.tableName} ($columnNames) VALUES($placeholder)"
    inserting.fire(insert)
    val resultSet = session.executeInsertMultiple(insertString, columnValues)
    try {
      val indexes = ListBuffer.empty[Int]
      while (resultSet.next()) {
        indexes += resultSet.getInt(1)
      }
      indexes.toList
    } finally {
      resultSet.close()
    }
  }
  
  def exec(merge: Merge) = {
    val table = merge.key.table
    val columnNames = merge.values.map(cv => cv.column.name).mkString(", ")
    val columnValues = merge.values.map(cv => cv.toSQL)
    val placeholder = columnValues.map(v => "?").mkString(", ")
    val mergeString = s"MERGE INTO ${table.tableName} ($columnNames) KEY(${merge.key.name}) VALUES($placeholder)"
    merging.fire(merge)
    session.executeUpdate(mergeString, columnValues)
  }

  def exec(update: Update) = {
    var args = List.empty[Any]
    val sets = update.values.map(cv => s"${cv.column.longName}=?").mkString(", ")
    val setArgs = update.values.map(cv => cv.toSQL)
    args = args ::: setArgs

    val (where, whereArgs) = where2SQL(update.whereCondition)
    args = args ::: whereArgs
    val sql = s"UPDATE ${update.table.tableName} SET $sets$where"
    updating.fire(update)
    session.executeUpdate(sql, args)
  }

  def exec(delete: Delete) = {
    var args = List.empty[Any]

    val (where, whereArgs) = where2SQL(delete.whereCondition)
    args = args ::: whereArgs
    val sql = s"DELETE FROM ${delete.table.tableName}$where"
    deleting.fire(delete)
    session.executeUpdate(sql, args)
  }

  def condition2String(condition: Condition, args: ListBuffer[Any]): String = condition match {
    case c: ColumnCondition[_] => {
      s"${c.column.longName} ${c.operator.symbol} ${c.other.longName}"
    }
    case c: NullCondition[_] => {
      s"${c.column.longName} ${c.operator.symbol} NULL"
    }
    case c: DirectCondition[_] => {
      args += c.column.converter.asInstanceOf[DataType[Any]].toSQLType(c.column.asInstanceOf[ColumnLike[Any]], c.value)
      s"${c.column.longName} ${c.operator.symbol} ?"
    }
    case c: LikeCondition[_] => {
      args += c.pattern
      s"${c.column.longName} ${if (c.not) "NOT " else ""}LIKE ?"
    }
    case c: RegexCondition[_] => {
      args += c.regex.toString()
      s"${c.column.longName} ${if (c.not) "NOT " else ""}REGEXP ?"
    }
    case c: RangeCondition[_] => {
      c.values.foreach {
        case v => args += c.column.converter.asInstanceOf[DataType[Any]].toSQLType(c.column.asInstanceOf[ColumnLike[Any]], v)
      }
      val entries = c.operator match {
        case Operator.Between => c.values.map(v => "?").mkString(" AND ")
        case _ => c.values.map(v => "?").mkString("(", ", ", ")")
      }
      s"${c.column.longName} ${c.operator.symbol}$entries"
    }
    case c: Conditions => {
      val sql = c.list.map(condition => condition2String(condition, args)).mkString(s" ${c.connectType.name.toUpperCase} ")
      s"($sql)"
    }
  }

  private def joins2SQL(joins: List[Join]): (String, List[Any]) = {
    val args = ListBuffer.empty[Any]

    val b = new StringBuilder
    joins.foreach {
      case join => {
        val pre = join.joinType match {
          case JoinType.Inner => " INNER JOIN "
          case JoinType.Join => " JOIN "
          case JoinType.Left => " LEFT JOIN "
          case JoinType.LeftOuter => " LEFT OUTER JOIN "
          case JoinType.Outer => " OUTER JOIN "
        }
        b.append(pre)
        b.append(join.table.tableName)
        if (join.alias != null) {
          b.append(s" AS ${join.alias}")
        }
        b.append(" ON ")
        b.append(condition2String(join.condition, args))
      }
    }

    (b.toString(), args.toList)
  }

  private def where2SQL(condition: Condition): (String, List[Any]) = if (condition != null) {
    val args = ListBuffer.empty[Any]
    val sql = condition2String(condition, args)
    if (sql != null && sql.nonEmpty) {
      s" WHERE $sql" -> args.toList
    } else {
      "" -> Nil
    }
  } else {
    "" -> Nil
  }

  override def dispose() = {
    dataSource.dispose()
  }
}