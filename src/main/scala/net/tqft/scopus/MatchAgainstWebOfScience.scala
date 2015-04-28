package net.tqft.scopus

import net.tqft.webofscience
import net.tqft.scholar.Scholar

object MatchCitationsAgainstWebOfScience {
  def apply(scopusArticle: Article): (Seq[(Article, webofscience.Citation)], Seq[Article], Seq[webofscience.Citation]) = {
    apply(scopusArticle, scopusArticle.onWebOfScience.get)
  }

  def apply(scopusArticle: Article, webOfScienceArticle: webofscience.Article): (Seq[(Article, webofscience.Citation)], Seq[Article], Seq[webofscience.Citation]) = {
    apply(scopusArticle.citations, webOfScienceArticle.citations)
  }

  // takes a sequence of Scopus articles, and sequence of WoS citations
  // returns a (X, Y, Z), where X is a list of matching pairs, Y is a list of unmatched Scopus articles, and Z is a list of unmatched WoS citations.
  def apply(scopusArticles: Seq[Article], webOfScienceCitations: Seq[webofscience.Citation]): (Seq[(Article, webofscience.Citation)], Seq[Article], Seq[webofscience.Citation]) = {
    // first, take out everything that has matching DOIs
    val scopusDOIs = scopusArticles.flatMap(_.DOIOption).toSet
    val webOfScienceDOIs = webOfScienceCitations.flatMap(_.DOIOption).toSet

    val matchingDOIs = scopusDOIs.intersect(webOfScienceDOIs)
    val DOIMatches = matchingDOIs.toSeq.map(doi => (scopusArticles.find(_.DOIOption == Some(doi)).get, webOfScienceCitations.find(_.DOIOption == Some(doi)).get))

    // second, take everything with exactly matching titles
    val remainingScopusArticles_1 = scopusArticles.filterNot(article => article.DOIOption.nonEmpty && matchingDOIs.contains(article.DOIOption.get))
    val remainingWebOfScienceCitations_2 = webOfScienceCitations.filterNot(article => article.DOIOption.nonEmpty && matchingDOIs.contains(article.DOIOption.get))

    val scopusTitles = remainingScopusArticles_1.map(_.title.trim.toLowerCase).toSet
    val webOfScienceTitles = remainingWebOfScienceCitations_2.map(_.title.trim.toLowerCase).toSet

    val matchingTitles = scopusTitles.intersect(webOfScienceTitles)
    val titleMatches = matchingTitles.toSeq.map(title => (remainingScopusArticles_1.find(_.title.trim.toLowerCase == title).get, remainingWebOfScienceCitations_2.find(_.title.trim.toLowerCase == title).get))

    // now, for everything else, go look at Google Scholar
    val remainingScopusArticles = remainingScopusArticles_1.filterNot(article => matchingTitles.contains(article.title.trim.toLowerCase)).map(a => (a, Scholar(a)))
    val remainingWebOfScienceCitations = remainingWebOfScienceCitations_2.filterNot(article => matchingTitles.contains(article.title.trim.toLowerCase)).map(a => (a, Scholar(a)))

    val scopusClusters = remainingScopusArticles.flatMap(_._2.map(_.cluster)).toSet
    val webOfScienceClusters = remainingWebOfScienceCitations.flatMap(_._2.map(_.cluster)).toSet

    val matchingClusters = scopusClusters.intersect(webOfScienceClusters)
    val clusterMatches = matchingClusters.toSeq.map(cluster => (remainingScopusArticles.find(pair => pair._2.nonEmpty && pair._2.get.cluster == cluster).get._1, remainingWebOfScienceCitations.find(pair => pair._2.nonEmpty && pair._2.get.cluster == cluster).get._1))
    // TODO check there is nothing obviously wrong with these clusters?

    val unmatchedScopusArticles = remainingScopusArticles.filterNot(pair => pair._2.nonEmpty && matchingClusters.contains(pair._2.get.cluster)).map(_._1)
    val unmatchedWebOfScienceCitations = remainingWebOfScienceCitations.filterNot(pair => pair._2.nonEmpty && matchingClusters.contains(pair._2.get.cluster)).map(_._1)

    (DOIMatches ++ clusterMatches, unmatchedScopusArticles, unmatchedWebOfScienceCitations)
  }
}