package net.tqft.arxiv2

import net.tqft.mlp.sql.SQLTables
import net.tqft.mlp.sql.SQL
import slick.jdbc.MySQLProfile.api._

object CountMatches extends App {

  val ids = SQL {
    (for (a <- SQLTables.arxiv; if a.categories.startsWith("math"); if a.arxivid.startsWith("08")) yield a.arxivid)
  }

  var matched = 0
  var unmatched = 0
  for (id <- ids) {
    println(s"processing $id")
    val results = Sources.referencesResolved(id).partition(_._3.nonEmpty)
    matched = matched + results._1.size
    unmatched = unmatched + results._2.size
    println(s"${matched * 1.0 / (matched + unmatched)} ($matched matched, $unmatched unmatched)")
  }

}