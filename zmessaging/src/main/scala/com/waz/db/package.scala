/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz

import android.database.sqlite._
import android.database.{Cursor, MatrixCursor}
import com.waz.utils._
import com.waz.ZLog._

import scala.language.implicitConversions
import scala.util.Try

package object db {
  val EmptyCursor = new MatrixCursor(Array())

  implicit def iterate[A](c: Cursor)(implicit read: Reader[A]): Iterator[A] = new CursorIterator[A](c)

  def iteratingWithReader[A](reader: Reader[A])(c: => Cursor): Managed[Iterator[A]] = Managed(c).map(new CursorIterator(_)(reader))

  class CursorIterator[A](c: Cursor)(implicit read: Reader[A]) extends Iterator[A] {
    c.moveToFirst()
    override def next(): A = returning(read(c)){ _ => c.moveToNext() }
    override def hasNext: Boolean = !c.isClosed && !c.isAfterLast
  }

  class ReverseCursorIterator[A](c: Cursor)(implicit read: Reader[A]) extends Iterator[A] {
    c.moveToLast()
    override def next(): A = returning(read(c)){ _ => c.moveToPrevious() }
    override def hasNext: Boolean = !c.isClosed && !c.isBeforeFirst
  }

  def bind[A: DbTranslator](value: A, index: Int, stmt: SQLiteProgram) = implicitly[DbTranslator[A]].bind(value, index, stmt)

  def load[A: DbTranslator](c: Cursor, index: Int) = implicitly[DbTranslator[A]].load(c, index)

  def forEachRow(cursor: Cursor)(f: Cursor => Unit): Unit = try {
    cursor.moveToFirst()
    while(! cursor.isAfterLast) { f(cursor); cursor.moveToNext() }
  } finally cursor.close()

  class Transaction(db: SQLiteDatabase) {
    def flush() = {
      db.setTransactionSuccessful()
      db.endTransaction()
      db.beginTransactionNonExclusive()
    }
  }

  def inTransaction[A](body: => A)(implicit db: SQLiteDatabase): A =
    inTransaction(_ => body)

  def inTransaction[A](body: Transaction => A)(implicit db: SQLiteDatabase): A = {
    val tr = new Transaction(db)
    if (db.inTransaction()) body(tr)
    else {
      db.beginTransactionNonExclusive()
      try returning(body(tr)) { _ => db.setTransactionSuccessful() }
      finally db.endTransaction()
    }
  }

  private lazy val readTransactions = ReadTransactionSupport.chooseImplementation()

  def inReadTransaction[A](body: => A)(implicit db: SQLiteDatabase): A =
    if (db.inTransaction()) body
    else {
      readTransactions.beginReadTransaction(db)
      try returning(body) { _ => db.setTransactionSuccessful() }
      finally db.endTransaction()
    }


  def withStatement[A](sql: String)(body: SQLiteStatement => A)(implicit db: SQLiteDatabase): A = {
    val stmt = db.compileStatement(sql)
    try {
      body(stmt)
    } finally
      stmt.close()
  }
}

package db {
  /** See https://www.sqlite.org/isolation.html - "Isolation And Concurrency", par. 4 and following.
    * TL;DR: the Android SQLite classes fail to support WAL mode correctly, we are forced to hack our way into them
    */
  trait ReadTransactionSupport {
    def beginReadTransaction(db: SQLiteDatabase): Unit
  }

  object ReadTransactionSupport {
    private implicit val logTag: LogTag = logTagFor[ReadTransactionSupport]
    def chooseImplementation(): ReadTransactionSupport = Try(DeferredModeReadTransactionSupport.create).getOrElse(FallbackReadTransactionSupport.create)
  }

  object DeferredModeReadTransactionSupport {
    def create(implicit logTag: LogTag): ReadTransactionSupport = new ReadTransactionSupport {
      private val method = classOf[SQLiteDatabase].getDeclaredMethod("getThreadSession")
      method.setAccessible(true)

      verbose("using deferred mode read transactions")

      override def beginReadTransaction(db: SQLiteDatabase): Unit = try reflectiveBegin(db) catch { case _: Exception => db.beginTransactionNonExclusive() }

      private def reflectiveBegin(db: SQLiteDatabase): Unit = {
        db.acquireReference()
        try {
          val session = method.invoke(db).asInstanceOf[SQLiteSession]
          session.beginTransaction(SQLiteSession.TRANSACTION_MODE_DEFERRED, null, SQLiteConnectionPool.CONNECTION_FLAG_READ_ONLY, null)
        }
        finally db.releaseReference()
      }
    }
  }

  object FallbackReadTransactionSupport {
    def create(implicit logTag: LogTag): ReadTransactionSupport = new ReadTransactionSupport {
      verbose("using fallback support for read transactions")
      override def beginReadTransaction(db: SQLiteDatabase): Unit = db.beginTransactionNonExclusive()
    }
  }
}
