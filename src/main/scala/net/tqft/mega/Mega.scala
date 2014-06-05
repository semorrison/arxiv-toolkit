package net.tqft.mega

import org.openqa.selenium.WebDriver
import net.tqft.toolkit.Logging
import org.openqa.selenium.firefox.FirefoxProfile
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions

object Mega extends Logging {

  lazy val driver: WebDriver = {
    Logging.info("Starting Firefox/webdriver")
    val profile = new FirefoxProfile();
    profile.setPreference("pdfjs.disabled", true);
    val result = new FirefoxDriver(profile)
    Logging.info("   ... finished starting Firefox")
    result
  }

  def executor = driver.asInstanceOf[JavascriptExecutor]

  def test {
    info("loading mega.co.nz")
    driver.get("https://mega.co.nz/")
    info("waiting for #pageholder to appear")
    val wait = new WebDriverWait(driver, 600)
    wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pageholder div.nstart-mega")))
    executor.executeScript("if ($('#seleniumUpload').length == 0) { seleniumUpload = window.$('<input/>').attr({id: 'seleniumUpload', type:'file'}).appendTo('body'); }")
    val uploadElement = driver.findElement(By.id("seleniumUpload"))
    uploadElement.sendKeys("~/foo")
    executor.executeScript("fileList = [ seleniumUpload.get(0).files[0] ]; e = $.Event('drop'); e.target = $('#pageholder'); e.dataTransfer = {files:fileList}; start_FileSelectHandler(e); ")
    wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.terms-main a.quota-button"))).click()
    
    // TODO dismiss the firefox extension dialog whenever it appears?
    // TODO wait until the file actually appears in the "Cloud Drive" section
    // TODO add a contact, transfer the file there?
  }
}