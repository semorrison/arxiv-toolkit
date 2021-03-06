package net.tqft.util
import scala.io.Source
import java.util.Date
import net.tqft.toolkit.Logging
import java.net.URL
import org.apache.commons.io.IOUtils
import net.tqft.toolkit.amazon.S3
import net.tqft.toolkit.amazon.AnonymousS3
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.params.HttpProtocolParams
import org.openqa.selenium.WebDriver
import org.openqa.selenium.firefox.FirefoxDriver
import scala.util.Random
import java.io.IOException
import org.apache.http.HttpException
import org.openqa.selenium.By
import java.io.BufferedInputStream
import com.ibm.icu.text.CharsetDetector
import java.nio.charset.UnsupportedCharsetException
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import com.gargoylesoftware.htmlunit.BrowserVersion
import org.apache.http.impl.conn.SchemeRegistryFactory
import org.apache.http.params.HttpConnectionParams
import org.apache.http.impl.client.DecompressingHttpClient
import java.io.FilterInputStream
import org.openqa.selenium.firefox.FirefoxProfile
import org.apache.http.impl.conn.PoolingClientConnectionManager
import scala.annotation.tailrec
import net.tqft.scopus.Scopus
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import scala.io.Codec
import net.tqft.webofscience.WebOfScience
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.cookie.BasicClientCookie
import org.apache.http.impl.client.HttpClientBuilder
import net.tqft.sciencedirect.ScienceDirect

trait Slurp {
  def getStream(url: String): InputStream = new URL(url).openStream
  final def getBytes(url: String) = IOUtils.toByteArray(getStream(url))

  def quit
  
  def apply(url: String): Iterator[String] = {
    val bis = new BufferedInputStream(getStream(url))
    val cd = new CharsetDetector()
    cd.setText(bis)
    val cm = cd.detect()

    if (cm != null) {
      val reader = cm.getReader()
      val charset = cm.getName()
      val codec = Codec(charset)
      codec.onMalformedInput(CodingErrorAction.REPLACE)
      codec.onUnmappableCharacter(CodingErrorAction.REPLACE)
      //      Logging.info("reading stream in charset " + charset)

      class ClosingIterator[A](i: Iterator[A]) extends Iterator[A] {
        var open = true

        override def hasNext = {
          open && i.hasNext match {
            case true => true
            case false => {
              open = false
              bis.close
              false
            }
          }
        }
        override def next = i.next
      }

      new ClosingIterator(Source.fromInputStream(bis)(codec).getLines): Iterator[String]
    } else {
      throw new UnsupportedCharsetException("")
    }
  }
  final def attempt(url: String): Either[Iterator[String], Throwable] = {
    try {
      Left(apply(url))
    } catch {
      case e @ (_: IOException | _: HttpException | _: IllegalStateException) => {
        Right(e)
      }
    }
  }

  def getString(url: String) = apply(url).mkString("\n")
}

trait HttpClientSlurp extends Slurp {
  val cxMgr = new PoolingClientConnectionManager(SchemeRegistryFactory.createDefault());
  cxMgr.setMaxTotal(100);
  cxMgr.setDefaultMaxPerRoute(20);

  val client: HttpClient = new DecompressingHttpClient(new DefaultHttpClient(cxMgr))

  override def quit = { /* nothing to do */ }
  
  val cookieStore = new BasicCookieStore();
  val cookie = new BasicClientCookie("SD_REMOTEACCESS", "92845a49cfd32a5b71ae0b4dcea2f3c130924b6c5014f5d667046e31a9efda345801c40c2ba552b6181d7e037d3e94263fd9f927ef262ddd3d9781711d2637cbde8f8e7e00fe62dbe4895322066480c289bcb8a14a5ea018604b02ff5ce9f9eafd98f637b12d7c55");
  cookie.setDomain(".sciencedirect.com")
  cookie.setPath("/")
  cookieStore.addCookie(cookie);

  val params = client.getParams()
  params.setBooleanParameter("http.protocol.handle-redirects", true)
  HttpConnectionParams.setConnectionTimeout(params, 20000);
  HttpConnectionParams.setSoTimeout(params, 20000);

