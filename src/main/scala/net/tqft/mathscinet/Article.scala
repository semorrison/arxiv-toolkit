package net.tqft.mathscinet
import net.tqft.util.Slurp
import net.tqft.util.URLEncode
import net.tqft.arxiv.arXiv
import net.tqft.util.BIBTEX
import java.io.File
import scala.io.Source
import net.tqft.toolkit.amazon.AnonymousS3
import net.tqft.toolkit.Extractors.Int
import java.net.URL
import net.tqft.toolkit.Logging
import java.io.BufferedInputStream
import net.tqft.util.Http
import net.tqft.util.HttpClientSlurp
import org.apache.commons.io.FileUtils
import java.io.InputStream
import net.tqft.util.Html
import scala.collection.parallel.ForkJoinTaskSupport
import net.tqft.util.Accents
import java.io.FilenameFilter
import org.apache.commons.lang3.StringUtils
import net.tqft.journals.ISSNs
import java.util.zip.GZIPInputStream
import java.io.FileInputStream
import net.tqft.util.pandoc
import net.tqft.util.PDF
import net.tqft.mlp.sql.SQL
import net.tqft.mlp.sql.SQLTables
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.DirectoryStream
import java.nio.file.Path

trait Article {
  def identifier: Int
  def identifierString = "MR" + ("0000000" + identifier.toString).takeRight(7)
  //  def shortIdentifierString = "MR" + identifier.toString

  def MathSciNetURL = "http://www.ams.org/mathscinet-getitem?mr=" + identifier
  def bibtexURL = "http://www.ams.org/mathscinet/search/publications.html?fmt=bibtex&pg1=MR&s1=" + identifier
  def endnoteURL = "http://www.ams.org/mathscinet/search/publications.html?fmt=endnote&pg1=MR&s1=" + identifier

  lazy val slurp = Slurp(MathSciNetURL).toList

  var endnoteData: Option[Map[String, List[String]]] = None
  var bibtexData: Option[BIBTEX] = None

  def sqlRow: net.tqft.mlp.sql.bibtexTuple = (identifier,
    bibtex.documentType,
    bibtex.get("title"),
    bibtex.get("booktitle"),
    bibtex.get("author"),
    bibtex.get("editor"),
    bibtex.get("doi"),
    bibtex.get("url"),
    bibtex.get("journal"),
    bibtex.get("fjournal"),
    bibtex.get("issn"),
    bibtex.get("isbn"),
    bibtex.get("volume"),
    bibtex.get("issue"),
    bibtex.get("year"),
    bibtex.get("pages"),
    bibtex.get("mrclass"),
    bibtex.get("number"),
    bibtex.get("address"),
    bibtex.get("edition"),
    bibtex.get("publisher"),
    bibtex.get("series"))

  def endnote = {
    if (endnoteData.isEmpty) {
      val lines = Slurp(endnoteURL).toList
      val start = lines.indexWhere(_.trim == "<pre>")
      val finish = lines.indexWhere(_.trim == "</pre>")
      endnoteData = Some(lines.slice(start + 1, finish).groupBy(_.take(2)).mapValues(l => l.map(_.drop(3))))
    }
    endnoteData.get
  }
  def bibtex = {
    if (bibtexData.isEmpty) {
      val text = BIBTEX.cache.getOrElseUpdate(identifierString, {
        val lines = Slurp(bibtexURL).toList
        val start = lines.indexWhere(_.trim.startsWith("<pre>"))
        val finish = lines.indexWhere(_.trim == "</pre>")
        if (start == -1 || finish <= start) {
          Logging.warn("Did not find BIBTEX block in: \n" + lines.mkString("\n"))
          try {
            BIBTEX.cache -= identifierString
            Slurp -= bibtexURL
          } catch {
            case e: Exception => Logging.warn("Failed to clean BIBTEX database.", e)
          }
          ???
        }
        (lines(start).trim.stripPrefix("<pre>") +: lines.slice(start + 1, finish)).mkString("\n").trim
      })
      try {
        bibtexData = BIBTEX.parse(text)
      } catch {
        case e: Exception => {
          Logging.error("Exception while parsing BIBTEX for " + identifierString + ": \n" + text, e)
          try {
            BIBTEX.cache -= identifierString
            Slurp -= bibtexURL
          } catch {
            case e: Exception => Logging.warn("Failed to clean BIBTEX database.", e)
          }
          ???
        }
      }
    }
    bibtexData.get
  }

