package app.localtube.compat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Stand-ins for Java methods that old Android (before 13) doesn't have. The build rewrites NewPipeExtractor's calls to them
 * (see `buildSrc/.../CompatRewriteFactory.kt`). Each uses the older overload that takes the charset's *name*.
 */
object Compat {
    @JvmStatic fun urlEncode(s: String, charset: Charset): String = URLEncoder.encode(s, charset.name())
    @JvmStatic fun urlDecode(s: String, charset: Charset): String = URLDecoder.decode(s, charset.name())
}
