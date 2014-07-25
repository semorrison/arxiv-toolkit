package net.tqft.scopus

import net.tqft.util.BIBTEX
import net.tqft.util.Slurp
import net.tqft.toolkit.Logging
import net.tqft.citationsearch.CitationScore
import net.tqft.toolkit.Extractors._

case class Article(id: String, titleHint: Option[String] = None) {
  def URL = "http://www.scopus.com/record/display.url?eid=" + id + "&origin=resultslist"
  def textURL = "http://www.scopus.com/onclick/export.url?oneClickExport=%7b%22Format%22%3a%22TEXT%22%2c%22View%22%3a%22FullDocument%22%7d&origin=recordpage&eid=" + id + "&zone=recordPageHeader&outputType=export"
  def bibtexURL = "http://www.scopus.com/onclick/export.url?oneClickExport=%7b%22Format%22%3a%22BIB%22%2c%22View%22%3a%22FullDocument%22%7d&origin=recordpage&eid=" + id + "&zone=recordPageHeader&outputType=export"
  def citationsURL(page: Int) = "http://www.scopus.com/results/results.url?cc=10&sort=plf-f&cite=" + id + "&src=s&nlo=&nlr=&nls=&imp=t&sot=cite&sdt=a&sl=0&ss=plf-f&ps=r-f&" + (if (page > 0) { "offset=" + (20 * page + 1) + "&" } else { "" }) + "origin=resultslist&zone=resultslist"
  // http://www.scopus.com/results/results.url?cc=10&sort=plf-f&cite=2-s2.0-46049118990&src=s&nlo=&nlr=&nls=&imp=t&sot=cite&sdt=a&sl=0&ss=plf-f&ps=r-f&origin=resultslist&zone=resultslist

  def getDataText: Stream[String] = {
    val slurp = Slurp(textURL).toStream
    if (slurp.mkString("\n").contains("getElementById")) {
      Logging.warn("Corrupted metadata for " + id, " clearing cache and trying again.")
      Slurp -= textURL
      getDataText
    } else {
      slurp
    }
  }

  lazy val dataText = getDataText

  private def dataWithPrefix(prefix: String) = dataText.find(_.startsWith(prefix + ": ")).map(_.stripPrefix(prefix + ": "))

  def title = titleHint.getOrElse(dataText(4))
  def citation = "(.*) Cited [0-9]* times?.".r.findFirstMatchIn(dataText(5).trim).map(_.group(1)).getOrElse(dataText(5))
  def ISSNOption = dataWithPrefix("ISSN").map(s => s.take(4) + "-" + s.drop(4))
  def DOIOption = dataWithPrefix("DOI")
  def authorData = dataText(3)
  def yearOption = """^\(([0-9]*)\) """.r.findFirstMatchIn(citation).map(_.group(1)).collect({ case Int(i) => i })

  def numberOfCitations: Option[Int] = ".* Cited ([0-9]*) times?.".r.findFirstMatchIn(dataText(5).trim).map(_.group(1).toInt)

  def fullCitation = title + " - " + authorData + " - " + citation + " - scopus:" + id
  def fullCitation_html = title + " - " + authorData + " - " + citation + " - <a href='" + URL + "'>scopus:" + id + "</a>"
  lazy val matches = net.tqft.citationsearch.Search.query(title + " - " + authorData + " - " + citation + DOIOption.map(" " + _).getOrElse("")).results

  lazy val satisfactoryMatch: Option[CitationScore] = {
    matches.headOption.filter(s => s.score > 0.85).orElse(
      matches.sliding(2).filter(p => p(0).score > 0.42 && scala.math.pow(p(0).score, 1.6) > p(1).score).toStream.headOption.map(_.head))
  }

  lazy val citations: Seq[Article] = {
    val firstPage = Slurp(citationsURL(0)).toStream

    val totalCitationCount = "Scopus - ([0-9]*) documents? that cite".r.findFirstMatchIn(firstPage.mkString("\n")).map(_.group(1)).get.toInt
    val allPages = for(i <- (0 until (totalCitationCount + 19) / 20).toStream; line <- Slurp(citationsURL(i))) yield line
    
    val r = "eid=([^&]*)&".r
    val result = allPages.flatMap(l => r.findFirstMatchIn(l).map(_.group(1))).distinct.filterNot(_ == id).map({ i => println("found citation: " + i); Article(i) })
    if(result.size != totalCitationCount) {
      Logging.warn("Scopus says there are " + totalCitationCount + " citations, but I see " + result.size)
    }
    result
  }

  lazy val citationMatches = citations.iterator.toStream.map(r => (r, net.tqft.citationsearch.Search.query(r.fullCitation).results))
  def bestCitationMathSciNetMatches = citationMatches.map({ p => (p._1, p._2.headOption.flatMap(_.citation.MRNumber).map(i => net.tqft.mathscinet.Article(i))) })

  def references: Seq[String] = {
    import net.tqft.toolkit.collections.TakeToFirst._
    dataText.iterator.dropWhile(!_.startsWith("REFERENCES: ")).map(_.stripPrefix("REFERENCES: ").trim).takeToFirst(!_.endsWith(";")).map(_.stripSuffix(";").ensuring(_.nonEmpty)).toSeq
  }

  lazy val referenceMatches = references.iterator.toStream.map(r => (r, net.tqft.citationsearch.Search.query(r).results))

  def bestReferenceMathSciNetMatches = referenceMatches.map({ p => (p._1, p._2.headOption.flatMap(_.citation.MRNumber).map(i => net.tqft.mathscinet.Article(i))) })
}