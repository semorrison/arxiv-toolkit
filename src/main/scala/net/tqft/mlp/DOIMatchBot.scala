package net.tqft.mlp

import net.tqft.mlp.sql.SQLTables
import slick.jdbc.MySQLProfile.api._
import net.tqft.mlp.sql.SQL
import net.tqft.mathscinet.Article
import net.tqft.toolkit.wiki.WikiMap
import net.tqft.toolkit.wiki.FirefoxDriver
import net.tqft.util.FirefoxSlurp

object DOIMatchBot extends App {

  FirefoxSlurp.disable

  lazy val arxivbot = {
    val b = WikiMap("http://tqft.net/mlp/index.php")
    b.login("arxivbot", "zytopex")
    b.enableSQLReads("jdbc:mysql://mysql.tqft.net/mathematicsliteratureproject?user=readonly1&password=readonly", "mlp_")
    b.setThrottle(5000)
    b
  }

  val matchingDOIs = SQL {
    (for (
      a <- SQLTables.arxiv;
      b <- SQLTables.mathscinet if a.doi === b.doi
    ) yield (a.arxivid, b.MRNumber)).result
  }

  println("Matching DOIs:")

  val results = scala.util.Random.shuffle(matchingDOIs)
  println("total: " + results.size)

  for (
    (arxivid, mrnumber) <- results;
    mr = Article(mrnumber).identifierString
  ) {
    println(arxivid + " <---> " + mr)
    arxivbot("Data:" + mr + "/FreeURL") = "http://arxiv.org/abs/" + arxivid
  }

  println("total: " + results.size)

  FirefoxDriver.quit
}