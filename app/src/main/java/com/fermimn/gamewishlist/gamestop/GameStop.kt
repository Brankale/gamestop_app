package com.fermimn.gamewishlist.gamestop

import android.util.Log
import com.fermimn.gamewishlist.models.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

class GameStop {

    companion object : GameStore {

        @Suppress("unused")
        private val TAG: String = GameStop::class.java.simpleName

        private const val WEBSITE_URL = "https://www.gamestop.it"
        private const val SEARCH_URL = "$WEBSITE_URL/SearchResult/QuickSearch?q="
        private const val PAGE_URL = "$WEBSITE_URL/Platform/Games/"

        fun getGamePageUrl(gameId: Int) : String = "$PAGE_URL/$gameId"
        private fun getSearchPageUrl(game: String) : String
                = "$SEARCH_URL/${URLEncoder.encode(game, "UTF-8")}"

        /**
         * @throws ParseItemException if the HTML is malformed
         */
        override fun search(game: String): GamePreviews {
            val url: String = getSearchPageUrl(game)
            val html: Document = Jsoup.connect(url).get()
            return getSearchResults(html)
        }

        override fun getGame(id: Int): Game {
            TODO("Not yet implemented")
        }

        /**
         * @throws ParseItemException if the HTML is malformed
         */
        private fun getSearchResults(html: Document) : GamePreviews {
            val results = GamePreviews()

            val items = getItemList(html)
            for (item in items) {
                val gamePreview = parseItem(item)
                results.add(gamePreview)
            }

            return results
        }

        /**
         * @param html it must be an element with the "prodList" tag or a tag that contains it
         * @throws ParseItemException if the HTML is malformed
         */
        private fun getItemList(html: Element) : Elements {
            try {
                val root: Element = html.getElementsByClass("prodList")[0]
                return root.getElementsByClass("singleProduct")
            } catch (ex : Exception) {
                throw ParseItemException()
            }
        }

        /**
         * @param item "singleProduct" html tag
         * @throws ParseItemException if the HTML is malformed
         */
        private fun parseItem(item: Element): GamePreview {

            fun getId() : Int {
                return item.getElementsByClass("prodImg")[0].attr("href")
                        .split("/")[3].toInt()
            }

            fun getTitle() : String = item.getElementsByTag("h3")[0].text()

            fun getPublisher() : String {
                return item.getElementsByTag("h4")[0]
                        .getElementsByTag("strong").text()
            }

            fun getPlatform() : String {
                return item.getElementsByTag("h4")[0].textNodes()[0].text().trim()
            }

            fun getCover() : String {
                return item.getElementsByClass("prodImg")[0]
                        .getElementsByTag("img")[0].attr("data-llsrc")
            }

            try {
                val gamePreview = GamePreview(getId())
                gamePreview.title = getTitle()
                gamePreview.platform = getPlatform()
                gamePreview.publisher = getPublisher()
                gamePreview.cover = getCover()

                // TODO: don't pass gamePreview to another method
                initPricesFromElementSingleProduct(item, gamePreview)

                return gamePreview
            } catch (ex: Exception) {
                throw ParseItemException()
            }
        }

        private fun initPricesFromElementSingleProduct(singleProduct: Element, gamePreview: GamePreview) {

            var categoryPrices = getPricesByCategory("buyNew", singleProduct)
            categoryPrices?.let {
                gamePreview.newPrice = it.first
                gamePreview.addOldNewPrices(it.second)
                gamePreview.newAvailable = it.third
            }

            categoryPrices = getPricesByCategory("buyUsed", singleProduct)
            categoryPrices?.let {
                gamePreview.usedPrice = it.first
                gamePreview.addOldUsedPrices(it.second)
                gamePreview.usedAvailable = it.third
            }

            categoryPrices = getPricesByCategory("buyPresell", singleProduct)
            categoryPrices?.let {
                gamePreview.preorderPrice = it.first
                gamePreview.addOldPreorderPrices(it.second)
                gamePreview.preorderAvailable = it.third
            }

            categoryPrices = getPricesByCategory("buyDLC", singleProduct)
            categoryPrices?.let {
                gamePreview.digitalPrice = it.first
                gamePreview.addOldDigitalPrices(it.second)
                gamePreview.digitalAvailable = it.third
            }
        }

        private fun getPricesByCategory(category: String, element: Element) : Triple<Float, ArrayList<Float>?, Boolean>? {
            val e: Elements = element.getElementsByClass(category)
            if (e.isNotEmpty()) {
                // <em> tag is present only if there are multiple prices
                val em: Elements = e[0].getElementsByTag("em")

                // if you can buy the product:
                //   - class "megaButton buyTier3 cartAddNoRadio" (NEW, USED prices)
                //   - class "megaButton cartAddNoRadio"          (PREORDER prices)
                // if you can't buy the product:
                //   - class "megaButton buyTier3 buyDisabled"    (NEW, USED prices)
                //   - class "megaButton buyDisabled"             (PREORDER prices)
                val available = e[0].getElementsByClass("megaButton buyTier3 cartAddNoRadio").size == 1 ||
                                e[0].getElementsByClass("megaButton cartAddNoRadio").size == 1

                return if (em.isEmpty()) {
                    // if there's just one price
                    val price: Float = stringToPrice(e[0].text())
                    Triple(price, null, available)
                } else {
                    // if more than one price is present
                    val price: Float = stringToPrice(em[0].text())
                    val oldPrices = ArrayList<Float>()
                    for (i in 1 until em.size) {
                        oldPrices.add(stringToPrice(em[i].text()))
                    }
                    Triple(price, oldPrices, available)
                }
            }
            return null
        }

        fun getGameById(gameId: Int) : Game {
            val html: Document = Jsoup.connect(getGamePageUrl(gameId)).get()
            return getGameFromGamePage(gameId, html)
        }

        private fun getGameFromGamePage(gameId: Int, html: Element) : Game {
            try {
                val game = Game(gameId)
                initGameMainInfo(game, html)
                initGameOptionalInfo(game, html)
                initGamePrices(game, html)
                initGamePegi(game, html)
                initGameCover(game, html)
                initGameGallery(game, html)
                initGameDescription(game, html)
                initGamePromos(game, html)
                return game
            } catch (ex: Exception) {                   // catch Exception because HTML can change
                throw ParseItemException()
            }
        }

        private fun getElementByClass(html: Element, className: String) : Element? {
            val elements: Elements = html.getElementsByClass(className)
            return if (elements.isNotEmpty()) elements[0] else null
        }

        private fun initGameMainInfo(game: Game, html: Element) {
            val prodTitle: Element? = getElementByClass(html, "prodTitle")
            prodTitle?.let {
                with (game) {
                    title = it.getElementsByTag("h1").text()
                    platform = it.getElementsByTag("p")[0].getElementsByTag("span").text()
                    publisher = it.getElementsByTag("strong").text()
                }
            }
        }

        private fun initGameOptionalInfo(game: Game, html: Element) {
            val addedDetInfo: Element? = getElementByClass(html, "addedDetInfo")

            addedDetInfo?.let {
                for (element in it.getElementsByTag("p")) {
                    val labels: Elements = element.getElementsByTag("label")
                    val spans: Elements = element.getElementsByTag("span")

                    if (labels.isNotEmpty() && spans.isNotEmpty()) {
                        val category: String? = labels.first()?.text()
                        val info: Element? = spans.first()

                        info?.let {
                            when (category) {
                                // use replace to make dates comparable
                                "Rilascio" -> game.releaseDate = info.text().replace(".", "/")
                                "Sito Ufficiale" -> game.website = info.getElementsByTag("a").attr("href")
                                "Giocatori" -> game.players = info.text()
                                "Genere" -> {
                                    val genres: List<String> = info.text().split("/")
                                    for (genre in genres) {
                                        game.addGenre(genre)
                                    }
                                }
                            }
                        }
                    }
                }

                val validForPromoClass = getElementByClass(it, "ProdottoNonValido")
                game.validForPromo = validForPromoClass?.text() == "Prodotto VALIDO per le promozioni"
            }
        }

        private fun initGamePrices(game: Game, html: Element) {
            val buySection: Element? = getElementByClass(html, "buySection")

            buySection?.let {

                for (svd in it.getElementsByClass("singleVariantDetails")) {
                    val radio: Element = svd.getElementsByTag("input")[0]
                    val svt: Element = svd.getElementsByClass("singleVariantText")[0]

                    when (svt.getElementsByClass("variantName")[0].text()) {
                        "Nuovo" -> {
                            game.newAvailable = radio.attr("data-int") != "0"
                            game.newPrice = getPriceFromSingleVariantText(svt)
                            game.addOldNewPrices(getOldPricesFromSingleVariantText(svt))
                        }
                        "Usato" -> {
                            game.usedAvailable = radio.attr("data-int") != "0"
                            game.usedPrice = getPriceFromSingleVariantText(svt)
                            game.addOldUsedPrices(getOldPricesFromSingleVariantText(svt))
                        }
                        "Prenotazione" -> {
                            game.preorderAvailable = true
                            game.preorderPrice = getPriceFromSingleVariantText(svt)

                            // TODO: need test cases
                            // Init of oldPreorder can be wrong due to too few test cases
                            // Leave UNCOMMENTED, if the app crashes here it can be fixed
                            game.addOldPreorderPrices(getOldPricesFromSingleVariantText(svt))
                        }
                        "Digitale" -> {
                            game.digitalAvailable = radio.attr("data-int") != "0"
                            game.digitalPrice = getPriceFromSingleVariantText(svt)

                            // TODO: need test cases
                            // for old digital prices you should retrieve data in this way
                            // if there are two old prices the behaviour is unknown
                            svt.getElementsByClass("pricetext2").remove()
                            svt.getElementsByClass("detailsLink").remove()
                            val priceStr = svt.text().replace(Regex("[^0-9.,]"),"")
                            if (priceStr.isNotEmpty()) {
                                game.addOldDigitalPrice(stringToPrice(priceStr))
                            }
                        }
                    }
                }
            }
        }

        private fun getPriceFromSingleVariantText(svt: Element) : Float {
            val price: String = svt.getElementsByClass("prodPriceCont")[0].text()
            return stringToPrice(price)
        }

        private fun getOldPricesFromSingleVariantText(svt: Element) : ArrayList<Float> {
            val oldPrices = ArrayList<Float>()
            for (olderPrice in svt.getElementsByClass("olderPrice")) {
                oldPrices.add(stringToPrice(olderPrice.text()))
            }
            return oldPrices
        }

        private fun stringToPrice(priceStr: String) : Float {
            var price: String = priceStr

            // remove all the characters except for numbers, ',' and '.'
            price = price.replace(Regex("[^0-9.,]"),"")
            // to handle prices over 999,99€ like 1.249,99€
            price = price.replace(".", "")
            // to convert the price in a string that can be parsed
            price = price.replace(',', '.')

            return price.toFloat()
        }

        private fun initGamePegi(game: Game, html: Element) {
            val ageBlock = getElementByClass(html, "ageBlock")
            ageBlock?.let {
                for (element in it.allElements) {
                    when (element.attr("class")) {
                        "pegi18" -> game.addPegi("pegi18")
                        "pegi16" -> game.addPegi("pegi16")
                        "pegi12" -> game.addPegi("pegi12")
                        "pegi7" -> game.addPegi("pegi7")
                        "pegi3" -> game.addPegi("pegi3")
                        "ageDescr BadLanguage" -> game.addPegi("bad-language")
                        "ageDescr violence" -> game.addPegi("violence")
                        "ageDescr online" -> game.addPegi("online")
                        "ageDescr sex" -> game.addPegi("sex")
                        "ageDescr fear" -> game.addPegi("fear")
                        "ageDescr drugs" -> game.addPegi("drugs")
                        "ageDescr discrimination" -> game.addPegi("discrimination")
                        "ageDescr gambling" -> game.addPegi("gambling")
                    }
                }
            }
        }

        private fun initGameCover(game: Game, html: Element) {
            val prodImgMax = getElementByClass(html, "prodImg max")
            game.cover = prodImgMax?.attr("href")
        }

        /**
         * What we know about gallery:
         *
         * Low res images always start with an even number
         * High res images always start with an odd number
         *
         * Cover:
         * low res:  2med.jpg
         * high res: 3max.jpg
         *
         * Gallery:
         * low res: <even number> + srcmin + <number of the image>
         * high res: <odd number> + srcmax + <number of the image>
         *
         *
         * NB:
         * <even number> starts from 4
         * <odd number> starts from 5
         * <number of the image> starts from 1
         * The low res version of an image is the odd number - 1
         *
         *
         * html example:
         * <a class="gallery" href="https://static-it.gamestop.it/images/products/154167/11scrmax4.jpg">
         *     <span>
         *         <img src="https://static-it.gamestop.it/images/products/154167/10scrmin4.jpg" alt="screen shot min">
         *     </span>
         * </a>
         *
         * NB: the high res version is in the "href" attribute of <a> tag
         * NB: the low res version is in the "src" attribute of <img> tag
         *
         *
         * Malformed HTML notes:
         * Sometimes only the low res version is available.
         * In these cases "href" attribute is not set.
         *
         * examples of malformed htmls are:
         * - new super mario bros 2
         * - catherine (PS3/Xbox360 Edition)
         *
         * malformed html example:
         *
         * <a class="gallery">
         *     <span>
         *         <img src="https://static-it.gamestop.it/images/products/154167/6scrmin1.jpg" alt="screen shot min">
         *     </span>
         * </a>
         * <a class="gallery" href="https://static-it.gamestop.it/images/products/154167/7scrmax2.jpg">
         *     <span>
         *         <img alt="screen shot min">
         *     </span>
         * </a>
         *
         * NB: As we can see in the first <a> tag "href" is not set and the
         *     low res version is available, but the same image is present
         *     in the next <a> tag only in high res version.
         *
         * Assumption: if "href" is not set we can skip the <a> tag because
         *             the next one will have the same image
         *
         */
        private fun initGameGallery(game: Game, html: Element) {
            val mediaImages: Element? = getElementByClass(html, "mediaImages")
            mediaImages?.let {
                for (element in it.getElementsByTag("a")) {
                    val link = element.attr("href")
                    // malformed html check: skip <a> tag
                    if (link.isNotEmpty()) {
                        game.addImage(link)
                    } else {
                        Log.w("Malformed HTML", "skip tag <a>")
                    }
                }
            }
        }

        private fun initGameDescription(game: Game, html: Element) {
            val prodDesc: Element? = html.getElementById("prodDesc")
            prodDesc?.let {
                // remove unnecessary elements
                it.getElementsByClass("prodToTop").remove()
                it.getElementsByClass("prodSecHead").remove()
                it.getElementsByTag("img").remove()
                game.description = it.outerHtml()
            }
        }

        private fun initGamePromos(game: Game, html: Element) {
            val bonusBlock: Element? = html.getElementById("bonusBlock")
            bonusBlock?.let {
                for (psp in it.getElementsByClass("prodSinglePromo")) {
                    val h4: Elements = psp.getElementsByTag("h4")
                    val p: Elements = psp.getElementsByTag("p")

                    val promo = Promo(h4.text())
                    promo.text = p[0].text()
                    if (p.size > 2) {
                        promo.findMore = p[0].text()
                        promo.findMoreUrl = "$WEBSITE_URL${p[1].getElementsByTag("a").attr("href")}"
                    }
                    game.addPromo(promo)
                }
            }
        }

    }

    private class ParseItemException : RuntimeException() {

        override val message: String
            get() = "Can't create game. Possibly caused by HTML changes."

    }

}