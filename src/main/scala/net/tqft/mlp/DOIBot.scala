package net.tqft.mlp

import net.tqft.mathscinet.Search
import net.tqft.journals.ISSNs
import net.tqft.wiki.WikiMap
import net.tqft.mathscinet.Article
import net.tqft.util.pandoc
import net.tqft.eigenfactor.Eigenfactor

object DOIBot extends App {

  lazy val doibot = {
    val b = WikiMap("http://tqft.net/mlp/index.php")
    b.login("doibot", "zytopex")
    b
  }

  //  Article.enableBibtexSaving

  for (a <- extendedCoverage) {
    for (link <- a.DOI.map("http://dx.doi.org/" + _).orElse(a.URL)) {
      println("posting link for " + a.identifierString)
      doibot("Data:" + a.identifierString + "/PublishedURL") = link

    }
  }

  net.tqft.wiki.FirefoxDriver.quit
}