package net.tqft.arxiv2

import net.tqft.util.PDF
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import scala.collection.mutable.ListBuffer
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.io.IOUtils
import java.io.File
import java.nio.file.Paths
import java.nio.file.Files
import scala.sys.process.ProcessBuilder.Source
import scala.io.Source
import net.tqft.citationsearch.CitationScore
import net.tqft.util.pandoc
import java.io.IOException
import net.tqft.util.HttpClientSlurp
import java.io.BufferedInputStream
import org.apache.commons.io.FileUtils
import net.tqft.toolkit.Logging
import net.tqft.util.Throttle

trait Sources {
  def rawSourceFile(identifier: String): Option[Array[Byte]]

  protected def processBytes(identifier: String, bytes: Array[Byte]) = {
    PDF.getBytes(new ByteArrayInputStream(bytes)) match {
      case Some(pdfBytes) => Map(identifier + ".pdf" -> pdfBytes)
      case None => {
        try {
          val is = try {
            new GZIPInputStream(new ByteArrayInputStream(bytes))
          } catch {
            case e: IOException => {
              require(e.getMessage == "Not in GZIP format", "Unexpected IOException: " + e.getMessage)
              new ByteArrayInputStream(bytes)
            }
          }
          val tis = new TarArchiveInputStream(is)
          var entry: ArchiveEntry = null
          val buffer = ListBuffer[(String, Array[Byte])]()
          while ({ entry = tis.getNextEntry; entry != null }) {
            if (!entry.isDirectory) {
              buffer += ((entry.getName, IOUtils.toByteArray(tis)))
            }
          }
          buffer.toMap
        } catch {
          case e: IOException => {
            require(e.getMessage == "Error detected parsing the header", "Unexpected IOException: " + e.getMessage)
            val is = try {
              new GZIPInputStream(new ByteArrayInputStream(bytes))
            } catch {
              case e: IOException => {
                require(e.getMessage == "Not in GZIP format", "Unexpected IOException: " + e.getMessage)
                new ByteArrayInputStream(bytes)
              }
            }
            Map(identifier + ".tex" -> IOUtils.toByteArray(is))
          }
        }
      }
    }
  }

  def apply(identifier: String): Map[String, Array[Byte]] = {
    rawSourceFile(identifier) match {
      case None        => Map.empty
      case Some(bytes) => processBytes(identifier, bytes)
    }
  }
  def bibitems(identifier: String): Seq[(String, String)] = {
    val lines = apply(identifier).collect({ case (name, bytes) if name.toLowerCase.endsWith(".tex") || name.toLowerCase.endsWith(".bbl") => Source.fromBytes(bytes).getLines }).iterator.flatten.toStream
    var state = false
    val bibitemLines = lines.filter(line => !line.trim.startsWith("%")).filter({ line =>
      // TODO allow whitespace after 'begin' and 'end'
      if (line.contains("\\begin{thebibliography}")) {
        state = true
        false
      } else if (line.contains("\\end{thebibliography}")) {
        state = false
        false
      } else {
        state
      }
    }).toStream

    if (bibitemLines.isEmpty) {
      if (lines.contains("% $ biblatex auxiliary file $")) {
        // Try parsing it as biblatex
        parse_biblatex(lines)
      } else {
        println("No bibliography found for " + identifier)
        ???
      }
    } else {

      val buffer = ListBuffer[(String, String)]()
      var key: String = null
      val entryBuffer = ListBuffer[String]()
      def post {
        if (key != null) {
          val latex = entryBuffer
            .filter(_.trim.nonEmpty)
            .map(line =>
              line
                .replaceAllLiterally("\\newblock", "")
                .replaceAllLiterally("{\\em ArXiv Mathematics e-prints}", "")
                .replaceAllLiterally("{\\em ArXiv e-prints}", "")).mkString("\n")
          val text = pandoc.latexToText(latex)
          buffer += ((key, text))
        }
        entryBuffer.clear
      }
      for (line <- bibitemLines.map(_.trim)) {
        if (line.startsWith("\\bibitem")) {
          post

          val tail = line.stripPrefix("\\bibitem").trim

          val (key_, remainder) = {
            if (tail.startsWith("{")) {
              (
                tail.stripPrefix("{").takeWhile(_ != '}'),
                tail.stripPrefix("{").dropWhile(_ != '}').stripPrefix("}"))
            } else if (tail.startsWith("[")) {
              (
                tail.stripPrefix("[").takeWhile(_ != ']').stripPrefix("{").stripSuffix("}"),
                tail.stripPrefix("[").dropWhile(_ != ']').stripPrefix("[").dropWhile(_ != '}').stripPrefix("}"))
            } else {
              println("I don't know how to parse this bibitem line:")
              println(line)
              ???
            }
          }

          key = key_
          entryBuffer += remainder
        } else {
          entryBuffer += line
        }
      }
      post
      buffer.toSeq
    }
  }