  def useragent = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0)"
  HttpProtocolParams.setUserAgent(params, useragent);

  override def getStream(url: String) = getStream(url, None)
  def getStream(url: String, referer: Option[String]): InputStream = {
    //    println("HttpClient slurping: " + url)

    val get = new HttpGet(url)
    get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    referer.map(r => get.setHeader("Referer", r))
//    if (url.contains("sciencedirect.com")) {
//      get.setHeader("Cookie", "SD_REMOTEACCESS=92845a49cfd32a5b71ae0b4dcea2f3c130924b6c5014f5d667046e31a9efda345801c40c2ba552b6181d7e037d3e94263fd9f927ef262ddd3d9781711d2637cbde8f8e7e00fe62dbe4895322066480c289bcb8a14a5ea018604b02ff5ce9f9eafd98f637b12d7c55")
//    }

    val response = client.execute(get);

    class ReleasingInputStream(is: InputStream) extends FilterInputStream(is) {
      override def close() {
        get.releaseConnection()
        is.close()
      }
    }

    new ReleasingInputStream(response.getEntity.getContent())
  }
}

object HttpClientSlurp extends HttpClientSlurp

trait FirefoxSlurp extends Slurp {
  private def driver = FirefoxSlurp.driverInstance

  override def quit: Unit = FirefoxSlurp.quit
  
  var throttle = 0
  
  override def getStream(url: String) = {
    import scala.collection.JavaConverters._

    if (FirefoxSlurp.enabled_?) {
      try {
        if (url.startsWith("http://www.scopus.com/")) Scopus.preload
        if (url.startsWith("http://apps.webofknowledge.com/")) WebOfScience.preload
        if (url.startsWith("http://www.sciencedirect.com/")) ScienceDirect.preload

        
        val link = try{
          driver.findElements(By.cssSelector("""a[href="""" + url + """"]""")).asScala.headOption
        } catch {
          case e: Exception => None
        }
        link match {
          case Some(element) => {
            Logging.info("webdriver: clicking an available link")
            element.click()
          }
          case None => driver.get(url)
        }

        // TODO more validation we really arrived?
        driver.getTitle match {
          case e @ ("502 Bad Gateway" | "500 Internal Server Error" | "503 Service Temporarily Unavailable") => {
            Logging.error("Exception accessing " + url, new HttpException(e))
            //            if (throttle == 0) throttle = 5000 else throttle *= 2
            //            if (throttle < 20000 && throttle > 0) {
            //              Thread.sleep(throttle)
            //              getStream(url)
            //            } else {
            throw new HttpException(e)
            //            }
          }
          case e @ ("MathSciNet Access Error") => throw new HttpException("403 " + e)
          case _ =>
        }
        throttle = 0
        if(url.contains("ams.org")) Thread.sleep(5000)
        val pageSource = {
          val firstAttempt = driver.getPageSource
          if(url.contains("ams.org") && firstAttempt.contains("setCookie( 'hasJavascript', 1 )")) {
            Logging.info("Waiting on javascript at ams.org")
            Thread.sleep(3000)
            driver.getPageSource
          } else {
            firstAttempt
          }
        }
        new ByteArrayInputStream(pageSource.getBytes("UTF-8"))
      } catch {
        case e @ (_: org.openqa.selenium.NoSuchWindowException | _: org.openqa.selenium.remote.UnreachableBrowserException | _: org.openqa.selenium.remote.ErrorHandler$UnknownServerException) => {
          Logging.warn("Browser window closed, trying to restart Firefox/webdriver")
          FirefoxSlurp.quit
          Logging.info("retrying ...")
          getStream(url)
        }
        case e @ (_: org.openqa.selenium.TimeoutException) => {
          Logging.warn("Timeout, sleeping then trying again.")
          Thread.sleep(60 * 1000)
          Logging.info("retrying ...")
          getStream(url)
        }
      }
    } else {
      throw new IllegalStateException("slurping via Selenium has been disabled, but someone asked for a URL: " + url)
    }
  }
}

trait HtmlUnitSlurp extends Slurp {
  private def driver = HtmlUnitSlurp.driverInstance

