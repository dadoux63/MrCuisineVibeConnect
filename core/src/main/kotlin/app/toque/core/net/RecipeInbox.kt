package app.toque.core.net

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.Cookomix
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * « Boîte aux lettres » des recettes : reçoit, depuis le téléphone, l'adresse d'UNE recette Cookomix
 * et la confie à [importer] (téléchargement, conversion, ajout à Mes recettes). Désactivée par défaut.
 *
 * - `GET /` : petite page web où coller l'adresse (aucun secret dans la page : le jeton reste sur le
 *   téléphone, dans le fragment `#t=` de l'adresse puis dans le navigateur).
 * - `POST /api/v1/recipes` : jeton obligatoire (`Authorization: Bearer`), corps JSON
 *   `{"url": …}`, `{"text": …}` (texte partagé contenant l'adresse) ou `{"url": …, "html": …}` (page
 *   déjà téléchargée par un autre appareil) ; ou bien texte brut contenant l'adresse.
 * - Seules les adresses `https://www.cookomix.com/recettes/…/` sont acceptées : le robot ne peut pas
 *   servir de relais vers un autre site.
 * - Une requête à la fois, une recette par requête, [MIN_INTERVAL_MS] entre deux imports : outil
 *   d'import à la demande, pas un aspirateur.
 * - Aucune commande machine : ce serveur ne connaît ni le moteur ni la chauffe. Il tourne dans le
 *   processus de l'interface, pas dans celui du pilotage ; aucune exception ne sort de son fil.
 */