  def title: String = {
    if (endnoteData.nonEmpty) {
      endnote("%T").head
    } else {
      bibtex.get("TITLE").getOrElse("Untitled")
    }
  }

  def authors: List[Author] = {
    if (endnoteData.nonEmpty) {
      endnote("%A").map(n => Author(0, n))
    } else {
      bibtex.get("AUTHOR") match {
        case None => List()
        case Some(a) => a.split(" and ").toList.map(a => Author(0, a /*Accents.LaTeXToUnicode(a)*/ ))
      }
    }
  }

  def journalOption = bibtex.get("JOURNAL")
  def journal = journalOption.get

  def volumeYearAndIssue: String = {
    (volumeOption.getOrElse("") + yearStringOption.map(" (" + _ + "), ").getOrElse("") + numberOption.map("no. " + _ + ", ").getOrElse("")).stripSuffix(", ").trim
  }

  def citation: String = {
    def restOfCitation = " " + volumeYearAndIssue + pagesOption.map(", " + _).getOrElse("")

    bibtex.documentType match {
      case "article" => {
        journal + restOfCitation
      }
      case "book" => {
        bibtex.get("ISBN").map("ISBN: " + _).getOrElse("")
      }
      case "inproceedings" => {
        bibtex.get("BOOKTITLE").map(" " + _).getOrElse("") + journalOption.getOrElse("") + restOfCitation
      }
      case "proceedings" => {
        bibtex.get("NOTE").getOrElse("")
      }
      case "incollection" => {
        bibtex.get("BOOKTITLE").map(_ + " ").getOrElse("") + pages
      }
      case otherwise => {
        Logging.warn("Citation format for " + identifierString + " of type " + otherwise + " undefined:\n" + bibtex.toBIBTEXString)
        ???
      }
    }
  }

  def yearStringOption: Option[String] = {
    bibtex.get("YEAR").map(y => y.replace("/", "-"))
  }
  def yearOption: Option[Int] = {
    bibtex.get("YEAR").map(s => s.takeWhile(_.isDigit)).flatMap({
      case Int(year) => Some(year)
      case _ => None
    })
  }
  def year = yearOption.get
  def volumeOption: Option[Int] = {
    bibtex.get("VOLUME").map(s => s.takeWhile(_.isDigit)).flatMap({
      case Int(v) => Some(v)
      case _ => None
    })
  }
  def volume: Int = volumeOption.get

  // Numbers are not always integers, e.g. "fasc. 1" in Advances
  def numberOption = bibtex.get("NUMBER")
  def number: String = numberOption.get

  def ISSNOption = bibtex.get("ISSN").map(_.toUpperCase)
  def ISSN = ISSNOption.get

  def pagesOption = bibtex.get("PAGES")
  def pages = pagesOption.get

  def pageStart: Option[Int] = {
    val pages = bibtex.get("PAGES")
    pages flatMap { p =>
      val pageString = if (p.contains("--")) {
        p.split("--")(0)
      } else {
        p
      }
      pageString match {
        case Int(i) => Some(i)
        case _ => None
      }
    }
  }
  def pageEnd: Option[Int] = {
    val pages = bibtex.get("PAGES")
    pages flatMap { p =>
      val pageString = if (p.contains("--")) {
        Some(p.split("--")(1))
      } else {
        None
      }
      pageString flatMap {
        case Int(i) => Some(i)
        case _ => None
      }
    }
  }

  def DOI: Option[String] = {
    // TODO There are probably other replacements needed; do this properly?
    // &gt; and &lt; appear in Wiley DOIs.
    bibtex.get("DOI").map(_.replaceAllLiterally("&gt;", ">").replaceAllLiterally("&lt;", "<").replaceAllLiterally("&amp;", "&"))
  }

  def URL: Option[String] = {
    bibtex.get("URL")
  }