  override def getStream(url: String) = {
    import scala.collection.JavaConverters._

    if (HtmlUnitSlurp.enabled_?) {
      try {
        driver.findElements(By.cssSelector("""a[href="""" + url + """"]""")).asScala.headOption match {
          case Some(element) => {
            Logging.info("webdriver: clicking an available link")
            element.click()
          }
          case None => driver.get(url)
        }

        // TODO more validation we really arrived?
        driver.getTitle match {
          case e @ ("502 Bad Gateway" | "500 Internal Server Error" | "503 Service Temporarily Unavailable") => throw new HttpException(e)
          case e @ ("MathSciNet Access Error") => throw new HttpException("403 " + e)
          case _ =>
        }
        new ByteArrayInputStream(driver.getPageSource.getBytes("UTF-8"))
      } catch {
        case e: Exception => {
          e.printStackTrace()
          Logging.warn("Browser window closed, trying to restart HtmlUnit")
          HtmlUnitSlurp.quit
          Logging.info("retrying ...")
          getStream(url)
        }
      }
    } else {
      throw new IllegalStateException("slurping via HtmlUnit has been disabled, but someone asked for a URL: " + url)
    }
  }

}

object HtmlUnitSlurp extends HtmlUnitSlurp {
  private var driverOption: Option[WebDriver] = None

  def driverInstance = {
    if (driverOption.isEmpty) {
      Logging.info("Starting HtmlUnit/webdriver")
      driverOption = Some(new HtmlUnitDriver(BrowserVersion.FIREFOX_52))
      java.util.logging.Logger.getLogger("com.gargoylesoftware").setLevel(java.util.logging.Level.OFF)
      Logging.info("   ... finished starting HTMLUnit")
    }
    driverOption.get
  }

  override def quit = {
    driverOption.map(_.quit)
    driverOption = None
  }

  private var enabled = true
  //  def disable = enabled = false
  def enabled_? = enabled
}

object FirefoxSlurp extends FirefoxSlurp {
  private var driverOption: Option[WebDriver] = None

  def driverInstance = {
    if (driverOption.isEmpty) {
      Logging.info("Starting Firefox/webdriver")
      //      val profile = new FirefoxProfile();
      //      profile.setPreference("network.proxy.socks", "localhost");
      //      profile.setPreference("network.proxy.socks_port", "1081");
      //      profile.setPreference("network.proxy.type", 1)
      System.setProperty("webdriver.gecko.driver", "/Users/scott/bin/geckodriver");
      driverOption = Some(new FirefoxDriver( /*profile*/ ))
      Logging.info("   ... finished starting Firefox")
    }
    driverOption.get
  }

  override def quit = {
    try {
      driverOption.map(_.quit)
    } catch {
      case e: Exception => Logging.error("Exception occurred while trying to quit Firefox:", e)
    }
    driverOption = None
  }

  private var enabled = true
  def disable = {
    Logging.warn("Disabling FirefoxSlurp")
    enabled = false
  }
  def enabled_? = enabled
}

trait MathSciNetMirrorSlurp extends Slurp {
  private var offset = Random.nextInt(10 * 60 * 1000)
  val mirrorList = Random.shuffle(List("www.ams.org" /*"ams.rice.edu",*/ /* "ams.impa.br",*/ /* "ams.math.uni-bielefeld.de", */ /*"ams.mpim-bonn.mpg.de",*/ /*"ams.u-strasbg.fr"*/ ))
  def mirror = mirrorList((((new Date().getTime() + offset) / (10 * 60 * 1000)) % mirrorList.size).toInt)

  override def getStream(url: String) = {
    val newURL = if (url.startsWith("http://www.ams.org/mathscinet") && !url.startsWith("http://www.ams.org/mathscinet-mref")) {
      "http://" + mirror + "/mathscinet" + url.stripPrefix("http://www.ams.org/mathscinet")
    } else {
      url
    }
    //    try {
    super.getStream(newURL)
    //    } catch {
    //      case e: HttpException => {
    //        offset = Random.nextInt(10 * 60 * 1000)
    //        getStream(url)
    //      }
    //    }
  }
}

