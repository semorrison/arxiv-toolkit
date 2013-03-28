package net.tqft.mathscinet

object BIBTEXApp extends App {
//	println(Article("MR1437496").bibtex)
	
	for(article <- Articles.withCachedBIBTEX) {
	  println(article.identifierString + " ---> " + article.DOI)
	}
}