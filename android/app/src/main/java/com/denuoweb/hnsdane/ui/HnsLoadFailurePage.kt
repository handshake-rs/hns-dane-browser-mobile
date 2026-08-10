package com.denuoweb.hnsdane.ui

/** Browser-owned fallback shown when an HNS main-frame request fails before an HTTP response. */
internal object HnsLoadFailurePage {

    fun render(
        title: String,
        detail: String,
        displayHost: String,
        retryLabel: String,
        retryUrl: String,
    ): String {
        val safeTitle = title.htmlEscape()
        val safeDetail = detail.htmlEscape()
        val safeHost = displayHost.htmlEscape()
        val safeRetryLabel = retryLabel.htmlEscape()
        val safeRetryUrl = retryUrl.htmlEscape()
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
                <title>$safeTitle</title>
                <style>
                  :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
                  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: Canvas; color: CanvasText; }
                  main { box-sizing: border-box; width: min(34rem, 100%); padding: 2rem; }
                  .icon { width: 3rem; height: 3rem; display: grid; place-items: center; border: 2px solid #a16207; border-radius: 50%; color: #a16207; font-size: 1.75rem; font-weight: 700; }
                  h1 { margin: 1.25rem 0 .75rem; font-size: 1.6rem; }
                  p { line-height: 1.5; color: GrayText; }
                  .host { overflow-wrap: anywhere; color: CanvasText; font-weight: 600; }
                  a { display: inline-block; margin-top: .75rem; padding: .75rem 1rem; border-radius: .5rem; background: #1a73e8; color: white; font-weight: 600; text-decoration: none; }
                </style>
              </head>
              <body>
                <main>
                  <div class="icon" aria-hidden="true">!</div>
                  <h1>$safeTitle</h1>
                  <p>$safeDetail</p>
                  <p class="host">$safeHost</p>
                  <a href="$safeRetryUrl">$safeRetryLabel</a>
                </main>
              </body>
            </html>
        """.trimIndent()
    }
}

private fun String.htmlEscape(): String = buildString(length) {
    for (character in this@htmlEscape) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
