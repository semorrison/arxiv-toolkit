package net.tqft.mathscinet

import net.tqft.mlp.sql.SQL
import net.tqft.mlp.sql.SQLTables
import slick.jdbc.MySQLProfile.api._
import net.tqft.toolkit.Logging
import net.tqft.util.pandoc
import scala.collection.parallel.ForkJoinTaskSupport
import net.tqft.citationsearch._

object SQLMatchesApp extends App {

  //    There's no good way to rebuild this table; if the citation search database is expanded, just clear this table and rebuild.
  //    SQLTables.arxiv_mathscinet_matches.delete

  def page(k: Int): Seq[(String, String, String, Option[String])] = try {
    SQL {
      (for (
        a <- SQLTables.arxiv;
        if a.doi.isEmpty;
        if !SQLTables.arxiv_mathscinet_matches.filter(_.arxivid === a.arxivid).exists
      ) yield (a.arxivid, a.title, a.authors, a.journalref)).drop(k * 100).take(100)
    }
  } catch {
    case e: Exception => {
      Logging.warn(e)
      page(k)
    }
  }

  val articles = Iterator.continually(Iterator.from(0).map(page).takeWhile(_.nonEmpty).flatten).takeWhile(_.nonEmpty).flatten

  for ((arxivid, title, authorsXML, journalref) <- articles) {
    val authors = (for (names <- (scala.xml.XML.loadString("<authors>" + authorsXML + "</authors>") \\ "author").iterator) yield (names \\ "keyname").text + ", " + (names \\ "forenames").text).mkString("", "; ", ";")
    val results = net.tqft.citationsearch.Search.query(title + " " + authors + " " + journalref.getOrElse("")).results
    for (
      (CitationScore(c: Citation, score), i) <- results.zipWithIndex;
      if i == 0 || score > 0.5;
      if c.MRNumber.nonEmpty
    ) {
      SQL {
        SQLTables.arxiv_mathscinet_matches += ((arxivid, c.MRNumber.get, score, c.best))
      }
    }
  }

}