class RecipeInbox(
    private val port: Int,
    private val token: () -> String,
    private val importer: (Request) -> Reply,
    private val log: (String) -> Unit = {},
    private val bindAddress: InetAddress? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * [html] : page déjà téléchargée (sinon le robot la télécharge lui-même).
     * [photo] : ajouter aussi la photo de la recette (oui par défaut ; `"photo": false` pour s'en passer).
     */
    class Request(val url: String, val html: String?, val photo: Boolean = true)

    class Reply(val code: Int, val body: Map<String, Any?>) {
        companion object {
            fun ok(body: Map<String, Any?>) = Reply(200, linkedMapOf<String, Any?>("ok" to true) + body)
            fun error(code: Int, message: String) = Reply(code, linkedMapOf("ok" to false, "error" to message))
        }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    private var lastImport = 0L

    init {
        require(port == 0 || port in 1024..65535) { "port hors plage" }
    }

    val localPort: Int get() = server?.localPort ?: -1

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port), 4)
        server = s
        thread = Thread({ acceptLoop(s) }, "toque-inbox").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        log("réception des recettes ouverte sur le port ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        try { s.close() } catch (_: IOException) { }
        thread?.interrupt()
        thread = null
        log("réception des recettes fermée")
    }

    private fun acceptLoop(s: ServerSocket) {
        try {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: SocketException) { break } catch (_: IOException) { continue }
                try {
                    c.use { handle(it) }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            try { log("réception des recettes arrêtée : $t") } catch (_: Throwable) { }
            try { s.close() } catch (_: Throwable) { }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = READ_TIMEOUT_MS
        c.tcpNoDelay = true
        val input = c.getInputStream()
        val (head, rest) = readHead(input) ?: return send(c, Reply.error(400, "requête illisible"))
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return send(c, Reply.error(400, "requête illisible"))
        val (method, target) = parts
        val path = target.substringBefore('?').substringBefore('#')
        fun header(name: String) = lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()

        when (path) {
            "/", "/importer" -> {
                if (method != "GET") return send(c, Reply.error(405, "méthode non permise"), "Allow: GET\r\n")
                return sendPage(c)
            }
            API -> if (method != "POST") return send(c, Reply.error(405, "méthode non permise"), "Allow: POST\r\n")
            else -> return send(c, Reply.error(404, "inconnu"))
        }

        val auth = header("Authorization")
        val given = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        val expected = token()
        if (expected.length < StatusServer.MIN_TOKEN ||
            !MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8)))
            return send(c, Reply.error(401, "jeton absent ou faux"), "WWW-Authenticate: Bearer\r\n")

        val length = header("Content-Length").toLongOrNull() ?: return send(c, Reply.error(411, "longueur absente"))
        if (length < 0 || length > MAX_BODY) return send(c, Reply.error(413, "requête trop longue"))
        val body = readBody(input, rest, length.toInt()) ?: return send(c, Reply.error(400, "requête incomplète"))
        val request = parse(body, header("Content-Type")) ?: return send(c, Reply.error(400,
            "aucune adresse de recette Cookomix (attendu : https://www.cookomix.com/recettes/…/)"))

        val now = clock()
        synchronized(this) {
            if (lastImport != 0L && now - lastImport in 0 until MIN_INTERVAL_MS)
                return send(c, Reply.error(429, "une recette à la fois : réessayez dans quelques secondes"))
            lastImport = now
        }
        c.soTimeout = IMPORT_TIMEOUT_MS
        val reply = try {
            importer(request)
        } catch (t: Throwable) {
            log("import impossible : $t")
            Reply.error(500, "import impossible : ${t.message ?: t.javaClass.simpleName}")
        }
        send(c, reply)
    }

    /** Le corps : [already] (lu avec l'en-tête), puis le reste du flux. */
    private fun readBody(input: InputStream, already: ByteArray, length: Int): String? {
        val out = ByteArrayOutputStream(length)
        out.write(already, 0, minOf(already.size, length))
        val buf = ByteArray(16 * 1024)
        while (out.size() < length) {
            val r = input.read(buf, 0, minOf(buf.size, length - out.size()))
            if (r < 0) return null
            out.write(buf, 0, r)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun sendPage(c: Socket) {
        val bytes = PAGE.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'\r\n" +
            "Connection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun send(c: Socket, reply: Reply, extra: String = "") {
        val bytes = Json.write(reply.body).toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 ${reply.code} ${REASONS[reply.code] ?: "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" + extra + "\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val API = "/api/v1/recipes"
        const val DEFAULT_PORT = 8767
        /** Une page Cookomix complète pèse quelques centaines de Ko. */
        const val MAX_BODY = 4 * 1024 * 1024
        const val MAX_HEAD = 8 * 1024
        const val READ_TIMEOUT_MS = 10_000
        const val IMPORT_TIMEOUT_MS = 90_000
        const val MIN_INTERVAL_MS = 3_000L

        private val REASONS = mapOf(
            200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed",
            411 to "Length Required", 413 to "Payload Too Large", 422 to "Unprocessable Entity", 429 to "Too Many Requests",
            500 to "Internal Server Error", 502 to "Bad Gateway",
        )

        /** En-tête HTTP (jusqu'à la ligne vide, [MAX_HEAD] octets au plus) et octets du corps déjà lus. */
        internal fun readHead(input: InputStream): Pair<String, ByteArray>? {
            val buf = ByteArray(MAX_HEAD)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) return null
                n += r
                val text = String(buf, 0, n, StandardCharsets.ISO_8859_1)
                val end = text.indexOf("\r\n\r\n")
                if (end >= 0) return text.substring(0, end) to buf.copyOfRange(end + 4, n)
            }
            return null
        }

        /**
         * Corps → requête ; null s'il ne contient aucune adresse de recette Cookomix.
         * JSON : `url` (ou à défaut `text`) et `html` facultatif ; sinon le corps est un texte partagé.
         */
        fun parse(body: String, contentType: String): Request? {
            val trimmed = body.trim()
            val json = if (contentType.contains("json", ignoreCase = true) || trimmed.startsWith("{"))
                try { Json.parse(trimmed).obj() } catch (_: Exception) { null } else null
            val text: String
            var html: String? = null
            var photo = true
            if (json != null && json.isNotEmpty()) {
                photo = json["photo"] != false
                text = listOfNotNull(json.str("url"), json.str("text")).joinToString(" ")
                html = json.str("html")?.takeIf { it.isNotBlank() }
            } else {
                text = trimmed
            }
            val url = Cookomix.findUrl(text) ?: return null
            return Request(url, html, photo)
        }

        /** Page servie au téléphone : coller l'adresse, envoyer. Aucune ressource extérieure. */
        val PAGE = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Toque · Importer</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#1c1b1a;--muted:#6b6660;--line:#d9d4cd;--accent:#c0532b;--ok:#2e7d32;--err:#b3261e}
@media (prefers-color-scheme:dark){:root{--bg:#161514;--fg:#f2efea;--muted:#a49d94;--line:#3a3633;--accent:#e07a4f;--ok:#81c784;--err:#f2b8b5}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.45 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:34rem;margin:0 auto;padding:24px 16px}h1{font-size:1.4rem;margin:0 0 4px}p{margin:.4rem 0;color:var(--muted)}
label{display:block;font-weight:600;margin:18px 0 6px}input{width:100%;font:inherit;padding:12px;border:1px solid var(--line);border-radius:10px;background:transparent;color:inherit}
button{margin-top:14px;width:100%;font:inherit;font-weight:600;padding:14px;border:0;border-radius:10px;background:var(--accent);color:#fff}
button:disabled{opacity:.5}#out{margin-top:18px;white-space:pre-wrap}.ok{color:var(--ok)}.err{color:var(--err)}
details{margin-top:28px;color:var(--muted)}label.c{display:flex;gap:10px;align-items:center;font-weight:400}label.c input{width:auto;transform:scale(1.4)}small{color:var(--muted)}
</style></head><body><main>
<h1>Importer une recette Cookomix</h1>
<p>Collez l'adresse d'une recette : le robot la convertit et l'ajoute à <b>Mes recettes</b>. Une recette à la fois, pour votre usage personnel.</p>
<form id="f">
<label for="u">Adresse de la recette</label>
<input id="u" type="url" inputmode="url" autocomplete="off" placeholder="https://www.cookomix.com/recettes/…/" required>
<label class="c"><input id="p" type="checkbox" checked> Avec la photo de la recette</label>
<button id="b" type="submit">Envoyer au robot</button>
</form>
<div id="out" role="status"></div>
<details id="d"><summary>Jeton</summary>
<label for="t">Jeton (Réglages › Mes recettes du robot)</label>
<input id="t" autocomplete="off" spellcheck="false">
<small>Gardé dans ce navigateur seulement.</small></details>
</main><script>
(function(){
var K='toque-jeton',P='toque-photo',p=document.getElementById('p'),t=document.getElementById('t'),u=document.getElementById('u'),o=document.getElementById('out'),b=document.getElementById('b');
function get(){try{return localStorage.getItem(K)||''}catch(e){return ''}}
function put(v){try{localStorage.setItem(K,v)}catch(e){}}
var m=/[#&]t=([0-9a-fA-F]{16,})/.exec(location.hash);
if(m){put(m[1]);history.replaceState(null,'',location.pathname);}
t.value=get();if(!t.value)document.getElementById('d').open=true;
t.addEventListener('change',function(){put(t.value.trim())});
try{p.checked=localStorage.getItem(P)!=='non'}catch(e){}
p.addEventListener('change',function(){try{localStorage.setItem(P,p.checked?'oui':'non')}catch(e){}});
function show(c,s){o.className=c;o.textContent=s}
document.getElementById('f').addEventListener('submit',function(ev){
 ev.preventDefault();var j=t.value.trim();put(j);
 if(!j){document.getElementById('d').open=true;show('err','Indiquez d\'abord le jeton.');return}
 b.disabled=true;show('','Téléchargement et conversion…');
 fetch('$API',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+j},body:JSON.stringify({url:u.value.trim(),photo:p.checked})})
 .then(function(r){return r.json()}).then(function(r){
  if(r.ok){var w=(r.warnings||[]);show('ok',r.message+(w.length?'\n\nÀ vérifier :\n• '+w.join('\n• '):''));u.value=''}
  else show('err',r.error||'Erreur');
 }).catch(function(e){show('err','Robot injoignable : '+e)}).then(function(){b.disabled=false});
});
})();
</script></body></html>"""
    }
}