  def numberOfCitations: Int = {
    val regex = "From References: ([0-9]*)".r
    slurp.flatMap(line => regex.findFirstMatchIn(line).map(_.group(1).toInt)).headOption.getOrElse(0)
  }
  def citations: Iterator[Article] = Search.citing(identifier)

  def correctedISSN = ISSN match {
    case "0890-5401" if yearOption.nonEmpty && year <= 1986 => "0019-9958"
    case "0019-3577" if yearOption.nonEmpty && year <= 1989 => "1385-7258"
    case ow => ow
  }

  private def searchElsevierForPDFURL: Option[String] = {
    Logging.info("Attempting to find URL for Elsevier article.")

    val issn = correctedISSN

    val numbers = {
      // Topology, special cases
      if (issn == "0040-9383" && volume == 2) {
        Stream("1-2", "3", "4")
      } else if (issn == "0040-9383" && volume == 3) {
        Stream("supp/S1", "supp/S2", "1", "2", "3", "4")
      } else if (issn == ISSNs.`Advances in Mathematics` && volume == 24 && number == "2") {
        Stream("1", "2")
      } else if (issn == "0012-365X" && volume == 57) {
        Stream("1-2", "3")
      } else if (issn == "0012-365X" && volume == 59) {
        Stream("1-2", "3")
      } else if (issn == "0012-365X" && volume == 7) {
        Stream("1-2", "3-4")
      } else if (issn == "0012-365X" && volume == 3) {
        Stream("1-3", "4")
      } else {
        "" +: (numberOption match {
          case Some(number) => Stream(number.split(" ").last)
          case _ => Stream.from(1).map(_.toString)
        })
      }
    }

    val volumeURL = "http://www.sciencedirect.com/science/journal/" + issn.replaceAllLiterally("-", "") + "/" + volume
    val pages = numbers.map({ n =>
      if (n == "10") {
        Logging.warn("Something went wrong while looking up " + identifierString + " on the Elsevier website.")
        ???
      }
      val url = volumeURL + "/" + n
      Logging.info("Scanning page: " + url)
      (url, Article.ElsevierSlurpCache(url))
    }).takeWhile({ p =>
      val lines = p._2
      val titleLine = lines.find(_.contains("<title>")).get
      Logging.info(titleLine)
      (titleLine.contains("| Vol " + volume + ",") || (titleLine.contains("| Vols ") && titleLine.contains(volume.toString))) &&
        !titleLine.contains("In Progress") &&
        !titleLine.contains("Topology | Vol 48, Isss 2–4, Pgs 41-224, (June–December, 2009)")
    }).toSeq

    val regex1 = """<span style="font-weight : bold ;">(.*)</span></a></h3>""".r
    val regex2 = """<a href="(http://www.sciencedirect.com/science\?_ob=MiamiImageURL.*.pdf) " target="newPdfWin"""".r // probably obsolete now??
    val regex3 = """<a class="cLink" rel="nofollow" href="(http://www.sciencedirect.com/science/article/pii/.*.pdf)" queryStr="\?_origin=browseVolIssue&_zone=rslt_list_item" target="_blank">""".r

    val matches = (for (
      (_, p) <- pages;
      l <- p;
      if l.contains("pdfIconSmall");
      titleFound <- regex1.findFirstMatchIn(l);
      urlFound <- regex3.findFirstMatchIn(l).orElse(regex2.findFirstMatchIn(l))
    ) yield {
      (
        titleFound.group(1),
        urlFound.group(1),
        StringUtils.getLevenshteinDistance(titleFound.group(1).replaceAll("<[^>]*>", ""), textTitle).toDouble / title.length())
    }).sortBy(_._3).distinct

    Logging.info("   found matches:")
    for (m <- matches) Logging.info(m)

    val chosenMatch = if (matches.filter(_._3 == 0.0).size == 1
      || matches.filter(_._3 <= 0.425).size == 1
      || (matches.filter(_._3 <= 0.425).size > 1 && matches(0)._3 < matches(1)._3 / 2)) {
      Some(matches.head._2)
    } else if (title.startsWith("Erratum") && matches.count(_._1.startsWith("Erratum")) == 1) {
      matches.find(_._1.startsWith("Erratum")).map(_._2)
    } else if (issn == 0024 - 3795 && title.startsWith("Erratum") && matches.count(_._1.startsWith("From the editor-in-chief")) == 1) {
      matches.find(_._1.startsWith("From the editor-in-chief")).map(_._2)
    } else if (title.startsWith("Errata") && matches.count(_._1.startsWith("Errata")) == 1) {
      matches.find(_._1.startsWith("Errata")).map(_._2)
    } else if (title.startsWith("Preface") && matches.count(_._1.startsWith("Preface")) == 1) {
      matches.find(_._1.startsWith("Preface")).map(_._2)
    } else if (title.startsWith("Correction to") && matches.count(_._1.startsWith("Correction to")) == 1) {
      matches.find(_._1.startsWith("Correction to")).map(_._2)
    } else if (title.startsWith("Addendum") && matches.count(_._1.startsWith("Addendum")) == 1) {
      matches.find(_._1.startsWith("Addendum")).map(_._2)
    } else if (title.startsWith("Obituary") && matches.count(_._1.startsWith("Obituary")) == 1) {
      matches.find(_._1.startsWith("Obituary")).map(_._2)
    } else if (title.startsWith("Corrigendum") && matches.count(_._1.contains("orrigendum")) == 1) {
      matches.find(_._1.contains("orrigendum")).map(_._2)
    } else {
      None
    }

    //      require(chosenMatch.nonEmpty, "\n" + title + matches.map(t => t._1 -> t._3).mkString("\n", "\n", ""))
    for (c <- chosenMatch) println("Found URL for old Elsevier article: " + c)

    chosenMatch
  }

