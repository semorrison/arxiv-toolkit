package net.tqft.mathscinet

import net.tqft.util.FirefoxSlurp
import java.io.File
import java.net.URL
import net.tqft.journals.ISSNs

object SavePDFApp extends App {
  FirefoxSlurp.disable
  Article.disableBibtexSaving

  //  val dir = new File(System.getProperty("user.home") + "/scratch/pdfs/")
  //
  //  val visitedHosts = scala.collection.mutable.Set[String]()
  //  val visitedPrefixes = scala.collection.mutable.Set[String]()

  // Springer
  //  // mysteriously not working... perhaps springer would like a cookie?
  //  val a = Article.fromDOI("10.1007/978-3-0346-0161-0_3").get
  //  println("Considering " + a.identifierString + " with DOI " + a.DOI.get + " and URL " + a.URL.get)
  //  println("Identified URL for PDF: " + a.pdfURL.get)
  //  a.savePDF(dir)

  // CUP
  //  val a = Article.fromDOI("10.1017/S0022112010001734").get
  //  println("Considering " + a.identifierString + " with DOI " + a.DOI.get + " and URL " + a.URL.get)
  //  println("Identified URL for PDF: " + a.pdfURL.get)
  //  a.savePDF(dir)

  //  val articles = Articles.fromBibtexFile(System.getProperty("user.home") + "/projects/arxiv-toolkit/50.bib").drop(scala.util.Random.nextInt(100000) + 50000)
  //    .filterNot(_.DOI.isEmpty)
  ////    .filterNot(_.DOI.get.startsWith("10.1002")) // Wiley (working)
  ////    .filterNot(_.DOI.get.startsWith("10.1007")) // Springer (working)
  ////    .filterNot(_.DOI.get.startsWith("10.1016")) // Elsevier (working)
  ////    .filterNot(_.DOI.get.startsWith("10.1017")) // CUP (working?)
  ////    .filterNot(_.DOI.get.startsWith("10.2307")) // JSTOR (working)
  //    .filter( a => !(new File(dir, a.identifierString + ".pdf").exists))
  ////    .filter({ a => val prefix = a.DOI.get.take(7); val result = !visitedPrefixes.contains(prefix); visitedPrefixes += prefix; result })
  //    .map({ a => println("Considering " + a.identifierString + " with DOI " + a.DOI.get + " and URL " + a.URL.get); a })
  ////    .filter(_.pdfURL.nonEmpty)
  //    .filter({ a => val host = new URL(a.pdfURL.get).getHost(); val result = !visitedHosts.contains(host); visitedHosts += host; result })
  //    .map({ a => println("Identified URL for PDF: " + a.pdfURL.get); a })
  //    .take(100)
  //
  //  for (article <- articles) {
  //    article.savePDF(dir)
  //  }

  def articles = Articles.fromBibtexGzipFile(System.getProperty("user.home") + "/projects/arxiv-toolkit/100_4.bib.gz")
//    def articles = Search.inJournalsJumbled(ISSNs.Elsevier)

  def openAccessElsevierArticles = for (a <- articles; issn <- a.ISSNOption; if ISSNs.Elsevier.contains(issn); y <- a.yearOption; if y <= 2008) yield a
  def openAccessAdvancesArticles = for (a <- articles; issn <- a.ISSNOption; if issn == ISSNs.`Advances in Mathematics`; if a.journal != "Advancement in Math."; y <- a.yearOption; if y <= 2008) yield a
  def openAccessTopologyArticles = for (a <- articles; issn <- a.ISSNOption; if issn == ISSNs.`Topology`; y <- a.yearOption; if y <= 2008) yield a

  val missingMRNumbers = Seq()
  val missing = articles.filter(a => missingMRNumbers.contains(a.identifierString))
  
//  println(openAccessAdvancesArticles.map(_.year).min)
//  println(openAccessAdvancesArticles.filter(_.pdfURL.nonEmpty).map(_.year).min)
//  println(openAccessAdvancesArticles.filter(_.pdfURL.isEmpty).map(_.year).max)
  
  val dir = new File(System.getProperty("user.home") + "/scratch/elsevier-oa/")

  for (article <- openAccessElsevierArticles) {
    try {
      println(article.bibtex.toBIBTEXString)
      article.savePDF(dir)
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

}