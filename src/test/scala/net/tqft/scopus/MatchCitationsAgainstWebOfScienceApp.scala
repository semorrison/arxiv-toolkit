package net.tqft.scopus

import java.io.PrintWriter
import java.io.FileWriter

object MatchCitationsAgainstWebOfScienceApp extends App {

  val articles = if (args(0).stripPrefix("scopus:").startsWith("2-s2.0-")) {
    Seq(Article(args(0).stripPrefix("scopus:")))
  } else {
    val name = args.tail.mkString(" ")
    println(s"Considering all articles by author '$name', with Scopus identifier ${args(0)}.")
    Author(args(0).toLong, name).publications.filter(a => a.yearOption.nonEmpty && a.yearOption.get >= 2005)
  }

  val html_start = """<!DOCTYPE html>
<html lang="en">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
<script type="text/javascript" src="http://ajax.googleapis.com/ajax/libs/jquery/1.6.4/jquery.min.js"></script>
<script type="text/x-mathjax-config">
        MathJax.Hub.Config(
            {
                "HTML-CSS": { preferredFont: "TeX", availableFonts: ["STIX","TeX"] },
                tex2jax: {
                    inlineMath: [ ["$", "$"], ["\\\\(","\\\\)"] ],
                    displayMath: [ ["$$","$$"], ["\\[", "\\]"] ],
                    processEscapes: true,
                    ignoreClass: "tex2jax_ignore|dno"
                },
                TeX: {
                    noUndefined: { attributes: { mathcolor: "red", mathbackground: "#FFEEEE", mathsize: "90%" } }
                },
                messageStyle: "none"
            });
</script>    
<script type="text/javascript" src="http://cdn.mathjax.org/mathjax/latest/MathJax.js?config=TeX-AMS_HTML"></script>
<script type="text/css">
      table {
		  border-collapse: collapse;
	  }
      table, th, td {
		  border: 1px solid black;     
	  }
      th {
           width: 50%; 
      }
</script>
<head>
<body>"""

  val html_end = "</body></html>"

  val html_out = new PrintWriter(new FileWriter("compare.html"))
  
  def p(s: String) {
    html_out.println(s)
  }

  def html[A](contents: => A) {
    p(html_start)
    contents
    p(html_end)
  }

  def table[A](contents: => A) {
    p("<table>")
    contents
    p("</table>")
  }

  def tableRow(entries: String*) {
    p("<tr>")
    for (entry <- entries) {
      p(s"<td>$entry</td>")
    }
    p("</tr>")
  }

  html {
    for (article <- articles) {
      println("Considering citations for " + article.fullCitation)
      p("<h2>Considering citations for " + article.fullCitation_html + "</h2>")

      if (article.onWebOfScience.isEmpty) {
        println("No matching article found on Web Of Science!")
        p("<p>No matching article found on Web Of Science!</p>")
      } else {
        println("Located matching article on Web of Science: " + article.onWebOfScience.get.fullCitation)
        p("<p>Located matching article on Web of Science: " + article.onWebOfScience.get.fullCitation_html + "</p>")

        val (matches, unmatchedScopus, unmatchedWebOfScience) = MatchCitationsAgainstWebOfScience(article)
        println("Found the following matching citations:")
        p("<p>Found the following matching citations:")
        table {
          println("---")
          for ((scopusArticle, webOfScienceCitation) <- matches) {
            println(scopusArticle.fullCitation)
            println(webOfScienceCitation.fullCitation)
            println("---")
            tableRow(scopusArticle.fullCitation_html, webOfScienceCitation.fullCitation_html)
          }
        }
        println("Found the following citations on Scopus, which do not appear on Web of Science:")
        p("</p><p>Found the following citations on Scopus, which do not appear on Web of Science:")
        table {
          println("---")
          for (article <- unmatchedScopus) {
            println(article.fullCitation)
            println("---")
            tableRow(article.fullCitation_html, "")
          }
        }
        println("Found the following citations on Web of Science, which do not appear on Scopus:")
        p("</p><p>Found the following citations on Web of Science, which do not appear on Scopus:")
        table {
          println("---")
          for (article <- unmatchedWebOfScience) {
            println(article.fullCitation)
            println("---")
            tableRow("", article.fullCitation_html)
          }
        }
        p("</p><hr>")
      }

      println
    }
  }

  net.tqft.scholar.FirefoxDriver.quit
  net.tqft.util.FirefoxSlurp.quit

}