  lazy val pdfURL: Option[String] = {
    // This mimics the logic of direct-article-link.user.js 

    if (ISSNs.Elsevier.contains(ISSN) && (URL.isEmpty || URL.get.startsWith("http://www.sciencedirect.com/science?_ob=GatewayURL") || (ISSN == "0377-0427" && volume == 33) || (ISSN == "0166-218X" && volume == 59 /* DOI resolution fails for these */ ))) {
      // Old Elsevier articles, that MathSciNet doesn't know about
      searchElsevierForPDFURL

    } else if (ISSN == ISSNs.`K-Theory`) {
      // K-Theory, have to get it from Portico for now
      // FIXME this is apparently broken
      val toc = HttpClientSlurp.getString("http://www.portico.org/Portico/browse/access/toc.por?journalId=ISSN_09203036&issueId=ISSN_09203036v" + volume.toString + "i" + number)
      //      println(toc)
      val pagesPosition = toc.indexOf(pages.replaceAllLiterally("--", "-"))
      val idPosition = toc.drop(pagesPosition).indexOf("articleId=")
      val identifier = toc.drop(pagesPosition).drop(idPosition).drop("articleId=".length()).take(11)

      println(identifier)
      val result = "http://www.portico.org/Portico/article/access/DownloadPDF.por?journalId=ISSN_09203036&issueId=ISSN_09203036v" + volume.toString + "i" + number + "&articleId=" + identifier + "&fileType=pdf&fileValid=true"
      println(result)
      Some(result)
    } else {

      URL flatMap { url =>
        url match {
          // Elsevier
          // 10.1006 10.1016, Elsevier, has complicated URLs, e.g.
          // 10.1006/jabr.1996.0306 ---resolves to---> http://www.sciencedirect.com/science/article/pii/S0021869396903063
          //						  ---follow link---> http://pdn.sciencedirect.com/science?_ob=MiamiImageURL&_cid=272332&_user=10&_pii=S0021869396903063&_check=y&_origin=article&_zone=toolbar&_coverDate=1996--15&view=c&originContentFamily=serial&wchp=dGLbVlt-zSkWz&md5=fb951ad4ff13953e97dc2afd6fd16d4a&pid=1-s2.0-S0021869396903063-main.pdf
          //                        ---resolves to---> http://ac.els-cdn.com/S0021869396903063/1-s2.0-S0021869396903063-main.pdf?_tid=756d984e-a048-11e2-8b82-00000aab0f02&acdnat=1365424565_666b1bf7394bbc91c15fac27d45952a0
          // 10.1016/0167-6687(83)90020-3 ---resolves to---> http://www.sciencedirect.com/science/article/pii/0167668783900203#
          //                              ---follow link (from campus)---> http://pdn.sciencedirect.com/science?_ob=MiamiImageURL&_cid=271685&_user=554534&_pii=0167668783900203&_check=y&_origin=article&_zone=toolbar&_coverDate=30-Apr-1983&view=c&originContentFamily=serial&wchp=dGLbVlt-zSkzS&md5=729643f534e9cc7abe5882e10cca9e40&pid=1-s2.0-0167668783900203-main.pdf
          // 								---follow link (off campus)----> http://pdn.sciencedirect.com/science?_ob=ShoppingCartURL&_method=add&_eid=1-s2.0-0167668783900203&originContentFamily=serial&_origin=article&_acct=C000228598&_version=1&_userid=10&_ts=1365482216&md5=ecfe2869e3c92d58e7f05c5762d02d90
          case url if url.startsWith("http://dx.doi.org/10.1006") || url.startsWith("http://dx.doi.org/10.1016") => {
            // If we're not logged in, it's not going to work. Just use the HttpClientSlurp, and don't make any attempt to save the answer.
            val regex = """pdfurl="([^"]*)"""".r
            regex.findFirstMatchIn(HttpClientSlurp(url).mkString("\n")).map(m => m.group(1)).orElse(searchElsevierForPDFURL)
          }
          // Cambridge University Press
          // 10.1017 10.1051
          // 10.1017/S0022112010001734 ---resolves to---> http://journals.cambridge.org/action/displayAbstract?fromPage=online&aid=7829674
          //						   ---follow "View PDF (" (or jQuery for "a.article-pdf")---> http://journals.cambridge.org/action/displayFulltext?type=1&fid=7829676&jid=FLM&volumeId=655&issueId=-1&aid=7829674&bodyId=&membershipNumber=&societyETOCSession=
          //						   ---resolves to something like---> http://journals.cambridge.org/download.php?file=%2FFLM%2FFLM655%2FS0022112010001734a.pdf&code=ac265aacb742b93fa69d566e33aeaf5e
          // We also need to grab some 10.1112 DOIs, for LMS journals run by CMP e.g. 10.1112/S0010437X04001034
          case url if url.startsWith("http://dx.doi.org/10.1017/S") || url.startsWith("http://dx.doi.org/10.1017/is") || url.startsWith("http://dx.doi.org/10.1051/S") || url.startsWith("http://dx.doi.org/10.1112/S0010437X") || url.startsWith("http://dx.doi.org/10.1112/S14611570") || url.startsWith("http://dx.doi.org/10.1112/S00255793") => {
            val regex = """<a href="([^"]*)"[ \t\n]*title="View PDF" class="article-pdf">""".r
            regex.findFirstMatchIn(HttpClientSlurp(url).mkString("\n")).map(m => "http://journals.cambridge.org/action/" + m.group(1).replaceAll("\n", "").replaceAll("\t", "").replaceAll(" ", ""))
          }

          // Wiley
          // 10.1002/(SICI)1097-0312(199602)49:2<85::AID-CPA1>3.0.CO;2-2 ---resolves to---> http://onlinelibrary.wiley.com/doi/10.1002/(SICI)1097-0312(199602)49:2%3C85::AID-CPA1%3E3.0.CO;2-2/abstract
          // 															 ---links to--->    http://onlinelibrary.wiley.com/doi/10.1002/(SICI)1097-0312(199602)49:2%3C85::AID-CPA1%3E3.0.CO;2-2/pdf
          //															 ---???---> http://onlinelibrary.wiley.com/store/10.1002/(SICI)1097-0312(199602)49:2%3C85::AID-CPA1%3E3.0.CO;2-2/asset/1_ftp.pdf?v=1&t=hfc3fjoo&s=dc6fad69f11cfc2ff2f302f5d1386c553d48f47c
          // the mystery step here is somewhat strange; it looks like it contains an iframe, but then redirects (via javascript) to the contents of the iframe?
          // anyway, the following scraping seems to work
          case url if url.startsWith("http://dx.doi.org/10.1002/") => {
            val regex = """id="pdfDocument" src="([^"]*)"""".r
            val url2 = "http://onlinelibrary.wiley.com/doi/" + url.stripPrefix("http://dx.doi.org/") + "/pdf"
            val slurped = HttpClientSlurp(url2).mkString("\n")
            regex.findFirstMatchIn(slurped).map(m => m.group(1))
          }

          // ACM
          case url if url.startsWith("http://dx.doi.org/10.1145/") => {
            val regex = """title="FullText Pdf" href="(ft_gateway\.cfm\?id=[0-9]*&type=pdf&CFID=[0-9]*&CFTOKEN=[0-9]*)"""".r
            regex.findFirstMatchIn(HttpClientSlurp.getString("http://dl.acm.org/citation.cfm?doid=" + url.drop(26))).map(m => m.group(1)).map("http://dl.acm.org/" + _)
          }

          // otherwise, try using DOI-direct
          case url if url.startsWith("http://dx.doi.org/") => {
            Http.findRedirect(url.replaceAllLiterally("http://dx.doi.org/", "http://evening-headland-2959.herokuapp.com/")) match {
              case None => None
              case Some(redirect) if redirect.startsWith("http://dx.doi.org/") => {
                // didn't learn anything
                None
              }
              case Some(redirect) => Some(redirect)
            }
          }
          case url if url.startsWith("http://projecteuclid.org/getRecord?id=") => {
            Some(url.replaceAllLiterally("http://projecteuclid.org/getRecord?id=", "http://projecteuclid.org/DPubS/Repository/1.0/Disseminate?view=body&id=pdf_1&handle="))
          }
          case url if url.startsWith("http://www.numdam.org/item?id=") => {
            Some(url.replaceAllLiterally("http://www.numdam.org/item?id=", "http://archive.numdam.org/article/") + ".pdf")
          }
          case url if url.startsWith("http://aif.cedram.org/item?id=") => {
            Some(url.replaceAllLiterally("http://aif.cedram.org/item?id=", "http://aif.cedram.org/cedram-bin/article/") + ".pdf")
          }
          case url if url.startsWith("http://muse.jhu.edu/journals/american_journal_of_mathematics/") => Some(url)
        }
      }
    }
  }

