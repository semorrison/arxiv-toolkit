package net.tqft

import net.tqft.eigenfactor.Eigenfactor
import net.tqft.mlp.sql.SQLTables
import net.tqft.mlp.sql.SQL
package object mlp {
  import net.tqft.journals.ISSNs
  import net.tqft.mathscinet.Search

  def selectedJournals = Iterator(
    ISSNs.`Advances in Mathematics`,
    ISSNs.`Discrete Mathematics`,
    ISSNs.`Annals of Mathematics`,
    ISSNs.`Algebraic & Geometric Topology`,
    ISSNs.`Geometric and Functional Analysis`,
    ISSNs.`Journal of Functional Analysis`,
    ISSNs.`Journal of Number Theory`,
    ISSNs.`Journal of Combinatorial Theory A`,
    ISSNs.`Journal of Combinatorial Theory B`)

  val selectedYears = Seq(2013)

  def selectedCoverage = {
    import scala.slick.driver.MySQLDriver.simple._
    SQL { implicit session =>
      for (j <- selectedJournals; y <- selectedYears; a <- SQLTables.mathscinet.filter(_.issn === j).filter(_.year === y.toString).iterator) yield a
    }
  }

  def extendedJournals = Iterator(ISSNs.`Journal of Algebra`, ISSNs.`Journal of Pure and Applied Algebra`) ++ selectedJournals
  def extendedYears = Seq(2010, 2013)

  def extendedCoverage = for (j <- extendedJournals; y <- extendedYears; a <- Search.inJournalYear(j, y)) yield a

  def topJournals(k: Int) = for (j <- extendedJournals; y <- extendedYears; a <- Search.inJournalYear(j, y)) yield a

}