  def parse_biblatex(lines: Seq[String]): Seq[(String, String)] = {
    import net.tqft.toolkit.collections.Split._
    implicit class Regex(sc: StringContext) {
      def r = new util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    for (chunk <- lines.splitBefore(_ == "% $ biblatex auxiliary file $").toStream.apply(1).splitBefore(line => line.trim.startsWith("\\entry") || line.trim.startsWith("\\endsortlist")).toStream.init.tail) yield {
      val key = chunk.head.trim.stripPrefix("\\entry{").takeWhile(_ != '}')
      val fields = Seq("title", "journaltitle", "year", "number", "volume", "pages", "note")
      val prefixes = Seq("family=", "given=") ++ fields.map(f => "\\field{" + f + "}")
      val text = (for (
        p <- prefixes;
        line <- chunk.tail.map(_.trim);
        if line.startsWith(p);
        data = line.stripPrefix(p + "{").stripSuffix(",").stripSuffix("}")
      ) yield {
        data.replaceAllLiterally("\\bibnamedelima", "").replaceAllLiterally("\\bibnamedelimi", "").replaceAllLiterally("\\bibrangedash ", "-")
      })
      (key, text.mkString(" "))
    }
  }

  def referencesResolved(identifier: String) = bibitems(identifier).par.map({
    case (key, text) =>
      (key, text, net.tqft.citationsearch.Search.goodMatch(pandoc.latexToText(text)))
  }).seq

  def referencesResolvedToMathSciNet(identifier: String) = referencesResolved(identifier).par.map(t => (t._1, t._2, t._3.flatMap(_.citation.MRNumber).map(net.tqft.mathscinet.Article.apply))).seq
}

trait SourcesFromLocalCopy extends Sources {
  def basePath: String
  override def rawSourceFile(identifier: String) = {
    val path = Paths.get(basePath).resolve(identifier.split("/").last.take(4))
    val stub = identifier.replaceAllLiterally("/", "")
    val gz = path.resolve(stub + ".gz")
    val pdf = path.resolve(stub + ".pdf")
    if (Files.exists(gz)) {
      Some(IOUtils.toByteArray(gz.toUri()))
    } else if (Files.exists(pdf)) {
      Some(IOUtils.toByteArray(pdf.toUri()))
    } else {
      None
    }
  }
}

trait SourcesFromArxiv extends Sources {
  def basePath: String
  abstract override def rawSourceFile(identifier: String): Option[Array[Byte]] = {
    super.rawSourceFile(identifier) match {
      case Some(bytes) => Some(bytes)
      case None => {
        try {
          val path = Paths.get(basePath).resolve(identifier.split("/").last.take(4))
          val stub = identifier.replaceAllLiterally("/", "")

          Logging.info("Downloading sources from the arxiv for " + identifier)
          val url = "https://arxiv.org/e-print/" + identifier
          Throttle(url)
          val bytes = IOUtils.toByteArray(new BufferedInputStream(HttpClientSlurp.getStream(url)))

          val files = processBytes(identifier, bytes)
          val file = if (files.keySet == Set(identifier + ".pdf")) {
            path.resolve(stub + ".pdf").toFile
          } else {
            path.resolve(stub + ".gz").toFile
          }

          FileUtils.writeByteArrayToFile(file, bytes)

          Some(bytes)
        } catch {
          case e: Exception => {
            Logging.info("Caught " + e.getMessage + " while downloading " + identifier)
            None
          }
        }
      }
    }

  }
}

object Sources extends SourcesFromLocalCopy with SourcesFromArxiv {
  override val basePath = "/Users/scott/scratch/arxiv/"
  //  override val basePath = "/Users/scott/scratch/arxiv/"
}