  def pdfInputStream: Option[InputStream] = {
    val result = if (DOI.nonEmpty && DOI.get.startsWith("10.1215")) {
      // Duke expects to see a Referer field.
      pdfURL.map({ u =>
        HttpClientSlurp.getStream(u, referer = Some(u))
      })
    } else {
      pdfURL.map(HttpClientSlurp.getStream)
    }
    result.orElse(exceptionalPDF)
  }

  private def exceptionalPDF: Option[InputStream] = {
    val path = Paths.get(System.getProperty("user.home") + "/media/exceptions/").resolve(identifierString + ".pdf")
    if (Files.exists(path)) {
      Some(new FileInputStream(path.toFile))
    } else {
      None
    }
  }

  def pdf: Option[Array[Byte]] = pdfInputStream.flatMap(PDF.getBytes)

  val defaultFilenameTemplate = "$TITLE - $AUTHOR - $JOURNALREF - $MRNUMBER.pdf"

  def plainTitle: String = {
    def preprocessAccents(s: String) = {
      s.replaceAllLiterally("""\Dbar""", "Đ")
        .replaceAllLiterally("""\soft{L}""", "Ľ")
        .replaceAllLiterally("""\cfac""", """\~""")
        .replaceAllLiterally("""\cftil{e}""", "ễ")
        .replaceAllLiterally("""\cftil{o}""", "ỗ")
    }

    preprocessAccents(title).replaceAll("""\[[^]]*MR[^]]*\]""", "").replaceAll("""\[[^]]*refcno[^]]*\]""", "")
  }

