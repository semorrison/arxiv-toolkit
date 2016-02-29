package net.tqft.arxiv.tools

import net.tqft.mlp.sql._

object UnmatchedDOIs extends App {

  import slick.driver.MySQLDriver.api._

  SQL { 
    val results = for (
      a <- SQLTables.arxiv;
      if a.journalref.isNotNull;
      if !SQLTables.mathscinet.filter(_.doi === a.doi).exists
    ) yield (a.title, a.authors, a.journalref)
    
    println(results.selectStatement)
    
    println(results.list.size)
    
//    for(a <- results) {
//      println(a)
//    }
  }
}