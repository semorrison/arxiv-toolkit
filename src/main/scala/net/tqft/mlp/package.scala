package net.tqft

package object mlp {
  import net.tqft.journals.ISSNs
  import net.tqft.mathscinet.Search

  def experimentalCoverage = Search.inJournalYear(ISSNs.`Discrete Mathematics`, 2013)
  
  def selectedJournals = Iterator(
    ISSNs.`Advances in Mathematics`,
    ISSNs.`Discrete Mathematics`,
    ISSNs.`Annals of Mathematics`,
    ISSNs.`Algebraic & Geometric Topology`,
    ISSNs.`Geometric and Functional Analysis`,
    ISSNs.`Journal of Functional Analysis`,
    ISSNs.`Journal of Number Theory`)

  val selectedYears = Seq(2013)

  def selectedCoverage = for (j <- selectedJournals; y <- selectedYears; a <- Search.inJournalYear(j, y)) yield a

  def extendedJournals = selectedJournals ++ Iterator(ISSNs.`Journal of Combinatorial Theory A`,
    ISSNs.`Journal of Combinatorial Theory B`)
  def extendedYears = Seq(2010, 2013)

  def extendedCoverage = for (j <- extendedJournals; y <- extendedYears; a <- Search.inJournalYear(j, y)) yield a

}