  def textTitle: String = {
    def stripMoreLaTeX(s: String) = {
      val r = "\\{\\\\(rm|bf|scr|Bbb|bold) ([A-Za-z]*)\\}".r
      r.replaceAllIn(s, m => m.group(2))
        .replaceAllLiterally("""\ast""", "*")
        .replaceAllLiterally("""\bold """, "")
        .replaceAllLiterally("""\bf """, "")
        .replaceAllLiterally("""\Bbb """, "")
        .replaceAllLiterally("""\scr """, "")
        .replaceAllLiterally("""\rm """, "")
    }
    stripMoreLaTeX(pandoc.latexToText(plainTitle)).stripPrefix(".")
  }

  def sanitizedTitle = textTitle.replaceAllLiterally("/", "⁄") // scary UTF-8 character that just *looks* like a forward slash
    .replaceAllLiterally(":", "꞉") // scary UTF-8 character that just *looks* like a colon

  def wikiTitle = {
    def pandocFragment(f: String) = {
      val f0 = f.replaceAllLiterally("{", "").replaceAllLiterally("}", "")
      (if (f0.startsWith(" ")) " " else "") +
        pandoc.latexToText(f0) +
        (if (f0.endsWith(" ")) " " else "")
    }
    plainTitle.split("\\$").grouped(2).map({ p =>
      pandocFragment(p(0)) +
        (p.tail.headOption match {
          case Some(q) => "$" + q
          case None => ""
        })
    }).mkString("$")
  }

