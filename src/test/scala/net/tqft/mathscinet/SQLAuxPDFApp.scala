package net.tqft.mathscinet

import net.tqft.mlp.sql.SQL
import net.tqft.mlp.sql.SQLTables
import scala.slick.driver.MySQLDriver.simple._
import net.tqft.toolkit.Logging
import net.tqft.util.pandoc
import scala.collection.parallel.ForkJoinTaskSupport

object SQLAuxPDFApp extends App {

  SQL { implicit session =>
    def articlesPage(k: Int) = {
      println("retrieving page " + k)
      (for (
        a <- SQLTables.mathscinet;
        aux <- SQLTables.mathscinet_aux;
        if a.MRNumber === aux.MRNumber;
        if a.issn =!= "";
        if aux.pdf.isNull || aux.pdf === ""
      ) yield (a, aux)).drop(k * 1000).take(1000).list
    }

    var group = articlesPage(2)
    while (group.nonEmpty) {

      for ((a, aux) <- group) {
          try {
            val pdf = a.stablePDFURL.getOrElse("-")
            SQLTables.mathscinet_aux.filter(_.MRNumber === a.identifier).map(_.pdf).update(Some(pdf))
            println("Adding PDF URL for " + a.identifierString + ": " + pdf)
          } catch {
            case e: Exception => {
              Logging.warn("Exception while inserting \n" + a.bibtex.toBIBTEXString)
              throw e
            }
          }
      }
      group = articlesPage(2)

    }
  }
}