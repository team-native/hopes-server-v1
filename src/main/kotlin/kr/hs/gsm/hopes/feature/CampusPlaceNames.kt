package kr.hs.gsm.hopes.feature

/** 공개 장소 이름 검색: 동의어와 표기만 정규화하며 존재하지 않는 시설을 생성하지 않는다. */
fun normalizedPlaceName(value: String): String {
    var text = value.lowercase().replace(Regex("\\s+"), "")
    text = Regex("([1-3])[-–]([1-9])(?:반)?").replace(text) { "${it.groupValues[1]}학년${it.groupValues[2]}반" }
    text = Regex("(?<!\\d)([3-5]\\d{2})(?!\\d|호)").replace(text) { "${it.groupValues[1]}호" }
    val aliases = mapOf("도서관" to "도서실", "라이브러리" to "도서실", "library" to "도서실", "양호실" to "보건실", "급식실" to "식생활관", "식당" to "식생활관", "기숙사" to "동행관")
    aliases.forEach { (alias, canonical) -> text = text.replace(alias, canonical) }
    text = text.replace(Regex("홈베(?!이스)"), "홈베이스")
    return text.replace(Regex("(?:앞쪽|근처|옆쪽|앞|옆|주변|부근)$"), "")
}

fun placeMatches(placeName: String, query: String): Boolean {
    val name = normalizedPlaceName(placeName)
    val word = normalizedPlaceName(query)
    if (word.isBlank()) return true
    return name.contains(word) || name.split('/').any { it.length >= 2 && word.contains(it) }
}