  def constructFilename(filenameTemplate: String = defaultFilenameTemplate) = {
    val authorNames = authors.map(a => pandoc.latexToText(a.name))
    val textCitation = pandoc.latexToText(citation)

    ({
      val attempt = filenameTemplate
        .replaceAllLiterally("$TITLE", sanitizedTitle)
        .replaceAllLiterally("$AUTHOR", authorNames.mkString(" and "))
        .replaceAllLiterally("$JOURNALREF", textCitation)
        .replaceAllLiterally("$MRNUMBER", identifierString)
      if (attempt.getBytes().length > 250) {
        val shortAuthors = if (authors.size > 4) {
          authorNames.head + " et al."
        } else {
          authorNames.mkString(" and ")
        }
        val maxCitationLength = scala.math.max(90, 250 - (filenameTemplate
          .replaceAllLiterally("$AUTHOR", shortAuthors)
          .replaceAllLiterally("$MRNUMBER", identifierString)
          .replaceAllLiterally("$TITLE", sanitizedTitle).getBytes().length - "$JOURNALREF".size))
        val shortCitation = if (textCitation.getBytes().length > maxCitationLength) {
          textCitation.reverse.tails.find(_.getBytes.length + 3 <= maxCitationLength).get.reverse + "..."
        } else {
          textCitation
        }
        val partialReplacement = filenameTemplate
          .replaceAllLiterally("$AUTHOR", shortAuthors)
          .replaceAllLiterally("$JOURNALREF", shortCitation)
          .replaceAllLiterally("$MRNUMBER", identifierString)
        val maxTitleLength = 250 - (partialReplacement.getBytes().length - "$TITLE".size)
        val shortTitle = if (sanitizedTitle.getBytes().length > maxTitleLength) {
          sanitizedTitle.reverse.tails.find(_.getBytes.length + 3 <= maxTitleLength).get.reverse + "..."
        } else {
          sanitizedTitle
        }
        partialReplacement.replaceAllLiterally("$TITLE", shortTitle).ensuring(_.getBytes().length <= 250)
      } else {
        attempt
      }
    })
  }

  def savePDF(directory: File, filenameTemplate: String = defaultFilenameTemplate) {
    val fileName = constructFilename(filenameTemplate)
    val file = new File(directory, fileName)
    val existingFiles = Article.fileNamesCache(Paths.get(directory.toURI)).find(_.contains(identifierString))
    if (existingFiles.nonEmpty) {
      Logging.info("PDF for " + identifierString + " already exists in " + directory + ", renaming...")
      Logging.info("  " + existingFiles.get)
      Logging.info("  " + fileName)
      Files.move(Paths.get(directory.toURI).resolve(existingFiles.get), Paths.get(file.toURI))
    } else {
      Logging.info("Downloading PDF from " + pdfURL + " ...")
      pdf match {
        case Some(bytes) => {
          Logging.info("Saving PDF to " + fileName)
          FileUtils.writeByteArrayToFile(file, bytes)
        }
        case None => {
          Logging.info("No PDF available for " + fileName)
        }
      }
    }
  }
}

object Article {
  def apply(identifierString: String): Article = {
    apply(identifierString.stripPrefix("MR").toInt)
  }