trait ScopusSlurp extends CachingSlurp {
  override def apply(url: String) = {
    super.apply(url).map({ line =>
      if (line.contains("Scopus cannot re-create the page that you bookmarked.")) {
        -=(url)
        throw new Exception("Scopus won't allow bookmarking this page! (I've attempted to remove the result from the cache.)")
      }
      line
    })
  }
}

trait CachingSlurp extends Slurp {
  protected def cache(url: String): scala.collection.mutable.Map[String, Array[Byte]]

  var overwriteCache = false

  override def getStream(url: String) = {
    Logging.info("Looking in cache for " + url)

    def retrieve = {
      Throttle(url)
      Logging.info("Loading " + url)
      val result = IOUtils.toByteArray(super.getStream(url))
      Logging.info("   ... finished")
      result
    }

    val bytes = if (overwriteCache) {
      Logging.warn("Overwriting cache entry for: " + url)
      val result = retrieve
      cache(url).put(url, result)
      result
    } else {
      cache(url).getOrElseUpdate(url, retrieve)
    }
    new ByteArrayInputStream(bytes)
  }

  def -=(url: String): this.type = {
    cache(url) -= url
    this
  }
}

trait S3CachingSlurp extends CachingSlurp {
  def s3: S3
  def bucketSuffix: String

  private val caches = {
    import net.tqft.toolkit.functions.Memo
    Memo({ hostName: String =>
      {
        import net.tqft.toolkit.collections.MapTransformer._
        s3.bytes(hostName + bucketSuffix).transformKeys({ relativeURL: String => "http://" + hostName + "/" + relativeURL }, { absoluteURL: String => new URL(absoluteURL).getFile().stripPrefix("/") })
      }
    })
  }

  //  def -=(url: String): this.type = {
  //    import net.tqft.toolkit.collections.MapTransformer._
  //    val hostName = new URL(url).getHost
  //    s3.bytes(hostName + bucketSuffix).transformKeys({ relativeURL: String => "http://" + hostName + "/" + relativeURL }, { absoluteURL: String => new URL(absoluteURL).getFile().stripPrefix("/") }) -= url
  //    this
  //  }

  override def cache(url: String) = {
    val hostName = new URL(url).getHost
    caches(hostName)
  }
}

trait ThrottledSlurp extends Slurp {
  override def getStream(url: String) = {
    Throttle(url)
    super.getStream(url)
  }
}

object Throttle extends Logging {
  val defaultInterval = 1000
  val hostIntervals = scala.collection.mutable.Map("ams.org" -> 60000, "scopus.com" -> 30000, "arxiv.org" -> 5000, "google.com" -> 500, "scholar.google.com" -> 500, "zbmath.org" -> 10000)
  val lastThrottle = scala.collection.mutable.Map[String, Long]().withDefaultValue(0)

  // poisson distributed gaps
  def exponentialDistribution(mean: Int) = {
    (-mean * (Math.log(1.0 - scala.util.Random.nextDouble())))
  }
  def normalDistribution = {
    import scala.math._
    import scala.util.Random.nextDouble
    sqrt(-2 * log(nextDouble)) * cos(2 * Pi * nextDouble)
  }
  def logNormalDistribution(mean: Double, shape: Double = 1) = {
    import scala.math._
    val sigma = sqrt(shape)
    val mu = log(mean) - shape / 2
    exp(mu + sigma * normalDistribution)
  }

  def apply(url: String) {
    val domain = new URL(url).getHost.split("\\.").takeRight(2).mkString(".")

    val interval = hostIntervals.get(domain).getOrElse(defaultInterval)
    def now = new Date().getTime
    if (lastThrottle(domain) + interval > now) {
      val delay = logNormalDistribution(interval).toLong
      info("Throttling access to " + domain + " for " + delay / 1000.0 + " seconds")
      Thread.sleep(delay)
    }
    info("Allowing access to " + domain)
    lastThrottle += ((domain, now))
  }
}

object Slurp extends FirefoxSlurp with MathSciNetMirrorSlurp with S3CachingSlurp with ScopusSlurp {
  override val s3 = AnonymousS3
  override val bucketSuffix = ".cache"
}

