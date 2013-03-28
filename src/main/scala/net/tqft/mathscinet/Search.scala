package net.tqft.mathscinet

import net.tqft.util.Slurp
import net.tqft.toolkit.collections.Split._
import net.tqft.toolkit.collections.TakeToFirst._
import java.util.Calendar

object Search {
  def query(q: String): Iterator[Article] = {
    def queryString(k: Int) = "http://www.ams.org/mathscinet/search/publications.html?" + q + "&r=" + (1 + 100 * k).toString + "&extend=1&fmt=bibtex"
    def queries = Iterator.from(0).map(queryString).map(Slurp.attempt).takeWhile(_.isLeft).map(_.left.get).flatten.takeWhile(_ != "<title>500 Internal Server Error</title>").takeWhile(!_.startsWith("No publications results")).takeWhile(_ != """<span class="disabled">Next</span>""")
    
    def bibtexChunks = queries.splitBefore(line => line.contains("<pre>")).filter(lines => lines.head.contains("<pre>")).map(lines => lines.iterator.takeToFirst(line => line.contains("</pre>")).mkString("\n").trim.stripPrefix("<pre>").stripSuffix("</pre>").trim)
    
    bibtexChunks.grouped(99).flatMap(group => group.par.flatMap(Article.fromBibtex))
  }
 
  val defaultQuery = Map[String, String](
      "pg4" -> "AUCN", 
      "s4" -> "", 
      "co4" -> "AND", 
      "pg5" -> "TI", 
      "s5" -> "", 
      "co5" -> "AND", 
      "pg6" -> "PC", 
      "s6" -> "", 
      "co6" -> "AND", 
      "pg7" -> "ALLF", 
      "s7" -> "", 
      "co7" -> "AND", 
      "dr" -> "all", 
      "yrop" -> "eq", 
      "arg3" -> "", 
      "yearRangeFirst" -> "", 
      "yearRangeSecond" -> "", 
      "pg8" -> "ET", 
      "s8" -> "All")
  val defaultParameters = List("pg4", "s4", "co4", "pg5", "s5", "co5", "pg6", "s6", "co6", "pg7", "s7", "co7", "dr", "yrop", "arg3", "yearRangeFirst", "yearRangeSecond", "pg8", "s8")
  
  def query(qs: (String, String)*): Iterator[Article] = query(qs.toMap)
  def query(q: Map[String, String]): Iterator[Article] = {
    val compositeQuery = defaultQuery ++ q
    val compositeParameters = (defaultParameters ++ q.keys).distinct
    query((for(p <- compositeParameters) yield p + "=" + compositeQuery(p)).mkString("&"))
  } 
  
  def by(author: String) = Search.query("pg4" -> "AUCN", "s4" -> author)
  def inJournal(text: String) = query("pg4" -> "JOUR", "s4" -> text)
  def during(k: Int) = query("arg3" -> k.toString, "dr" -> "pubyear", "pg8" -> "ET", "yrop" -> "eq")

  def everything = ((Calendar.getInstance().get(Calendar.YEAR)) to 1810 by -1).iterator.flatMap(during)

}