  def apply(identifier: Int): Article = {
    import scala.slick.driver.MySQLDriver.simple._
    val lookup = SQL { implicit session =>
      (for (
        a <- SQLTables.mathscinet;
        if a.MRNumber === identifier
      ) yield a).firstOption
    }

    lookup.getOrElse({
      val identifier_ = identifier
      new Article {
        override val identifier = identifier_
      }
    })
  }

  def fromDOI(doi: String): Option[Article] = {
    val result = AnonymousS3("DOI2mathscinet").get(doi).map({ identifierString =>
      val identifier_ = identifierString.stripPrefix("MR").toInt
      new Article {
        override val identifier = identifier_
        override val DOI = Some(doi)
      }
    })
    if (result.isEmpty) {
      Logging.info("No mathscinet entry found for DOI: " + doi)
    }
    result
  }

  def fromBibtex(bibtexString: String): Option[Article] = {
    BIBTEX.parse(bibtexString).map({
      case b @ BIBTEX(_, identifierString @ MRIdentifier(id), data) =>
        if (saving_?) b.save
        val result = new Article {
          override val identifier = id
        }
        result.bibtexData = Some(b)
        result
    })
  }

  val ElsevierSlurpCache = {
    import net.tqft.toolkit.functions.Memo._
    { url: String => HttpClientSlurp.apply(url).toList }.memo
  }

  private var saving_? = false
  def disableBibtexSaving { saving_? = false }
  def enableBibtexSaving { saving_? = true }

  val fileNamesCache = {
    import net.tqft.toolkit.functions.Memo._
    import scala.collection.JavaConverters._

    { path: Path => Files.newDirectoryStream(path).iterator.asScala.map(_.getFileName.toString).toSet }.memo
  }
}

object MRIdentifier {
  def unapply(s: String): Option[Int] = {
    import net.tqft.toolkit.Extractors.Int
    s.stripPrefix("MR") match {
      case Int(id) => Some(id)
      case _ => None
    }
  }
}

object Articles {

  def apply(identifierStrings: Traversable[String]): Map[String, Article] = {
    def apply(identifiers: Traversable[Int]): Map[Int, Article] = {
      import scala.slick.driver.MySQLDriver.simple._
      SQL { implicit session =>
        (for (
          a <- SQLTables.mathscinet;
          if a.MRNumber.inSet(identifiers)
        ) yield a).list.map(a => a.identifier -> a).toMap
      }
    }
    apply(identifierStrings.collect({ case MRIdentifier(id) => id })).map(p => p._2.identifierString -> p._2)
  }

  def withCachedBIBTEX: Iterator[Article] = {
    BIBTEX.cache.keysIterator.collect({ case MRIdentifier(id) => Article(id) })
  }

  def fromBibtexFile(file: String): Iterator[Article] = {
    import net.tqft.toolkit.collections.Split._
    Source.fromFile(file).getLines.splitOn(_.isEmpty).map(_.mkString("\n")).grouped(100).flatMap(_.par.flatMap(Article.fromBibtex))
  }

  private def filesInDirectory(directory: String) = {
    val process = Runtime.getRuntime().exec(List("ls", "-f", directory).toArray)
    Source.fromInputStream(process.getInputStream()).getLines.filterNot(_.startsWith(".")).map(directory + _)
  }

  def fromBibtexDirectory(directory: String): Iterator[Article] = {
    for (file <- filesInDirectory(directory); if file.endsWith(".bib"); article <- fromBibtexFile(file)) yield {
      article
    }
  }

  def fromBibtexGzipFile(file: String): Iterator[Article] = {
    import net.tqft.toolkit.collections.Split._
    Source.fromInputStream(new GZIPInputStream(new FileInputStream(file))).getLines.splitOn(_.isEmpty).map(_.mkString("\n")).grouped(100).flatMap(_.par.flatMap(Article.fromBibtex))
  }

  def withDOIPrefix(prefix: String): Iterator[Article] = {
    val pool = new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(50))
    for (group <- AnonymousS3("DOI2mathscinet").keysWithPrefix(prefix).iterator.grouped(1000); doi <- { val p = group.par; p.tasksupport = pool; p }; article <- Article.fromDOI(doi)) yield article
  }

}